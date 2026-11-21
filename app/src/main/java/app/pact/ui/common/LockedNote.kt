package app.pact.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pact.PactApp

/** True while the Mac has settings unlocked. */
@Composable
fun rememberUnlocked(): Boolean {
    val app = LocalContext.current.applicationContext as PactApp
    val unlocked by app.lock.unlocked.collectAsStateWithLifecycle()
    return unlocked
}

@Composable
fun LockedNote(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Locked. To change, plug into the Mac and run pact-unlock.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}
