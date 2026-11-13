package com.manytwo.hakeypad

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils

/**
 * The keypad's colours: a dark or light theme (or the phone's own Dark/Light setting), with
 * optional own colours for the background, the text, and the buttons (off and on). The text
 * on an "on" button is black or white, whichever reads better on the chosen colour.
 */
data class Palette(
    val bg: Int,
    val bar: Int,          // title and clock
    val text: Int,         // text on off / sensor / offline tiles
    val tileOff: Int,
    val tileOn: Int,
    val textOn: Int,
    val tileSensor: Int,
    val tileUnavailable: Int,
    val dark: Boolean,
) {
    companion object {
        private val DARK = Palette(
            bg = 0xFF101214.toInt(), bar = 0xFFB0B6BC.toInt(), text = 0xFFE8EAED.toInt(),
            tileOff = 0xFF2C3136.toInt(), tileOn = 0xFFFFB300.toInt(), textOn = 0xFF1A1200.toInt(),
            tileSensor = 0xFF1E3A4C.toInt(), tileUnavailable = 0xFF1A1C1E.toInt(), dark = true)
        private val LIGHT = Palette(
            bg = 0xFFF1F3F4.toInt(), bar = 0xFF5F6368.toInt(), text = 0xFF202124.toInt(),
            tileOff = 0xFFFFFFFF.toInt(), tileOn = 0xFFFFB300.toInt(), textOn = 0xFF1A1200.toInt(),
            tileSensor = 0xFFD6E6F2.toInt(), tileUnavailable = 0xFFE0E2E5.toInt(), dark = false)

        fun systemIsDark(ctx: Context) =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        fun current(ctx: Context, prefs: Prefs): Palette {
            val base = when (prefs.theme) {
                "dark" -> DARK
                "light" -> LIGHT
                else -> if (systemIsDark(ctx)) DARK else LIGHT
            }
            val bg = prefs.colBackground.takeIf { it != 0 } ?: base.bg
            val text = prefs.colText.takeIf { it != 0 } ?: base.text
            val off = prefs.colButtonOff.takeIf { it != 0 } ?: base.tileOff
            val on = prefs.colButtonOn.takeIf { it != 0 } ?: base.tileOn
            val darkBg = ColorUtils.calculateLuminance(bg) < 0.4
            return base.copy(
                bg = bg,
                text = text,
                bar = if (prefs.colText != 0) ColorUtils.setAlphaComponent(text, 0xC0) else base.bar,
                tileOff = off,
                tileOn = on,
                textOn = readableOn(on),
                // Offline tiles: between the button and the background, so they look "absent".
                tileUnavailable = if (prefs.colButtonOff != 0 || prefs.colBackground != 0)
                    ColorUtils.blendARGB(off, bg, 0.55f) else base.tileUnavailable,
                dark = darkBg,
            )
        }

        /** Black or white, whichever has more contrast on [c]. */
        fun readableOn(c: Int): Int =
            if (ColorUtils.calculateContrast(0xFF000000.toInt(), c or 0xFF000000.toInt()) >=
                ColorUtils.calculateContrast(0xFFFFFFFF.toInt(), c or 0xFF000000.toInt())) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()

        fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

        /** Choices in the colour dialog. */
        val SWATCHES = intArrayOf(
            0xFF000000.toInt(), 0xFF101214.toInt(), 0xFF202124.toInt(), 0xFF2C3136.toInt(), 0xFF3C4043.toInt(), 0xFF5F6368.toInt(),
            0xFF9AA0A6.toInt(), 0xFFDADCE0.toInt(), 0xFFF1F3F4.toInt(), 0xFFFFFFFF.toInt(), 0xFF0D1B2A.toInt(), 0xFF1E3A4C.toInt(),
            0xFF1565C0.toInt(), 0xFF42A5F5.toInt(), 0xFF00838F.toInt(), 0xFF26A69A.toInt(), 0xFF2E7D32.toInt(), 0xFF66BB6A.toInt(),
            0xFF9E9D24.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFB300.toInt(), 0xFFFB8C00.toInt(), 0xFFE53935.toInt(), 0xFFAD1457.toInt(),
            0xFF6A1B9A.toInt(), 0xFFAB47BC.toInt(), 0xFF5D4037.toInt(), 0xFFA1887F.toInt(),
        )

        /**
         * A simple colour chooser: tap a swatch or type #RRGGBB. [current] 0 = theme default.
         * Calls [done] with the colour, or 0 for "theme default".
         */
        fun choose(ctx: Context, title: String, current: Int, done: (Int) -> Unit) {
            val d = ctx.resources.displayMetrics.density
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0) }
            val hexField = EditText(ctx).apply {
                hint = "#RRGGBB"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                if (current != 0) setText(hex(current))
            }
            val grid = GridLayout(ctx).apply { columnCount = 7 }
            var dialog: AlertDialog? = null
            // Fit 7 swatches across the dialog on any screen (dialog ≈ 80 % of the width, minus padding).
            val avail = ctx.resources.displayMetrics.widthPixels * 0.8f - 2 * 44 * d
            val size = (avail / 7 - 6 * d).toInt().coerceIn((24 * d).toInt(), (44 * d).toInt())
            for (c in SWATCHES) {
                grid.addView(View(ctx).apply {
                    background = GradientDrawable().apply {
                        setColor(c); cornerRadius = 8 * d
                        setStroke((1 * d).toInt(), 0x66888888)
                    }
                    layoutParams = GridLayout.LayoutParams().apply { width = size; height = size; setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt()) }
                    contentDescription = hex(c)
                    setOnClickListener { done(c); dialog?.dismiss() }
                })
            }
            box.addView(grid)
            box.addView(hexField)
            dialog = AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Use #RRGGBB") { _, _ ->
                    val t = hexField.text.toString().trim().removePrefix("#")
                    val c = t.toLongOrNull(16)
                    if (t.length == 6 && c != null) done((0xFF000000 or c).toInt())
                }
                .setNeutralButton("Theme default") { _, _ -> done(0) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** A small coloured square for the Settings rows. */
        fun swatch(view: View, c: Int) {
            val d = view.resources.displayMetrics.density
            view.background = GradientDrawable().apply {
                setColor(c); cornerRadius = 6 * d; setStroke((1 * d).toInt(), 0x66888888)
            }
        }
    }
}
