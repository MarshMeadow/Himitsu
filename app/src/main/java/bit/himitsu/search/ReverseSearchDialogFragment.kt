package bit.himitsu.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import ani.himitsu.databinding.BottomSheetSourceSearchBinding
import ani.himitsu.dpToColumns
import ani.himitsu.media.MediaDetailsViewModel
import ani.himitsu.media.MediaType
import ani.himitsu.media.cereal.Media
import ani.himitsu.parsers.AnimeSources
import ani.himitsu.parsers.BaseParser
import ani.himitsu.parsers.BaseSources
import ani.himitsu.parsers.DynamicAnimeParser
import ani.himitsu.parsers.DynamicMangaParser
import ani.himitsu.parsers.MangaSources
import ani.himitsu.parsers.NovelSources
import ani.himitsu.parsers.ShowResponse
import ani.himitsu.parsers.novel.DynamicNovelParser
import ani.himitsu.parsers.novel.NovelExtension
import ani.himitsu.toPx
import ani.himitsu.tryWithSuspend
import ani.himitsu.view.dialog.BottomSheetDialogFragment
import bit.himitsu.onCompletedAction
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ReverseSearchDialogFragment : BottomSheetDialogFragment {

    private var _binding: BottomSheetSourceSearchBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by viewModels()
    private var searched = false

    private var queryString: String = ""
    private lateinit var mediaType: MediaType
    private lateinit var animeExtension: AnimeExtension.Installed
    private lateinit var mangaExtension: MangaExtension.Installed
    private lateinit var novelExtension: NovelExtension.Installed

    constructor()

    constructor(extension: AnimeExtension.Installed) {
        mediaType = MediaType.ANIME
        animeExtension = extension
    }

    constructor(extension: MangaExtension.Installed) {
        mediaType = MediaType.MANGA
        mangaExtension = extension
    }

    constructor(extension: NovelExtension.Installed) {
        mediaType = MediaType.NOVEL
        novelExtension = extension
    }

    constructor(search: String) {
        queryString = search
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSourceSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED

        val scope = requireActivity().lifecycleScope
        model.getMedia().observe(viewLifecycleOwner) { media: Media? ->
            binding.mediaListProgressBar.visibility = View.GONE
            binding.mediaListLayout.visibility = View.VISIBLE
            binding.searchRecyclerView.visibility = View.GONE
            binding.searchProgress.visibility = View.VISIBLE

            val recyclerView = binding.searchRecyclerView
            val params = recyclerView.layoutParams as ViewGroup.MarginLayoutParams
            params.leftMargin = 16.toPx
            recyclerView.layoutParams = params

            if (queryString.isNotBlank()) {
                binding.searchSourceTitle.text = queryString.uppercase(Locale.ROOT)
                binding.searchBar.visibility = View.GONE
                scope.launch {
                    val allResults = HashMap<BaseParser, List<ShowResponse>?>()
                    withContext(Dispatchers.IO) {
                        listOf(AnimeSources, MangaSources, NovelSources)
                            .flatMap { sources: BaseSources -> sources.list }
                            .map { lazy -> async { lazy.get.value?.let { it to tryWithSuspend { it.search(queryString) } } } }
                            .awaitAll()
                            .filterNotNull()
                            .forEach { (parser, results) -> allResults[parser] = results }
                    }
                    model.collections.postValue(allResults)
                }
                model.collections.observe(viewLifecycleOwner) { map ->
                    if (map != null) {
                        val adapter = ConcatAdapter()
                        map.forEach { (parser, list) ->
                            if (list != null) {
                                adapter.addAdapter(ReverseSearchAdapter(list, parser, model, scope))
                            }
                        }
                        binding.searchRecyclerView.visibility = View.VISIBLE
                        binding.searchProgress.visibility = View.GONE
                        binding.searchRecyclerView.adapter = adapter
                        binding.searchRecyclerView.layoutManager =
                            GridLayoutManager(requireActivity(), 124.dpToColumns)
                    }
                }
            } else {
                val parser: BaseParser = when (mediaType) {
                    MediaType.ANIME -> DynamicAnimeParser(animeExtension)
                    MediaType.MANGA -> DynamicMangaParser(mangaExtension)
                    MediaType.NOVEL -> DynamicNovelParser(novelExtension)
                }
                binding.searchBarText.setOnEditorActionListener(onCompletedAction {
                    search(scope, parser)
                })
                binding.searchBar.setEndIconOnClickListener {
                    search(scope, parser)
                }
                if (!searched) {
                    search(scope, parser)
                    binding.searchSourceTitle.text = parser.name
                }
                searched = true
                model.responses.observe(viewLifecycleOwner) { list ->
                    if (list != null) {
                        binding.searchRecyclerView.visibility = View.VISIBLE
                        binding.searchProgress.visibility = View.GONE
                        binding.searchRecyclerView.adapter =
                            ReverseSearchAdapter(list, parser, model, scope)
                        binding.searchRecyclerView.layoutManager =
                            GridLayoutManager(requireActivity(), 124.dpToColumns)
                    }
                }
            }
        }
    }

    private fun search(scope: androidx.lifecycle.LifecycleCoroutineScope, parser: BaseParser) {
        binding.searchBarText.clearFocus()
        val imm = requireActivity().getSystemService("input_method") as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
        binding.searchSourceTitle.text =
            binding.searchBarText.text.toString().uppercase(Locale.ROOT)
        scope.launch {
            model.responses.postValue(
                tryWithSuspend { parser.search(binding.searchBarText.text.toString()) }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun dismiss() {
        model.responses.value = null
        model.collections.value = null
        super.dismiss()
    }
}
