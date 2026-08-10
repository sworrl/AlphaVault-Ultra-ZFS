package com.alphasteg.pro

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.alphasteg.pro.security.SecurityManager

/**
 * Hex-code lock screen. No biometric (Android can't bind a specific finger to
 * duress). Onboarding is mandatory two-step: a master code and a distinct duress
 * code, each at least 8 hex digits. Entering the duress code later wipes the vault.
 */
class LockScreenActivity : AppCompatActivity() {

    private lateinit var securityManager: SecurityManager
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPinDisplay: TextView
    private lateinit var btnSubmit: Button
    private lateinit var btnKeyClear: Button

    private val hexButtons = mutableListOf<Button>()
    private var enteredPin = ""

    private enum class Step { SETUP_MASTER, SETUP_DURESS, LOCKED }
    private var step = Step.LOCKED
    private var pendingMaster = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setInheritShowWhenLocked(false)
        }

        // Hardening: block screenshots, screen recording, and recents thumbnails.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContentView(R.layout.activity_lock_screen)

        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)
        tvPinDisplay = findViewById(R.id.tvPinDisplay)
        btnSubmit = findViewById(R.id.btnSubmit)
        btnKeyClear = findViewById(R.id.btnKeyClear)

        val rootLayout: View = findViewById(R.id.lockRoot)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        securityManager = SecurityManager(this)
        setupKeypad()

        step = if (securityManager.isVaultSetup()) Step.LOCKED else Step.SETUP_MASTER
        applyStep()

        btnSubmit.setOnClickListener { onSubmit() }
        btnKeyClear.setOnClickListener {
            if (enteredPin.isNotEmpty()) {
                enteredPin = enteredPin.dropLast(1)
                updatePinDisplay()
            }
        }
    }

    private fun applyStep() {
        when (step) {
            Step.SETUP_MASTER -> {
                tvTitle.text = getString(R.string.lock_title_onboarding)
                tvStatus.text = getString(R.string.lock_status_setup_master)
                btnSubmit.text = getString(R.string.btn_next)
            }
            Step.SETUP_DURESS -> {
                tvTitle.text = getString(R.string.lock_title_onboarding)
                tvStatus.text = getString(R.string.lock_status_setup_duress)
                btnSubmit.text = getString(R.string.btn_create_vault)
            }
            Step.LOCKED -> {
                tvTitle.text = getString(R.string.lock_title_locked)
                tvStatus.text = getString(R.string.lock_status_pin_only)
                btnSubmit.text = getString(R.string.btn_unlock)
            }
        }
        clearPin()
    }

    private fun onSubmit() {
        when (step) {
            Step.SETUP_MASTER -> {
                if (!securityManager.isValidPin(enteredPin)) {
                    toast("Master code must be at least ${SecurityManager.MIN_LEN} hex digits.")
                    return
                }
                pendingMaster = enteredPin
                step = Step.SETUP_DURESS
                applyStep()
            }
            Step.SETUP_DURESS -> {
                if (!securityManager.isValidPin(enteredPin)) {
                    toast("Duress code must be at least ${SecurityManager.MIN_LEN} hex digits.")
                    return
                }
                if (enteredPin.equals(pendingMaster, ignoreCase = true)) {
                    toast("Duress code must be different from the master code.")
                    return
                }
                securityManager.setupCredentials(pendingMaster, enteredPin)
                toast("Vault created.")
                proceedToMain(isDecoy = false, wipe = false, key = pendingMaster)
            }
            Step.LOCKED -> {
                if (enteredPin.isEmpty()) { toast("Enter your code"); return }
                when (securityManager.verifyPin(enteredPin)) {
                    SecurityManager.AuthResult.SUCCESS_MASTER ->
                        proceedToMain(isDecoy = false, wipe = false, key = enteredPin)
                    SecurityManager.AuthResult.SUCCESS_DURESS -> {
                        // Duress: destroy credentials, launch a wiping, empty-looking vault.
                        securityManager.wipeCredentials()
                        proceedToMain(isDecoy = true, wipe = true, key = enteredPin)
                    }
                    SecurityManager.AuthResult.INVALID -> {
                        toast("Invalid code")
                        clearPin()
                        randomizeKeypad()
                    }
                }
            }
        }
    }

    private fun setupKeypad() {
        val ids = listOf(
            R.id.btnHex0, R.id.btnHex1, R.id.btnHex2, R.id.btnHex3,
            R.id.btnHex4, R.id.btnHex5, R.id.btnHex6, R.id.btnHex7,
            R.id.btnHex8, R.id.btnHex9, R.id.btnHex10, R.id.btnHex11,
            R.id.btnHex12, R.id.btnHex13, R.id.btnHex14, R.id.btnHex15
        )
        ids.forEach { id ->
            val btn = findViewById<Button>(id)
            hexButtons.add(btn)
            btn.setOnClickListener {
                if (enteredPin.length < MAX_LEN) {
                    enteredPin += btn.text.toString()
                    updatePinDisplay()
                    randomizeKeypad()
                }
            }
        }
        randomizeKeypad()
    }

    private fun randomizeKeypad() {
        val hex = "0123456789abcdef".toList().shuffled()
        hexButtons.forEachIndexed { i, btn -> btn.text = hex[i].toString() }
    }

    private fun updatePinDisplay() {
        tvPinDisplay.text = "•".repeat(enteredPin.length)
    }

    private fun clearPin() {
        enteredPin = ""
        updatePinDisplay()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun proceedToMain(isDecoy: Boolean, wipe: Boolean, key: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_DECOY_MODE", isDecoy)
            putExtra("EXTRA_WIPE", wipe)
            putExtra("EXTRA_VAULT_KEY", key)
        }
        startActivity(intent)
        finish()
    }

    companion object {
        private const val MAX_LEN = 32
    }
}
