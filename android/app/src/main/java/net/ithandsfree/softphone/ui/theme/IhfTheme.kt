package net.ithandsfree.softphone.ui.theme

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import net.ithandsfree.softphone.data.SoftphoneAccount

data class LineUi(
    val id: String,
    val label: String,
    val ext: String,
    val did: String,
    val colorSlot: LineColorSlot,
    val smsEnabled: Boolean = true,
    val mmsEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val dndEnabled: Boolean = false,
)

fun SoftphoneAccount.toLineUi(index: Int): LineUi = LineUi(
    id = id,
    label = label.ifBlank { umUsername },
    ext = sipExtension.ifBlank { umUsername },
    did = did,
    colorSlot = if (index == 0) LineColorSlot.Emerald else LineColorSlot.Sky,
    smsEnabled = capSms,
    mmsEnabled = capMms,
    voiceEnabled = capVoice,
    dndEnabled = capDnd,
)

@Composable
fun IhfTheme(
    skin: SoftphoneSkin = SoftphoneSkins.IhfNight,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val durationScale = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }
    val colors = skin.colors
    val motion = remember(durationScale) { IhfMotion.create(durationScale) }

    val material = darkColorScheme(
        primary = colors.gold,
        onPrimary = colors.goldInk,
        secondary = colors.emerald,
        onSecondary = colors.ground,
        tertiary = colors.sky,
        background = colors.ground,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.raised,
        onSurfaceVariant = colors.muted,
        outline = colors.hairline,
        error = colors.coral,
        onError = colors.coralInk,
    )

    CompositionLocalProvider(
        LocalIhfColors provides colors,
        LocalSoftphoneSkin provides skin,
        LocalIhfType provides IhfType.Default,
        LocalIhfSpace provides IhfSpace(),
        LocalIhfRadius provides IhfRadius(),
        LocalIhfMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = material,
            content = content,
        )
    }
}

object IhfThemeAccess {
    val colors: IhfColors
        @Composable get() = LocalIhfColors.current
    val skin: SoftphoneSkin
        @Composable get() = LocalSoftphoneSkin.current
    val type: IhfType
        @Composable get() = LocalIhfType.current
    val space: IhfSpace
        @Composable get() = LocalIhfSpace.current
    val radius: IhfRadius
        @Composable get() = LocalIhfRadius.current
    val motion: IhfMotion
        @Composable get() = LocalIhfMotion.current
}
