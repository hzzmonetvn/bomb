package com.hzzmonet.zkbomb.ui.recorder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.data.LiveApp
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombEmptyState
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperCheckbox

/**
 * Chooses which apps the recorder watches, from the packages actually installed
 * on this device.
 *
 * This screen previously listed seven hardcoded apps — Zalo, Telegram, WhatsApp
 * and so on — each carrying a `captureSupported` flag and a reason string like
 * "App blocks capture on this build". None of that had been measured. Offering a
 * fixed list means an app the user actually calls with may not appear at all,
 * and the per-app verdicts asserted the outcome of a probe that has never run.
 *
 * What replaces it: the real installed-package list, and one honest statement
 * about capture support for the whole feature rather than a fabricated one per
 * app.
 */
fun LazyListScope.voipPickerContent(
    state: PreviewUiState,
    apps: List<LiveApp>?,
) {
    item {
        TextField(
            state = state.voipQuery,
            label = "Search apps",
            useLabelAsPlaceholder = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = {
                BombIcon(
                    icon = BombIcons.Search,
                    tint = BombTheme.miuix.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(18.dp),
                )
            },
        )
    }

    // Capture support is a device question, not a per-app one, and it has not
    // been answered on this build: VoIP capture has no working reference path,
    // so it starts unsupported and stays that way until an actual capture probe
    // yields non-silent frames. Saying so once, here, is honest. Marking
    // individual apps "supported" would not be.
    item {
        BombUnsupportedState(
            title = "VoIP capture not available on this build",
            reason = "Apps selected here are remembered, but nothing is recorded " +
                "until Bomb can prove it captures real audio from a VoIP call. " +
                "Phone calls are a separate path and are not affected.",
        )
    }

    if (apps == null) {
        item { BombEmptyState("Cannot read the installed app list") }
        return
    }

    val query = state.voipQuery.text.toString().trim().lowercase()
    val matches = apps
        .filter {
            query.isEmpty() ||
                it.name.lowercase().contains(query) ||
                it.packageName.lowercase().contains(query)
        }
        // User apps first: a VoIP app is almost always one the user installed,
        // and a device has a few hundred system packages to scroll past.
        .sortedWith(compareBy({ it.isSystem }, { it.name.lowercase() }))

    if (matches.isEmpty()) {
        item { BombEmptyState("No apps match") }
        return
    }

    val watched = state.voipWatched.count { it.value }
    item { BombSectionTitle(if (watched == 0) "None watched" else "$watched watched") }

    item {
        BombCard {
            matches.forEachIndexed { index, app ->
                if (index > 0) BombRowDivider()
                SuperCheckbox(
                    title = app.name,
                    summary = if (app.isSystem) "${app.packageName} · System" else app.packageName,
                    checked = state.voipWatched[app.packageName] == true,
                    onCheckedChange = { state.voipWatched[app.packageName] = it },
                )
            }
        }
    }

    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Watching an app costs nothing while it is closed. Bomb only " +
                        "arms the recorder when a watched app is in the foreground and " +
                        "the audio mode reports an active call.",
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }
}
