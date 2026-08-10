package com.alphasteg.pro.calc

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A small scientific expression evaluator: + - * / ^ %, parentheses, unary
 * minus, factorial (!), the functions sin cos tan sqrt ln log, and the constants
 * pi and e. Recursive-descent, radians. Throws on malformed input so the UI can
 * show "Error".
 */
object Calculator {

    fun evaluate(input: String): Double {
        val normalized = input
            .replace("×", "*").replace("÷", "/")
            .replace("π", "π").replace("√", "sqrt")
        val parser = Parser(normalized)
        val value = parser.parseExpression()
        parser.expectEnd()
        return value
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun expectEnd() {
            skipWs()
            if (pos != s.length) throw IllegalArgumentException("Unexpected '${s[pos]}'")
        }

        // expression := term (('+' | '-') term)*
        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        // term := power (('*' | '/' | '%') power)*
        private fun parseTerm(): Double {
            var value = parsePower()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { pos++; value *= parsePower() }
                    '/' -> { pos++; value /= parsePower() }
                    '%' -> { pos++; value %= parsePower() }
                    else -> return value
                }
            }
        }

        // power := factor ('^' power)?  (right-associative)
        private fun parsePower(): Double {
            val base = parseFactor()
            skipWs()
            return if (peek() == '^') { pos++; base.pow(parsePower()) } else base
        }

        // factor := unary with optional postfix '!'
        private fun parseFactor(): Double {
            skipWs()
            var value = parseUnary()
            skipWs()
            while (peek() == '!') { pos++; value = factorial(value); skipWs() }
            return value
        }

        private fun parseUnary(): Double {
            skipWs()
            return when (peek()) {
                '-' -> { pos++; -parseUnary() }
                '+' -> { pos++; parseUnary() }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipWs()
            val c = peek() ?: throw IllegalArgumentException("Unexpected end")
            if (c == '(') {
                pos++
                val v = parseExpression()
                skipWs()
                if (peek() != ')') throw IllegalArgumentException("Missing ')'")
                pos++
                return v
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter() || c == 'π') return parseIdentifier()
            throw IllegalArgumentException("Unexpected '$c'")
        }

        private fun parseNumber(): Double {
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            return s.substring(start, pos).toDouble()
        }

        private fun parseIdentifier(): Double {
            if (peek() == 'π') { pos++; return Math.PI }
            val start = pos
            while (peek()?.isLetter() == true) pos++
            val name = s.substring(start, pos).lowercase()
            return when (name) {
                "pi" -> Math.PI
                "e" -> Math.E
                "sin" -> sin(parseParenArg())
                "cos" -> cos(parseParenArg())
                "tan" -> tan(parseParenArg())
                "sqrt" -> sqrt(parseParenArg())
                "ln" -> ln(parseParenArg())
                "log" -> log10(parseParenArg())
                else -> throw IllegalArgumentException("Unknown '$name'")
            }
        }

        private fun parseParenArg(): Double {
            skipWs()
            if (peek() != '(') throw IllegalArgumentException("Expected '('")
            pos++
            val v = parseExpression()
            skipWs()
            if (peek() != ')') throw IllegalArgumentException("Missing ')'")
            pos++
            return v
        }

        private fun factorial(x: Double): Double {
            val n = x.toInt()
            if (n < 0 || n.toDouble() != x) throw IllegalArgumentException("Bad factorial")
            var r = 1.0
            for (i in 2..n) r *= i
            return r
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null
        private fun skipWs() { while (pos < s.length && s[pos] == ' ') pos++ }
    }
}
