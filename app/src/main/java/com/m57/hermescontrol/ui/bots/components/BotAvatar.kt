package com.m57.hermescontrol.ui.bots.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Circular monogram avatar for a bot.
 *
 * The backend exposes no avatar field on `ProfileInfo`, so identity is carried
 * by a monogram plus a colour picked deterministically from the bot's name —
 * the same bot always gets the same colour, across launches and devices.
 *
 * The palette is derived from [MaterialTheme.colorScheme] container pairs
 * rather than fixed hexes, so it follows light/dark/AMOLED/Material You (and
 * satisfies the `checkColorLiterals` build guard). Four slots means colours
 * repeat past four bots — deliberate: the colour is a recognition aid, the
 * monogram and name are what actually identify the row.
 */
@Composable
fun BotAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val palette =
        listOf(
            scheme.primaryContainer to scheme.onPrimaryContainer,
            scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer,
            scheme.surfaceVariant to scheme.onSurfaceVariant,
        )
    val (background, foreground) = palette[paletteIndex(name, palette.size)]

    Box(
        modifier =
            modifier
                .size(size)
                .background(color = background, shape = CircleShape)
                // The monogram is decoration: the row already announces the bot
                // name, so a screen reader must not read the initials as well.
                .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogram(name),
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            // Scales with the avatar so the sheet's compact variant stays legible.
            fontSize = (size.value * MONOGRAM_SIZE_RATIO).sp,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private const val MONOGRAM_SIZE_RATIO = 0.4f

/** Stable, non-negative palette slot for [name] (`%` alone can return negative). */
internal fun paletteIndex(
    name: String,
    paletteSize: Int,
): Int = ((name.hashCode() % paletteSize) + paletteSize) % paletteSize

/**
 * Up to two initials: `"research-bot"` → `RB`, `"Hermes"` → `H`. Separator-split
 * first, so multi-word names read as initials rather than as their first two
 * letters.
 */
internal fun monogram(name: String): String {
    val words = name.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}
