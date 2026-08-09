package com.hzzmonet.zkbomb.ui.appearance

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.preview.PreviewUiState
import com.hzzmonet.zkbomb.ui.design.BackdropImageState
import com.hzzmonet.zkbomb.ui.design.BombBackdrop
import com.hzzmonet.zkbomb.ui.design.BombBackdropStyle
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.component.BombCard
import com.hzzmonet.zkbomb.ui.design.component.BombPreference
import com.hzzmonet.zkbomb.ui.design.component.BombRowDivider
import com.hzzmonet.zkbomb.ui.design.component.BombSectionTitle
import com.hzzmonet.zkbomb.ui.design.component.BombSliderPreference
import com.hzzmonet.zkbomb.ui.design.component.BombUnsupportedState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperRadioButton

/**
 * Wallpaper and surface controls.
 *
 * The built-in styles are drawn, so they cost nothing and re-tint with the
 * theme; the fourth option is the user's own picture. Dim, blur and card
 * opacity are exposed because a photo the user picked is unpredictable — a
 * bright one needs a heavier scrim than a dark one, and only they can judge it.
 */
fun LazyListScope.appearanceContent(
    state: PreviewUiState,
    backdropImage: BackdropImageState,
) {
    item { AppearancePreview(state, backdropImage) }

    item { BombSectionTitle("Background") }
    item {
        BombCard {
            // A vertical list, not a segmented control: five labels do not fit
            // across a phone and "Image" was being clipped to "I…".
            BombBackdropStyle.entries.forEachIndexed { index, style ->
                if (index > 0) BombRowDivider()
                SuperRadioButton(
                    title = style.label,
                    summary = styleSummary(style),
                    selected = state.backdrop == style,
                    enabled = style != BombBackdropStyle.Custom || backdropImage.image != null,
                    onClick = { state.backdrop = style },
                )
            }
            BombRowDivider()
            BombPreference(
                title = if (backdropImage.image == null) "Choose image" else "Change image",
                summary = when {
                    !backdropImage.canPick -> backdropImage.unavailableReason
                    backdropImage.image == null -> "Pick a picture from your gallery"
                    else -> "Stored inside the app, not a gallery permission"
                },
                enabled = backdropImage.canPick,
                onClick = {
                    backdropImage.pick()
                    state.backdrop = BombBackdropStyle.Custom
                },
            )
            if (backdropImage.image != null) {
                BombRowDivider()
                BombPreference(
                    title = "Remove image",
                    onClick = {
                        backdropImage.clear()
                        if (state.backdrop == BombBackdropStyle.Custom) {
                            state.backdrop = BombBackdropStyle.Ember
                        }
                    },
                )
            }
        }
    }

    if (state.backdrop == BombBackdropStyle.Custom && backdropImage.image == null) {
        item {
            BombUnsupportedState(
                title = "No image selected",
                reason = if (backdropImage.canPick) {
                    "Pick a picture above, or switch back to one of the drawn styles."
                } else {
                    backdropImage.unavailableReason.orEmpty()
                },
            )
        }
    }

    item { BombSectionTitle("Adjust") }
    item {
        BombCard {
            BombSliderPreference(
                title = "Dim",
                value = state.backdropDim,
                onValueChange = { state.backdropDim = it },
                valueLabel = "${(state.backdropDim * 100).toInt()}%",
                valueRange = 0f..0.8f,
            )
            BombRowDivider()
            BombSliderPreference(
                title = "Blur",
                value = state.backdropBlur,
                onValueChange = { state.backdropBlur = it },
                valueLabel = "${state.backdropBlur.toInt()} dp",
                valueRange = 0f..32f,
            )
            BombRowDivider()
            BombSliderPreference(
                title = "Card opacity",
                value = state.cardOpacity,
                onValueChange = { state.cardOpacity = it },
                valueLabel = "${(state.cardOpacity * 100).toInt()}%",
                valueRange = 0.45f..1f,
            )
        }
    }

    item {
        BombCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Dim and blur apply to your own image only — the drawn styles " +
                        "already carry their own falloff. Card opacity applies to every " +
                        "surface so text stays legible over a busy picture.",
                    fontSize = 12.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** Live sample of the current settings, cards included. */
@Composable
private fun AppearancePreview(state: PreviewUiState, backdropImage: BackdropImageState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(BombTheme.miuix.background),
    ) {
        val image = backdropImage.image
        if (state.backdrop == BombBackdropStyle.Custom && image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (state.backdropBlur > 0f) {
                            Modifier.blur(state.backdropBlur.dp)
                        } else {
                            Modifier
                        },
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        (if (state.darkTheme) Color.Black else Color.White)
                            .copy(alpha = state.backdropDim),
                    ),
            )
        } else {
            BombBackdrop(style = state.backdrop, darkTheme = state.darkTheme)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            SampleCard(state, "System state", "CPU 18%  ·  41.2 °C")
            SampleCard(state, "Task Manager", "13 processes")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Card opacity ${(state.cardOpacity * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = BombTheme.miuix.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun SampleCard(state: PreviewUiState, title: String, detail: String) {
    val alpha = if (state.backdrop == BombBackdropStyle.None) 1f else state.cardOpacity
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BombTheme.miuix.surfaceContainer.copy(alpha = alpha))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BombTheme.miuix.onSurface,
        )
        Text(
            text = detail,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 11.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
    }
}

private fun styleSummary(style: BombBackdropStyle): String = when (style) {
    BombBackdropStyle.None -> "Plain surfaces, no wallpaper"
    BombBackdropStyle.Ember -> "Warm drawn gradient"
    BombBackdropStyle.Ocean -> "Cool drawn gradient"
    BombBackdropStyle.Void -> "Neutral drawn gradient"
    BombBackdropStyle.Custom -> "Your own picture"
}
