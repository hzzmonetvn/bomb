package com.hzzmonet.zkbomb.ui.navigation

import androidx.compose.runtime.Composable

data class BombBackEvent(
    val progress: Float,
    val fromLeftEdge: Boolean,
)

/** Platform back bridge. Android provides gesture progress for predictive back. */
@Composable
expect fun BombBackHandler(
    enabled: Boolean,
    onProgress: (BombBackEvent) -> Unit,
    onCancelled: () -> Unit,
    onBack: () -> Unit,
)
