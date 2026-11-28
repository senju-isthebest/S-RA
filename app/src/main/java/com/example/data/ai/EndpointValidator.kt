package com.example.data.ai

import java.net.URI

object SecretRedactor {
    private val GOOGLE_KEY_REGEX = Regex("AIza[0-9A-Za-z\\-_]{16,}")
    private val QUERY_KEY_REGEX = Regex("([?&](?:key|api_key|apikey)=)[^&\\s]+", RegexOption.IGNORE_CASE)
    private val BEARER_REGEX = Regex("(Bearer\\s+)[A-Za-z0-9._\\-]+", RegexOption.IGNORE_CASE)
    private val HEADER_KEY_REGEX = Regex("(x-goog-api-key\\s*[:=]\\s*)\\S+", RegexOption.IGNORE_CASE)

    fun redact(text: String, extraSecrets: List<String> = emptyList()): String {
        var cleaned = text
            .replace(GOOGLE_KEY_REGEX, "[REDACTED_API_KEY]")
            .replace(QUERY_KEY_REGEX, "$1[REDACTED]")
            .replace(BEARER_REGEX, "$1[REDACTED]")
            .replace(HEADER_KEY_REGEX, "$1[REDACTED]")
        for (secret in extraSecrets) {
            val trimmed = secret.trim()
            if (trimmed.length >= 6) {
                cleaned = cleaned.replace(trimmed, "[REDACTED]")
            }
        }
        return cleaned
    }
}

data class EndpointValidationResult(
    val isValid: Boolean,
    val normalizedUrl: String = "",
    val isLocalOrPrivate: Boolean = false,
    val errorMessage: String = ""
)

object EndpointValidator {

    fun validateOllamaEndpoint(raw: String): EndpointValidationResult {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return EndpointValidationResult(false, errorMessage = "Ollama endpoint URL cannot be empty.")
        }
        if (trimmed.any { it.isWhitespace() }) {
            return EndpointValidationResult(false, errorMessage = "Ollama endpoint URL must not contain spaces.")
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return EndpointValidationResult(false, errorMessage = "Invalid URL syntax: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") {
            return EndpointValidationResult(false, errorMessage = "Endpoint must use http:// or https://.")
        }
        if (!uri.userInfo.isNullOrBlank()) {
            return EndpointValidationResult(false, errorMessage = "Endpoint URL must not include embedded credentials.")
        }
        if (!uri.rawQuery.isNullOrBlank() || !uri.rawFragment.isNullOrBlank()) {
            return EndpointValidationResult(false, errorMessage = "Endpoint URL must not contain query parameters or fragments.")
        }

        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) {
            return EndpointValidationResult(false, errorMessage = "Endpoint URL must include a valid host.")
        }
        if (uri.port != -1 && (uri.port < 1 || uri.port > 65535)) {
            return EndpointValidationResult(false, errorMessage = "Endpoint port must be between 1 and 65535.")
        }
        if (isBlockedHost(host)) {
            return EndpointValidationResult(
                isValid = false,
                isLocalOrPrivate = false,
                errorMessage = "Endpoint host '$host' is blocked by SSRF security policy."
            )
        }

        val isLocal = isLocalOrPrivateHost(host)
        if (scheme == "http" && !isLocal) {
            return EndpointValidationResult(
                isValid = false,
                isLocalOrPrivate = false,
                errorMessage = "Cleartext HTTP is restricted to local/private addresses. Use HTTPS for remote hosts."
            )
        }

        val path = uri.rawPath?.trimEnd('/').orEmpty()
        val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
        val hostFormatted = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val normalized = "$scheme://$hostFormatted$portSuffix$path"

        return EndpointValidationResult(
            isValid = true,
            normalizedUrl = normalized,
            isLocalOrPrivate = isLocal
        )
    }

    fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h.isBlank() || h == "0.0.0.0" || h == "::" || h == "0:0:0:0:0:0:0:0") return true
        if (h == "metadata" || h == "metadata.google.internal" || h == "instance-data") return true
        if (h.endsWith(".metadata.google.internal")) return true
        if (h.startsWith("fe80:") || h.startsWith("::ffff:")) return true
        val parts = h.split(".")
        if (parts.size == 4) {
            val octets = parts.map { it.toIntOrNull() }
            if (octets.all { it != null && it in 0..255 }) {
                val a = octets[0]!!
                val b = octets[1]!!
                if (a == 0) return true
                if (a == 169 && b == 254) return true
                if (a in 224..239) return true
            }
        }
        return false
    }

    fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (isBlockedHost(h)) return false
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "10.0.2.2") return true
        if (h.endsWith(".local")) return true

        val parts = h.split(".")
        if (parts.size == 4) {
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            val (a, b, _, _) = octets
            if (a == 10) return true
            if (a == 127) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
        }
        return false
    }
}
