package com.typorb.ui.dashboard

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
import com.typorb.ui.TyporbAppRoot
import com.typorb.ui.TyporbViewModel
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