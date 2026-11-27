package com.example.domain

import kotlin.math.*

data class FormulaReference(
    val category: String,
    val name: String,
    val expression: String,
    val variables: String
)

object StudentToolboxEngine {

    val formulaLibrary: List<FormulaReference> = listOf(
        FormulaReference("Physics", "Newton's Second Law", "F = m * a", "F: Force (N), m: Mass (kg), a: Acceleration (m/s^2)"),
        FormulaReference("Physics", "Kinetic Energy", "KE = 0.5 * m * v^2", "m: Mass (kg), v: Velocity (m/s)"),
        FormulaReference("Physics", "Gravitational Potential Energy", "PE = m * g * h", "g = 9.81 m/s^2, h: Height (m)"),
        FormulaReference("Physics", "Ohm's Law", "V = I * R", "V: Voltage (V), I: Current (A), R: Resistance (Ohm)"),
        FormulaReference("Physics", "Wave Equation", "v = f * lambda", "v: Speed (m/s), f: Frequency (Hz), lambda: Wavelength (m)"),
        FormulaReference("Chemistry", "Ideal Gas Law", "P * V = n * R * T", "R = 8.314 J/(mol*K), T in Kelvin"),
        FormulaReference("Chemistry", "pH Definition", "pH = -log10([H+])", "[H+]: Hydrogen ion molarity (mol/L)"),
        FormulaReference("Chemistry", "Molarity", "M = n / V", "n: Moles of solute, V: Solution volume (L)"),
        FormulaReference("Mathematics", "Quadratic Formula", "x = (-b +/- sqrt(b^2 - 4ac)) / (2a)", "For ax^2 + bx + c = 0"),
        FormulaReference("Mathematics", "Derivative Power Rule", "d/dx (x^n) = n * x^(n-1)", "Valid for any real constant n"),
        FormulaReference("Mathematics", "Standard Deviation", "sigma = sqrt(sum((xi - mu)^2) / N)", "mu: Population mean, N: Count"),
        FormulaReference("Biology", "Hardy-Weinberg Equilibrium", "p^2 + 2pq + q^2 = 1", "p + q = 1 allele frequencies"),
        FormulaReference("Economics", "Compound Interest", "A = P * (1 + r/n)^(n*t)", "P: Principal, r: Annual rate, n: Compounds/yr, t: Years")
    )

    data class UnitCategory(val name: String, val units: Map<String, Double>, val isTemp: Boolean = false)

    val unitCategories: List<UnitCategory> = listOf(
        UnitCategory("Length (base: m)", mapOf("mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0, "in" to 0.0254, "ft" to 0.3048, "mi" to 1609.344)),
        UnitCategory("Mass (base: kg)", mapOf("mg" to 1e-6, "g" to 0.001, "kg" to 1.0, "oz" to 0.0283495, "lb" to 0.453592)),
        UnitCategory("Energy (base: J)", mapOf("J" to 1.0, "kJ" to 1000.0, "cal" to 4.184, "kcal" to 4184.0, "Wh" to 3600.0, "eV" to 1.60218e-19)),
        UnitCategory("Pressure (base: Pa)", mapOf("Pa" to 1.0, "kPa" to 1000.0, "bar" to 100000.0, "atm" to 101325.0, "psi" to 6894.76)),
        UnitCategory("Data (base: KB)", mapOf("B" to 0.001, "KB" to 1.0, "MB" to 1024.0, "GB" to 1048576.0, "TB" to 1073741824.0)),
        UnitCategory("Temperature", mapOf("C" to 1.0, "F" to 1.0, "K" to 1.0), isTemp = true)
    )

    fun convertUnit(value: Double, category: UnitCategory, from: String, to: String): Double {
        if (category.isTemp) {
            val celsius = when (from) {
                "F" -> (value - 32.0) * 5.0 / 9.0
                "K" -> value - 273.15
                else -> value
            }
            return when (to) {
                "F" -> celsius * 9.0 / 5.0 + 32.0
                "K" -> celsius + 273.15
                else -> celsius
            }
        }
        val fromFactor = category.units[from] ?: 1.0
        val toFactor = category.units[to] ?: 1.0
        return (value * fromFactor) / toFactor
    }

    fun calculateWeightedGrade(items: List<Pair<Double, Double>>): Pair<Double, Double> {
        val totalWeight = items.sumOf { it.second }
        if (totalWeight <= 0.0) return 0.0 to 0.0
        val weightedSum = items.sumOf { it.first * it.second }
        return (weightedSum / totalWeight) to totalWeight
    }

    fun calculateRequiredFinalScore(
        currentGrade: Double,
        targetGrade: Double,
        finalExamWeightPercent: Double
    ): Double {
        val w = (finalExamWeightPercent / 100.0).coerceIn(0.01, 0.99)
        return (targetGrade - currentGrade * (1.0 - w)) / w
    }

    fun countTextMetrics(text: String): Triple<Int, Int, Int> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Triple(0, 0, 0)
        val words = trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
        val chars = trimmed.length
        val readMinutes = ceil(words / 200.0).toInt().coerceAtLeast(1)
        return Triple(words, chars, readMinutes)
    }

    fun formatCitation(
        style: String,
        authors: String,
        title: String,
        year: String,
        publisher: String,
        pages: String
    ): String {
        val a = authors.ifBlank { "Author Unknown" }
        val y = year.ifBlank { "n.d." }
        val p = publisher.ifBlank { "Academic Press" }
        val pg = if (pages.isNotBlank()) "pp. $pages" else ""
        return when (style.uppercase()) {
            "MLA" -> "$a. $title. $p, $y${if (pg.isNotEmpty()) ", $pg" else ""}."
            "CHICAGO" -> "$a. $y. $title. $p${if (pg.isNotEmpty()) ", $pg" else ""}."
            "IEEE" -> "$a, \"$title,\" $p, $y${if (pg.isNotEmpty()) ", $pg" else ""}."
            else -> "$a ($y). $title${if (pg.isNotEmpty()) " ($pg)" else ""}. $p."
        }
    }

    // Recursive-descent scientific expression evaluator with step trace
    fun evaluateScientificExpression(raw: String): Result<Pair<Double, List<String>>> {
        val cleaned = raw.trim()
            .replace("×", "*")
            .replace("÷", "/")
            .replace("−", "-")
            .replace("π", "pi")
        if (cleaned.isBlank()) return Result.failure(IllegalArgumentException("Enter an expression."))
        return try {
            val steps = mutableListOf<String>()
            val parser = ExprParser(cleaned, steps)
            val result = parser.parseExpression()
            parser.expectEnd()
            if (result.isNaN() || result.isInfinite()) {
                Result.failure(ArithmeticException("Undefined mathematical result."))
            } else {
                Result.success(result to steps)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private class ExprParser(
        private val input: String,
        private val steps: MutableList<String>
    ) {
        private var pos = 0

        fun expectEnd() {
            skipWhitespace()
            if (pos < input.length) {
                throw IllegalArgumentException("Unexpected character '${input[pos]}' at position ${pos + 1}")
            }
        }

        fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                skipWhitespace()
                when {
                    match('+') -> {
                        val right = parseTerm()
                        val res = x + right
                        steps.add("${fmt(x)} + ${fmt(right)} = ${fmt(res)}")
                        x = res
                    }
                    match('-') -> {
                        val right = parseTerm()
                        val res = x - right
                        steps.add("${fmt(x)} - ${fmt(right)} = ${fmt(res)}")
                        x = res
                    }
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parsePower()
            while (true) {
                skipWhitespace()
                when {
                    match('*') -> {
                        val right = parsePower()
                        val res = x * right
                        steps.add("${fmt(x)} * ${fmt(right)} = ${fmt(res)}")
                        x = res
                    }
                    match('/') -> {
                        val right = parsePower()
                        if (right == 0.0) throw ArithmeticException("Division by zero")
                        val res = x / right
                        steps.add("${fmt(x)} / ${fmt(right)} = ${fmt(res)}")
                        x = res
                    }
                    else -> return x
                }
            }
        }

        private fun parsePower(): Double {
            var x = parseUnary()
            skipWhitespace()
            if (match('^')) {
                val exp = parsePower()
                val res = x.pow(exp)
                steps.add("${fmt(x)} ^ ${fmt(exp)} = ${fmt(res)}")
                x = res
            }
            return x
        }

        private fun parseUnary(): Double {
            skipWhitespace()
            if (match('+')) return parseUnary()
            if (match('-')) return -parseUnary()
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWhitespace()
            if (match('(')) {
                val x = parseExpression()
                skipWhitespace()
                if (!match(')')) throw IllegalArgumentException("Missing closing ')'")
                return x
            }
            val start = pos
            if (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
                return input.substring(start, pos).toDouble()
            }
            if (pos < input.length && input[pos].isLetter()) {
                while (pos < input.length && (input[pos].isLetterOrDigit())) pos++
                val name = input.substring(start, pos).lowercase()
                if (name == "pi") return PI
                if (name == "e") return E
                skipWhitespace()
                if (!match('(')) throw IllegalArgumentException("Expected '(' after function $name")
                val arg = parseExpression()
                skipWhitespace()
                if (!match(')')) throw IllegalArgumentException("Missing ')' after $name(")
                val res = when (name) {
                    "sin" -> sin(Math.toRadians(arg))
                    "cos" -> cos(Math.toRadians(arg))
                    "tan" -> tan(Math.toRadians(arg))
                    "asin" -> Math.toDegrees(asin(arg))
                    "acos" -> Math.toDegrees(acos(arg))
                    "atan" -> Math.toDegrees(atan(arg))
                    "sqrt" -> sqrt(arg)
                    "ln" -> ln(arg)
                    "log" -> log10(arg)
                    "abs" -> abs(arg)
                    else -> throw IllegalArgumentException("Unknown function '$name'")
                }
                steps.add("$name(${fmt(arg)}) = ${fmt(res)}")
                return res
            }
            throw IllegalArgumentException("Expected number or expression at position ${pos + 1}")
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun match(ch: Char): Boolean {
            if (pos < input.length && input[pos] == ch) {
                pos++
                return true
            }
            return false
        }

        private fun fmt(v: Double): String =
            if (v % 1.0 == 0.0 && abs(v) < 1e9) v.toLong().toString() else "%.4f".format(v).trimEnd('0').trimEnd('.')
    }
}
