package com.peexr.sift.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peexr.sift.SiftApplication
import com.peexr.sift.data.embedding.ModelState
import com.peexr.sift.ui.theme.SiftTheme

/**
 * Plain Settings dialog displaying on-device model status, privacy declaration, and app version.
 */
@Composable
fun SettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as SiftApplication
    val modelState by app.modelStore.state.collectAsState()
    val isInstalled = remember(modelState) { app.modelStore.isInstalled() }

    val versionName = remember(context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "1.0.0"
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            }
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = SiftTheme.extended.displayInk
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section "Smart search model"
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Smart search model",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val (statusLabel, showDownloadButton) = when {
                        isInstalled || modelState is ModelState.Ready -> {
                            Pair("Installed", false)
                        }
                        modelState is ModelState.Downloading -> {
                            val downloading = modelState as ModelState.Downloading
                            val total = downloading.bytesTotal.coerceAtLeast(1L)
                            val pct = ((downloading.bytesDone.toFloat() / total.toFloat()) * 100).toInt().coerceIn(0, 100)
                            Pair("Downloading ($pct%)", false)
                        }
                        modelState is ModelState.Failed -> {
                            Pair("Download failed", true)
                        }
                        else -> {
                            Pair("Not installed", true)
                        }
                    }

                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)) {
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = if (isInstalled || modelState is ModelState.Ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "On-device AI for search and collections",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (showDownloadButton) {
                                Button(
                                    onClick = { app.startModelDownload() },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text("Download model")
                                }
                            }
                        }
                    }
                }

                // Privacy notice line
                Text(
                    text = "Everything runs on your phone. Your screenshots never leave it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Version display
                Text(
                    text = "Version $versionName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = CircleShape
            ) {
                Text("Done")
            }
        },
        shape = MaterialTheme.shapes.extraLarge
    )
}
