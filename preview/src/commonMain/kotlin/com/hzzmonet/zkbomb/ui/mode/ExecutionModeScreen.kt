package com.hzzmonet.zkbomb.ui.mode

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.BombCapabilityKeys
import com.hzzmonet.zkbomb.data.BombConnection
import com.hzzmonet.zkbomb.data.BombInstallInfo
import com.hzzmonet.zkbomb.data.BombServiceState
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCapabilityBadge
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import top.yukonga.miuix.kmp.basic.Text

/**
 * What Bomb can actually do on this device — probed, not chosen.
 *
 * This screen used to offer a ROM/Root toggle and then describe whichever mode
 * was selected, including a row reporting `bombd` as "Running · SELinux domain
 * bombd". There is no `bombd`. The mode was a preference the user could set and
 * the readout followed the preference rather than the device.
 *
 * Privilege is a fact about how Bomb was installed and what the platform granted
 * it. It is not a setting, so there is nothing here to toggle: every value below
 * comes from the install path or from `IBombService.getCapabilities()`.
 *
 * @param service bound **once** by the caller. Binding is not free and
 *   `bindService` is not idempotent per call site: calling
 *   `rememberBombService()` from each section would open one connection per
 *   section and tear them all down again on every recomposition. The state is
 *   resolved at the top of the app, exactly as the installed-app list already is.
 */
fun LazyListScope.executionModeContent(
    service: BombServiceState,
    install: BombInstallInfo,
) {
    item { ServiceCard(service) }
    item { InstallCard(install) }

    item { BombSectionTitle("Capabilities") }
    item { CapabilityLegend() }
    capabilityGroups(service)
}

@Composable
private fun ServiceCard(service: BombServiceState) {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Privileged service",
                    fontWeight = FontWeight.SemiBold,
                    color = BombTheme.miuix.onSurface,
                )
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    when (service.connection) {
                        BombConnection.CONNECTED ->
                            BombBadge("Connected", BombTheme.colors.ok)
                        BombConnection.CONNECTING ->
                            BombBadge("Connecting", BombTheme.colors.warn)
                        BombConnection.UNAVAILABLE ->
                            BombBadge("Unavailable", BombTheme.colors.critical)
                        BombConnection.NOT_SUPPORTED ->
                            BombBadge("Not supported", BombTheme.miuix.onSurfaceVariantSummary)
                    }
                }
            }
            Text(
                text = when (service.runtimeMode) {
                    "ROM" -> "ROM mode — the image declares Bomb integrated " +
                        "(ro.bomb.integrated=1)"
                    "ROOT" -> "Root mode — backend module installed"
                    else -> "Normal app — no privileged backend declared"
                },
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 13.sp,
                color = BombTheme.miuix.onSurface,
            )
            if (service.runtimeMode == "ROM") {
                // Said plainly, because the flag is a claim by whoever built the
                // image and cannot prove any single operation works. Every row
                // in the capability list below is still a measurement.
                Text(
                    text = "The flag says the integration was intended. Each " +
                        "capability below is still probed separately.",
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            service.apiVersion?.let {
                Text(
                    text = "Contract version $it",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 13.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            service.error?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 13.sp,
                    color = BombTheme.colors.critical,
                )
            }
            if (service.connection == BombConnection.CONNECTED && service.capabilities.isEmpty()) {
                // Connected but told nothing: the service refuses callers it
                // does not trust and returns an empty set rather than throwing,
                // so this is what a rejected caller looks like from here.
                Text(
                    text = "Connected, but the service reported no capabilities — " +
                        "the caller check refused this process.",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 13.sp,
                    color = BombTheme.colors.warn,
                )
            }
        }
    }
}

@Composable
private fun InstallCard(install: BombInstallInfo) {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Installed as",
                    fontWeight = FontWeight.SemiBold,
                    color = BombTheme.miuix.onSurface,
                )
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    if (install.privileged) {
                        BombBadge(install.slot, BombTheme.colors.ok)
                    } else {
                        BombBadge(install.slot, BombTheme.miuix.onSurfaceVariantSummary)
                    }
                }
            }
            install.apkPath?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
            if (!install.privileged) {
                Text(
                    text = "Not in a priv-app directory, so privileged permissions " +
                        "are not granted. If a system copy also exists, this one " +
                        "shadows it — uninstall this copy for the user and reboot.",
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 13.sp,
                    color = BombTheme.colors.warn,
                )
            }
        }
    }
}

@Composable
private fun CapabilityLegend() {
    BombCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Every row below is probed on this device. Nothing defaults " +
                    "to supported: a capability the service did not answer reads " +
                    "as not probed, and its controls stay disabled.",
                fontSize = 13.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

private fun LazyListScope.capabilityGroups(service: BombServiceState) {
    BombCapabilityKeys.ORDERED.forEach { (group, keys) ->
        item { BombSectionTitle(group) }
        item {
            BombCard {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    keys.forEachIndexed { index, key ->
                        if (index > 0) BombRowDivider()
                        CapabilityRow(key, service)
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(key: String, service: BombServiceState) {
    val state = service.stateOf(key)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = BombCapabilityKeys.label(key),
                fontSize = 15.sp,
                color = BombTheme.miuix.onSurface,
            )
            explain(key, state)?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
        BombCapabilityBadge(state)
    }
}

/**
 * The reason behind a state, where the reason is not obvious from the name.
 *
 * Only for cases where "unsupported" alone would leave the user guessing whether
 * it is their device, their install, or the feature.
 */
private fun explain(key: String, state: String): String? = when {
    key == BombCapabilityKeys.AIRDROP_INTEROP && state == "DECLARED_NOT_IMPLEMENTED" ->
        "The ROM declares the HAL but ships no implementation"

    key == BombCapabilityKeys.PACKAGE_VISIBILITY_VIRTUALIZATION && state == "UNSUPPORTED" ->
        "Needs a framework patch — ROM builds only"

    key == BombCapabilityKeys.SETTINGS_VIRTUALIZATION && state == "UNSUPPORTED" ->
        "Needs a framework patch — ROM builds only"

    key == BombCapabilityKeys.VOIP_RECORDING && state == "UNSUPPORTED" ->
        "No capture path has been proven on this build"

    key == BombCapabilityKeys.LOG_DISABLE && state == "REQUIRES_ROOT" ->
        "Root-only by choice, not by device limit"

    key == BombCapabilityKeys.TASK_MANAGER && state == "REQUIRES_ROOT" ->
        "Reading other processes' /proc has been restricted since Android 9"

    state == "NOT_PROBED" -> "Not answered by the service"

    else -> null
}
