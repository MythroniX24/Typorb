package com.typorb.ui.dashboard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.typorb.diagnostics.CrashStore
import com.typorb.ui.TyporbAppRoot
import com.typorb.ui.TyporbViewModel
import com.typorb.ui.diagnostics.CrashActivity
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbTheme

/**
 * The launcher activity — the app's only one.
 *
 * It owns nothing but the window: the [TyporbAppRoot] hosts the whole navigation graph, and the
 * activity-scoped [TyporbViewModel] keeps engine selection, the offline download and the transcript
 * history consistent across every tab.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: TyporbViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A stored crash outranks the dashboard: showing the home screen over the top of an error the
        // user never saw would hide the only useful diagnostic the app captured.
        if (CrashStore(this).peek() != null) {
            startActivity(
                Intent(this, CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
            return
        }

        setContent {
            TyporbTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TyporbPalette.Background),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TyporbAppRoot(viewModel = viewModel)
                }
            }
        }
    }
}