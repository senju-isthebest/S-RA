package com.example.data.ai

class SoraJsonException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

sealed class JsonVal {
    abstract fun toJsonString(): String
}

data class JsonObj(val entries: Map<String, JsonVal>) : JsonVal() {
    fun str(key: String, default: String = ""): String =
        (entries[key] as? JsonStr)?.value ?: default

    fun intVal(key: String, default: Int = 0): Int =
        (entries[key] as? JsonNum)?.toIntOrNull() ?: default

    fun longVal(key: String, default: Long = 0L): Long =
        (entries[key] as? JsonNum)?.toLongOrNull() ?: default

    fun doubleVal(key: String, default: Double = 0.0): Double =
        (entries[key] as? JsonNum)?.toDoubleOrNull() ?: default

    fun boolVal(key: String, default: Boolean = false): Boolean =
        (entries[key] as? JsonBool)?.value ?: default

    fun obj(key: String): JsonObj? = entries[key] as? JsonObj

    fun arr(key: String): JsonArr? = entries[key] as? JsonArr

    fun stringList(key: String): List<String> =
        arr(key)?.items?.mapNotNull { (it as? JsonStr)?.value?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?: emptyList()

    fun strList(key: String): List<String> = stringList(key)

    fun has(key: String): Boolean = entries.containsKey(key)

    override fun toJsonString(): String = buildString {
        append('{')
        var first = true
        for ((k, v) in entries) {
            if (!first) append(',')
            first = false
            append(SoraJson.quoteString(k))
            append(':')
            append(v.toJsonString())
        }
        append('}')
    }
}

data class JsonArr(val items: List<JsonVal>) : JsonVal() {
    override fun toJsonString(): String = buildString {
        append('[')
        items.forEachIndexed { idx, v ->
            if (idx > 0) append(',')
            append(v.toJsonString())
        }
        append(']')
    }
}

data class JsonStr(val value: String) : JsonVal() {
    override fun toJsonString(): String = SoraJson.quoteString(value)
}

data class JsonNum(val raw: String) : JsonVal() {
    fun toIntOrNull(): Int? = raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
    fun toLongOrNull(): Long? = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    fun toDoubleOrNull(): Double? = raw.toDoubleOrNull()
    override fun toJsonString(): String = raw
}

data class JsonBool(val value: Boolean) : JsonVal() {
    override fun toJsonString(): String = if (value) "true" else "false"
}

data object JsonNull : JsonVal() {
    override fun toJsonString(): String = "null"
}

object SoraJson {
    fun obj(vararg pairs: Pair<String, Any?>): JsonObj {
        val map = LinkedHashMap<String, JsonVal>(pairs.size)
        for ((k, v) in pairs) {
            map[k] = wrap(v)
        }
        return JsonObj(map)
    }

    fun arr(items: Iterable<Any?>): JsonArr = JsonArr(items.map { wrap(it) })

    private fun wrap(value: Any?): JsonVal = when (value) {
        null -> JsonNull
        is JsonVal -> value
        is String -> JsonStr(value)
        is Boolean -> JsonBool(value)
        is Int -> JsonNum(value.toString())
        is Long -> JsonNum(value.toString())
        is Float -> if (value.isNaN() || value.isInfinite()) JsonNull else JsonNum(value.toString())
        is Double -> if (value.isNaN() || value.isInfinite()) JsonNull else JsonNum(value.toString())
        is Number -> JsonNum(value.toString())
        is Map<*, *> -> JsonObj(
            value.entries.associate { (k, v) -> k.toString() to wrap(v) }
        )
        is Iterable<*> -> JsonArr(value.map { wrap(it) })
        else -> JsonStr(value.toString())
    }

    fun quoteString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u%04x".format(ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    fun extractJsonPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("```")) {
            val withoutOpen = trimmed
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .trimStart()
            val closeIdx = withoutOpen.lastIndexOf("```")
            if (closeIdx >= 0) {
                return withoutOpen.substring(0, closeIdx).trim()
            }
        }
        return trimmed
    }

    fun parseObject(raw: String): JsonObj {
        val valParsed = parse(extractJsonPayload(raw))
        return valParsed as? JsonObj
            ?: throw SoraJsonException("Expected JSON object but found ${valParsed::class.simpleName}")
    }

    fun parse(raw: String): JsonVal {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw SoraJsonException("Empty JSON input")
        val parser = Parser(trimmed)
        val result = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isAtEnd()) {
            throw SoraJsonException("Unexpected trailing character at index ${parser.pos}")
        }
        return result
    }

    private class Parser(private val src: String) {
        var pos: Int = 0
            private set

        fun isAtEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun parseValue(): JsonVal {
            skipWhitespace()
            if (pos >= src.length) throw SoraJsonException("Unexpected end of JSON at index $pos")
            return when (val ch = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonStr(parseString())
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw SoraJsonException("Unexpected character '$ch' at index $pos")
            }
        }

        private fun parseObject(): JsonObj {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, JsonVal>()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return JsonObj(map)
            }
            while (true) {
                skipWhitespace()
                if (pos >= src.length || src[pos] != '"') {
                    throw SoraJsonException("Expected string key at index $pos")
                }
                val key = parseString()
                skipWhitespace()
                if (pos >= src.length || src[pos] != ':') {
                    throw SoraJsonException("Expected ':' after key '$key' at index $pos")
                }
                pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (pos >= src.length) throw SoraJsonException("Unterminated JSON object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonObj(map)
                    }
                    else -> throw SoraJsonException("Expected ',' or '}' at index $pos")
                }
            }
        }

        private fun parseArray(): JsonArr {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<JsonVal>()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return JsonArr(list)
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (pos >= src.length) throw SoraJsonException("Unterminated JSON array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonArr(list)
                    }
                    else -> throw SoraJsonException("Expected ',' or ']' at index $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw SoraJsonException("Unterminated escape sequence")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw SoraJsonException("Invalid unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw SoraJsonException("Invalid unicode hex '$hex'")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw SoraJsonException("Invalid escape '\\$esc'")
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) throw SoraJsonException("Unescaped control char in string")
                        sb.append(ch)
                    }
                }
            }
            throw SoraJsonException("Unterminated JSON string")
        }

        private fun parseBoolean(): JsonBool {
            return if (src.startsWith("true", pos)) {
                pos += 4
                JsonBool(true)
            } else if (src.startsWith("false", pos)) {
                pos += 5
                JsonBool(false)
            } else {
                throw SoraJsonException("Invalid boolean at index $pos")
            }
        }

        private fun parseNull(): JsonNull {
            if (src.startsWith("null", pos)) {
                pos += 4
                return JsonNull
            }
            throw SoraJsonException("Invalid null literal at index $pos")
        }

        private fun parseNumber(): JsonNum {
            val start = pos
            if (src[pos] == '-') pos++
            if (pos >= src.length || !src[pos].isDigit()) {
                throw SoraJsonException("Invalid number at index $start")
            }
            if (src[pos] == '0') {
                pos++
            } else {
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos < src.length && src[pos] == '.') {
                pos++
                if (pos >= src.length || !src[pos].isDigit()) {
                    throw SoraJsonException("Invalid decimal fraction at index $pos")
                }
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos >= src.length || !src[pos].isDigit()) {
                    throw SoraJsonException("Invalid exponent at index $pos")
                }
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            return JsonNum(src.substring(start, pos))
        }
    }
}
