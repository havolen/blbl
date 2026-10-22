package blbl.cat3399.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.navigation.MainNavigationKeys
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.databinding.ItemTopNavigationBinding

class MainTopNavAdapter(
    private val onClick: (NavItem) -> Unit,
    private val onFocus: (NavItem) -> Unit,
) : RecyclerView.Adapter<MainTopNavAdapter.Vh>() {
    data class NavItem(
        val key: String,
        val title: String,
        val iconRes: Int,
        val avatarUrl: String? = null,
    )

    private val items = ArrayList<NavItem>()
    private var selectedKey: String = KEY_RECOMMEND
    private var focusedKey: String? = null
    private var attachedRecyclerView: RecyclerView? = null

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<NavItem>, selectedKey: String) {
        if (items == list) {
            setSelectedKey(selectedKey)
            return
        }
        items.clear()
        items.addAll(list)
        this.selectedKey = selectedKey
        focusedKey = focusedKey?.takeIf { key -> list.any { it.key == key } }
        notifyDataSetChanged()
    }

    fun select(key: String, trigger: Boolean) {
        val item = items.firstOrNull { it.key == key } ?: return
        setSelectedKey(key)
        if (trigger) onClick(item)
    }

    fun setSelectedKey(key: String) {
        if (selectedKey == key) return
        val previousActiveKey = focusedKey ?: selectedKey
        selectedKey = key
        updateActiveRows(previousActiveKey, focusedKey ?: selectedKey)
    }

    private fun setFocusedKey(key: String?) {
        val previousActiveKey = focusedKey ?: selectedKey
        if (focusedKey == key) return
        focusedKey = key
        updateActiveRows(previousActiveKey, focusedKey ?: selectedKey)
    }

    private fun updateActiveRows(previousKey: String, nextKey: String) {
        if (previousKey == nextKey) return
        notifyActiveRow(previousKey)
        notifyActiveRow(nextKey)
    }

    private fun notifyActiveRow(key: String) {
        val position = items.indexOfFirst { it.key == key }
        if (position >= 0) notifyItemChanged(position, ACTIVE_STATE_PAYLOAD)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (attachedRecyclerView === recyclerView) attachedRecyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun selectedKey(): String = selectedKey

    fun selectedAdapterPosition(): Int = items.indexOfFirst { it.key == selectedKey }

    fun positionOf(key: String): Int = items.indexOfFirst { it.key == key }

    fun itemAt(position: Int): NavItem? = items.getOrNull(position)

    fun updateAccountAvatar(avatarUrl: String?) {
        val position = positionOf(MainNavigationKeys.MY_ACCOUNT)
        if (position < 0) return
        val item = items[position]
        if (item.avatarUrl == avatarUrl) return
        items[position] = item.copy(avatarUrl = avatarUrl)
        notifyItemChanged(position)
    }

    fun matches(list: List<NavItem>, selectedKey: String): Boolean = items == list && this.selectedKey == selectedKey

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemTopNavigationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val item = items[position]
        holder.bind(
            item = item,
            selected = item.key == (focusedKey ?: selectedKey),
            onClick = { onClick(item) },
            onFocus = { onFocus(item) },
            onFocusChanged = { hasFocus ->
                if (hasFocus) {
                    setFocusedKey(item.key)
                } else {
                    attachedRecyclerView?.post {
                        if (focusedKey == item.key && attachedRecyclerView?.hasFocus() != true) {
                            setFocusedKey(null)
                        }
                    }
                }
            },
        )
    }

    override fun onBindViewHolder(holder: Vh, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(ACTIVE_STATE_PAYLOAD)) {
            holder.setActive(items[position].key == (focusedKey ?: selectedKey))
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemTopNavigationBinding) : RecyclerView.ViewHolder(binding.root) {
        private var hasAvatar = false

        fun bind(
            item: NavItem,
            selected: Boolean,
            onClick: () -> Unit,
            onFocus: () -> Unit,
            onFocusChanged: (Boolean) -> Unit,
        ) {
            hasAvatar = bindCard(binding, item, selected, onClick, onFocus, onFocusChanged)
        }

        fun setActive(active: Boolean) = renderCardState(binding, active, hasAvatar)
    }

    companion object {
        const val KEY_SEARCH = MainNavigationKeys.SEARCH
        const val KEY_RECOMMEND = MainNavigationKeys.RECOMMEND
        const val KEY_POPULAR = MainNavigationKeys.POPULAR
        const val KEY_CATEGORY = MainNavigationKeys.CATEGORY
        const val KEY_CINEMA = MainNavigationKeys.CINEMA
        const val KEY_BANGUMI = MainNavigationKeys.BANGUMI
        const val KEY_LIVE = MainNavigationKeys.LIVE
        const val KEY_MY_HISTORY = MainNavigationKeys.MY_HISTORY
        const val KEY_MY_FAVORITES = MainNavigationKeys.MY_FAVORITES
        const val KEY_MY_BANGUMI = MainNavigationKeys.MY_BANGUMI
        const val KEY_MY_DRAMA = MainNavigationKeys.MY_DRAMA
        const val KEY_MY_WATCH_LATER = MainNavigationKeys.MY_WATCH_LATER
        const val KEY_MY_LIKES = MainNavigationKeys.MY_LIKES
        const val KEY_SETTINGS = "tool_settings"
        private const val ACTIVE_STATE_PAYLOAD = "nav_active_state"
        private const val DEFAULT_ICON_OFFSET_DP = 1f
        private const val LOWER_ICON_OFFSET_DP = 2f
        private const val RAISED_ICON_OFFSET_DP = 0f

        fun bindCard(
            binding: ItemTopNavigationBinding,
            item: NavItem,
            selected: Boolean,
            onClick: () -> Unit,
            onFocus: () -> Unit,
            onFocusChanged: ((Boolean) -> Unit)? = null,
        ): Boolean {
            val hasAvatar = item.key == MainNavigationKeys.MY_ACCOUNT && !item.avatarUrl.isNullOrBlank()
            binding.tvLabel.text = item.title
            binding.ivIcon.visibility = View.VISIBLE
            if (hasAvatar) {
                binding.ivIcon.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                binding.ivIcon.imageTintList = null
                ImageLoader.loadInto(binding.ivIcon, ImageUrl.avatar(item.avatarUrl))
            } else {
                ImageLoader.loadInto(binding.ivIcon, null)
                binding.ivIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                binding.ivIcon.setImageResource(item.iconRes)
            }
            binding.ivIcon.translationY = binding.root.resources.displayMetrics.density * when (item.key) {
                KEY_SEARCH, KEY_RECOMMEND, KEY_POPULAR, KEY_CATEGORY -> LOWER_ICON_OFFSET_DP
                KEY_LIVE -> RAISED_ICON_OFFSET_DP
                else -> DEFAULT_ICON_OFFSET_DP
            }
            (binding.tvLabel.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.marginStart = binding.root.resources.getDimensionPixelSize(R.dimen.top_nav_icon_label_gap)
                binding.tvLabel.layoutParams = params
            }
            binding.card.isSelected = selected
            binding.card.setOnClickListener { onClick() }
            renderCardState(binding, binding.card.hasFocus() || selected, hasAvatar)
            binding.card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    renderCardState(binding, true, hasAvatar)
                    onFocusChanged?.invoke(true)
                    onFocus()
                } else if (onFocusChanged == null) {
                    renderCardState(binding, selected, hasAvatar)
                } else {
                    onFocusChanged(false)
                }
            }
            return hasAvatar
        }

        private fun renderCardState(binding: ItemTopNavigationBinding, active: Boolean, hasAvatar: Boolean = false) {
            val context = binding.root.context
            val textColor = if (active) {
                ThemeColor.resolve(context, R.attr.blblNavigationFocusText, R.color.blbl_glass_focus_text)
            } else {
                ThemeColor.resolve(context, R.attr.blblOnPageBackdropSecondary, R.color.blbl_text_secondary)
            }
            val background = if (active) {
                ThemeColor.resolve(context, R.attr.blblNavigationFocusBackground, R.color.blbl_glass_focus)
            } else {
                android.graphics.Color.TRANSPARENT
            }
            val stroke = if (active) {
                ThemeColor.resolve(context, R.attr.blblAccent, R.color.blbl_glass_focus_stroke)
            } else {
                android.graphics.Color.TRANSPARENT
            }
            binding.card.isSelected = active
            binding.card.setCardBackgroundColor(background)
            binding.card.strokeColor = stroke
            binding.tvLabel.setTextColor(textColor)
            binding.ivIcon.imageTintList = if (hasAvatar) null else ColorStateList.valueOf(textColor)
        }
    }
}
