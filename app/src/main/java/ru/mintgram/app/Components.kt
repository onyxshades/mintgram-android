package ru.mintgram.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val ru = Locale("ru")

fun fmtTime(ts: Long): String = SimpleDateFormat("HH:mm", ru).format(Date(ts))

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

fun fmtDay(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ts, now) -> "Сегодня"
        sameDay(ts, now - 86_400_000L) -> "Вчера"
        else -> SimpleDateFormat("d MMMM", ru).format(Date(ts))
    }
}

/** Время в списке чатов: сегодня — часы, иначе — дата. */
fun fmtListTime(ts: Long): String = when {
    ts <= 0 -> ""
    sameDay(ts, System.currentTimeMillis()) -> fmtTime(ts)
    else -> SimpleDateFormat("d MMM", ru).format(Date(ts))
}

fun lastSeenText(ts: Long?): String {
    if (ts == null || ts <= 0) return "не в сети"
    val now = System.currentTimeMillis()
    return when {
        now - ts < 60_000 -> "был(а) только что"
        sameDay(ts, now) -> "был(а) в сети в ${fmtTime(ts)}"
        sameDay(ts, now - 86_400_000L) -> "был(а) вчера в ${fmtTime(ts)}"
        else -> "был(а) в сети " + SimpleDateFormat("d MMM", ru).format(Date(ts))
    }
}

fun avatarColor(name: String, accent: Int?): Color {
    val hue = accent?.toFloat() ?: ((name.lowercase().hashCode().toLong() and 0x7fffffff) % 360).toFloat()
    return Color.hsv(hue, 0.55f, 0.72f)
}

@Composable
fun Avatar(
    name: String,
    url: String?,
    accent: Int?,
    size: Dp = 48.dp,
    icon: ImageVector? = null,
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(avatarColor(name, accent)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            url != null -> AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
            icon != null -> Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
            else -> Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}
