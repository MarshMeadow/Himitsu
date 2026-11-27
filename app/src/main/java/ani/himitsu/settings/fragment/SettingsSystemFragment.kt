package ani.himitsu.settings.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.himitsu.BuildConfig
import ani.himitsu.Himitsu
import ani.himitsu.R
import ani.himitsu.Refresh
import ani.himitsu.databinding.ActivitySettingsSystemBinding
import ani.himitsu.restart
import ani.himitsu.savePrefsToDownloads
import ani.himitsu.settings.Settings
import ani.himitsu.settings.SettingsActivity
import ani.himitsu.settings.SettingsAdapter
import ani.himitsu.settings.ViewType
import ani.himitsu.settings.data.AutoBackupWorker
import ani.himitsu.settings.saving.PrefManager
import ani.himitsu.settings.saving.PrefName
import ani.himitsu.settings.saving.internal.Location
import ani.himitsu.settings.saving.internal.PreferenceKeystore
import ani.himitsu.settings.saving.internal.PreferencePackager
import ani.himitsu.snackString
import ani.himitsu.toast
import ani.himitsu.databinding.DialogSetPasswordBinding
import ani.himitsu.others.calc.BiometricPromptUtils
import ani.himitsu.util.Logger
import ani.himitsu.util.customAlertDialog
import androidx.core.view.isVisible
import java.util.UUID
import ani.himitsu.util.StoragePermissions
import bit.himitsu.onCompletedAction
import bit.himitsu.update.MatagiUpdater
import com.bumptech.glide.Glide
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsSystemFragment : Fragment() {
    private lateinit var binding: ActivitySettingsSystemBinding


    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val settings = requireActivity() as SettingsActivity
            val component = ComponentName(settings.packageName, settings::class.qualifiedName!!)
            if (uri != null) {
                try {
                    val jsonString = settings.contentResolver.openInputStream(uri)?.readBytes()
                        ?: throw Exception("Error reading file")
                    val name = DocumentFile.fromSingleUri(settings, uri)?.name ?: "settings"
                    //.sani is encrypted, .ani is not
                    if (name.endsWith(".sani")
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        passwordAlertDialog(false) { password ->
                            if (password != null) {
                                val salt = jsonString.copyOfRange(0, 16)
                                val encrypted = jsonString.copyOfRange(16, jsonString.size)
                                val decryptedJson = try {
                                    PreferenceKeystore.decryptWithPassword(
                                        password,
                                        encrypted,
                                        salt
                                    )
                                } catch (e: Exception) {
                                    toast(getString(R.string.incorrect_password))
                                    return@passwordAlertDialog
                                }
                                if (PreferencePackager.unpack(decryptedJson))
                                    settings.restart(component)
                            } else {
                                toast(getString(R.string.password_cannot_be_empty))
                            }
                        }
                    } else if (name.endsWith(".ani")) {
                        val decryptedJson = jsonString.toString(Charsets.UTF_8)
                        if (PreferencePackager.unpack(decryptedJson))
                            settings.restart(component)
                    } else {
                        toast(getString(R.string.unknown_file_type))
                    }
                } catch (e: Exception) {
                    Logger.log(e)
                    toast(getString(R.string.error_importing_settings))
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ActivitySettingsSystemBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val settings = requireActivity() as SettingsActivity
        val component = ComponentName(settings.packageName, settings::class.qualifiedName!!)

        val intervals = arrayOf(
            getString(R.string.auto_backup_interval_off),
            getString(R.string.auto_backup_interval_6h),
            getString(R.string.auto_backup_interval_12h),
            getString(R.string.auto_backup_interval_24h),
            getString(R.string.auto_backup_interval_48h),
            getString(R.string.auto_backup_interval_weekly)
        )
        val copyCounts = arrayOf("1", "2", "3", "5")

        binding.apply {
            systemSettingsBack.setOnClickListener {
                settings.backToMenu()
            }

            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = ViewType.BUTTON,
                        name = getString(R.string.backup_restore),
                        desc = getString(R.string.backup_restore_desc),
                        icon = R.drawable.backup_restore,
                        onClick = {
                            if (!StoragePermissions.downloadsPermission(settings as AppCompatActivity))
                                return@Settings
                            val selectedArray = mutableListOf(false)
                            var filteredLocations = Location.entries.filter { it.exportable }
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                                filteredLocations = filteredLocations.filter {
                                    it.location != Location.Protected.location
                                }
                            }
                            selectedArray.addAll(List(filteredLocations.size - 1) { false })
                            val dialog =
                                AlertDialog.Builder(settings, R.style.MyPopup)
                                    .setTitle(R.string.backup_restore)
                                    .setMultiChoiceItems(
                                        filteredLocations.map { it.name }.toTypedArray(),
                                        selectedArray.toBooleanArray()
                                    ) { _, which, isChecked ->
                                        selectedArray[which] = isChecked
                                    }
                                    .setPositiveButton(R.string.button_restore) { dialog, _ ->
                                        try {
                                            openDocumentLauncher.launch(arrayOf("*/*"))
                                        } catch (e: ActivityNotFoundException) {
                                            toast(R.string.file_manager_not_found)
                                        }
                                        dialog.dismiss()
                                    }
                                    .setNegativeButton(R.string.button_backup) { dialog, _ ->
                                        if (!selectedArray.contains(true)) {
                                            toast(R.string.no_location_selected)
                                            return@setNegativeButton
                                        }
                                        dialog.dismiss()
                                        val selected =
                                            filteredLocations.filterIndexed { index, _ -> selectedArray[index] }
                                        if (selected.contains(Location.Protected)) {
                                            passwordAlertDialog(true) { password ->
                                                if (password != null) {
                                                    savePrefsToDownloads(
                                                        "HimitsuXSettings",
                                                        PrefManager.exportAllPrefs(selected),
                                                        settings,
                                                        password
                                                    )
                                                } else {
                                                    toast(R.string.password_cannot_be_empty)
                                                }
                                            }
                                        } else {
                                            savePrefsToDownloads(
                                                "HimitsuXSettings",
                                                PrefManager.exportAllPrefs(selected),
                                                settings,
                                                null
                                            )
                                        }
                                    }
                                    .setNeutralButton(R.string.cancel) { dialog, _ ->
                                        dialog.dismiss()
                                    }
                                    .create()
                            dialog.window?.setDimAmount(0.8f)
                            dialog.show()
                        },
                        isDialog = true
                    ),
                    Settings(
                        type = ViewType.BUTTON,
                        name = getString(R.string.auto_backup_interval),
                        desc = intervals.getOrElse(
                            PrefManager.getVal(PrefName.AutoBackupInterval)
                        ) { intervals[0] },
                        icon = R.drawable.ic_round_auto_awesome_24,
                        onClick = {
                            val current = PrefManager.getVal<Int>(PrefName.AutoBackupInterval)
                            AlertDialog.Builder(settings, R.style.MyPopup)
                                .setTitle(R.string.auto_backup_interval)
                                .setSingleChoiceItems(intervals, current) { dialog, which ->
                                    PrefManager.setVal(PrefName.AutoBackupInterval, which)
                                    AutoBackupWorker.schedule(settings, which)
                                    binding.settingsRecyclerView.adapter?.notifyDataSetChanged()
                                    dialog.dismiss()
                                }
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        },
                        isDialog = true
                    ),
                    Settings(
                        type = ViewType.BUTTON,
                        name = getString(R.string.auto_backup_max_copies),
                        desc = "${PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies)}",
                        icon = R.drawable.backup_restore,
                        onClick = {
                            val current = PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies)
                            val currentIndex = copyCounts.indexOf(current.toString()).coerceAtLeast(0)
                            AlertDialog.Builder(settings, R.style.MyPopup)
                                .setTitle(R.string.auto_backup_max_copies)
                                .setSingleChoiceItems(copyCounts, currentIndex) { dialog, which ->
                                    val copies = copyCounts[which].toIntOrNull() ?: 3
                                    PrefManager.setVal(PrefName.AutoBackupMaxCopies, copies)
                                    binding.settingsRecyclerView.adapter?.notifyDataSetChanged()
                                    dialog.dismiss()
                                }
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        },
                        isDialog = true
                    ),
                    Settings(
                        type = ViewType.BUTTON,
                        name = getString(R.string.auto_backup),
                        desc = run {
                            val timestamp = PrefManager.getVal<Long>(PrefName.LastAutoBackupTimestamp)
                            if (timestamp > 0) {
                                val formattedDate = SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm",
                                    Locale.getDefault()
                                ).format(Date(timestamp))
                                getString(R.string.last_auto_backup, formattedDate)
                            } else {
                                getString(R.string.last_auto_backup_never)
                            }
                        },
                        icon = R.drawable.backup_restore,
                        onClick = {}
                    ),
                    Settings(
                        type = ViewType.SWITCH,
                        name = getString(R.string.check_app_updates),
                        desc = getString(R.string.check_app_updates_desc),
                        icon = R.drawable.ic_round_new_releases_24,
                        isChecked = PrefManager.getVal(PrefName.CheckUpdate),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.CheckUpdate, isChecked)
                            if (!isChecked) {
                                snackString(getString(R.string.long_click_to_check_update))
                            }
                        },
                        onLongClick = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                MatagiUpdater.check(settings, true)
                            }
                        },
                        isVisible = !BuildConfig.FLAVOR.contains("fdroid")
                    ),
                    Settings(
                        type = ViewType.SWITCH,
                        name = getString(R.string.biometric_title),
                        desc = getString(R.string.biometric_summary),
                        icon = R.drawable.ic_fingerprint_24,
                        pref = PrefName.SecureLock,
                        isVisible = canUseBiometrics()
                    ),
                    Settings(
                        type = ViewType.BUTTON,
                        name = getString(R.string.app_lock),
                        desc = getString(R.string.app_lock_desc),
                        icon = R.drawable.ic_round_lock_24,
                        onClick = { showAppLockDialog() },
                        isDialog = true
                    ),
                    Settings(
                        type = ViewType.SWITCH,
                        name = getString(R.string.add_shortcuts),
                        desc = getString(R.string.add_shortcuts_desc),
                        icon = R.drawable.ic_app_shortcut_24,
                        isChecked = PrefManager.getVal(PrefName.UseShortcuts),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.UseShortcuts, isChecked)
                            settings.restart(component)
                        }
                    ),
                    Settings(
                        type = ViewType.BUTTON,
                        name = getString(R.string.clear_glide),
                        desc = getString(R.string.clear_glide_desc),
                        icon = R.drawable.ic_round_coronavirus_24,
                        onClick = {
                            CoroutineScope(Dispatchers.IO).launch {
                                Glide.get(Himitsu.instance).clearDiskCache()
                            }
                            Glide.get(Himitsu.instance).clearMemory()
                            Refresh.all()
                        }
                    ),
                    Settings(
                        type = ViewType.SWITCH,
                        name = getString(R.string.share_username_in_logs),
                        desc = getString(R.string.share_username_in_logs_desc),
                        icon = R.drawable.ic_round_search_24,
                        pref = PrefName.SharedUserID,
                        isVisible = !BuildConfig.FLAVOR.contains("fdroid")
                    ),
                    Settings(
                        type = ViewType.SWITCH,
                        name = getString(R.string.verbose_logging),
                        desc = getString(R.string.verbose_logging_desc),
                        icon = R.drawable.ic_round_search_24,
                        pref = PrefName.VerboseLogging
                    ),
                    Settings(
                        type = ViewType.SWITCH,
                        name = getString(R.string.log_to_file),
                        desc = getString(R.string.logging_warning),
                        icon = R.drawable.ic_round_edit_note_24,
                        isChecked = PrefManager.getVal(PrefName.LogToFile),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.LogToFile, isChecked)
                            Logger.clearLog()
                            if (isChecked) Logger.init(settings)
                        }

                    ),
                    Settings(
                        type = ViewType.BUTTON,
                        name = "",
                        desc = getString(R.string.share_log),
                        icon = R.drawable.ic_round_share_24,
                        onClick = {
                            Logger.shareLog(settings)
                        }
                    ),
                    Settings(
                        type = ViewType.SWITCH,
                        name = getString(R.string.disable_debug),
                        desc = getString(R.string.rogue_warning),
                        icon = R.drawable.ic_bug_report_24,
                        isChecked = PrefManager.getVal(PrefName.Lightspeed),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.Lightspeed, isChecked)
                            Logger.clearLog()
                            settings.restart(component)
                        }
                    )
                )
            )
            settingsRecyclerView.apply {
                layoutManager = LinearLayoutManager(settings, LinearLayoutManager.VERTICAL, false)
                setHasFixedSize(true)
            }
        }
    }

    private fun showAppLockDialog() {
        val activity = requireActivity() as AppCompatActivity
        activity.customAlertDialog().apply {
            val view = DialogSetPasswordBinding.inflate(activity.layoutInflater)
            setTitle(R.string.app_lock)
            setCustomView(view.root)
            setPosButton(R.string.ok) {
                if (view.forgotPasswordCheckbox.isChecked) {
                    PrefManager.setVal(PrefName.OverridePassword, true)
                }
                val password = view.passwordInput.text.toString()
                val confirmPassword = view.confirmPasswordInput.text.toString()
                if (password == confirmPassword && password.isNotEmpty()) {
                    PrefManager.setVal(PrefName.AppPassword, password)
                    if (view.biometricCheckbox.isChecked) {
                        val canBiometricPrompt =
                            BiometricManager.from(activity)
                                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                                BiometricManager.BIOMETRIC_SUCCESS

                        if (canBiometricPrompt) {
                            val biometricPrompt =
                                BiometricPromptUtils.createBiometricPrompt(activity) { _ ->
                                    val token = UUID.randomUUID().toString()
                                    PrefManager.setVal(PrefName.BiometricToken, token)
                                    toast(R.string.success)
                                }
                            val promptInfo =
                                BiometricPromptUtils.createPromptInfo(activity)
                            biometricPrompt.authenticate(promptInfo)
                        }
                    } else {
                        PrefManager.setVal(PrefName.BiometricToken, "")
                        toast(R.string.success)
                    }
                } else {
                    toast(R.string.password_mismatch)
                }
            }
            setNegButton(R.string.cancel)
            setNeutralButton(R.string.remove) {
                PrefManager.setVal(PrefName.AppPassword, "")
                PrefManager.setVal(PrefName.BiometricToken, "")
                PrefManager.setVal(PrefName.OverridePassword, false)
                toast(R.string.success)
            }
            attach {
                view.passwordInput.requestFocus()
                val canAuthenticate =
                    BiometricManager.from(activity).canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
                    ) == BiometricManager.BIOMETRIC_SUCCESS
                view.biometricCheckbox.isVisible = canAuthenticate
                view.biometricCheckbox.isChecked =
                    PrefManager.getVal(PrefName.BiometricToken, "").isNotEmpty()
                view.forgotPasswordCheckbox.isChecked =
                    PrefManager.getVal(PrefName.OverridePassword)
            }
            show()
        }
    }

    private fun canUseBiometrics() : Boolean {
        val biometricManager = BiometricManager.from(requireActivity())
        return when (biometricManager.canAuthenticate(BIOMETRIC_STRONG
                or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> false
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> false
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> true
            else -> { false }
        }
    }

    private fun passwordAlertDialog(isExporting: Boolean, callback: (CharArray?) -> Unit) {

        // Inflate the dialog layout
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_user_agent, null)
        val box = dialogView.findViewById<TextInputEditText>(R.id.userAgentTextBox)
        box?.hint = getString(R.string.password)
        box?.setSingleLine()

        val dialog = AlertDialog.Builder(context, R.style.MyPopup)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                box?.text?.clear()
                dialog.dismiss()
                callback(null)
            }
            .create()

        fun handleOkAction() {
            val editText = dialog.findViewById<TextInputEditText>(R.id.userAgentTextBox)
            if (editText?.text?.isNotBlank() == true) {
                val password = editText.text?.toString()?.trim()?.toCharArray()
                dialog.dismiss()
                callback(password)
            } else {
                toast(getString(R.string.password_cannot_be_empty))
            }
        }
        box?.setOnEditorActionListener(onCompletedAction { handleOkAction() })
        val subtitleTextView = dialogView.findViewById<TextView>(R.id.subtitle)
        subtitleTextView?.visibility = View.VISIBLE
        if (!isExporting)
            subtitleTextView?.text = getString(R.string.enter_password_to_decrypt_file)


        dialog.window?.setDimAmount(0.8f)
        dialog.show()

        // Override the positive button here
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            handleOkAction()
        }
    }
}