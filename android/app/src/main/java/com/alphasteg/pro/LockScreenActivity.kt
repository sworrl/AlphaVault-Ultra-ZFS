package com.alphasteg.pro

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.alphasteg.pro.security.SecurityManager
import java.util.concurrent.Executor

class LockScreenActivity : AppCompatActivity() {

    private lateinit var securityManager: SecurityManager
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPinDisplay: TextView
    private lateinit var btnSubmit: Button
    private lateinit var btnKeyBio: Button
    private lateinit var btnKeyClear: Button

    private val pinButtons = mutableListOf<Button>()
    private var enteredPin = ""

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure app NEVER pops over the Android system lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setInheritShowWhenLocked(false)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_lock_screen)

        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)
        tvPinDisplay = findViewById(R.id.tvPinDisplay)
        btnSubmit = findViewById(R.id.btnSubmit)
        btnKeyBio = findViewById(R.id.btnKeyBio)
        btnKeyClear = findViewById(R.id.btnKeyClear)

        val rootLayout: View = findViewById(R.id.lockRoot)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val cutoutTop = insets.displayCutout?.safeInsetTop ?: statusBarHeight
            v.setPadding(v.paddingLeft, cutoutTop + 8, v.paddingRight, v.paddingBottom)
            insets
        }

        securityManager = SecurityManager(this)

        setupKeypad()
        setupBiometrics()

        if (!securityManager.isVaultSetup()) {
            tvTitle.text = "ALPHAVAULT // ONBOARDING"
            tvStatus.text = "Setup a master PIN for your FLAC Vault"
            btnSubmit.text = "CREATE MASTER VAULT"
        } else {
            tvTitle.text = "ALPHAVAULT ULTRA // LOCKED"
            tvStatus.text = "Tap digits or use Biometric Fingerprint to unlock"
            btnSubmit.text = "UNLOCK VAULT"
            
            // Post biometric prompt to UI message loop to guarantee window focus
            tvTitle.postDelayed({
                triggerBiometricPrompt()
            }, 300)
        }

        btnSubmit.setOnClickListener {
            if (enteredPin.isEmpty()) {
                Toast.makeText(this, "Please enter your PIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!securityManager.isVaultSetup()) {
                securityManager.setupMasterPin(enteredPin, decoyPin = "${enteredPin}99")
                Toast.makeText(this, "Master Vault Setup Successfully!", Toast.LENGTH_SHORT).show()
                proceedToMain(isDecoy = false)
            } else {
                when (securityManager.verifyPin(enteredPin)) {
                    SecurityManager.AuthResult.SUCCESS_MASTER -> proceedToMain(isDecoy = false)
                    SecurityManager.AuthResult.SUCCESS_DECOY -> {
                        Toast.makeText(this, "Decoy Vault Mode Activated", Toast.LENGTH_SHORT).show()
                        proceedToMain(isDecoy = true)
                    }
                    SecurityManager.AuthResult.INVALID -> {
                        Toast.makeText(this, "Invalid PIN!", Toast.LENGTH_SHORT).show()
                        clearPin()
                        randomizeKeypad()
                    }
                }
            }
        }

        btnKeyClear.setOnClickListener {
            if (enteredPin.isNotEmpty()) {
                enteredPin = enteredPin.substring(0, enteredPin.length - 1)
                updatePinDisplay()
            }
        }

        btnKeyBio.setOnClickListener {
            triggerBiometricPrompt()
        }
    }

    private fun setupKeypad() {
        val buttonIds = listOf(
            R.id.btnKey0, R.id.btnKey1, R.id.btnKey2, R.id.btnKey3, R.id.btnKey4,
            R.id.btnKey5, R.id.btnKey6, R.id.btnKey7, R.id.btnKey8, R.id.btnKey9
        )

        buttonIds.forEach { id ->
            val btn: Button = findViewById(id)
            pinButtons.add(btn)
            btn.setOnClickListener {
                if (enteredPin.length < 8) {
                    enteredPin += btn.text.toString()
                    updatePinDisplay()
                    randomizeKeypad()
                }
            }
        }
        randomizeKeypad()
    }

    private fun randomizeKeypad() {
        val digits = (0..9).shuffled()
        pinButtons.forEachIndexed { index, button ->
            button.text = digits[index].toString()
        }
    }

    private fun updatePinDisplay() {
        tvPinDisplay.text = "•".repeat(enteredPin.length)
    }

    private fun clearPin() {
        enteredPin = ""
        updatePinDisplay()
    }

    private fun setupBiometrics() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Toast.makeText(applicationContext, "Biometric Unlock Succeeded!", Toast.LENGTH_SHORT).show()
                    proceedToMain(isDecoy = false)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("AlphaVault Biometric Unlock")
            .setSubtitle("Use your Pixel 10 Fingerprint or Face to unlock your FLAC Vault")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
    }

    private fun triggerBiometricPrompt() {
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            try {
                biometricPrompt.authenticate(promptInfo)
            } catch (e: Exception) {
                Toast.makeText(this, "Biometric Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Biometrics setup required in Android Settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun proceedToMain(isDecoy: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_DECOY_MODE", isDecoy)
        }
        startActivity(intent)
        finish()
    }
}
