package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import blbl.cat3399.R

/** Tencent Video history is a placeholder until its authenticated history API is wired. */
class TencentHistoryFragment : Fragment(), MyTabSwitchFocusTarget {
    private var placeholder: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_tencent_history, container, false)
        placeholder = view.findViewById(R.id.tv_tencent_history_placeholder)
        return view
    }

    override fun requestFocusFirstItemFromTabSwitch(): Boolean =
        placeholder?.requestFocus() == true

    override fun onDestroyView() {
        placeholder = null
        super.onDestroyView()
    }
}
