package blbl.cat3399.core.account

import blbl.cat3399.core.net.BiliClient

/** Resolves the current avatar, falling back to the saved account snapshot when the live state has none. */
object AccountAvatar {
    fun resolve(session: BiliSession.State = BiliSession.state.value): String? {
        if (!session.canAccessPersonal) return null
        session.avatarUrl?.takeIf { it.isNotBlank() }?.let { return it }

        val saved = BiliClient.accounts.accounts()
        return saved.firstOrNull { it.mid == session.mid }?.avatarUrl
            ?: saved.firstOrNull { it.isActive }?.avatarUrl
    }
}
