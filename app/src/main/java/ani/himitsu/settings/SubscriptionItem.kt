package ani.himitsu.settings

import android.content.Intent
import android.view.View
import androidx.core.content.ContextCompat
import ani.himitsu.R
import ani.himitsu.databinding.ItemSubscriptionBinding
import ani.himitsu.loadImage
import ani.himitsu.media.MediaDetailsActivity
import ani.himitsu.notifications.subscription.SubscriptionHelper
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem

class SubscriptionItem(
    val id: Int,
    private val media: SubscriptionHelper.Companion.SubscribeMedia,
    private val adapter: GroupieAdapter
) : BindableItem<ItemSubscriptionBinding>() {
    private lateinit var binding: ItemSubscriptionBinding
    override fun bind(p0: ItemSubscriptionBinding, p1: Int) {
        val context = p0.root.context
        binding = p0
        val parserName = if (media.isAnime)
            SubscriptionHelper.getAnimeParser(media.id).name
        else
            SubscriptionHelper.getMangaParser(media.id).name
        binding.subscriptionName.text = media.name
        binding.subscriptionSource.text = parserName
        binding.root.setOnClickListener {
            ContextCompat.startActivity(
                context,
                Intent(context, MediaDetailsActivity::class.java).putExtra(
                    "mediaId", media.id
                ),
                null
            )
        }
        binding.subscriptionCover.loadImage(media.cover)
        binding.deleteSubscription.setOnClickListener {
            SubscriptionHelper.deleteSubscription(id, true)
            adapter.remove(this)
        }
    }

    override fun getLayout(): Int {
        return R.layout.item_subscription
    }

    override fun initializeViewBinding(p0: View): ItemSubscriptionBinding {
        return ItemSubscriptionBinding.bind(p0)
    }
}
