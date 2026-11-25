package com.patternsystems.pocketcash.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.ui.theme.Cash
import kotlin.math.roundToInt

/** The four destinations of the bottom bar (docs/spec.md section 7). */
enum class Destination(val label: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Outlined.Home),
    INSIGHTS(R.string.nav_insights, Icons.Outlined.Insights),
    ENVELOPES(R.string.nav_envelopes, Icons.Outlined.Email),
    SETTINGS(R.string.nav_settings, Icons.Outlined.Settings),
}

/**
 * Collapses the bar while the content scrolls down and expands it when it scrolls up or runs into the top. Installed
 * on the destinations' container, so it hears every list's scrolling.
 */
@Stable
class BarScrollState(private val thresholdPx: Float) {
    var expanded by mutableStateOf(true)

    val connection = object : NestedScrollConnection {
        private var travelled = 0f

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            if ((dy < 0f) != (travelled < 0f)) travelled = 0f
            travelled += dy
            if (travelled < -thresholdPx) expanded = false
            if (travelled > thresholdPx) expanded = true
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Still pulling down with nothing left to scroll: the list is at the top.
            if (available.y > 0f) expanded = true
            return Offset.Zero
        }
    }
}

@Composable
fun rememberBarScrollState(): BarScrollState {
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    return remember(threshold) { BarScrollState(threshold) }
}

/**
 * The floating capsule with Home, Insights, Envelopes and Settings, and the round + beside it (tap: keypad,
 * long-press: Count my cash). The selected destination shows its label; the highlight slides between items.
 * Collapsed, only the selected icon stays; tapping it expands the bar again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomBar(
    selected: Destination,
    expanded: Boolean,
    onSelect: (Destination) -> Unit,
    onExpand: () -> Unit,
    onPlus: () -> Unit,
    onPlusLong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.navigationBarsPadding().padding(bottom = 14.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(0.5.dp, Cash.colors.hairline),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
            val bounds = remember { mutableStateMapOf<Destination, Rect>() }
            Box(Modifier.onGloballyPositioned { container = it }) {
                bounds[selected]?.let { target -> Highlight(target) }
                Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Destination.entries.forEach { d ->
                        val isSelected = d == selected
                        AnimatedVisibility(
                            visible = expanded || isSelected,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally(),
                        ) {
                            BarItem(
                                destination = d,
                                selected = isSelected,
                                showLabel = isSelected && expanded,
                                modifier = Modifier.onGloballyPositioned { item ->
                                    container?.takeIf { it.isAttached && item.isAttached }?.let { bounds[d] = it.localBoundingBoxOf(item) }
                                },
                                onClick = { if (!expanded) onExpand() else onSelect(d) },
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                .combinedClickable(
                    onClickLabel = stringResource(R.string.nav_plus),
                    onLongClickLabel = stringResource(R.string.count_title_short),
                    onLongClick = onPlusLong,
                    onClick = onPlus,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.nav_plus), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
        }
    }
}

/** The grey pill behind the selected item. It follows the item's bounds, so it slides and stretches with the label. */
@Composable
private fun Highlight(target: Rect) {
    val motion = spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f)
    val left by animateFloatAsState(target.left, motion, label = "left")
    val top by animateFloatAsState(target.top, motion, label = "top")
    val width by animateFloatAsState(target.width, motion, label = "width")
    val height by animateFloatAsState(target.height, motion, label = "height")
    val density = LocalDensity.current
    Box(
        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
private fun BarItem(destination: Destination, selected: Boolean, showLabel: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(destination.label)
    Row(
        modifier.height(46.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            destination.icon,
            contentDescription = if (showLabel) null else label,
            tint = if (selected) MaterialTheme.colorScheme.onBackground else Cash.colors.subtle,
            modifier = Modifier.size(22.dp),
        )
        AnimatedVisibility(visible = showLabel, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            Text(
                label,
                modifier = Modifier.padding(start = 8.dp),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
        }
    }
}
