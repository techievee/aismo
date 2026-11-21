package aismo.sms.ui

import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import aismo.sms.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(context.canReadSms()) }
    var isDefault by remember { mutableStateOf(context.isDefaultSmsApp()) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = context.canReadSms()
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = context.isDefaultSmsApp()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.ic_aismo_logo), contentDescription = null, modifier = Modifier.size(72.dp))
            Column {
                Text("aismo", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("AI SMS Manager", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "Your messages, sorted automatically. Codes, bank alerts, bills, orders and travel are grouped for you. " +
                "Everything stays on this phone: all sorting, search and AI run here, and nothing is ever sent to the internet.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Step(
            done = permissionsGranted,
            title = "Allow access to your messages",
            body = "Needed to read, organize and send SMS. Contacts show names instead of numbers.",
            action = "Grant access",
            onClick = { permissionLauncher.launch(RuntimePermissions) },
        )
        Step(
            done = isDefault,
            title = "Make this your default SMS app",
            body = "Required to receive and send messages, and to delete or block senders. You can switch back anytime.",
            action = "Set as default",
            onClick = {
                val rm = context.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onContinue, enabled = permissionsGranted, modifier = Modifier.fillMaxWidth()) {
            Text(if (isDefault) "Continue" else "Continue without default app")
        }
        if (!permissionsGranted) {
            Text(
                "Messages can't be shown until access is granted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Step(done: Boolean, title: String, body: String, action: String, onClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (done) "Done" else "Not done",
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!done) OutlinedButton(onClick = onClick) { Text(action) } else TextButton(onClick = {}, enabled = false) { Text("Done") }
        }
    }
}
