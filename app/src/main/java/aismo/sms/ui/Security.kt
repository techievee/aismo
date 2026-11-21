package aismo.sms.ui

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import aismo.sms.container
import aismo.sms.data.security.PinCrypto
import aismo.sms.data.settings.AppSettings
import aismo.sms.data.settings.UnlockMethod

/**
 * The shared unlock prompt. When the phone has a fingerprint enrolled it is asked first, with a "Use PIN instead"
 * button when the app has a PIN; the PIN is entered in a dialog of its own. A success opens the categories for the
 * chosen window ([onUnlock] is then called once); [onDismiss] is called when the user gives up; neither is called twice.
 */
@Composable
fun SecurityPromptDialog(onUnlock: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val s by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val methods = remember(s) { c.security.availableMethods(s) }
    // Start on the fingerprint when it is one of the available methods, otherwise straight to the PIN dialog.
    var pinMode by remember { mutableStateOf(!(UnlockMethod.FINGERPRINT in methods)) }
    var error by remember { mutableStateOf<String?>(null) }
    val activity = LocalContext.current as? FragmentActivity

    fun succeeded() {
        c.security.unlock(s.unlockWindowMinutes * 60_000L)
        onUnlock()
    }

    LaunchedEffect(Unit) {
        if (!pinMode) {
            if (activity == null) {
                pinMode = true
                return@LaunchedEffect
            }
            // The library's biometric-only prompt (it never offers the device credential) requires a negative button:
            // the fallback to the PIN when there is one, a plain cancel when there is not.
            val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock")
                .setSubtitle("Your locked categories open for ${s.unlockWindowMinutes} minute${if (s.unlockWindowMinutes == 1) "" else "s"} after this.")
                .setConfirmationRequired(false)
                .setNegativeButtonText(if (UnlockMethod.PIN in methods) "Use PIN instead" else "Cancel")
                .build()
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = succeeded()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        // A deliberate cancel with no fallback: just close, no error.
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_UNABLE_TO_PROCESS -> onDismiss()
                        else -> if (UnlockMethod.PIN in methods) pinMode = true else { error = errString.toString(); onDismiss() }
                    }
                }
            })
            runCatching { prompt.authenticate(info) }
                .onFailure { if (UnlockMethod.PIN in methods) pinMode = true else { error = "The fingerprint could not be used."; onDismiss() } }
        }
    }

    if (pinMode) {
        PinEntryDialog(onUnlock = { pin -> if (c.security.verifyPin(pin)) succeeded() else error = "That PIN is not right." }, onDismiss = onDismiss, error = error)
    }
}

/** The in-app PIN entry: a short numeric code, never sent anywhere. */
@Composable
internal fun PinEntryDialog(onUnlock: (String) -> Unit, onDismiss: () -> Unit, error: String?) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter your PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all { ch -> ch.isDigit() }) pin = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUnlock(pin) }, enabled = PinCrypto.isAcceptable(pin)) { Text("Unlock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The Security group in Settings: the PIN itself (set, change, remove), the way a locked category is unlocked, and
 * how long an unlock keeps it open.
 */
@Composable
fun SecuritySection(s: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    // null = closed; false = create a PIN; true = change it.
    var pinDialog by remember { mutableStateOf<Boolean?>(null) }
    var methodDialog by remember { mutableStateOf(false) }
    var windowDialog by remember { mutableStateOf(false) }
    var confirmRemovePin by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf(false) }

    // A PIN is set and cleared without a settings write, so the method set keys on both.
    val pinPresent by c.security.pinPresent.collectAsStateWithLifecycle()
    val methods = remember(s, pinPresent) { c.security.availableMethods(s) }
    val hasPin = UnlockMethod.PIN in methods
    val hasFingerprint = UnlockMethod.FINGERPRINT in methods

    val accent = SettingsAccent.Security
    SettingsGroup("Security", Icons.Filled.Lock, accent) {
        // The actual unlock: the fingerprint or the PIN, then the categories open for the chosen window. Without a
        // method yet, the tap goes to creating one instead.
        NavRow(
            Icons.Filled.Fingerprint, accent, "Unlock categories",
            when {
                s.securedCategories.isEmpty() -> "Nothing is locked yet; lock a category under Inbox categories"
                !hasPin && !hasFingerprint -> "No way to unlock right now; set a PIN"
                else -> "Opens the locked categories; they stay open for ${s.unlockWindowMinutes} minutes"
            },
        ) { if (hasPin || hasFingerprint) unlocking = true else pinDialog = false }
        NavRow(Icons.Filled.Key, accent, "PIN", if (hasPin) "Set" else "Not set") {
            pinDialog = if (hasPin) true else false
        }
        NavRow(Icons.Filled.LockOpen, accent, "Unlock with", if (hasPin || hasFingerprint) s.unlockMethod.label else "Set a PIN first") {
            if (hasPin || hasFingerprint) methodDialog = true else pinDialog = false
        }
        NavRow(Icons.Filled.Timer, accent, "Unlock for", "${s.unlockWindowMinutes} minutes") { windowDialog = true }
        if (s.securedCategories.isNotEmpty() && methods.isEmpty()) {
            Text(
                "Your unlock method is no longer available, so your secured categories are visible. Set a PIN or enroll a fingerprint to lock them again.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (pinDialog != null) {
        PinSetDialog(
            changing = pinDialog == true,
            onSaved = { pinDialog = null },
            onRemove = { confirmRemovePin = true },
            onDismiss = { pinDialog = null },
        )
    }
    if (confirmRemovePin) {
        AlertDialog(
            onDismissRequest = { confirmRemovePin = false },
            title = { Text("Remove your PIN?") },
            text = { Text("Locked categories will unlock with the fingerprint only, or stay visible if this phone has none.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { c.security.clearPin() }; confirmRemovePin = false; pinDialog = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemovePin = false }) { Text("Cancel") } },
        )
    }
    if (methodDialog) {
        val options = buildList {
            if (hasFingerprint) add(UnlockMethod.FINGERPRINT)
            if (hasPin) add(UnlockMethod.PIN)
            add(UnlockMethod.BOTH)
        }
        ChoiceDialog(
            "Unlock with",
            options.map { it.label },
            options.indexOf(s.unlockMethod).coerceAtLeast(0),
            { i -> scope.launch { c.settings.update { it.copy(unlockMethod = options[i]) } } },
        ) { methodDialog = false }
    }
    if (windowDialog) {
        val options = listOf(1, 5, 10, 30, 60)
        ChoiceDialog(
            "Unlock for",
            options.map { if (it == 1) "1 minute" else "$it minutes" },
            options.indexOf(s.unlockWindowMinutes).coerceAtLeast(0),
            { i -> scope.launch { c.settings.update { it.copy(unlockWindowMinutes = options[i]) } } },
        ) { windowDialog = false }
    }
    if (unlocking) {
        SecurityPromptDialog(onUnlock = { unlocking = false }, onDismiss = { unlocking = false })
    }
}

/** Set a first PIN (with a confirmation), or change it (asking for the current one first). */
@Composable
private fun PinSetDialog(changing: Boolean, onSaved: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    var current by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = when {
        !changing -> PinCrypto.isAcceptable(first) && first == second
        else -> PinCrypto.isAcceptable(second)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (changing) "Change your PIN" else "Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (changing) {
                    PinField("Current PIN", current) { current = it }
                    PinField("New PIN", first) { first = it }
                    PinField("Confirm new PIN", second) { second = it }
                } else {
                    PinField("New PIN", first) { first = it }
                    PinField("Confirm PIN", second) { second = it }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) }
                if (changing) {
                    Row {
                        TextButton(onClick = onRemove) { Text("Remove PIN") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val saved = if (changing) c.security.changePin(current, second) else {
                        c.security.setPin(first)
                        true
                    }
                    if (saved) onSaved() else error = if (changing) "That PIN is not right." else "The PINs do not match."
                },
                enabled = valid,
            ) { Text(if (changing) "Save" else "Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PinField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all { ch -> ch.isDigit() }) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}
