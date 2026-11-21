package bit.himitsu.net

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ani.himitsu.currContext
import bit.himitsu.os.Version

object Bandwidth {

    fun downstream(): Int {
        if (Version.isLowerThan(23)) return 0
        val connectivityManager =
            currContext().getSystemService("connectivity") as ConnectivityManager
        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return (caps?.linkDownstreamBandwidthKbps ?: 1000) / 1000
    }

    fun getNetworkSpeed(): String =
        if (downstream() > 0) "${downstream()} Mbps" else "?? Mbps"
}
