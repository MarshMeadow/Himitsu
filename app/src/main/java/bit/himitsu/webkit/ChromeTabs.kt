package bit.himitsu.webkit

import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import ani.himitsu.media.cereal.Media
import bit.himitsu.ShellActivity

private fun isAndroidTV(context: Context): Boolean =
    (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
        ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

fun ChromeIntegration.openStreamTab(
    context: Context,
    link: String?,
    media: Media? = null,
    title: String? = null
) {
    if (link == null) return
    if (isAndroidTV(context)) {
        openStreamDialog(context, link)
        return
    }
    try {
        openCustomTab(context, link)
    } catch (_: ActivityNotFoundException) {
        openStreamDialog(context, link)
    }
}

fun ChromeIntegration.openPluginTab(context: Context, link: String?) {
    if (link == null) return
    if (isAndroidTV(context)) {
        context.startActivity(
            Intent(context, ShellActivity::class.java).putExtra("pluginUrl", link)
        )
        return
    }
    try {
        openCustomTab(context, link)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(context, ShellActivity::class.java).putExtra("pluginUrl", link)
        )
    }
}
