package com.xiaomi.liquidwatermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class LiquidOpticsConfig(
    val refractionPx: Float = 20f,
    val blurPx: Float = 2f,
    val specularIntensity: Float = 0.20f,
    val widthRatio: Float = 0.98f,
    val heightRatio: Float = 0.176f,
    val bottomOffsetRatio: Float = 0.010f,
    val contentShiftXRatio: Float = 0.0f,
    val contentSpreadRatio: Float = 0.0f
)

object LiquidGlassRenderer {

    fun renderWatermark(
        source: Bitmap,
        meta: PhotoMetadata,
        optics: LiquidOpticsConfig = LiquidOpticsConfig(),
        logoBitmap: Bitmap? = null
    ): Bitmap {
        val W = source.width
        val H = source.height
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val shortEdge = min(W, H).toFloat()
        val refScale = shortEdge / 743f

        val barW = (W * optics.widthRatio).roundToInt().coerceIn(80, W)
        val rawBarH = (shortEdge * optics.heightRatio).roundToInt()
        val barH = rawBarH.coerceIn(44, (H * 0.38f).roundToInt())
        val barX = ((W - barW) / 2).coerceAtLeast(0)
        val barY = (H - barH - (H * optics.bottomOffsetRatio).roundToInt()).coerceIn(0, H - barH)
        val radius = barH * 0.49f

        val roiW = barW
        val roiH = barH
        val sharpPixels = IntArray(roiW * roiH)
        source.getPixels(sharpPixels, 0, roiW, barX, barY, roiW, roiH)

        val downscaleFactor = max(1, (optics.blurPx * 1.5f).roundToInt())
        val blurPixels = IntArray(roiW * roiH)
        if (optics.blurPx > 0.2f) {
            val cropBmp = Bitmap.createBitmap(source, barX, barY, roiW, roiH)
            val smallBmp = Bitmap.createScaledBitmap(
                cropBmp,
                max(1, roiW / downscaleFactor),
                max(1, roiH / downscaleFactor),
                true
            )
            val blurredBmp = Bitmap.createScaledBitmap(smallBmp, roiW, roiH, true)
            blurredBmp.getPixels(blurPixels, 0, roiW, 0, 0, roiW, roiH)
            if (cropBmp !== source) cropBmp.recycle()
            if (smallBmp !== blurredBmp) smallBmp.recycle()
            blurredBmp.recycle()
        } else {
            System.arraycopy(sharpPixels, 0, blurPixels, 0, sharpPixels.size)
        }

        val outPixels = IntArray(roiW * roiH)
        val cx = barW / 2f
        val cy = barH / 2f
        val halfW = barW / 2f
        val halfH = barH / 2f
        val bevelWidth = max(10f, barH * 0.28f)
        val maxRefract = optics.refractionPx * refScale
        val spec = optics.specularIntensity

        for (y in 0 until roiH) {
            for (x in 0 until roiW) {
                val qx = abs(x - cx) - (halfW - radius)
                val qy = abs(y - cy) - (halfH - radius)
                val outDist = hypot(max(qx, 0f), max(qy, 0f))
                val inDist = min(max(qx, qy), 0f)
                val d = outDist + inDist - radius

                val idx = y * roiW + x
                if (d > 1.0f) {
                    outPixels[idx] = sharpPixels[idx]
                    continue
                }

                var nx = 0f
                var ny = 0f
                if (qx > 0f || qy > 0f) {
                    val ox = max(qx, 0f)
                    val oy = max(qy, 0f)
                    val len = max(1e-4f, hypot(ox, oy))
                    nx = (ox / len) * if (x >= cx) 1f else -1f
                    ny = (oy / len) * if (y >= cy) 1f else -1f
                } else {
                    if (qx > qy) nx = if (x >= cx) 1f else -1f
                    else ny = if (y >= cy) 1f else -1f
                }

                val rimT = (1f + d / bevelWidth).coerceIn(0f, 1f)
                val lensCurve = rimT * rimT * (3f - 2f * rimT)

                val sx = (x - nx * lensCurve * maxRefract).roundToInt().coerceIn(0, roiW - 1)
                val sy = (y - ny * lensCurve * maxRefract * 1.15f).roundToInt().coerceIn(0, roiH - 1)
                val sIdx = sy * roiW + sx

                val sCol = sharpPixels[sIdx]
                val bCol = blurPixels[sIdx]
                val frostMix = 1f - lensCurve * 0.78f

                var r = ((sCol shr 16 and 0xFF) * (1f - frostMix) + (bCol shr 16 and 0xFF) * frostMix) * 0.89f + 16f
                var g = ((sCol shr 8 and 0xFF) * (1f - frostMix) + (bCol shr 8 and 0xFF) * frostMix) * 0.89f + 17f
                var b = ((sCol and 0xFF) * (1f - frostMix) + (bCol and 0xFF) * frostMix) * 0.89f + 20f

                val extremeRim = (1f + d / (bevelWidth * 0.35f)).coerceIn(0f, 1f)
                val rimHighlight = (extremeRim.pow(2.2f) * 0.65f + lensCurve.pow(3f) * 0.18f) * spec * 255f
                r = (r + rimHighlight).coerceIn(0f, 255f)
                g = (g + rimHighlight).coerceIn(0f, 255f)
                b = (b + rimHighlight).coerceIn(0f, 255f)

                outPixels[idx] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }

        val patchBmp = Bitmap.createBitmap(outPixels, roiW, roiH, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(patchBmp, barX.toFloat(), barY.toFloat(), null)
        patchBmp.recycle()

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.2f, barH * 0.012f)
            color = Color.argb((140 * spec).roundToInt().coerceIn(15, 255), 255, 255, 255)
        }
        val rectF = RectF(barX.toFloat(), barY.toFloat(), (barX + barW).toFloat(), (barY + barH).toFloat())
        canvas.drawRoundRect(rectF, radius, radius, strokePaint)

        val centerY = barY + barH / 2f
        val aspect = W.toFloat() / H.toFloat()
        val landscapeScale = if (aspect > 1.08f) min(1.18f, 1.0f + (aspect - 1.0f) * 0.22f) else 1.0f

        val modelFontSize = barH * 0.215f * landscapeScale
        val subFontSize = barH * 0.125f * landscapeScale
        val specsFontSize = barH * 0.172f * landscapeScale
        val gpsFontSize = barH * 0.118f * landscapeScale

        val topBaselineY = centerY - barH * 0.045f
        val botBaselineY = centerY + barH * 0.185f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(max(2f, barH * 0.03f), 0f, max(1f, barH * 0.01f), Color.argb(80, 0, 0, 0))
        }

        textPaint.textSize = modelFontSize
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val modelW = textPaint.measureText(meta.model)

        textPaint.textSize = subFontSize
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val dateW = textPaint.measureText(meta.dateTime)
        val leftBlockW = max(modelW, dateW)

        textPaint.textSize = specsFontSize
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val specsW = textPaint.measureText(meta.specs)

        textPaint.textSize = gpsFontSize
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val gpsW = textPaint.measureText(meta.gps)
        val rightTextW = max(specsW, gpsW)

        val logoBoxSize = barH * 0.35f * landscapeScale
        val gapLogoToDiv = barH * 0.105f * landscapeScale
        val gapDivToRight = barH * 0.115f * landscapeScale
        val rightClusterW = logoBoxSize + gapLogoToDiv + gapDivToRight + rightTextW

        val usableW = (barW - leftBlockW - rightClusterW).coerceAtLeast(barH * 0.2f)
        val middleRatio = if (aspect > 1.05f) 0.34f else 0.24f
        val spreadDeltaPx = barW * optics.contentSpreadRatio
        val middleGap = max(barH * 0.18f, usableW * middleRatio + spreadDeltaPx)
        val totalContentW = leftBlockW + middleGap + rightClusterW

        val shiftXPx = barW * optics.contentShiftXRatio
        val minStartX = barX + barH * 0.28f
        val maxStartX = (barX + barW - totalContentW - barH * 0.24f).coerceAtLeast(minStartX)
        val startLeftX = (barX + (barW - totalContentW) / 2f + shiftXPx).coerceIn(minStartX, maxStartX)

        val leftPad = startLeftX
        val logoLeftX = leftPad + leftBlockW + middleGap
        val logoCenterX = logoLeftX + logoBoxSize / 2f
        val logoCenterY = centerY + barH * 0.01f
        val divX = logoLeftX + logoBoxSize + gapLogoToDiv
        val rightTextX = divX + gapDivToRight

        textPaint.textSize = modelFontSize
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.alpha = 245
        canvas.drawText(meta.model, leftPad, topBaselineY, textPaint)

        textPaint.textSize = subFontSize
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.alpha = 188
        canvas.drawText(meta.dateTime, leftPad, botBaselineY, textPaint)

        if (logoBitmap != null && !logoBitmap.isRecycled && logoBitmap.width > 0 && logoBitmap.height > 0) {
            val lw = logoBitmap.width.toFloat()
            val lh = logoBitmap.height.toFloat()
            val scaleLogo = min(logoBoxSize / lw, logoBoxSize / lh)
            val drawW = lw * scaleLogo
            val drawH = lh * scaleLogo
            val dstRect = RectF(
                logoCenterX - drawW / 2f,
                logoCenterY - drawH / 2f,
                logoCenterX + drawW / 2f,
                logoCenterY + drawH / 2f
            )
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(logoBitmap, null, dstRect, bmpPaint)
        }

        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(95, 255, 255, 255)
            strokeWidth = max(1f, barH * 0.011f)
        }
        canvas.drawLine(divX, centerY - barH * 0.165f * landscapeScale, divX, centerY + barH * 0.19f * landscapeScale, divPaint)

        textPaint.textSize = specsFontSize
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.alpha = 242
        canvas.drawText(meta.specs, rightTextX, topBaselineY, textPaint)

        textPaint.textSize = gpsFontSize
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.alpha = 178
        canvas.drawText(meta.gps, rightTextX, botBaselineY, textPaint)

        return result
    }
}
