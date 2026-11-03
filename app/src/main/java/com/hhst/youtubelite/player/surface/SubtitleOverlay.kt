package com.hhst.youtubelite.player.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Base cue look (material bodyMedium); [SubtitleStyle.textScale] multiplies it. */
const val BASE_FONT_SP = PlayerUi.BASE_SUBTITLE_FONT_SP

/** Background alpha per [SubtitleStyle.Background] style. */
private fun SubtitleStyle.Background.alpha(): Float = when (this) {
    SubtitleStyle.Background.NONE -> 0f
    SubtitleStyle.Background.SEMI -> 0.6f
    SubtitleStyle.Background.OPAQUE -> 1f
}

/**
 * Subtitle cue overlay: styled text, placed by the caller via
 * [SubtitleStyle.verticalBias] / [SubtitleStyle.horizontal].
 * Each cue line hugs its own background; the column is width-constrained by
 * the caller so long lines wrap instead of running off-screen.
 *
 * [baseFontSp] is scaled to the player dimensions (PiP ~8.5 sp, embedded ~14 sp,
 * fullscreen ~21 sp) so captions scale proportionally to the video.
 */
@Composable
fun SubtitleOverlay(
    cues: List<String>,
    style: SubtitleStyle = SubtitleStyle(),
    baseFontSp: Float = BASE_FONT_SP,
    modifier: Modifier = Modifier,
) {
    val fontSp = baseFontSp * style.textScale
    val scaleRatio = (fontSp / BASE_FONT_SP).coerceIn(0.5f, 2.5f)
    val bgAlpha = style.background.alpha()
    val textColor = Color(style.colorArgb)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((2.dp * style.textScale * scaleRatio).coerceAtLeast(1.dp)),
    ) {
        cues.forEach { line ->
            Text(
                text = line,
                color = textColor,
                fontSize = fontSp.sp,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .then(
                        if (bgAlpha > 0f) {
                            Modifier.background(
                                Color.Black.copy(alpha = bgAlpha),
                                RoundedCornerShape((4.dp * scaleRatio).coerceAtLeast(2.dp)),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        horizontal = (8.dp * style.textScale * scaleRatio).coerceAtLeast(4.dp),
                        vertical = (2.dp * style.textScale * scaleRatio).coerceAtLeast(1.dp),
                    ),
            )
        }
    }
}
