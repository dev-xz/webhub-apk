package net.marscore.webhub.ui.hub

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import net.marscore.webhub.R

/** Keeps the onboarding card readable on wide screens while filling narrow screens. */
class MaxWidthFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val maxWidth = resources.getDimensionPixelSize(R.dimen.onboarding_max_width)
        if (measuredWidth > maxWidth) {
            super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.EXACTLY),
                heightMeasureSpec
            )
        }
    }
}
