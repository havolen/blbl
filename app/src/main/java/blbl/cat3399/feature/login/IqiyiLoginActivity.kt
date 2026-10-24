package blbl.cat3399.feature.login

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.account.IqiyiSessionStore
import blbl.cat3399.core.api.IqiyiApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.QrCodeBitmap
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ActivityIqiyiLoginBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class IqiyiLoginActivity : BaseActivity() {
    private lateinit var binding: ActivityIqiyiLoginBinding
    private lateinit var sessionStore: IqiyiSessionStore
    private lateinit var api: IqiyiApi
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIqiyiLoginBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        sessionStore = IqiyiSessionStore(applicationContext)
        api = IqiyiApi(sessionStore.cookies)
        binding.btnRefresh.setOnClickListener { startLogin() }
        binding.btnClear.setOnClickListener { logout() }
        val account = sessionStore.currentAccount()
        renderAccount(account?.username, account?.vip)
        if (account == null) binding.ivQr.post { startLogin() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
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
        binding.tvTitle.text = getString(R.string.iqiyi_login_title)
        binding.tvStatus.text = getString(R.string.iqiyi_login_requesting_qr)
        binding.ivQr.setImageDrawable(null)
        loginJob = lifecycleScope.launch {
            try {
                val qr = api.requestQrCode()
                val size = targetQrSizePx()
                binding.ivQr.setImageBitmap(withContext(Dispatchers.Default) { QrCodeBitmap.encode(qr.contentUrl, size) })
                binding.tvStatus.text = getString(R.string.iqiyi_login_scan_prompt)
                pollLogin(qr)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("IqiyiLogin", "QR request failed: ${error.javaClass.simpleName}")
                binding.tvStatus.text = getString(R.string.iqiyi_login_request_failed)
            }
        }
    }

    private suspend fun pollLogin(qr: IqiyiApi.QrCode) {
        val attempts = (qr.expireSeconds / POLL_INTERVAL_SECONDS).coerceIn(1, 300)
        repeat(attempts) {
            try {
                when (val result = api.poll(qr.token)) {
                    IqiyiApi.PollResult.Waiting -> binding.tvStatus.text = getString(R.string.iqiyi_login_waiting)
                    IqiyiApi.PollResult.Expired -> {
                        binding.tvStatus.text = getString(R.string.iqiyi_login_expired)
                        return
                    }

                    is IqiyiApi.PollResult.Success -> {
                        api.persistAuthCookie(result.data)
                        val profile = result.profile ?: api.fetchProfile()
                        if (profile == null) {
                            AppLog.w("IqiyiLogin", "login confirmed but profile is unavailable")
                            binding.tvStatus.text = getString(R.string.iqiyi_login_profile_failed)
                            return
                        }
                        try {
                            sessionStore.saveAccount(profile.username, profile.uid, profile.vip)
                        } catch (error: IllegalArgumentException) {
                            AppLog.w("IqiyiLogin", "login completed without a persistable session")
                            binding.tvStatus.text = getString(R.string.iqiyi_login_session_failed)
                            return
                        }
                        renderAccount(profile.username, profile.vip)
                        binding.tvStatus.text = getString(R.string.iqiyi_login_success)
                        setResult(RESULT_OK)
                        AppLog.i("IqiyiLogin", "login completed; profile saved vip=${profile.vip}")
                        delay(600)
                        finish()
                        return
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("IqiyiLogin", "poll failed: ${error.javaClass.simpleName}")
                binding.tvStatus.text = getString(R.string.iqiyi_login_network_retry)
            }
            delay(POLL_INTERVAL_MS)
        }
        binding.tvStatus.text = getString(R.string.iqiyi_login_expired)
    }

    private fun renderAccount(
        username: String?,
        vip: Boolean?,
    ) {
        val loggedIn = !username.isNullOrBlank()
        binding.tvTitle.text = getString(R.string.iqiyi_login_title)
        binding.tvStatus.text = if (loggedIn) {
            getString(R.string.iqiyi_login_account_fmt, username.orEmpty(), vipLabel(vip))
        } else {
            getString(R.string.iqiyi_login_scan_prompt)
        }
        binding.ivQr.visibility = if (loggedIn) View.INVISIBLE else View.VISIBLE
        binding.btnRefresh.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.btnClear.visibility = if (loggedIn) View.VISIBLE else View.GONE
    }

    private fun vipLabel(vip: Boolean?): String = when (vip) {
        true -> getString(R.string.vip_status_vip)
        false -> getString(R.string.vip_status_not_vip)
        null -> getString(R.string.vip_status_unknown)
    }

    private fun logout() {
        loginJob?.cancel()
        loginJob = lifecycleScope.launch {
            runCatching { api.logout() }
            sessionStore.clear()
            binding.ivQr.setImageDrawable(null)
            binding.ivQr.visibility = View.VISIBLE
            binding.btnClear.visibility = View.GONE
            binding.btnRefresh.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.iqiyi_login_signed_out)
            setResult(RESULT_OK)
        }
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
        const val POLL_INTERVAL_MS = 2_000L
        const val POLL_INTERVAL_SECONDS = 2
        const val FALLBACK_QR_SIZE_PX = 900
    }
}
