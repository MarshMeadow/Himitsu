package eu.kanade.tachiyomi.network

import android.content.Context
import android.os.Build
import ani.himitsu.Mapper
import ani.himitsu.settings.saving.PrefManager
import ani.himitsu.settings.saving.PrefName
import com.lagradost.nicehttp.Requests
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.IgnoreGzipInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit

class NetworkHelper(
    context: Context
) {

    init {
        setupSocks5Proxy()
    }

    private fun setupSocks5Proxy() {
        val proxyEnabled = PrefManager.getVal<Boolean>(PrefName.EnableSocks5Proxy)
        if (proxyEnabled) {
            val proxyHost = PrefManager.getVal<String>(PrefName.Socks5ProxyHost)
            val proxyPort = PrefManager.getVal<String>(PrefName.Socks5ProxyPort)

            if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
                System.setProperty("socksProxyHost", proxyHost)
                System.setProperty("socksProxyPort", proxyPort)
            }

            if (PrefManager.getVal<Boolean>(PrefName.ProxyAuthEnabled)) {
                val proxyUsername = PrefManager.getVal<String>(PrefName.Socks5ProxyUsername)
                val proxyPassword = PrefManager.getVal<String>(PrefName.Socks5ProxyPassword)

                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                        return java.net.PasswordAuthentication(
                            proxyUsername,
                            proxyPassword.toCharArray()
                        )
                    }
                })
            }
        } else {
            System.clearProperty("socksProxyHost")
            System.clearProperty("socksProxyPort")
            java.net.Authenticator.setDefault(null)
        }
    }

    val cookieJar = AndroidCookieJar()

    val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .cache(
                Cache(
                    directory = File(context.externalCacheDir ?: context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
            .addInterceptor(IgnoreGzipInterceptor())
            .addInterceptor(BrotliInterceptor)

        if (PrefManager.getVal(PrefName.VerboseLogging)) {
            val httpLoggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
        }

        builder.addInterceptor(
            CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider),
        )

        when (PrefManager.getVal<Int>(PrefName.DohProvider)) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
            PREF_DOH_LIBREDNS -> builder.dohLibreDNS()
        }

        builder.build()
    }

    val downloadClient = client.newBuilder().callTimeout(20, TimeUnit.MINUTES).build()

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient = client

    val requestClient = Requests(
        client,
        mapOf(
            "User-Agent" to
                    defaultUserAgentProvider()
                        .format(Build.VERSION.RELEASE, Build.MODEL)
        ),
        defaultCacheTime = 6,
        defaultCacheTimeUnit = TimeUnit.HOURS,
        responseParser = Mapper
    )

    companion object {
        fun defaultUserAgentProvider() = PrefManager.getVal<String>(PrefName.DefaultUserAgent)
    }
}
