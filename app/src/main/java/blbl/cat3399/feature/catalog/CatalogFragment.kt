package blbl.cat3399.feature.catalog

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.catalog.ContentCategory
import blbl.cat3399.core.catalog.StreamingSite
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.PageSectionFocusHost
import blbl.cat3399.databinding.FragmentCatalogBinding
import blbl.cat3399.feature.search.IqiyiSearchActivity
import blbl.cat3399.feature.search.MangoSearchActivity
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.MainNavigationHost

/**
 * Cross-site category matrix.
 *
 * The left rail is the unified category list. Selecting a category rebuilds the site navigation
 * above the detail card so only sites that expose that category remain, matching the product matrix.
 */
class CatalogFragment : Fragment(), PageSectionFocusHost, BackPressHandler {
    private var _binding: FragmentCatalogBinding? = null
    private val binding get() = _binding!!

    private lateinit var categoryAdapter: CatalogCategoryAdapter
    private lateinit var siteAdapter: CatalogSiteAdapter
    private var selectedCategory: ContentCategory = ContentCategory.TV
    private var focusedSite: StreamingSite = StreamingSite.IQIYI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedCategory =
            ContentCategory.fromKey(savedInstanceState?.getString(STATE_CATEGORY_KEY)) ?: ContentCategory.TV
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCatalogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        categoryAdapter =
            CatalogCategoryAdapter(
                onFocusCategory = { category -> selectCategory(category, rebuildSites = true) },
                onClickCategory = { category ->
                    selectCategory(category, rebuildSites = true)
                    focusFirstSite()
                },
                onMoveLeft = { requestSidebarFocus() },
                onMoveRight = { focusFirstSite() },
                columns = CATEGORY_COLUMNS,
            )
        binding.recyclerCategories.layoutManager = GridLayoutManager(requireContext(), CATEGORY_COLUMNS)
        binding.recyclerCategories.adapter = categoryAdapter
        binding.recyclerCategories.itemAnimator = null

        siteAdapter =
            CatalogSiteAdapter(
                onFocusSite = { site ->
                    focusedSite = site
                    renderSiteDetail(site)
                },
                onClickSite = { site -> openSite(site) },
                onMoveLeft = { focusSelectedCategory() },
                columns = SITE_COLUMNS,
            )
        binding.recyclerSites.layoutManager = GridLayoutManager(requireContext(), SITE_COLUMNS)
        binding.recyclerSites.adapter = siteAdapter
        binding.recyclerSites.itemAnimator = null

        selectCategory(selectedCategory, rebuildSites = true)
    }

    private fun selectCategory(category: ContentCategory, rebuildSites: Boolean) {
        val changed = category != selectedCategory
        selectedCategory = category
        categoryAdapter.setSelected(category)
        if (rebuildSites && (changed || siteAdapter.itemCount() == 0)) {
            val sites = category.sitesInDisplayOrder()
            siteAdapter.submit(sites)
            focusedSite = sites.firstOrNull() ?: focusedSite
        }
        binding.tvCategoryTitle.text = category.displayName
        binding.tvCategoryAlias.text = category.aliasText
        binding.tvCoverage.text =
            "覆盖 ${category.siteCount} 个站点 · 已接入 ${category.integratedSites.size} 个"
        renderSiteDetail(focusedSite)
        binding.tvCatalogHint.text =
            if (category.integratedSites.isEmpty()) {
                "当前分类尚无已接入站点，切换键已保留。"
            } else {
                "未接入的站点已保留切换键，接入后即可直接打开；按 OK 在已接入站点搜索「${category.displayName}」。"
            }
    }

    private fun renderSiteDetail(site: StreamingSite) {
        val b = _binding ?: return
        b.tvSiteName.text = site.displayName
        if (site.integrated) {
            b.tvSiteStatus.text = "已接入 · 按 OK 打开「${selectedCategory.displayName}」"
            b.tvSiteHint.text =
                "将在${site.displayName}搜索「${selectedCategory.displayName}」。" +
                    "该分类的别名同样适用：${selectedCategory.aliasText}。"
        } else {
            b.tvSiteStatus.text = "暂未接入 · 切换键已保留"
            b.tvSiteHint.text =
                "${site.displayName}尚未接入内容，当前仅保留入口；" +
                    "该分类覆盖：${selectedCategory.sitesInDisplayOrder().joinToString("、") { it.displayName }}。"
        }
    }

    private fun openSite(site: StreamingSite) {
        if (!site.integrated) {
            AppToast.show(requireContext(), "${site.displayName}暂未接入，入口已保留")
            return
        }
        val keyword = selectedCategory.displayName
        when (site) {
            StreamingSite.BILIBILI -> {
                val opened = (activity as? MainNavigationHost)?.openBilibiliSearch(keyword) == true
                if (!opened) AppToast.show(requireContext(), "无法打开哔哩哔哩搜索")
            }

            StreamingSite.IQIYI ->
                startActivity(
                    Intent(requireContext(), IqiyiSearchActivity::class.java)
                        .putExtra(IqiyiSearchActivity.EXTRA_QUERY, keyword),
                )

            StreamingSite.MANGO ->
                startActivity(
                    Intent(requireContext(), MangoSearchActivity::class.java)
                        .putExtra(MangoSearchActivity.EXTRA_QUERY, keyword),
                )

            else -> AppToast.show(requireContext(), "${site.displayName}暂未接入，入口已保留")
        }
    }

    private fun requestSidebarFocus(): Boolean =
        (activity as? MainNavigationHost)?.requestFocusTopNavigationSelected() == true

    private fun focusFirstSite() {
        val b = _binding ?: return
        b.recyclerSites.post {
            val target = b.recyclerSites.findViewHolderForAdapterPosition(0)?.itemView
            if (target != null) {
                target.requestFocus()
            } else if (siteAdapter.itemCount() == 0) {
                focusSelectedCategory()
            }
        }
    }

    private fun focusSelectedCategory(): Boolean {
        val b = _binding ?: return false
        val position = categoryAdapter.positionOf(selectedCategory)
        b.recyclerCategories.post {
            val target = b.recyclerCategories.findViewHolderForAdapterPosition(position)?.itemView
            if (target != null) {
                target.requestFocus()
            } else {
                b.recyclerCategories.scrollToPosition(position)
                b.recyclerCategories.post {
                    b.recyclerCategories.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                }
            }
        }
        return true
    }

    override fun requestFocusPageSection(): Boolean = focusSelectedCategory()

    override fun handleBackPressed(): Boolean {
        val b = _binding ?: return false
        val focused = activity?.currentFocus ?: return false
        if (focused !== b.root && !isDescendantOf(focused, b.root)) return false
        return requestSidebarFocus()
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CATEGORY_KEY, selectedCategory.key)
    }

    override fun onDestroyView() {
        _binding?.recyclerCategories?.adapter = null
        _binding?.recyclerSites?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val STATE_CATEGORY_KEY = "catalog_category_key"
        private const val CATEGORY_COLUMNS = 2
        private const val SITE_COLUMNS = 3

        fun newInstance(): CatalogFragment = CatalogFragment()
    }
}
