package com.nexo.browser

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import kotlin.math.floor

/** Shared geometry; adapt to the available window, including accessibility font scaling. */
object UiLayout {
    const val MIN_TOUCH_DP = 48
    const val CONTENT_MAX_DP = 720
    const val SHEET_MAX_DP = 640

    fun actionColumns(widthDp: Float, fontScale: Float, max: Int = 4): Int =
        floor(widthDp / (96f * fontScale.coerceIn(1f, 2f))).toInt().coerceIn(1, max)
}

/** A readable column centered by its parent ScrollView, even in tablet / desktop windows. */
class ContentColumn @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = (UiLayout.CONTENT_MAX_DP * resources.displayMetrics.density).toInt()
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        val width = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) limit else minOf(size, limit)
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}
