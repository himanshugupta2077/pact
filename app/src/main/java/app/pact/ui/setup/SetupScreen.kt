package app.pact.ui.setup

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.pact.PactApp
import app.pact.enforce.PactNotificationListener
import app.pact.enforce.SecureSettings
import app.pact.ui.common.SwitchRow
import app.pact.ui.theme.Paper
import app.pact.ui.theme.Rust
import app.pact.ui.theme.Sage
import app.pact.ui.theme.pactFieldColors

@Composable
fun SetupScreen(
    usageOk: Boolean,
    accessOk: Boolean,
    notificationOk: Boolean,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PactApp
    val scheme = MaterialTheme.colorScheme
    var serverUrl by remember { mutableStateOf(app.overrides.serverUrl()) }
    var serverSaved by remember { mutableStateOf(false) }
    val grayscale by app.focus.grayscale.collectAsStateWithLifecycle()
    val colorApps by app.focus.colorApps.collectAsStateWithLifecycle()
    val sites by app.focus.blockedSites.collectAsStateWithLifecycle()
    val canWriteSecure = remember { SecureSettings.canWrite(context) }
    var siteDraft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
    ) {
        Text("PACT", style = MaterialTheme.typography.labelSmall, color = Rust)
        Spacer(Modifier.height(12.dp))
        Text(
            "Permissions required",
            style = MaterialTheme.typography.headlineLarge,
            color = scheme.onBackground,
        )
        Spacer(Modifier.height(24.dp))

        PermissionCard(
            icon = Icons.Outlined.Schedule,
            title = "Usage access",
            body = "So Pact can see how long each app has been open today.",
            granted = usageOk,
            action = "Grant",
            onAction = {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            icon = Icons.Outlined.Lock,
            title = "Accessibility",
            body = "So Pact can notice full-screen, pop-up, and picture-in-picture windows and cover them.",
            granted = accessOk,
            action = "Enable",
            onAction = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            icon = Icons.Outlined.NotificationsActive,
            title = "Notification access",
            body = "So Pact can pause YouTube and other background playback when the limit is hit. It does not read your messages.",
            granted = notificationOk,
            action = "Grant",
            onAction = {
                val cn = ComponentName(context, PactNotificationListener::class.java)
                val intent = if (Build.VERSION.SDK_INT >= 30) {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                        putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
                    }
                } else {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .recoverCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
            },
        )

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onContinue,
            enabled = usageOk,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Paper),
        ) {
            Text(
                when {
                    accessOk && notificationOk -> "Continue"
                    accessOk -> "Continue without media pause"
                    else -> "Continue without blocking"
                },
                color = Paper,
            )
        }
        if (!usageOk) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Usage access is required to measure time.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(28.dp))
        SwitchRow(
            title = "Black and white",
            body = if (canWriteSecure) {
                val kept = colorApps.size
                val apps = if (kept == 1) "1 app" else "$kept apps"
                "Grey screen all the time. Color comes back during calls and in $apps " +
                    "you keep in color (switch on each app's page)."
            } else {
                "Needs one permission from a computer first: ${SecureSettings.GRANT_COMMAND}"
            },
            checked = grayscale && canWriteSecure,
            enabled = canWriteSecure,
            onChange = { on ->
                app.focus.setGrayscale(on)
                if (!on) SecureSettings.setGrayscale(context, false)
            },
        )

        Spacer(Modifier.height(28.dp))
        Text("Blocked websites", style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Closed in Chrome, Brave, Firefox and other common browsers. Subdomains count too, so youtube.com covers m.youtube.com.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        sites.sorted().forEach { host ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    host,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { app.focus.removeSite(host) }) {
                    Text("Remove", color = scheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = siteDraft,
                onValueChange = { siteDraft = it.take(100) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("e.g. facebook.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(14.dp),
                colors = pactFieldColors(),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { if (app.focus.addSite(siteDraft) != null) siteDraft = "" },
                enabled = siteDraft.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onBackground),
            ) {
                Text("Add", color = scheme.onBackground)
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("Override server", style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Requests go here. Open /pact on that server to approve them.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = {
                serverUrl = it
                serverSaved = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server URL") },
            placeholder = { Text("https://pact.example") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(14.dp),
            colors = pactFieldColors(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                app.overrides.setServerUrl(serverUrl)
                serverUrl = app.overrides.serverUrl()
                serverSaved = true
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onBackground),
        ) {
            Text(if (serverSaved) "Saved" else "Save server", color = scheme.onBackground)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData("package:${context.packageName}".toUri())
                runCatching { context.startActivity(intent) }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("App info", color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    body: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = if (granted) Sage else Rust, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
                Text(
                    if (granted) "On" else "Required",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) Sage else Rust,
                )
            }
            if (granted) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Sage)
            } else {
                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onBackground),
                ) {
                    Text(action, color = scheme.onBackground)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}
