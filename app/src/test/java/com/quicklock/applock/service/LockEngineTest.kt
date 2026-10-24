package com.quicklock.applock.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockEngineTest {
    private var now = 0L
    private var relock = 0
    private val policy = object : LockPolicy {
        override fun isLocked(pkg: String) = pkg == "bank"
        override fun relockSeconds() = relock
    }
    private val engine = LockEngine(policy) { now }

    @Test
    fun unprotectedAppsNeverLock() {
        assertFalse(engine.onForeground("notes"))
    }

    @Test
    fun protectedAppLocksOnFirstOpen() {
        assertTrue(engine.onForeground("bank"))
    }

    @Test
    fun staysOpenAfterUnlockWhileInForeground() {
        engine.onForeground("bank")
        engine.onUnlocked("bank")
        assertFalse(engine.onForeground("bank"))
    }

    @Test
    fun relocksImmediatelyAfterLeaving() {
        engine.onForeground("bank")
        engine.onUnlocked("bank")
        engine.onForeground("home")
        now += 1
        assertTrue(engine.onForeground("bank"))
    }

    @Test
    fun gracePeriodKeepsSessionThenExpires() {
        relock = 30
        engine.onForeground("bank")
        engine.onUnlocked("bank")
        engine.onForeground("home")
        now += 10_000
        assertFalse(engine.onForeground("bank")) // back within 30 s
        engine.onForeground("home")
        now += 31_000
        assertTrue(engine.onForeground("bank")) // away for more than 30 s
    }

    @Test
    fun screenOffRelocksEverythingAndRecheckFindsCurrentApp() {
        relock = -1
        engine.onForeground("bank")
        engine.onUnlocked("bank")
        engine.onScreenOff()
        assertEquals("bank", engine.recheckCurrent())
        assertTrue(engine.onForeground("bank"))
    }

    @Test
    fun recheckIsNullWhenUnlocked() {
        engine.onForeground("bank")
        engine.onUnlocked("bank")
        assertNull(engine.recheckCurrent())
    }

    @Test
    fun preAuthorizeIsOneShotAndExpires() {
        engine.preAuthorize("bank")
        assertFalse(engine.onForeground("bank")) // consumed
        engine.onForeground("home")
        now += 1
        assertTrue(engine.onForeground("bank"))  // relock = 0, grant already used

        engine.onForeground("home")
        engine.preAuthorize("bank")
        now += 9_000                             // grant window is 8 s
        assertTrue(engine.onForeground("bank"))
    }
}
