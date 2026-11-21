package bit.himitsu.viewpager

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

class FidgetSpinTransformer : ViewPager2.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        page.translationX = -position * page.width
        if (abs(position) < 0.5) {
            page.visibility = View.VISIBLE
            page.scaleX = 1 - abs(position)
            page.scaleY = 1 - abs(position)
        } else if (abs(position) > 0.5) {
            page.visibility = View.GONE
        }
        val a = abs(position)
        val spin = a * a * a * a * a * a * a
        when {
            position < -1f -> page.alpha = 0f
            position <= 0f -> {
                page.alpha = 1f
                page.rotation = 36000 * spin
            }
            position <= 1f -> {
                page.alpha = 1f
                page.rotation = -36000 * spin
            }
            else -> page.alpha = 0f
        }
    }
}
