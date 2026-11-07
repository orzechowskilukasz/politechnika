package io.github.orzechowskilukasz.tagfinder

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Button
import android.widget.TextView
import kotlin.math.roundToInt

internal object Palette {
    val ink = Color.rgb(0x1F, 0x23, 0x28)
    val muted = Color.rgb(0x5F, 0x66, 0x6D)
    val track = Color.rgb(0xE3, 0xE6, 0xEA)
    val accent = Color.rgb(0x1A, 0x73, 0xE8)
    val warm = Color.rgb(0xE8, 0x5D, 0x1A)
    val panel = Color.argb(0xCC, 0xFF, 0xFF, 0xFF)
}

internal fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

internal fun Context.label(text: String = "", sizeSp: Float = 16f, bold: Boolean = false, color: Int = Palette.ink) =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(16), dp(6), dp(16), dp(6))
    }

internal fun Context.button(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}
