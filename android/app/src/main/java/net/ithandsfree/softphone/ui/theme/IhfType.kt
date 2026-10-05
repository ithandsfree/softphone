package net.ithandsfree.softphone.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.ithandsfree.softphone.R

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

val InstrumentSerifFamily = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

val IbmPlexMonoFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

@Immutable
data class IhfType(
    val display: TextStyle,
    val title: TextStyle,
    val titleSm: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
    val numberXl: TextStyle,
    val number: TextStyle,
    val timer: TextStyle,
) {
    companion object {
        val Default = IhfType(
            display = TextStyle(
                fontFamily = InstrumentSerifFamily,
                fontSize = 40.sp,
                lineHeight = 42.sp,
            ),
            title = TextStyle(
                fontFamily = InstrumentSerifFamily,
                fontSize = 32.sp,
                lineHeight = 34.sp,
            ),
            titleSm = TextStyle(
                fontFamily = InstrumentSerifFamily,
                fontSize = 26.sp,
                lineHeight = 29.sp,
            ),
            heading = TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                lineHeight = 22.sp,
            ),
            body = TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            ),
            label = TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            ),
            caption = TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            overline = TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.96.sp,
            ),
            numberXl = TextStyle(
                fontFamily = IbmPlexMonoFamily,
                fontSize = 36.sp,
                lineHeight = 40.sp,
            ),
            number = TextStyle(
                fontFamily = IbmPlexMonoFamily,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            ),
            timer = TextStyle(
                fontFamily = IbmPlexMonoFamily,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.8.sp,
            ),
        )
    }
}
