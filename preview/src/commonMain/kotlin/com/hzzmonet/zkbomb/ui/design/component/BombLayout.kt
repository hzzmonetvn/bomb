package com.hzzmonet.zkbomb.ui.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hzzmonet.zkbomb.ui.design.BombDimens
import com.hzzmonet.zkbomb.ui.design.BombIcon
import com.hzzmonet.zkbomb.ui.design.BombIcons
import com.hzzmonet.zkbomb.ui.design.BombTheme
import com.hzzmonet.zkbomb.ui.design.LocalBombSurfaceAlpha
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState

/**
 * The Bomb page shell: collapsing large title, optional back affordance,
 * optional bottom bar, and a lazy content column already inset correctly.
 *
 * Feature screens never touch MIUIX's Scaffold/TopAppBar directly — swapping the
 * underlying library stays a change inside `ui/design`.
 */
@Composable
fun BombScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // With a backdrop showing, the shell gets out of the way: transparent
    // container and bars, so the wallpaper runs edge to edge behind the cards.
    val alpha = LocalBombSurfaceAlpha.current
    val translucent = alpha < 1f
    Scaffold(
        modifier = modifier,
        containerColor = if (translucent) Color.Transparent else BombTheme.miuix.surface,
        topBar = {
            TopAppBar(
                title = title,
                color = if (translucent) Color.Transparent else BombTheme.miuix.surface,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) {
                        BombIconButton(
                            icon = BombIcons.Back,
                            contentDescription = "Back",
                            onClick = onBack,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + BombDimens.PagePadding,
                end = padding.calculateEndPadding(direction) + BombDimens.PagePadding,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(BombDimens.CardSpacing),
            content = content,
        )
    }
}

/** Section heading between cards. */
@Composable
fun BombSectionTitle(text: String, modifier: Modifier = Modifier) {
    SmallTitle(
        text = text,
        modifier = modifier.padding(top = 6.dp),
    )
}

/** Grouped container. Rows inside are separated by [BombRowDivider]. */
@Composable
fun BombCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = BombDimens.CardCorner,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = BombTheme.miuix.surfaceContainer.copy(alpha = LocalBombSurfaceAlpha.current),
        ),
        content = content,
    )
}

@Composable
fun BombRowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp)
            .height(0.75.dp)
            .background(BombTheme.miuix.dividerLine),
    )
}

/** Round, tappable icon — used for back, theme toggle and row actions. */
@Composable
fun BombIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = BombTheme.miuix.onSurface,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BombIcon(
            icon = icon,
            tint = tint,
            contentDescription = contentDescription,
            modifier = Modifier.size(BombDimens.IconSize),
        )
    }
}

/** Small state chip: capability states, rule states, freeze modes. */
@Composable
fun BombBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The state a capability-gated control shows when the device cannot do it.
 * Bomb never renders a plausible-looking number in place of one it cannot read.
 */
@Composable
fun BombUnsupportedState(
    title: String,
    reason: String,
    modifier: Modifier = Modifier,
) {
    BombCard(modifier = modifier) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BombIcon(
                    icon = BombIcons.Freeze,
                    tint = BombTheme.colors.frozen,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = title,
                    modifier = Modifier.padding(start = 8.dp),
                    fontWeight = FontWeight.Medium,
                    color = BombTheme.miuix.onSurface,
                )
            }
            Text(
                text = reason,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 13.sp,
                color = BombTheme.miuix.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
fun BombEmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = BombTheme.miuix.onSurfaceVariantSummary,
        )
    }
}
