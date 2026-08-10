package com.hzzmonet.zkbomb.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.V6ApplyState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * One line reporting the outcome of a v6 apply, shared by the Firewall, App
 * Visibility and Settings Virtualization consoles.
 *
 * It reports the service's own [com.hzzmonet.zkbomb.data.BombOperationResult]
 * verbatim — success or the exact failure status and detail — because the v6
 * contract is write-only and the app has nothing else it could truthfully show.
 */
@Composable
fun V6ApplyStatusLine(status: V6ApplyState, modifier: Modifier = Modifier) {
    when (status) {
        V6ApplyState.Idle -> Unit
        V6ApplyState.Applying ->
            Text(
                text = "Applying…",
                modifier = modifier,
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        is V6ApplyState.Done -> {
            val result = status.result
            val text = if (result.isSuccess) {
                "Applied — ${result.message ?: "policy set"}"
            } else {
                "${result.status}${result.message?.let { ": $it" } ?: ""}"
            }
            val color: Color = if (result.isSuccess) BombTheme.colors.ok else BombTheme.colors.warn
            Text(text = text, modifier = modifier, fontSize = 12.sp, color = color)
        }
    }
}
