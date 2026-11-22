package ani.himitsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.himitsu.R
import ani.himitsu.connections.anilist.AniList
import ani.himitsu.connections.anilist.AniList.activityMergeTimeMap
import ani.himitsu.connections.anilist.AniList.rowOrderMap
import ani.himitsu.connections.anilist.AniList.scoreFormats
import ani.himitsu.connections.anilist.AniList.staffNameLang
import ani.himitsu.connections.anilist.AniList.titleLang
import ani.himitsu.connections.anilist.AniListMutations
import ani.himitsu.databinding.ActivitySettingsAnilistBinding
import ani.himitsu.initActivity
import ani.himitsu.navBarHeight
import ani.himitsu.snackString
import ani.himitsu.statusBarHeight
import ani.himitsu.themes.ThemeManager
import ani.himitsu.toast
import ani.himitsu.util.MarkdownCreatorActivity
import ani.himitsu.util.customAlertDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class AnilistSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAnilistBinding
    private lateinit var anilistMutations: AniListMutations

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsAnilistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        anilistMutations = AniListMutations()

        binding.apply {
            settingsAnilistLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            binding.anilistSettingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }

            val titleFormat = when (AniList.titleLanguage) {
                "ROMAJI" -> 1
                "NATIVE" -> 2
                else -> 0
            }
            settingsAnilistTitleLanguage.setText(titleLang[titleFormat])
            settingsAnilistTitleLanguage.setAdapter(
                ArrayAdapter(context, R.layout.item_dropdown, titleLang)
            )
            settingsAnilistTitleLanguage.setOnItemClickListener { _, _, i, _ ->
                val selectedLanguage = when (i) {
                    1 -> "ROMAJI"
                    2 -> "NATIVE"
                    else -> "ENGLISH"
                }
                lifecycleScope.launch {
                    anilistMutations.updateSettings(titleLanguage = selectedLanguage)
                    AniList.titleLanguage = selectedLanguage
                    snackString(R.string.success)
                }
                settingsAnilistTitleLanguage.clearFocus()
            }

            val staffNameFormat = when (AniList.staffNameLanguage) {
                "ROMAJI" -> 1
                "NATIVE" -> 2
                else -> 0
            }
            settingsAnilistStaffLanguage.setText(staffNameLang[staffNameFormat])
            settingsAnilistStaffLanguage.setAdapter(
                ArrayAdapter(context, R.layout.item_dropdown, staffNameLang)
            )
            settingsAnilistStaffLanguage.setOnItemClickListener { _, _, i, _ ->
                val selectedLanguage = when (i) {
                    1 -> "ROMAJI"
                    2 -> "NATIVE"
                    else -> "ROMAJI_WESTERN"
                }
                lifecycleScope.launch {
                    anilistMutations.updateSettings(staffNameLanguage = selectedLanguage)
                    AniList.staffNameLanguage = selectedLanguage
                    snackString(R.string.success)
                }
                settingsAnilistStaffLanguage.clearFocus()
            }

            val currentMergeTimeDisplay =
                activityMergeTimeMap.entries.firstOrNull { it.value == AniList.activityMergeTime }?.key
                    ?: "${AniList.activityMergeTime} mins"
            settingsAnilistActivityMergeTime.setText(currentMergeTimeDisplay)
            settingsAnilistActivityMergeTime.setAdapter(
                ArrayAdapter(context, R.layout.item_dropdown, activityMergeTimeMap.keys.toList())
            )
            settingsAnilistActivityMergeTime.setOnItemClickListener { _, _, i, _ ->
                val selectedDisplayTime = activityMergeTimeMap.keys.toList()[i]
                val selectedApiTime = activityMergeTimeMap[selectedDisplayTime] ?: 0
                lifecycleScope.launch {
                    anilistMutations.updateSettings(activityMergeTime = selectedApiTime)
                    AniList.activityMergeTime = selectedApiTime
                    snackString(R.string.success)
                }
                settingsAnilistActivityMergeTime.clearFocus()
            }

            val scoreFormatIndex = when (AniList.scoreFormat) {
                "POINT_10_DECIMAL" -> 1
                "POINT_10" -> 2
                "POINT_5" -> 3
                "POINT_3" -> 4
                else -> 0
            }
            settingsAnilistScoreFormat.setText(scoreFormats[scoreFormatIndex])
            settingsAnilistScoreFormat.setAdapter(
                ArrayAdapter(context, R.layout.item_dropdown, scoreFormats)
            )
            settingsAnilistScoreFormat.setOnItemClickListener { _, _, i, _ ->
                val selectedFormat = when (i) {
                    1 -> "POINT_10_DECIMAL"
                    2 -> "POINT_10"
                    3 -> "POINT_5"
                    4 -> "POINT_3"
                    else -> "POINT_100"
                }
                lifecycleScope.launch {
                    anilistMutations.updateSettings(scoreFormat = selectedFormat)
                    AniList.scoreFormat = selectedFormat
                    snackString(R.string.success)
                }
                settingsAnilistScoreFormat.clearFocus()
            }

            val currentRowOrder =
                rowOrderMap.entries.firstOrNull { it.value == AniList.rowOrder }?.key ?: "Score"
            settingsAnilistRowOrder.setText(currentRowOrder)
            settingsAnilistRowOrder.setAdapter(
                ArrayAdapter(context, R.layout.item_dropdown, rowOrderMap.keys.toList())
            )
            settingsAnilistRowOrder.setOnItemClickListener { _, _, i, _ ->
                val selectedDisplayOrder = rowOrderMap.keys.toList()[i]
                val selectedApiOrder = rowOrderMap[selectedDisplayOrder] ?: "score"
                lifecycleScope.launch {
                    anilistMutations.updateSettings(rowOrder = selectedApiOrder)
                    AniList.rowOrder = selectedApiOrder
                    snackString(R.string.success)
                }
                settingsAnilistRowOrder.clearFocus()
            }

            val containers =
                listOf(binding.animeCustomListsContainer, binding.mangaCustomListsContainer)
            val customLists = listOf(AniList.animeCustomLists, AniList.mangaCustomLists)
            val buttons = listOf(binding.addAnimeListButton, binding.addMangaListButton)

            containers.forEachIndexed { index, container ->
                customLists[index]?.forEach { listName ->
                    addCustomListItem(listName, container, index == 0)
                }
            }

            buttons.forEachIndexed { index, button ->
                button.setOnClickListener {
                    addCustomListItem("", containers[index], index == 0)
                }
            }

            binding.SettingsAnilistCustomListSave.setOnClickListener {
                saveCustomLists()
            }

            val currentTimezone = AniList.timezone?.let { AniList.getDisplayTimezone(it, context) }
                ?: context.getString(R.string.selected_no_time_zone)
            settingsAnilistTimezone.setText(currentTimezone)
            settingsAnilistTimezone.setAdapter(
                ArrayAdapter(context, R.layout.item_dropdown, AniList.timeZone)
            )
            settingsAnilistTimezone.setOnItemClickListener { _, _, i, _ ->
                val selectedTimezone = AniList.timeZone[i]
                val apiTimezone = AniList.getApiTimezone(selectedTimezone)
                lifecycleScope.launch {
                    anilistMutations.updateSettings(timezone = apiTimezone)
                    AniList.timezone = apiTimezone
                    snackString(R.string.success)
                }
                settingsAnilistTimezone.clearFocus()
            }

            val displayAdultContent = AniList.adult
            val airingNotifications = AniList.airingNotifications

            binding.settingsRecyclerView1.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = ViewType.SWITCH,
                        name = getString(R.string.airing_notifications),
                        desc = getString(R.string.airing_notifications_desc),
                        icon = R.drawable.ic_round_notifications_active_24,
                        isChecked = airingNotifications,
                        switch = { isChecked, _ ->
                            lifecycleScope.launch {
                                anilistMutations.updateSettings(airingNotifications = isChecked)
                                AniList.airingNotifications = isChecked
                                snackString(R.string.success)
                            }
                        }
                    ),
                    Settings(
                        type = ViewType.SWITCH,
                        name = getString(R.string.display_adult_content),
                        desc = getString(R.string.display_adult_content_desc),
                        icon = R.drawable.ic_round_nsfw_24,
                        isChecked = displayAdultContent,
                        switch = { isChecked, _ ->
                            lifecycleScope.launch {
                                anilistMutations.updateSettings(displayAdultContent = isChecked)
                                AniList.adult = isChecked
                                snackString(R.string.success)
                            }
                        }
                    ),
                )
            )
            binding.settingsRecyclerView1.layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        }

        binding.settingsRecyclerView2.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = ViewType.BUTTON,
                    name = getString(R.string.edit_bio),
                    desc = "Update your AniList profile bio and 'About Me' description",
                    icon = R.drawable.ic_round_edit_note_24,
                    onClick = {
                        startActivity(
                            Intent(this, MarkdownCreatorActivity::class.java).apply {
                                putExtra("type", "bio")
                            }
                        )
                    }
                ),
                Settings(
                    type = ViewType.SWITCH,
                    name = getString(R.string.restrict_messages),
                    desc = getString(R.string.restrict_messages_desc),
                    icon = R.drawable.ic_round_lock_open_24,
                    isChecked = AniList.restrictMessagesToFollowing,
                    switch = { isChecked, _ ->
                        lifecycleScope.launch {
                            anilistMutations.updateSettings(restrictMessagesToFollowing = isChecked)
                            AniList.restrictMessagesToFollowing = isChecked
                            snackString(R.string.success)
                        }
                    }
                ),
            )
        )
        binding.settingsRecyclerView2.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

    }

    private fun addCustomListItem(listName: String, container: LinearLayout, isAnime: Boolean) {
        val customListItemView = layoutInflater.inflate(R.layout.item_custom_list, container, false)
        val textInputLayout = customListItemView.findViewById<TextInputLayout>(R.id.customListItem)
        val editText = textInputLayout.editText as? TextInputEditText
        editText?.setText(listName)
        textInputLayout.setEndIconOnClickListener {
            val name = editText?.text.toString()
            if (name.isNotEmpty()) {
                val listExists = if (isAnime) {
                    AniList.animeCustomLists?.contains(name) ?: false
                } else {
                    AniList.mangaCustomLists?.contains(name) ?: false
                }

                if (listExists) {
                    customAlertDialog().apply {
                        setTitle(getString(R.string.delete_custom_list))
                        setMessage(getString(R.string.delete_custom_list_confirm, name))
                        setPosButton(getString(R.string.delete)) {
                            deleteCustomList(name, isAnime)
                            container.removeView(customListItemView)
                        }
                        setNegButton(getString(R.string.cancel))
                    }.show()
                } else {
                    container.removeView(customListItemView)
                }
            } else {
                container.removeView(customListItemView)
            }
        }
        container.addView(customListItemView)
    }

    private fun deleteCustomList(name: String, isAnime: Boolean) {
        lifecycleScope.launch {
            val type = if (isAnime) "ANIME" else "MANGA"
            val success = anilistMutations.deleteCustomList(name, type)
            if (success) {
                if (isAnime) {
                    AniList.animeCustomLists = AniList.animeCustomLists?.filter { it != name }
                } else {
                    AniList.mangaCustomLists = AniList.mangaCustomLists?.filter { it != name }
                }
                toast("Custom list deleted")
            } else {
                toast("Failed to delete custom list")
            }
        }
    }

    private fun saveCustomLists() {
        val animeCustomLists = binding.animeCustomListsContainer.children
            .mapNotNull { (it.findViewById<TextInputLayout>(R.id.customListItem).editText as? TextInputEditText)?.text?.toString() }
            .filter { it.isNotEmpty() }
            .toList()
        val mangaCustomLists = binding.mangaCustomListsContainer.children
            .mapNotNull { (it.findViewById<TextInputLayout>(R.id.customListItem).editText as? TextInputEditText)?.text?.toString() }
            .filter { it.isNotEmpty() }
            .toList()

        lifecycleScope.launch {
            val success = anilistMutations.updateCustomLists(animeCustomLists, mangaCustomLists)
            if (success) {
                AniList.animeCustomLists = animeCustomLists
                AniList.mangaCustomLists = mangaCustomLists
                toast("Custom lists saved")
            } else {
                toast("Failed to save custom lists")
            }
        }
    }
}
