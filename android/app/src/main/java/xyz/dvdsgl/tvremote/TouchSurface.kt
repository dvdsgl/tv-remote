package xyz.dvdsgl.tvremote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * The big touch area. Drags stream to the TV as live touches (press, hold, release in a
 * 0..1000 space), the same way the iPhone Remote's touch surface works. A quick tap with
 * little movement is a click (select). A soft glow follows the finger.
 */
class TouchSurface(context: Context) : View(context) {
    interface Listener {
        fun onTouch(x: Int, y: Int, mode: Int)
        fun onTap()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val radius = 32 * density
    /** Light (free) or dark theme: the surface's color and its glow. */
    var dark = false
        set(v) { field = v; fill.color = Color.parseColor(if (v) "#1C1C1E" else "#F2F2F7"); invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2F2F7") }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fx = 0f
    private var fy = 0f
    private var glowAlpha = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var lastSent = 0L
    private var fade: ValueAnimator? = null

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (glowAlpha > 0f) {
            val r = 90 * density
            glow.shader = RadialGradient(fx, fy, r, if (dark) Color.argb((46 * glowAlpha).toInt(), 255, 255, 255) else Color.argb((22 * glowAlpha).toInt(), 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawCircle(fx, fy, r, glow)
            canvas.restore()
        }
    }

    private fun sx(x: Float) = (x / width * 1000).toInt().coerceIn(0, 1000)
    private fun sy(y: Float) = (y / height * 1000).toInt().coerceIn(0, 1000)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        fx = e.x; fy = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                fade?.cancel()
                glowAlpha = 1f
                downX = e.x; downY = e.y
                downAt = SystemClock.uptimeMillis()
                moved = false
                listener?.onTouch(sx(e.x), sy(e.y), 1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > 10 * density) moved = true
                val now = SystemClock.uptimeMillis()
                // About 60 updates a second is plenty and keeps the link quiet.
                if (now - lastSent >= 16) {
                    lastSent = now
                    listener?.onTouch(sx(e.x), sy(e.y), 3)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                listener?.onTouch(sx(e.x), sy(e.y), 4)
                val quick = SystemClock.uptimeMillis() - downAt < 350
                if (e.actionMasked == MotionEvent.ACTION_UP && !moved && quick) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    listener?.onTap()
                }
                fade = ValueAnimator.ofFloat(glowAlpha, 0f).apply {
                    duration = 280
                    addUpdateListener { glowAlpha = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        invalidate()
        return true
    }
}
