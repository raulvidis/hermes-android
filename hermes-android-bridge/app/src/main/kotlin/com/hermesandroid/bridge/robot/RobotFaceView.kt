package com.hermesandroid.bridge.robot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.hermesandroid.bridge.R
import kotlin.math.min
import kotlin.math.sin

/**
 * Cinematic hybrid face.
 *
 * The bundled render supplies only Cradata's shell and blank glass faceplate.
 * Eyes, mouth, expressions and state colours stay native and animate locally;
 * no conversation data is embedded in or written to the image asset.
 */
internal class RobotFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    companion object {
        private const val ACTIVE_FRAME_DELAY_MS = 33L
        private const val RESTING_FRAME_DELAY_MS = 66L
        private const val PHASE_TRANSITION_MS = 360f

        private val BACKGROUND = Color.rgb(4, 13, 25)
        private val GLASS_DARK = Color.rgb(2, 10, 20)
        private val PUPIL_DARK = Color.rgb(3, 17, 28)
        private val IDLE_ACCENT = Color.rgb(104, 238, 224)
        private val LISTENING_ACCENT = Color.rgb(255, 188, 82)
        private val THINKING_ACCENT = Color.rgb(104, 202, 255)
        private val SPEAKING_ACCENT = Color.rgb(91, 236, 161)
        private val ERROR_ACCENT = Color.rgb(255, 112, 120)
    }

    private val shell: Bitmap = requireNotNull(
        BitmapFactory.decodeResource(resources, R.drawable.cradata_cinematic_shell),
    ) { "Cradata cinematic shell resource is missing" }

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val faceBounds = RectF()
    private val featureBounds = RectF()
    private val glowBounds = RectF()
    private val glassBounds = RectF()
    private val featurePath = Path()

    private var cachedShaderAccent = Color.TRANSPARENT
    private var cachedShaderWidth = -1
    private var cachedShaderHeight = -1
    private var eyeShader: LinearGradient? = null
    private var glassShader: RadialGradient? = null

    private var previousPhase = RobotPhase.IDLE
    private var phaseChangedAt = SystemClock.uptimeMillis()

    var phase: RobotPhase = RobotPhase.IDLE
        set(value) {
            if (field != value) {
                previousPhase = field
                phaseChangedAt = SystemClock.uptimeMillis()
                field = value
            }
            contentDescription = "Cradata Roboter-Gesicht: ${value.wireName}"
            invalidate()
        }

    init {
        contentDescription = "Cradata Roboter-Gesicht: ${phase.wireName}"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val now = SystemClock.uptimeMillis()
        val unit = min(width, height).toFloat()
        val bob = unit * 0.0045f * sin(now / 1_250.0).toFloat()
        val breathingScale = 1f + 0.0035f * sin(now / 1_900.0).toFloat()
        val left = (width - unit) / 2f
        val top = (height - unit) / 2f + bob
        faceBounds.set(left, top, left + unit, top + unit)

        canvas.drawColor(BACKGROUND)
        canvas.save()
        canvas.scale(breathingScale, breathingScale, faceBounds.centerX(), faceBounds.centerY())
        canvas.drawBitmap(shell, null, faceBounds, imagePaint)

        val accent = animatedAccent(now)
        ensureShaders(accent, unit)
        drawGlassAmbience(canvas, unit, now)
        drawListeningRipples(canvas, accent, unit, now)
        drawEyes(canvas, accent, unit, now)
        drawMouth(canvas, accent, unit, now)
        drawHardwareLights(canvas, accent, unit, now)
        canvas.restore()

        val active = phase == RobotPhase.LISTENING ||
            phase == RobotPhase.THINKING ||
            phase == RobotPhase.SPEAKING
        postInvalidateDelayed(if (active) ACTIVE_FRAME_DELAY_MS else RESTING_FRAME_DELAY_MS)
    }

    private fun drawGlassAmbience(canvas: Canvas, unit: Float, now: Long) {
        glassBounds.set(
            faceX(0.218f),
            faceY(0.315f),
            faceX(0.782f),
            faceY(0.785f),
        )
        val pulse = 0.78f + 0.22f * sin(now / 620.0).toFloat()
        fillPaint.shader = glassShader
        fillPaint.alpha = (255f * pulse).toInt()
        canvas.save()
        featurePath.reset()
        featurePath.addRoundRect(
            glassBounds,
            unit * 0.16f,
            unit * 0.16f,
            Path.Direction.CW,
        )
        canvas.clipPath(featurePath)
        canvas.drawRect(glassBounds, fillPaint)
        canvas.restore()
        fillPaint.shader = null
        fillPaint.alpha = 255
    }

    private fun drawEyes(canvas: Canvas, accent: Int, unit: Float, now: Long) {
        val listeningPulse = if (phase == RobotPhase.LISTENING) {
            1f + 0.055f * sin(now / 180.0).toFloat()
        } else {
            1f
        }
        val blinkScale = blinkScale(now)
        val baseWidth = unit * 0.115f * listeningPulse
        val baseHeight = unit * 0.090f * listeningPulse * blinkScale
        val eyeY = faceY(
            when (phase) {
                RobotPhase.THINKING -> 0.475f
                else -> 0.49f
            },
        )
        val glanceX = when (phase) {
            RobotPhase.THINKING -> unit * 0.018f
            RobotPhase.SPEAKING -> unit * 0.004f * sin(now / 430.0).toFloat()
            else -> 0f
        }
        val glanceY = if (phase == RobotPhase.THINKING) -unit * 0.012f else 0f

        drawEye(
            canvas = canvas,
            centerX = faceX(0.378f),
            centerY = eyeY,
            width = baseWidth,
            height = baseHeight * if (phase == RobotPhase.THINKING) 0.88f else 1f,
            pupilOffsetX = glanceX,
            pupilOffsetY = glanceY,
            accent = accent,
            unit = unit,
        )
        drawEye(
            canvas = canvas,
            centerX = faceX(0.622f),
            centerY = eyeY,
            width = baseWidth,
            height = baseHeight,
            pupilOffsetX = glanceX,
            pupilOffsetY = glanceY,
            accent = accent,
            unit = unit,
        )

        if (blinkScale > 0.3f) {
            drawBrows(canvas, accent, unit)
        }
    }

    private fun drawEye(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        pupilOffsetX: Float,
        pupilOffsetY: Float,
        accent: Int,
        unit: Float,
    ) {
        val halfWidth = width / 2f
        val halfHeight = maxOf(height / 2f, unit * 0.005f)
        featureBounds.set(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight,
        )

        fillPaint.color = withAlpha(accent, 26)
        canvas.drawRoundRect(
            expanded(featureBounds, unit * 0.027f),
            halfHeight + unit * 0.027f,
            halfHeight + unit * 0.027f,
            fillPaint,
        )
        fillPaint.color = withAlpha(accent, 62)
        canvas.drawRoundRect(
            expanded(featureBounds, unit * 0.013f),
            halfHeight + unit * 0.013f,
            halfHeight + unit * 0.013f,
            fillPaint,
        )

        fillPaint.shader = eyeShader
        canvas.drawRoundRect(featureBounds, halfHeight, halfHeight, fillPaint)
        fillPaint.shader = null

        if (height > unit * 0.025f) {
            val pupilRadius = min(width, height) * 0.27f
            fillPaint.color = PUPIL_DARK
            canvas.drawCircle(
                centerX + pupilOffsetX,
                centerY + pupilOffsetY,
                pupilRadius,
                fillPaint,
            )
            linePaint.color = withAlpha(accent, 185)
            linePaint.strokeWidth = unit * 0.004f
            canvas.drawCircle(
                centerX + pupilOffsetX,
                centerY + pupilOffsetY,
                pupilRadius * 0.72f,
                linePaint,
            )
            fillPaint.color = withAlpha(Color.WHITE, 230)
            canvas.drawCircle(
                centerX + pupilOffsetX - pupilRadius * 0.32f,
                centerY + pupilOffsetY - pupilRadius * 0.36f,
                pupilRadius * 0.19f,
                fillPaint,
            )
        }
    }

    private fun drawBrows(canvas: Canvas, accent: Int, unit: Float) {
        if (phase != RobotPhase.THINKING && phase != RobotPhase.ERROR) return
        linePaint.color = withAlpha(accent, 180)
        linePaint.strokeWidth = unit * 0.009f
        featurePath.reset()
        if (phase == RobotPhase.ERROR) {
            featurePath.moveTo(faceX(0.325f), faceY(0.415f))
            featurePath.lineTo(faceX(0.425f), faceY(0.44f))
            featurePath.moveTo(faceX(0.575f), faceY(0.44f))
            featurePath.lineTo(faceX(0.675f), faceY(0.415f))
        } else {
            featurePath.moveTo(faceX(0.325f), faceY(0.425f))
            featurePath.quadTo(faceX(0.38f), faceY(0.397f), faceX(0.435f), faceY(0.42f))
            featurePath.moveTo(faceX(0.57f), faceY(0.405f))
            featurePath.quadTo(faceX(0.625f), faceY(0.373f), faceX(0.68f), faceY(0.397f))
        }
        canvas.drawPath(featurePath, linePaint)
    }

    private fun drawMouth(canvas: Canvas, accent: Int, unit: Float, now: Long) {
        val centerX = faceX(0.5f)
        val centerY = faceY(0.655f)
        when (phase) {
            RobotPhase.LISTENING -> drawListeningMouth(canvas, centerX, centerY, accent, unit, now)
            RobotPhase.THINKING -> drawThinkingMouth(canvas, centerX, centerY, accent, unit, now)
            RobotPhase.SPEAKING -> drawSpeakingMouth(canvas, centerX, centerY, accent, unit, now)
            RobotPhase.ERROR -> drawFrown(canvas, centerX, centerY, accent, unit)
            else -> drawSmile(canvas, centerX, centerY, accent, unit)
        }
    }

    private fun drawSmile(canvas: Canvas, centerX: Float, centerY: Float, accent: Int, unit: Float) {
        featurePath.reset()
        featurePath.moveTo(centerX - unit * 0.095f, centerY - unit * 0.018f)
        featurePath.cubicTo(
            centerX - unit * 0.055f,
            centerY + unit * 0.055f,
            centerX + unit * 0.055f,
            centerY + unit * 0.055f,
            centerX + unit * 0.095f,
            centerY - unit * 0.018f,
        )
        drawGlowingPath(canvas, featurePath, accent, unit * 0.010f, unit)
    }

    private fun drawFrown(canvas: Canvas, centerX: Float, centerY: Float, accent: Int, unit: Float) {
        featurePath.reset()
        featurePath.moveTo(centerX - unit * 0.085f, centerY + unit * 0.035f)
        featurePath.cubicTo(
            centerX - unit * 0.05f,
            centerY - unit * 0.025f,
            centerX + unit * 0.05f,
            centerY - unit * 0.025f,
            centerX + unit * 0.085f,
            centerY + unit * 0.035f,
        )
        drawGlowingPath(canvas, featurePath, accent, unit * 0.010f, unit)
    }

    private fun drawListeningMouth(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        accent: Int,
        unit: Float,
        now: Long,
    ) {
        val pulse = 1f + 0.09f * sin(now / 155.0).toFloat()
        featureBounds.set(
            centerX - unit * 0.031f * pulse,
            centerY - unit * 0.045f * pulse,
            centerX + unit * 0.031f * pulse,
            centerY + unit * 0.045f * pulse,
        )
        drawGlowingOval(canvas, featureBounds, accent, unit)
    }

    private fun drawThinkingMouth(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        accent: Int,
        unit: Float,
        now: Long,
    ) {
        (-1..1).forEach { index ->
            val pulse = 0.82f + 0.25f * sin(now / 175.0 + index * 1.25).toFloat()
            val radius = unit * 0.0135f * pulse
            val x = centerX + index * unit * 0.055f
            fillPaint.color = withAlpha(accent, 42)
            canvas.drawCircle(x, centerY, radius + unit * 0.016f, fillPaint)
            fillPaint.color = lighten(accent, 0.38f)
            canvas.drawCircle(x, centerY, radius, fillPaint)
        }
    }

    private fun drawSpeakingMouth(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        accent: Int,
        unit: Float,
        now: Long,
    ) {
        val openness = 0.5f + 0.5f * sin(now / 105.0).toFloat()
        val halfHeight = unit * (0.032f + openness * 0.034f)
        featureBounds.set(
            centerX - unit * 0.105f,
            centerY - halfHeight,
            centerX + unit * 0.105f,
            centerY + halfHeight,
        )
        fillPaint.color = withAlpha(accent, 34)
        canvas.drawRoundRect(
            expanded(featureBounds, unit * 0.018f),
            halfHeight + unit * 0.018f,
            halfHeight + unit * 0.018f,
            fillPaint,
        )
        fillPaint.color = GLASS_DARK
        canvas.drawRoundRect(featureBounds, halfHeight, halfHeight, fillPaint)
        linePaint.color = lighten(accent, 0.22f)
        linePaint.strokeWidth = unit * 0.008f
        canvas.drawRoundRect(featureBounds, halfHeight, halfHeight, linePaint)

        (-2..2).forEach { index ->
            val wave = 0.35f + 0.65f *
                ((sin(now / 90.0 + index * 0.9).toFloat() + 1f) / 2f)
            val barHalfHeight = halfHeight * 0.55f * wave
            featureBounds.set(
                centerX + index * unit * 0.031f - unit * 0.0045f,
                centerY - barHalfHeight,
                centerX + index * unit * 0.031f + unit * 0.0045f,
                centerY + barHalfHeight,
            )
            fillPaint.color = withAlpha(lighten(accent, 0.45f), 225)
            canvas.drawRoundRect(featureBounds, unit * 0.005f, unit * 0.005f, fillPaint)
        }
    }

    private fun drawGlowingOval(canvas: Canvas, bounds: RectF, accent: Int, unit: Float) {
        fillPaint.color = withAlpha(accent, 32)
        canvas.drawOval(expanded(bounds, unit * 0.018f), fillPaint)
        fillPaint.color = GLASS_DARK
        canvas.drawOval(bounds, fillPaint)
        linePaint.color = lighten(accent, 0.25f)
        linePaint.strokeWidth = unit * 0.008f
        canvas.drawOval(bounds, linePaint)
    }

    private fun drawGlowingPath(
        canvas: Canvas,
        path: Path,
        accent: Int,
        coreWidth: Float,
        unit: Float,
    ) {
        linePaint.color = withAlpha(accent, 35)
        linePaint.strokeWidth = coreWidth + unit * 0.025f
        canvas.drawPath(path, linePaint)
        linePaint.color = withAlpha(accent, 95)
        linePaint.strokeWidth = coreWidth + unit * 0.011f
        canvas.drawPath(path, linePaint)
        linePaint.color = lighten(accent, 0.4f)
        linePaint.strokeWidth = coreWidth
        canvas.drawPath(path, linePaint)
    }

    private fun drawListeningRipples(canvas: Canvas, accent: Int, unit: Float, now: Long) {
        if (phase != RobotPhase.LISTENING) return
        val progress = (now % 1_350L) / 1_350f
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = unit * 0.004f
        repeat(2) { index ->
            val localProgress = (progress + index * 0.5f) % 1f
            val radius = unit * (0.038f + 0.064f * localProgress)
            linePaint.color = withAlpha(accent, (90f * (1f - localProgress)).toInt())
            canvas.drawCircle(faceX(0.088f), faceY(0.56f), radius, linePaint)
            canvas.drawCircle(faceX(0.912f), faceY(0.56f), radius, linePaint)
        }
    }

    private fun drawHardwareLights(canvas: Canvas, accent: Int, unit: Float, now: Long) {
        val pulse = 0.72f + 0.28f * sin(now / 480.0).toFloat()
        fillPaint.color = withAlpha(accent, (34f * pulse).toInt())
        canvas.drawCircle(faceX(0.5f), faceY(0.052f), unit * 0.038f, fillPaint)
        fillPaint.color = withAlpha(lighten(accent, 0.45f), (210f * pulse).toInt())
        canvas.drawCircle(faceX(0.5f), faceY(0.052f), unit * 0.009f, fillPaint)

        featureBounds.set(
            faceX(0.468f),
            faceY(0.944f),
            faceX(0.532f),
            faceY(0.965f),
        )
        fillPaint.color = withAlpha(accent, 52)
        canvas.drawRoundRect(
            expanded(featureBounds, unit * 0.011f),
            unit * 0.018f,
            unit * 0.018f,
            fillPaint,
        )
        fillPaint.color = lighten(accent, 0.28f)
        canvas.drawRoundRect(featureBounds, unit * 0.012f, unit * 0.012f, fillPaint)
    }

    private fun blinkScale(now: Long): Float {
        if (phase == RobotPhase.LISTENING) return 1f
        val position = (now % 4_900L).toFloat()
        return when {
            position < 90f -> 1f - 0.91f * (position / 90f)
            position < 180f -> 0.09f + 0.91f * ((position - 90f) / 90f)
            else -> 1f
        }
    }

    private fun animatedAccent(now: Long): Int {
        val progress = ((now - phaseChangedAt) / PHASE_TRANSITION_MS).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        return blend(phaseAccent(previousPhase), phaseAccent(phase), eased)
    }

    private fun ensureShaders(accent: Int, unit: Float) {
        if (
            cachedShaderAccent == accent &&
            cachedShaderWidth == width &&
            cachedShaderHeight == height
        ) {
            return
        }
        cachedShaderAccent = accent
        cachedShaderWidth = width
        cachedShaderHeight = height
        val stableTop = (height - unit) / 2f
        val stableLeft = (width - unit) / 2f
        eyeShader = LinearGradient(
            stableLeft + unit * 0.31f,
            stableTop + unit * 0.43f,
            stableLeft + unit * 0.69f,
            stableTop + unit * 0.55f,
            intArrayOf(Color.WHITE, lighten(accent, 0.34f), accent),
            floatArrayOf(0f, 0.43f, 1f),
            Shader.TileMode.CLAMP,
        )
        glassShader = RadialGradient(
            stableLeft + unit * 0.5f,
            stableTop + unit * 0.54f,
            unit * 0.32f,
            withAlpha(accent, 24),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    private fun phaseAccent(value: RobotPhase): Int = when (value) {
        RobotPhase.LISTENING -> LISTENING_ACCENT
        RobotPhase.THINKING -> THINKING_ACCENT
        RobotPhase.SPEAKING -> SPEAKING_ACCENT
        RobotPhase.ERROR -> ERROR_ACCENT
        else -> IDLE_ACCENT
    }

    private fun faceX(fraction: Float): Float = faceBounds.left + faceBounds.width() * fraction

    private fun faceY(fraction: Float): Float = faceBounds.top + faceBounds.height() * fraction

    private fun expanded(source: RectF, amount: Float): RectF = glowBounds.apply {
        set(
            source.left - amount,
            source.top - amount,
            source.right + amount,
            source.bottom + amount,
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun lighten(color: Int, amount: Float): Int = blend(color, Color.WHITE, amount)

    private fun blend(from: Int, to: Int, progress: Float): Int {
        val clamped = progress.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * clamped).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * clamped).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped).toInt(),
        )
    }
}
