// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import io.agents.pokeclaw.utils.KVUtils

/**
 * "Large text (elder mode)": one place for the bigger-text / bigger-touch-target scaling and the
 * screen-width helpers every Saathi screen uses. Default ON for the family build.
 */
object ElderScale {
    private const val KEY = "ELDER_MODE_LARGE_TEXT"
    const val DENSITY_FACTOR = 1.12f
    const val FONT_FACTOR = 1.3f
    /** XML screens (text sizes are in dp there, so fontScale does not reach them). */
    const val XML_TEXT_FACTOR = 1.25f

    // Compose state so open screens re-render when the switch changes. Lazy: MMKV is initialised
    // in Application.onCreate, before any screen touches this.
    private val state by lazy { mutableStateOf(KVUtils.getBoolean(KEY, true)) }

    fun isEnabled(): Boolean = state.value

    fun setEnabled(enabled: Boolean) {
        KVUtils.putBoolean(KEY, enabled)
        state.value = enabled
    }

    /**
     * Enlarges every TextView under [root] (XML screens). Works in dp, never raw pixels: the current
     * size is read back as dp through the view's own display metrics, then multiplied.
     */
    fun scaleTextViews(root: View, factor: Float = XML_TEXT_FACTOR) {
        if (root is TextView) {
            val density = root.resources.displayMetrics.density
            if (density > 0f) {
                val currentDp = root.textSize / density
                root.setTextSize(TypedValue.COMPLEX_UNIT_DIP, currentDp * factor)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) scaleTextViews(root.getChildAt(i), factor)
        }
    }
}

/** Wrap a screen's setContent body in this. Scales text and touch targets when elder mode is ON. */
@Composable
fun ElderScaled(content: @Composable () -> Unit) {
    if (!ElderScale.isEnabled()) {
        content()
        return
    }
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = d.density * ElderScale.DENSITY_FACTOR,
            fontScale = d.fontScale * ElderScale.FONT_FACTOR,
        )
    ) {
        content()
    }
}

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/**
 * Usable screen width in the dp the current composition draws with. Inside [ElderScaled] one dp is
 * drawn larger, so fewer of them fit: this accounts for that (raw screenWidthDp would not).
 */
@Composable
fun screenWidth(): Dp {
    val config = LocalConfiguration.current
    val systemDensity = config.densityDpi / 160f
    val current = LocalDensity.current.density
    val ratio = if (current > 0f && systemDensity > 0f) systemDensity / current else 1f
    return (config.screenWidthDp * ratio).dp
}

/** < 360dp compact (small phones / elder scaling), >= 600dp expanded (tablets, landscape). */
@Composable
fun widthClass(): WidthClass {
    val w = screenWidth()
    return when {
        w < 360.dp -> WidthClass.COMPACT
        w >= 600.dp -> WidthClass.EXPANDED
        else -> WidthClass.MEDIUM
    }
}

/** Side padding that follows the screen width. */
@Composable
fun edgePadding(): Dp = when (widthClass()) {
    WidthClass.COMPACT -> 8.dp
    WidthClass.MEDIUM -> 12.dp
    WidthClass.EXPANDED -> 24.dp
}

/** A size as a fraction of the screen width, kept between [min] and [max]. */
@Composable
fun widthFraction(fraction: Float, min: Dp, max: Dp): Dp = (screenWidth() * fraction).coerceIn(min, max)

/** Usable screen height in the dp the current composition draws with (see [screenWidth]). */
@Composable
fun screenHeight(): Dp {
    val config = LocalConfiguration.current
    val systemDensity = config.densityDpi / 160f
    val current = LocalDensity.current.density
    val ratio = if (current > 0f && systemDensity > 0f) systemDensity / current else 1f
    return (config.screenHeightDp * ratio).dp
}
