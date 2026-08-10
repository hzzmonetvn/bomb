package com.hzzmonet.zkbomb.ui.adblock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.BombCapabilityKeys
import com.hzzmonet.zkbomb.data.BombOperationResult
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.data.V6ApplyState
import com.hzzmonet.zkbomb.ui.common.V6ApplyStatusLine
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * Bomb AdBlock screen (BOMB_PLAN.md §16).
 *
 * DNS-based domain blocking. The service owns the raw source data and performs
 * the parse → normalize → dedupe → allow-rules → compile → atomic-swap pipeline;
 * the one operation the v6 contract exposes to the app is [reloadAdBlockRules],
 * which triggers that compile-and-swap and keeps the previous working blocklist
 * if the new one is too small.
 *
 * The contract exposes **no** getter for source lists, domain counts, per-query
 * statistics or an allowlist, so this screen does not show any — inventing them
 * would be fabricated telemetry. It reports exactly what it can act on and read
 * back: the reload result. Requires [BombCapabilityKeys.AD_BLOCK] = SUPPORTED.
 *
 * Does not implement cosmetic filtering, TLS interception or payload inspection.
 */
fun LazyListScope.adBlockContent(service: BombServiceState) {
    if (!service.isSupported(BombCapabilityKeys.AD_BLOCK)) {
        item {
            BombUnsupportedState(
                title = "AdBlock unavailable",
                reason = "DNS-based blocking is reported as " +
                    "${service.stateOf(BombCapabilityKeys.AD_BLOCK)} on this install. " +
                    "The service handles DNS filtering; no payload inspection or TLS interception is performed.",
            )
        }
        return
    }

    item { AdBlockIntroCard() }
    item { BombSectionTitle("Blocklist") }
    item { ReloadCard(service) }
}

@Composable
private fun AdBlockIntroCard() {
    BombCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "About AdBlock",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "Bomb blocks ad and tracker domains at the DNS layer. The service parses " +
                    "its source data, compiles an immutable blocklist and swaps it in atomically. " +
                    "If a rebuild produces too few rules it is rejected and the previous working " +
                    "blocklist is kept.",
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ReloadCard(service: BombServiceState) {
    val controller = service.controller
    var status by remember { mutableStateOf<V6ApplyState>(V6ApplyState.Idle) }
    val applying = status is V6ApplyState.Applying

    BombCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Reload blocklist rules",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BombTheme.miuix.onSurface,
            )
            Text(
                text = "Recompile from the current source data and hot-swap the active blocklist " +
                    "without a restart. The result below is the service's own outcome.",
                fontSize = 12.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )

            V6ApplyStatusLine(status)

            Button(
                onClick = {
                    status = V6ApplyState.Applying
                    val c = controller
                    if (c == null) {
                        status = V6ApplyState.Done(
                            BombOperationResult("BACKEND_UNAVAILABLE", "Service is not connected"),
                        )
                    } else {
                        c.reloadAdBlockRules { result -> status = V6ApplyState.Done(result) }
                    }
                },
                enabled = controller != null && !applying,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = if (applying) "Reloading…" else "Reload now")
            }
        }
    }
}
