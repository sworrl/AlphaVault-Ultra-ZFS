package com.alphasteg.pro

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.alphasteg.pro.calc.Calculator
import com.alphasteg.pro.security.SecurityManager
import java.util.Locale

/**
 * The disguise: a working scientific calculator. It is the launcher face when
 * disguise mode is on. It also quietly watches the keys the user presses; if the
 * master or duress code appears in that stream (typed bare or inside an
 * equation), the real app unlocks. Everything else behaves as a normal calculator.
 */
class CalculatorActivity : AppCompatActivity() {

    private data class Key(val label: String, val exprView: String?, val pin: String?)

    private val entries = mutableListOf<Key>()
    private lateinit var exprView: TextView
    private lateinit var result: TextView
    private lateinit var security: SecurityManager
    private var handled = false

    private val rows = listOf(
        listOf("AC", "(", ")", "√", "^", "!"),
        listOf("sin", "cos", "tan", "ln", "log", "π"),
        listOf("A", "B", "C", "D", "E", "F"),
        listOf("7", "8", "9", "÷", "%", "@"),
        listOf("4", "5", "6", "×", "&", "#"),
        listOf("1", "2", "3", "-", "*", "$"),
        listOf("0", ".", "e", "+", "?", "⌫")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        security = SecurityManager(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0D"))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        result = TextView(this).apply {
            setTextColor(Color.parseColor("#8A8A8F"))
            textSize = 20f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        exprView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 40f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(16))
            maxLines = 2
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1.4f)
            gravity = Gravity.BOTTOM
            addView(result)
            addView(exprView)
        }
        root.addView(screen)

        for (row in rows) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
            for (label in row) rowView.addView(keyButton(label))
            root.addView(rowView)
        }
        // Full-width equals
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(keyButton("="))
        })

        setContentView(root)
        refresh()
    }

    private fun keyButton(label: String): Button {
        val accent = label in listOf("÷", "×", "-", "+", "=", "^", "AC", "⌫")
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = if (label.length > 2) 15f else 20f
            setTextColor(if (accent) Color.parseColor("#00F2FE") else Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#161619"))
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(1, 1, 1, 1) }
            setOnClickListener { onKey(label) }
        }
    }

    private fun onKey(label: String) {
        when (label) {
            "AC" -> entries.clear()
            "⌫" -> if (entries.isNotEmpty()) entries.removeAt(entries.lastIndex)
            "=" -> { evaluate(); return }
            "√" -> add("sqrt(", null)
            "sin" -> add("sin(", null)
            "cos" -> add("cos(", null)
            "tan" -> add("tan(", null)
            "ln" -> add("ln(", null)
            "log" -> add("log(", null)
            "π" -> add("π", null)
            "e" -> add("e", "e")
            in listOf("A", "B", "C", "D", "E", "F") -> add(label, label.lowercase(Locale.US))
            in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9") -> add(label, label)
            in listOf("!", "@", "#", "$", "%", "&", "*", "?") -> add(label, label)
            else -> add(label, null) // + - × ÷ ^ ( ) .
        }
        refresh()
    }

    private fun add(displayToken: String, pin: String?) {
        entries.add(Key(displayToken, displayToken, pin))
    }

    @Volatile private var checking = false

    private fun refresh() {
        exprView.text = entries.joinToString("") { it.exprView ?: "" }
        val pinTrail = entries.mapNotNull { it.pin }.joinToString("")
        // The code check is slow PBKDF2, so run it off the UI thread. Skip if a
        // check is already in flight or we've already unlocked.
        if (handled || checking || pinTrail.length < SecurityManager.MIN_LEN) return
        checking = true
        Thread {
            val match = runCatching { security.findCode(pinTrail) }.getOrNull()
            runOnUiThread {
                checking = false
                if (match != null && !handled) {
                    handled = true
                    unlock(match)
                }
            }
        }.start()
    }

    private fun evaluate() {
        val expr = entries.joinToString("") { it.exprView ?: "" }
        if (expr.isBlank()) return
        try {
            val value = Calculator.evaluate(expr)
            val text = if (value == value.toLong().toDouble())
                value.toLong().toString()
            else
                String.format(Locale.US, "%.10g", value).trimEnd('0').trimEnd('.')
            result.text = expr
            entries.clear()
            entries.add(Key(text, text, null))
            exprView.text = text
        } catch (e: Exception) {
            result.text = "Error"
        }
    }

    private fun unlock(match: SecurityManager.Match) {
        val duress = match.result == SecurityManager.AuthResult.SUCCESS_DURESS
        if (duress) security.wipeCredentials()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra("EXTRA_DECOY_MODE", duress)
                putExtra("EXTRA_WIPE", duress)
                putExtra("EXTRA_VAULT_KEY", match.code)
            }
        )
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
