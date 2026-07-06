package com.sample.edgedetection.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import org.opencv.core.Point
import kotlin.math.abs

class CardGuideView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attributes: AttributeSet) : super(context, attributes)
    constructor(context: Context, attributes: AttributeSet, defTheme: Int) : super(
        context,
        attributes,
        defTheme
    )

    companion object {
        // ID-1 카드 규격(85.6mm x 54mm) 비율
        private const val CARD_RATIO = 85.6f / 54f
        private const val WIDTH_RATIO = 0.85f
        private const val CENTER_Y_RATIO = 0.42f
        private const val CORNER_TOLERANCE_RATIO = 0.06f
        private const val MIN_AREA_RATIO = 0.5
    }

    private val dimPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
    }
    private val clearPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.WHITE
    }

    private val cornerRadius = 12f * resources.displayMetrics.density
    private var detected = false

    init {
        // PorterDuff CLEAR로 컷아웃을 뚫기 위해 오프스크린 레이어 사용
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun guideRect(): RectF {
        val guideWidth = measuredWidth * WIDTH_RATIO
        val guideHeight = guideWidth / CARD_RATIO
        val centerX = measuredWidth / 2f
        val centerY = measuredHeight * CENTER_Y_RATIO
        return RectF(
            centerX - guideWidth / 2f,
            centerY - guideHeight / 2f,
            centerX + guideWidth / 2f,
            centerY + guideHeight / 2f
        )
    }

    fun setDetected(value: Boolean) {
        if (detected != value) {
            detected = value
            invalidate()
        }
    }

    // 뷰 좌표계의 꼭짓점들이 가이드 안에 충분한 크기로 들어왔는지 판정
    fun contains(points: List<Point>): Boolean {
        if (points.size != 4 || measuredWidth == 0 || measuredHeight == 0) {
            return false
        }

        val rect = guideRect()
        val tolerance = rect.width() * CORNER_TOLERANCE_RATIO
        val expanded = RectF(
            rect.left - tolerance,
            rect.top - tolerance,
            rect.right + tolerance,
            rect.bottom + tolerance
        )
        if (points.any { it.x < expanded.left || it.x > expanded.right || it.y < expanded.top || it.y > expanded.bottom }) {
            return false
        }

        // 신발끈 공식으로 면적 계산 — 가이드 대비 너무 작으면 미인식 처리
        var area = 0.0
        for (i in points.indices) {
            val next = points[(i + 1) % points.size]
            area += points[i].x * next.y - next.x * points[i].y
        }
        return abs(area) / 2.0 >= rect.width() * rect.height() * MIN_AREA_RATIO
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (canvas == null) {
            return
        }

        val rect = guideRect()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, clearPaint)

        borderPaint.color = if (detected) Color.rgb(76, 217, 100) else Color.WHITE
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
    }
}
