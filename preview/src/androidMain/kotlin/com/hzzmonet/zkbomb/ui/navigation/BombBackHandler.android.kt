package com.hzzmonet.zkbomb.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

@Composable
actual fun BombBackHandler(
    enabled: Boolean,
    onProgress: (BombBackEvent) -> Unit,
    onCancelled: () -> Unit,
    onBack: () -> Unit,
) {
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                onProgress(
                    BombBackEvent(
                        progress = event.progress.coerceIn(0f, 1f),
                        fromLeftEdge = event.swipeEdge == BackEventCompat.EDGE_LEFT,
                    ),
                )
            }
            onBack()
        } catch (_: CancellationException) {
            onCancelled()
        }
    }
}
