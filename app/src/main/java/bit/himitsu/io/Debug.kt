package bit.himitsu.io

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import ani.himitsu.BuildConfig
import ani.himitsu.Himitsu
import ani.himitsu.R
import java.io.File
import java.io.FileOutputStream

object Debug {

    private const val ISSUE_URL =
        "https://github.com/Nekomo-App/HimitsuX/issues/new?labels=logcat&title=[Bug]%3A+"

    private val manufacturer by lazy {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val manufacturer = clazz.getMethod("get", String::class.java)
                .invoke(clazz, "ro.product.manufacturer") as String
            manufacturer.ifEmpty { "Unknown" }
        } catch (ignored: Exception) {
            Build.MANUFACTURER
        }
    }

    private val separator = System.getProperty("line.separator") ?: "\n"

    private fun deviceProfile(): StringBuilder {
        val builder = StringBuilder(separator)
        builder.append("HimitsuX ${BuildConfig.VERSION_NAME}")
        builder.append(separator)
        builder.append(manufacturer)
        builder.append(' ')
        var codeName = "UNKNOWN"
        for (field in Build.VERSION_CODES::class.java.fields) {
            codeName = try {
                if (field.getInt(Build.VERSION_CODES::class.java) == Build.VERSION.SDK_INT)
                    field.name else codeName
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
                "UNKNOWN"
            }
        }
        builder.append(codeName)
        builder.append(" (")
        builder.append(Build.VERSION.RELEASE)
        builder.append(") - ")
        builder.append(Memory.getDeviceRAM())
        builder.append(" RAM")
        builder.append("[${Build.SUPPORTED_ABIS.joinToString()} ]")
        return builder
    }

    @JvmStatic
    fun clipException(context: Context, exception: String) =
        processException(context, exception, copyToClip = true)

    @JvmStatic
    fun processException(
        context: Context,
        exception: String,
        copyToClip: Boolean = false,
        saveToFile: Boolean = false,
        submit: Boolean = false
    ) {
        val log = deviceProfile().apply {
            append(separator)
            append(separator)
            append(exception)
        }
        val label = context.getString(R.string.git_issue_title, BuildConfig.VERSION_NAME)
        if (copyToClip) setClipboard(label, log.toString())
        if (saveToFile) writeLogcat(label, log.toString())
        if (submit) submitLogcat(context, log.toString())
    }

    @JvmStatic
    fun saveException(context: Context, exception: String) =
        processException(context, exception, saveToFile = true)

    private fun setClipboard(label: String, text: String) {
        val clipboard = Himitsu.currentContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun setEmailParams(action: String, subject: String, body: String): Intent {
        return Intent(action).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("tagmo.git@gmail.com"))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
    }

    private fun submitLogcat(context: Context, log: String) {
        val label = context.getString(R.string.git_issue_title, BuildConfig.VERSION_NAME)
        try {
            context.startActivity(
                Intent.createChooser(
                    setEmailParams(Intent.ACTION_SENDTO, label, log), label
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent.createChooser(
                        setEmailParams(Intent.ACTION_SEND, label, log), label
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: ActivityNotFoundException) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUE_URL)))
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun writeLogcat(filename: String, text: String) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        FileOutputStream(File(dir, "$filename.txt")).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
    }
}
