package net.ithandsfree.softphone.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class IhfSpace(
    val base: Dp = 4.dp,
    val gutter: Dp = 16.dp,
    val hitMin: Dp = 44.dp,
    val rowH: Dp = 72.dp,
    val rowHSm: Dp = 52.dp,
    val keyH: Dp = 64.dp,
    val callBtn: Dp = 72.dp,
    val fab: Dp = 56.dp,
)

@Immutable
data class IhfRadius(
    val chip: Dp = 8.dp,
    val control: Dp = 12.dp,
    val card: Dp = 16.dp,
    val sheet: Dp = 24.dp,
)

@Immutable
data class IhfMotion(
    val lineSwitch: AnimationSpec<Float>,
    val send: AnimationSpec<Float>,
    val connect: AnimationSpec<Float>,
) {
    companion object {
        private val EaseOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

        fun create(durationScale: Float): IhfMotion {
            fun dur(ms: Int): Int = (ms * durationScale).toInt().coerceAtLeast(0)
            return IhfMotion(
                lineSwitch = tween(durationMillis = dur(180), easing = EaseOut),
                send = tween(durationMillis = dur(240), easing = EaseOut),
                connect = tween(durationMillis = dur(600), easing = EaseInOut),
            )
        }
    }
}

val LocalIhfColors = staticCompositionLocalOf { IhfColors.Dark }
val LocalSoftphoneSkin = staticCompositionLocalOf { SoftphoneSkins.IhfNight }
val LocalIhfType = staticCompositionLocalOf { IhfType.Default }
val LocalIhfSpace = staticCompositionLocalOf { IhfSpace() }
val LocalIhfRadius = staticCompositionLocalOf { IhfRadius() }
val LocalIhfMotion = staticCompositionLocalOf { IhfMotion.create(1f) }
