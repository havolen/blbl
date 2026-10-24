package blbl.cat3399.feature.login

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.account.MangoSessionStore
import blbl.cat3399.core.api.MangoLoginApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.QrCodeBitmap
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ActivityMangoLoginBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class MangoLoginActivity : BaseActivity() {
    private lateinit var binding: ActivityMangoLoginBinding
    private lateinit var sessionStore: MangoSessionStore
    private lateinit var loginApi: MangoLoginApi
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMangoLoginBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, blbl.cat3399.core.net.BiliClient.prefs.fullscreenEnabled)

        sessionStore = MangoSessionStore(applicationContext)
        loginApi = MangoLoginApi(sessionStore.cookies)
        binding.btnRefresh.setOnClickListener { startLogin() }
        binding.btnClear.setOnClickListener { logout() }
        val account = sessionStore.currentAccount()
        renderAccount(account?.username)
        if (account == null) binding.ivQr.post { startLogin() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, blbl.cat3399.core.net.BiliClient.prefs.fullscreenEnabled)
    }

    override fun onResume() {
        super.onResume()
        if (currentFocus == null) {
            val focusTarget = if (binding.btnClear.visibility == View.VISIBLE) binding.btnClear else binding.btnRefresh
            focusTarget.post { focusTarget.requestFocus() }
        }
    }

    override fun onDestroy() {
        loginJob?.cancel()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            binding.btnRefresh.post { binding.btnRefresh.requestFocus() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startLogin() {
        loginJob?.cancel()
        binding.tvStatus.text = getString(R.string.mango_login_requesting_qr)
        binding.ivQr.setImageDrawable(null)
        loginJob = lifecycleScope.launch {
            try {
                val qr = loginApi.requestQrCode()
                val size = targetQrSizePx()
                binding.ivQr.setImageBitmap(withContext(Dispatchers.Default) { QrCodeBitmap.encode(qr.value, size) })
                binding.tvStatus.text = getString(R.string.mango_login_scan_prompt)
                pollLogin(qr)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("MangoLogin", "QR request failed: ${error.javaClass.simpleName}")
                binding.tvStatus.text = getString(R.string.mango_login_request_failed)
            }
        }
    }

    private suspend fun pollLogin(qr: MangoLoginApi.QrCode) {
        repeat(MAX_POLL_COUNT) {
            try {
                when (val result = loginApi.poll(qr)) {
                    MangoLoginApi.PollResult.Waiting -> {
                        binding.tvStatus.text = getString(R.string.mango_login_waiting)
                    }

                    MangoLoginApi.PollResult.NeedsPhoneBinding -> {
                        binding.tvStatus.text = getString(R.string.mango_login_phone_binding_required)
                        return
                    }

                    is MangoLoginApi.PollResult.LoggedIn -> {
                        try {
                            sessionStore.saveAccount(result.profile.username, result.profile.uid)
                        } catch (error: IllegalArgumentException) {
                            AppLog.w("MangoLogin", "login completed without a persistable session")
                            binding.tvStatus.text = getString(R.string.mango_login_session_not_saved)
                            return
                        }
                        renderAccount(result.profile.username)
                        setResult(RESULT_OK)
                        AppLog.i("MangoLogin", "login completed; account profile saved")
                        return
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("MangoLogin", "poll failed: ${error.javaClass.simpleName}")
                binding.tvStatus.text = getString(R.string.mango_login_network_retry)
            }
            delay(POLL_INTERVAL_MS)
        }
        binding.tvStatus.text = getString(R.string.mango_login_qr_expired)
    }

    private fun renderAccount(username: String?) {
        val loggedIn = !username.isNullOrBlank()
        binding.tvTitle.text = getString(R.string.mango_login_title)
        binding.tvStatus.text = if (loggedIn) {
            getString(R.string.mango_login_account_fmt, username)
        } else {
            getString(R.string.mango_login_scan_prompt)
        }
        binding.ivQr.visibility = if (loggedIn) View.INVISIBLE else View.VISIBLE
        binding.btnRefresh.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.btnClear.visibility = if (loggedIn) View.VISIBLE else View.GONE
    }

    private fun logout() {
        loginJob?.cancel()
        sessionStore.clear()
        binding.ivQr.setImageDrawable(null)
        binding.ivQr.visibility = View.VISIBLE
        binding.btnClear.visibility = View.GONE
        binding.btnRefresh.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.mango_login_signed_out)
        setResult(RESULT_OK)
    }

    private fun targetQrSizePx(): Int {
        val viewSize = min(binding.ivQr.width, binding.ivQr.height)
        val padding = max(
            binding.ivQr.paddingLeft + binding.ivQr.paddingRight,
            binding.ivQr.paddingTop + binding.ivQr.paddingBottom,
        )
        return if (viewSize > 0) (viewSize - padding).coerceAtLeast(1) else FALLBACK_QR_SIZE_PX
    }

    private fun isNavKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_TAB,
        -> true
        else -> false
    }

    private companion object {
        const val MAX_POLL_COUNT = 60
        const val POLL_INTERVAL_MS = 3_000L
        const val FALLBACK_QR_SIZE_PX = 900
    }
}
