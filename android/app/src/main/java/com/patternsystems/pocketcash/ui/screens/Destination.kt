package com.patternsystems.pocketcash.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.ui.BarClearance
import com.patternsystems.pocketcash.ui.Hairline
import com.patternsystems.pocketcash.ui.PrimaryButton
import com.patternsystems.pocketcash.ui.ScreenPadding
import com.patternsystems.pocketcash.ui.theme.Cash

/**
 * A destination other than Home: a large title that shrinks into the bar on scroll (docs/spec.md section 7), then a
 * list that leaves room for the floating nav bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationPage(
    title: String,
    subtitle: String? = null,
    actionIcon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val collapsed by remember { derivedStateOf { behavior.state.collapsedFraction > 0.98f } }
    val background = MaterialTheme.colorScheme.background
    Column(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection)) {
        LargeTopAppBar(
            title = {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null && !collapsed) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Cash.colors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            actions = {
                if (actionIcon != null) IconButton(onClick = onAction) { Icon(actionIcon, contentDescription = actionLabel) }
            },
            scrollBehavior = behavior,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
        )
        if (collapsed) Hairline()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BarClearance)) { content() }
    }
}

/**
 * What Free sees on a Pro destination (Insights, Envelopes): a quiet, blurred preview of the real thing with an Unlock
 * button that opens the paywall (docs/spec.md section 7). Below Android 12, which can't blur, the preview is faded.
 */
@Composable
fun ProPreview(title: String, message: String, onUnlock: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        Box(
            Modifier.fillMaxSize().clearAndSetSemantics {}
                .then(if (canBlur) Modifier.blur(14.dp).alpha(0.5f) else Modifier.alpha(0.12f)),
        ) { content() }
        // Swallows taps and scrolls, so the preview stays a picture.
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.35f))
                .clickable(interactionSource = null, indication = null) {},
        )
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 420.dp).padding(horizontal = ScreenPadding * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onBackground)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(message, color = Cash.colors.subtle, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            PrimaryButton(stringResource(R.string.pro_unlock), onClick = onUnlock, modifier = Modifier.fillMaxWidth())
        }
    }
}
