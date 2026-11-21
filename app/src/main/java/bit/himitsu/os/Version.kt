package bit.himitsu.os

import android.os.Build
import ani.himitsu.BuildConfig

object Version {

    @JvmStatic
    val isHimitsu: Boolean
        get() = BuildConfig.BUILD_TYPE.contentEquals("matagi")

    @JvmStatic
    val isLollipop: Boolean
        get() = true

    @JvmStatic
    val isLollipopMR: Boolean
        get() = Build.VERSION.SDK_INT >= 22

    @JvmStatic
    val isMarshmallow: Boolean
        get() = Build.VERSION.SDK_INT >= 23

    @JvmStatic
    val isNougat: Boolean
        get() = Build.VERSION.SDK_INT >= 24

    @JvmStatic
    val isOreo: Boolean
        get() = Build.VERSION.SDK_INT >= 26

    @JvmStatic
    val isPie: Boolean
        get() = Build.VERSION.SDK_INT >= 28

    @JvmStatic
    val isQuinceTart: Boolean
        get() = Build.VERSION.SDK_INT >= 29

    @JvmStatic
    val isSnowCone: Boolean
        get() = Build.VERSION.SDK_INT >= 31

    @JvmStatic
    val isTiramisu: Boolean
        get() = Build.VERSION.SDK_INT >= 33

    @JvmStatic
    val isUpsideDownCake: Boolean
        get() = Build.VERSION.SDK_INT >= 34

    @JvmStatic
    fun isLowerThan(versionCode: Int): Boolean = Build.VERSION.SDK_INT < versionCode
}
