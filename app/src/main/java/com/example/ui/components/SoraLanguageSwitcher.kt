package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.AppLanguage
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily

/**
 * Compact, tactile segmented pill (`EN | عربي`) with an animated sliding indicator.
 * Always lays out options left-to-right internally so EN stays left and عربي stays right
 * regardless of active RTL/LTR direction.
 */
@Composable
fun SoraLanguageSwitcher(
    currentLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
    onDarkBackdrop: Boolean = false,
    pillWidth: Dp = 120.dp
) {
    val switchDesc = stringResource(R.string.cd_language_switcher)
    val containerColor = if (onDarkBackdrop) {
        Color.White.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val borderColor = if (onDarkBackdrop) {
        Color.White.copy(alpha = 0.24f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    }
    val activePillColor = if (onDarkBackdrop) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val activeTextColor = MaterialTheme.colorScheme.onPrimary
    val inactiveTextColor = if (onDarkBackdrop) {
        Color.White.copy(alpha = 0.88f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            modifier = modifier
                .width(pillWidth)
                .height(48.dp)
                .semantics { contentDescription = switchDesc }
                .testTag("language_switcher"),
            shape = RoundedCornerShape(24.dp),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                val segmentWidth = maxWidth / 2f
                val targetOffset = if (currentLanguage == AppLanguage.ENGLISH) 0.dp else segmentWidth
                val animatedOffset by animateDpAsState(
                    targetValue = targetOffset,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    label = "lang_pill_offset"
                )

                Box(
                    modifier = Modifier
                        .offset(x = animatedOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(activePillColor)
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    LanguageOptionSegment(
                        label = stringResource(R.string.lang_short_en),
                        selected = currentLanguage == AppLanguage.ENGLISH,
                        activeColor = activeTextColor,
                        inactiveColor = inactiveTextColor,
                        fontFamily = SpaceGroteskFontFamily,
                        contentDesc = stringResource(R.string.lang_english),
                        tag = "lang_option_en",
                        onClick = { onLanguageSelected(AppLanguage.ENGLISH) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    LanguageOptionSegment(
                        label = stringResource(R.string.lang_short_ar),
                        selected = currentLanguage == AppLanguage.ARABIC,
                        activeColor = activeTextColor,
                        inactiveColor = inactiveTextColor,
                        fontFamily = CairoFontFamily,
                        contentDesc = stringResource(R.string.lang_arabic),
                        tag = "lang_option_ar",
                        onClick = { onLanguageSelected(AppLanguage.ARABIC) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageOptionSegment(
    label: String,
    selected: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    contentDesc: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        animationSpec = tween(durationMillis = 180),
        label = "lang_text_color"
    )
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics {
                this.selected = selected
                this.contentDescription = contentDesc
            }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = fontFamily,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = textColor,
            maxLines = 1
        )
    }
}
