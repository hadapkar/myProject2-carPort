package dev.hadapkar.carport.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun JobDialogs(
    job: JobState,
    onConfirmUninstall: () -> Unit,
    onCancelWaiting: () -> Unit,
    onReopenPrompt: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (job) {
        JobState.Idle -> Unit

        is JobState.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(job.title) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.size(16.dp))
                    Text(job.step)
                }
            },
            confirmButton = {
                if (job.canReopenPrompt) {
                    TextButton(onClick = onReopenPrompt) { Text("Show prompt again") }
                }
            },
            dismissButton = {
                if (job.cancellable) {
                    TextButton(onClick = onCancelWaiting) { Text("Cancel") }
                }
            },
        )

        is JobState.ConfirmUninstall -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Replace ${job.label}?") },
            text = {
                Text(
                    "The installed ${job.label} is signed by its developer, so Android must uninstall it before the " +
                        "patched copy can be installed.\n\nThis erases the app's data on this phone (logins, settings, " +
                        "downloads). Patching it again later keeps its data.\n\nIf this app is also in your private " +
                        "space or a cloned profile, remove it there first, or the install will fail."
                )
            },
            confirmButton = { TextButton(onClick = onConfirmUninstall) { Text("Uninstall & continue") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        is JobState.Finished -> AlertDialog(
            // A failed install may be the only copy of the app left: closing it must be deliberate.
            onDismissRequest = { if (!job.canRetry) onDismiss() },
            properties = DialogProperties(
                dismissOnBackPress = !job.canRetry,
                dismissOnClickOutside = !job.canRetry,
            ),
            icon = {
                Icon(
                    imageVector = if (job.success) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (job.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(job.title) },
            text = { Text(job.message, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                if (job.canRetry) {
                    TextButton(onClick = onRetry) { Text("Try again") }
                } else {
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
            },
            dismissButton = {
                if (job.canRetry) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            },
        )
    }
}
