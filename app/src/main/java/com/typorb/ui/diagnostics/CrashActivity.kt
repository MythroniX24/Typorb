package com.typorb.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typorb.diagnostics.CrashLog
import com.typorb.diagnostics.CrashReport
import com.typorb.diagnostics.CrashStore
import com.typorb.ui.components.PrimaryButton
import com.typorb.ui.components.SecondaryButton
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbTheme

/**
 * Shows the last uncaught exception instead of letting the user stare at the launcher.
 *
 * Reached from [com.typorb.ui.dashboard.MainActivity] whenever [CrashStore] holds a report. The
 * trace is the point: on a phone with no logcat access, "the app closed" gives nobody anything to
 * work with, while the exception and its frames identify the defect directly.
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = CrashStore(this)
        // peek, not consume: the report must survive rotation and a process restart while it is
        // on screen, and only a deliberate tap on Dismiss should clear it.
        val report = store.peek()

        if (report == null) {
            finish()
            return
        }

        setContent {
            TyporbTheme {
                CrashScreen(
                    report = report,
                    onDismiss = {
                        store.clear()
                        startActivity(
                            Intent(this, com.typorb.ui.dashboard.MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun CrashScreen(
    report: CrashReport,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TyporbPalette.Background)
            .padding(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(TyporbPalette.DangerTint),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = TyporbPalette.Danger,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column {
                    Text(
                        text = "Typorb hit an error",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TyporbPalette.TextPrimary,
                    )
                    Text(
                        text = "It was caught instead of closing. Details below.",
                        fontSize = 12.sp,
                        color = TyporbPalette.TextSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // The headline error, in monospace so nested colons and generics stay readable.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TyporbShapeHolder.Card)
                    .background(TyporbPalette.DangerTint)
                    .border(
                        BorderStroke(1.dp, TyporbPalette.Danger.copy(alpha = 0.3f)),
                        TyporbShapeHolder.Card,
                    )
                    .padding(14.dp),
            ) {
                Text(
                    text = report.headline,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = TyporbPalette.Danger,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    // First, because it decides whether the rest of the report is even about the
                    // build the user thinks they are running.
                    text = "Build: ${CrashLog.buildStamp}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TyporbPalette.Danger.copy(alpha = 0.85f),
                )
                Text(
                    text = "Thread: ${report.threadName}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TyporbPalette.Danger.copy(alpha = 0.85f),
                )
                Text(
                    text = "Top frame: ${report.topFrame}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TyporbPalette.Danger.copy(alpha = 0.85f),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Full stack trace",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TyporbPalette.TextSecondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TyporbShapeHolder.Card)
                    .background(TyporbPalette.Surface)
                    .border(BorderStroke(1.dp, TyporbPalette.Border), TyporbShapeHolder.Card)
                    .padding(14.dp),
            ) {
                Text(
                    text = report.stackTrace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TyporbPalette.TextPrimary,
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "Copy",
                    icon = Icons.Rounded.ContentCopy,
                    onClick = { copyToClipboard(context, report) },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = "Dismiss",
                    icon = Icons.Rounded.Refresh,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun copyToClipboard(context: Context, report: CrashReport) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Typorb crash", CrashLog.render(report)),
        )
    }
}

/** Indirection so the screen does not need to import the whole theme object. */
private object TyporbShapeHolder {
    val Card = com.typorb.ui.theme.TyporbShapes.Medium
}