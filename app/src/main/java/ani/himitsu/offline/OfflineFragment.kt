package ani.himitsu.offline

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import ani.himitsu.R
import ani.himitsu.Refresh
import ani.himitsu.databinding.FragmentOfflineBinding
import ani.himitsu.isOnline
import ani.himitsu.navBarHeight
import ani.himitsu.refresh
import ani.himitsu.settings.saving.PrefManager
import ani.himitsu.settings.saving.PrefName
import ani.himitsu.statusBarHeight

class OfflineFragment : Fragment() {
    private var offline = false
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentOfflineBinding.inflate(inflater, container, false)
        binding.refreshContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        offline = PrefManager.getVal(PrefName.OfflineMode)
        binding.noInternet.text =
            if (offline) getString(R.string.offline_mode) else getString(R.string.no_internet)
        binding.refreshButton.text = getString(if (offline) R.string.go_online else R.string.refresh)
        binding.refreshButton.setOnClickListener {
            if (offline && isOnline(requireContext())) {
                PrefManager.setVal(PrefName.OfflineMode, false)
                requireActivity().recreate()
                Refresh.all()
            } else {
                if (isOnline(requireContext()) ) {
                    requireActivity().refresh()
                }
            }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        offline = PrefManager.getVal(PrefName.OfflineMode)
    }
}