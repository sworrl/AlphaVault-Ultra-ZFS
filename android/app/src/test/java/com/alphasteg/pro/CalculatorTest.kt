package com.alphasteg.pro

import com.alphasteg.pro.calc.Calculator
import org.junit.Assert.assertEquals
import org.junit.Test

class CalculatorTest {

    private fun eval(s: String) = Calculator.evaluate(s)

    @Test fun arithmetic() {
        assertEquals(7.0, eval("1+2*3"), 1e-9)
        assertEquals(9.0, eval("(1+2)*3"), 1e-9)
        assertEquals(2.0, eval("8/4"), 1e-9)
        assertEquals(1.0, eval("10%3"), 1e-9)
    }

    @Test fun powerIsRightAssociative() {
        assertEquals(512.0, eval("2^3^2"), 1e-9)
    }

    @Test fun functionsAndConstants() {
        assertEquals(0.0, eval("sin(0)"), 1e-9)
        assertEquals(1.0, eval("cos(0)"), 1e-9)
        assertEquals(3.0, eval("sqrt(9)"), 1e-9)
        assertEquals(2.0, eval("log(100)"), 1e-9)
        assertEquals(1.0, eval("ln(e)"), 1e-9)
        assertEquals(Math.PI, eval("π"), 1e-9)
    }

    @Test fun factorial() {
        assertEquals(120.0, eval("5!"), 1e-9)
        assertEquals(720.0, eval("(3+3)!"), 1e-9)
    }

    @Test fun unicodeOperators() {
        assertEquals(6.0, eval("2×3"), 1e-9)
        assertEquals(4.0, eval("8÷2"), 1e-9)
    }
}
