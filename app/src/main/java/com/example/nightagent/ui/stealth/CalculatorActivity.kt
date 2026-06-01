package com.example.nightagent.ui.stealth

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightagent.MainActivity
import com.example.nightagent.stealth.StealthRepository
import com.example.nightagent.ui.theme.NightagentTheme

/**
 * Fake Calculator that serves as the app's disguise launcher.
 *
 * It is a fully functional calculator. The hidden unlock mechanism:
 *   1. Enter your PIN digits using the number buttons.
 *   2. Press "=" to submit.
 *   3. If the PIN matches (or no PIN is set yet), the real app opens.
 *
 * If Stealth Mode is disabled in settings, tapping "=" with no PIN
 * input also opens the real app directly (so first-time users aren't locked out).
 */
class CalculatorActivity : ComponentActivity() {

    private lateinit var stealthRepo: StealthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stealthRepo = StealthRepository(this)

        setContent {
            NightagentTheme {
                CalculatorScreen(
                    onUnlock = { openMainApp() },
                    stealthRepo = stealthRepo
                )
            }
        }
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}

// ── Calculator UI ─────────────────────────────────────────────────────────────

@Composable
fun CalculatorScreen(
    onUnlock: () -> Unit,
    stealthRepo: StealthRepository
) {
    // Calculator state
    var display by remember { mutableStateOf("0") }
    var firstOperand by remember { mutableStateOf<Double?>(null) }
    var pendingOp by remember { mutableStateOf<String?>(null) }
    var justEvaluated by remember { mutableStateOf(false) }

    // PIN buffer — digits entered since last clear/operator
    var pinBuffer by remember { mutableStateOf("") }

    // Feedback for wrong PIN
    var showWrongPin by remember { mutableStateOf(false) }
    var lockoutMessage by remember { mutableStateOf<String?>(null) }

    val bgColor = Color(0xFF1C1C1E)
    val displayBg = Color(0xFF2C2C2E)
    val opColor = Color(0xFFFF9F0A)
    val numColor = Color(0xFF3A3A3C)
    val funcColor = Color(0xFF636366)
    val textColor = Color.White

    fun handleEquals() {
        // ── PIN check ──────────────────────────────────────────────────────
        if (stealthRepo.isLockedOut()) {
            lockoutMessage = "Too many attempts. Wait ${stealthRepo.lockoutRemainingSeconds()}s"
            showWrongPin = true
            display = "Locked"
            return
        }

        val stealthEnabled = stealthRepo.isStealthModeEnabled.value
        val pinSet = stealthRepo.isPinSet()

        // If stealth mode is off OR no PIN set yet → open app directly
        if (!stealthEnabled || !pinSet) {
            onUnlock()
            return
        }

        // Verify PIN
        if (stealthRepo.verifyPin(pinBuffer)) {
            stealthRepo.resetFailedAttempts()
            onUnlock()
            return
        }

        // Wrong PIN
        val attempts = stealthRepo.recordFailedAttempt()
        val remaining = StealthRepository.MAX_ATTEMPTS - attempts
        lockoutMessage = if (remaining > 0) "Wrong PIN ($remaining attempts left)" else null
        showWrongPin = true
        display = "0"
        pinBuffer = ""

        // Auto-hide the error after 2 s
        Handler(Looper.getMainLooper()).postDelayed({
            showWrongPin = false
            lockoutMessage = null
        }, 2000)
    }

    fun handleDigit(d: String) {
        pinBuffer += d
        if (justEvaluated) {
            display = d
            justEvaluated = false
        } else {
            display = if (display == "0") d else display + d
        }
        if (display.length > 12) display = display.takeLast(12)
    }

    fun handleOperator(op: String) {
        pinBuffer = ""   // reset PIN buffer on operator press
        val current = display.toDoubleOrNull() ?: 0.0
        if (firstOperand != null && pendingOp != null && !justEvaluated) {
            val result = calculate(firstOperand!!, current, pendingOp!!)
            display = formatResult(result)
            firstOperand = result
        } else {
            firstOperand = current
        }
        pendingOp = op
        justEvaluated = true
    }

    fun handleClear() {
        display = "0"
        firstOperand = null
        pendingOp = null
        justEvaluated = false
        pinBuffer = ""
        showWrongPin = false
        lockoutMessage = null
    }

    fun handleToggleSign() {
        val v = display.toDoubleOrNull() ?: return
        display = formatResult(-v)
    }

    fun handlePercent() {
        val v = display.toDoubleOrNull() ?: return
        display = formatResult(v / 100.0)
    }

    fun handleDecimal() {
        if (!display.contains('.')) display += "."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        // ── Display ──────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(displayBg)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Column(horizontalAlignment = Alignment.End) {
                if (showWrongPin && lockoutMessage != null) {
                    Text(
                        text = lockoutMessage!!,
                        color = Color(0xFFFF453A),
                        fontSize = 14.sp
                    )
                }
                Text(
                    text = display,
                    color = textColor,
                    fontSize = if (display.length > 9) 36.sp else 56.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.End,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Button grid ──────────────────────────────────────────────────────
        val rows = listOf(
            listOf("AC", "+/-", "%", "÷"),
            listOf("7",  "8",  "9", "×"),
            listOf("4",  "5",  "6", "−"),
            listOf("1",  "2",  "3", "+"),
            listOf("0",  ".",  "=")
        )

        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { label ->
                    val isWide = label == "0"
                    val btnColor = when (label) {
                        "AC", "+/-", "%" -> funcColor
                        "÷", "×", "−", "+", "=" -> opColor
                        else -> numColor
                    }
                    CalcButton(
                        label = label,
                        color = btnColor,
                        textColor = textColor,
                        modifier = if (isWide) Modifier.weight(2f) else Modifier.weight(1f)
                    ) {
                        when (label) {
                            "AC"  -> handleClear()
                            "+/-" -> handleToggleSign()
                            "%"   -> handlePercent()
                            "÷"   -> handleOperator("/")
                            "×"   -> handleOperator("*")
                            "−"   -> handleOperator("-")
                            "+"   -> handleOperator("+")
                            "="   -> handleEquals()
                            "."   -> handleDecimal()
                            else  -> handleDigit(label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalcButton(
    label: String,
    color: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun calculate(a: Double, b: Double, op: String): Double = when (op) {
    "+" -> a + b
    "-" -> a - b
    "*" -> a * b
    "/" -> if (b != 0.0) a / b else Double.NaN
    else -> b
}

private fun formatResult(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "Error"
    return if (value == kotlin.math.floor(value) && !value.isInfinite()) {
        value.toLong().toString()
    } else {
        "%.8g".format(value).trimEnd('0').trimEnd('.')
    }
}
