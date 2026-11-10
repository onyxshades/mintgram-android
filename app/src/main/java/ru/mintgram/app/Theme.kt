package ru.mintgram.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1FA384),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F3E7),
    onPrimaryContainer = Color(0xFF00382C),
    secondaryContainer = Color(0xFFD6EFE7),
    onSecondaryContainer = Color(0xFF0C2B24),
    surface = Color(0xFFF7FBF9),
    background = Color(0xFFF7FBF9),
    surfaceVariant = Color(0xFFE6EFEB),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5ED8BB),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFFC8F3E7),
    secondaryContainer = Color(0xFF2A4A41),
    onSecondaryContainer = Color(0xFFD6EFE7),
    surface = Color(0xFF0F1513),
    background = Color(0xFF0F1513),
    surfaceVariant = Color(0xFF26302C),
)

@Composable
fun MintTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
