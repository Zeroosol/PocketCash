package com.patternsystems.pocketcash.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.ui.PrimaryButton
import com.patternsystems.pocketcash.ui.ScreenPadding
import com.patternsystems.pocketcash.ui.theme.Cash

/**
 * The plain lock screen of App lock (docs/spec.md section 12): the app icon, "PocketCash is locked" and Unlock. It
 * replaces the app's content entirely, so nothing shows behind it.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = ScreenPadding * 2, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(Cash.colors.cashIn), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.size(96.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.lock_unlock), onClick = onUnlock)
        }
    }
}
