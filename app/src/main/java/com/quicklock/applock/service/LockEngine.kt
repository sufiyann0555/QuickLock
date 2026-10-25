package com.quicklock.applock.service

interface LockPolicy {
    fun isLocked(pkg: String): Boolean

    /** 0 = relock as soon as the user leaves, >0 = grace period in seconds, -1 = only on screen off. */
    fun relockSeconds(): Int
}

/**
 * Pure state machine (no Android classes) that decides whether a package that just came to the
 * foreground needs to be locked. Not thread-safe by design: accessibility events, broadcast receivers
 * and activities all run on the main thread, so no locking and no allocations are needed on the hot path.
 *
 * State model: a package is "unlocked" while it has a session. The session is IN_FOREGROUND while the
 * user is in the app, or holds the time the user left it. Coming back after the grace period expires
 * (or immediately, for relock = 0) removes the session and demands authentication again.
 */
class LockEngine(
    private val policy: LockPolicy,
    private val clock: () -> Long,
) {
    private val sessions = HashMap<String, Long>()
    private val grants = HashMap<String, Long>()
    private var current: String? = null

    /** @return true if [pkg] is protected and must be covered by the lock screen right now. */
    fun onForeground(pkg: String): Boolean {
        val now = clock()
        if (pkg != current) {
            current?.let { if (sessions[it] == IN_FOREGROUND) sessions[it] = now }
            current = pkg
        }
        if (!policy.isLocked(pkg)) {
            grants.remove(pkg)
            return false
        }

        val state = sessions[pkg] ?: return !consumeGrant(pkg, now)
        if (state == IN_FOREGROUND) {
            grants.remove(pkg)
            return false
        }

        val timeout = policy.relockSeconds()
        val expired = timeout == 0 || (timeout > 0 && now - state > timeout * 1000L)
        if (expired) {
            sessions.remove(pkg)
            return !consumeGrant(pkg, now)
        }
        grants.remove(pkg)
        sessions[pkg] = IN_FOREGROUND
        return false
    }

    /** The user authenticated successfully for [pkg]. */
    fun onUnlocked(pkg: String) {
        sessions[pkg] = IN_FOREGROUND
    }

    /**
     * One-shot, short-lived pass for a package we're about to open ourselves after the user has already
     * authenticated inside QuickLock (e.g. opening system Settings from our own setup screen).
     */
    fun preAuthorize(pkg: String) {
        grants[pkg] = clock()
    }

    fun revokePreAuthorization(pkg: String) {
        grants.remove(pkg)
    }

    /** Screen turned off: everything relocks. [current] is kept so we can re-check after unlock. */
    fun onScreenOff() {
        sessions.clear()
        grants.clear()
    }

    /** After the keyguard is dismissed: is the app that's about to be shown protected and locked? */
    fun recheckCurrent(): String? {
        val pkg = current ?: return null
        return if (policy.isLocked(pkg) && sessions[pkg] == null) pkg else null
    }

    private fun consumeGrant(pkg: String, now: Long): Boolean {
        val grantedAt = grants.remove(pkg) ?: return false
        if (now - grantedAt > GRANT_WINDOW_MS) return false
        sessions[pkg] = IN_FOREGROUND
        return true
    }

    private companion object {
        const val IN_FOREGROUND = -1L
        const val GRANT_WINDOW_MS = 8_000L
    }
}
