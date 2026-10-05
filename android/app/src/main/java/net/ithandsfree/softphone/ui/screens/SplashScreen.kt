package net.ithandsfree.softphone.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.ithandsfree.softphone.BuildConfig
import net.ithandsfree.softphone.R
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.InstrumentSerifFamily
import net.ithandsfree.softphone.ui.theme.LocalIhfType

/**
 * Launch splash — design handoff splash.html / WELCOME-SPEC.
 * Navy ground, gold emblem, product name, brand line. Brief hold
 * then continues to Welcome (pre-enrol) when no lines are enrolled.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val c = IhfThemeAccess.colors
    val skin = IhfThemeAccess.skin
    val type = LocalIhfType.current
    val brandAlpha = remember { Animatable(0f) }
    val emblemScale = remember { Animatable(0.92f) }

    LaunchedEffect(Unit) {
        brandAlpha.animateTo(1f, tween(durationMillis = 480))
        emblemScale.animateTo(1f, tween(durationMillis = 520))
        delay(1100)
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .alpha(brandAlpha.value)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_ihf_emblem),
                contentDescription = null,
                modifier = Modifier
                    .width((88 * emblemScale.value).dp)
                    .height((72 * emblemScale.value).dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                BuildConfig.PRODUCT_NAME.ifBlank { skin.productName },
                style = type.display.copy(
                    fontSize = 40.sp,
                    lineHeight = 42.sp,
                    fontFamily = InstrumentSerifFamily,
                ),
                color = c.text,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            BuildConfig.BRAND_SUB,
            style = type.overline.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                fontSize = 11.sp,
            ),
            color = c.gold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .alpha(brandAlpha.value)
                .padding(bottom = 36.dp),
        )
    }
}
