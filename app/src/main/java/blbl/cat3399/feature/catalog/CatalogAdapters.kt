package blbl.cat3399.feature.catalog

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.catalog.ContentCategory
import blbl.cat3399.core.catalog.StreamingSite
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.databinding.ItemCatalogCategoryBinding
import blbl.cat3399.databinding.ItemCatalogSiteBinding

/** Two-column category rail. Focus drives the site navigation row on the right. */
internal class CatalogCategoryAdapter(
    private val onFocusCategory: (ContentCategory) -> Unit,
    private val onClickCategory: (ContentCategory) -> Unit,
    private val onMoveLeft: () -> Unit,
    private val onMoveRight: () -> Unit,
) : RecyclerView.Adapter<CatalogCategoryAdapter.Vh>() {
    private val items: List<ContentCategory> = ContentCategory.entries
    private var selected: ContentCategory = ContentCategory.TV
    private var focusedPosition: Int = RecyclerView.NO_POSITION

    init {
        setHasStableIds(true)
    }

    fun setSelected(category: ContentCategory) {
        if (selected == category) return
        val previous = selected
        selected = category
        notifyItemChanged(previous.ordinal)
        notifyItemChanged(category.ordinal)
    }

    fun positionOf(category: ContentCategory): Int = category.ordinal

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh =
        Vh(ItemCatalogCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val category = items[position]
        holder.bind(
            category = category,
            selected = category == selected,
            onClick = { onClickCategory(category) },
            onFocusChanged = { hasFocus ->
                if (hasFocus) {
                    focusedPosition = position
                    onFocusCategory(category)
                } else if (focusedPosition == position) {
                    focusedPosition = RecyclerView.NO_POSITION
                }
                holder.renderSelected(category == selected)
            },
        )
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    onMoveRight()
                    true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    onMoveLeft()
                    true
                }

                else -> false
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemCatalogCategoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            category: ContentCategory,
            selected: Boolean,
            onClick: () -> Unit,
            onFocusChanged: (Boolean) -> Unit,
        ) {
            binding.tvLabel.text = category.displayName
            binding.card.setOnClickListener { onClick() }
            binding.card.setOnFocusChangeListener { _, hasFocus -> onFocusChanged(hasFocus) }
            binding.card.isSelected = selected
            binding.card.contentDescription = "${category.displayName}，${category.siteCount} 个站点"
            renderSelected(selected)
        }

        fun renderSelected(selected: Boolean) {
            val context = binding.root.context
            binding.tvLabel.setTextColor(
                if (selected) {
                    ThemeColor.resolve(context, R.attr.blblAccent, R.color.blbl_glass_focus_text)
                } else {
                    ThemeColor.resolve(context, R.attr.blblOnPageBackdrop, R.color.blbl_glass_focus_text)
                },
            )
            binding.card.setCardBackgroundColor(
                if (selected) {
                    ThemeColor.resolve(context, R.attr.blblAccentWeak, R.color.blbl_glass_focus_fill)
                } else {
                    ThemeColor.resolve(context, com.google.android.material.R.attr.colorSurface, R.color.blbl_glass_focus_fill)
                },
            )
        }
    }
}

/** Site switch row: one focusable button per site that exposes the selected category. */
internal class CatalogSiteAdapter(
    private val onFocusSite: (StreamingSite) -> Unit,
    private val onClickSite: (StreamingSite) -> Unit,
    private val onMoveLeft: () -> Unit,
) : RecyclerView.Adapter<CatalogSiteAdapter.Vh>() {
    private val items = ArrayList<StreamingSite>()
    private var focusedPosition: Int = RecyclerView.NO_POSITION

    init {
        setHasStableIds(true)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<StreamingSite>) {
        if (items == next) return
        items.clear()
        items.addAll(next)
        focusedPosition = RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    fun itemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh =
        Vh(ItemCatalogSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val site = items[position]
        holder.bind(site = site, onClick = { onClickSite(site) })
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focusedPosition = position
                onFocusSite(site)
            } else if (focusedPosition == position) {
                focusedPosition = RecyclerView.NO_POSITION
            }
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                    onMoveLeft()
                    true
                }

                else -> false
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemCatalogSiteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(site: StreamingSite, onClick: () -> Unit) {
            val context = binding.root.context
            binding.ivLogo.setImageResource(site.iconRes)
            binding.tvName.text = site.displayName
            binding.tvStatus.text = site.availableLabel
            binding.tvStatus.setTextColor(
                if (site.integrated) {
                    ThemeColor.resolve(context, R.attr.blblAccent, R.color.blbl_glass_focus_text)
                } else {
                    ThemeColor.resolve(context, R.attr.blblOnPageBackdropSecondary, R.color.blbl_text_secondary)
                },
            )
            binding.card.alpha = if (site.integrated) 1f else 0.66f
            binding.card.setOnClickListener { onClick() }
            binding.card.contentDescription = "${site.displayName}，${site.availableLabel}"
        }
    }
}
