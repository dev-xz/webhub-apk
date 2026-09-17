package net.marscore.webhub.ui.hub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

/** Square crop viewport with constrained pan and pinch-to-zoom. */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val imageMatrix = Matrix()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            imageMatrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
            constrainMatrix()
            invalidate()
            return true
        }
    })
    private var bitmap: Bitmap? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width)
    }

    fun setBitmap(value: Bitmap) {
        bitmap = value
        if (width > 0) resetMatrix() else post { resetMatrix() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, imageMatrix, imagePaint) }
        val inset = framePaint.strokeWidth / 2f
        canvas.drawRect(inset, inset, width - inset, height - inset, framePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                imageMatrix.postTranslate(event.x - lastX, event.y - lastY)
                lastX = event.x
                lastY = event.y
                constrainMatrix()
                invalidate()
            }
        }
        return true
    }

    fun crop(outputSize: Int = 512): Bitmap {
        check(width > 0 && height > 0) { "Crop view is not laid out" }
        val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.scale(outputSize.toFloat() / width, outputSize.toFloat() / height)
        bitmap?.let { canvas.drawBitmap(it, imageMatrix, imagePaint) }
        return output
    }

    private fun resetMatrix() {
        val source = bitmap ?: return
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val dx = (width - source.width * scale) / 2f
        val dy = (height - source.height * scale) / 2f
        imageMatrix.setScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        invalidate()
    }

    private fun constrainMatrix() {
        val source = bitmap ?: return
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        imageMatrix.mapRect(bounds)
        if (bounds.width() < width || bounds.height() < height) {
            val factor = max(width / bounds.width(), height / bounds.height())
            imageMatrix.postScale(factor, factor, width / 2f, height / 2f)
            bounds.set(0f, 0f, source.width.toFloat(), source.height.toFloat())
            imageMatrix.mapRect(bounds)
        }
        val dx = when {
            bounds.left > 0f -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.top > 0f -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        imageMatrix.postTranslate(dx, dy)
    }
}
