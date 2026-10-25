package com.quicklock.applock.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedNotificationStoreTest {

    @Test
    fun notificationsRemainIndependentAndScopedByPackage() {
        val store = LockedNotificationStore()
        val instagramMessage = notification("instagram-1", "com.instagram.android")
        val instagramMention = notification("instagram-2", "com.instagram.android")
        val snapchatMessage = notification("snapchat-1", "com.snapchat.android")
        store.put(instagramMessage)
        store.put(instagramMention)
        store.put(snapchatMessage)

        assertEquals(listOf(instagramMessage, instagramMention), store.forPackage("com.instagram.android"))
        assertEquals(listOf(snapchatMessage), store.forPackage("com.snapchat.android"))
    }

    @Test
    fun updatesReplaceOnlyTheMatchingNotification() {
        val store = LockedNotificationStore()
        val firstMessage = notification("instagram-1", "com.instagram.android", text = "first")
        val secondMessage = notification("instagram-2", "com.instagram.android", text = "second")
        val updatedFirstMessage = notification("instagram-1", "com.instagram.android", text = "updated")
        store.put(firstMessage)
        store.put(secondMessage)
        store.put(updatedFirstMessage)

        assertEquals(listOf(updatedFirstMessage, secondMessage), store.forPackage("com.instagram.android"))
    }

    @Test
    fun tapActionCanOnlyBeClaimedOnceForCurrentNotification() {
        val store = LockedNotificationStore()
        val original = notification("instagram-1", "com.instagram.android")
        store.put(original)
        val originalAction = store.tapAction(original.key, original.captureToken)!!

        assertTrue(store.claimTapAction(originalAction))
        assertFalse(store.claimTapAction(originalAction))

        store.remove(original.key)
        val replacement = notification(original.key, original.packageName, text = "updated")
        store.put(replacement)
        assertFalse(store.claimTapAction(originalAction))
        val replacementAction = store.tapAction(replacement.key, replacement.captureToken)!!
        assertTrue(store.claimTapAction(replacementAction))
        assertSame(replacement, store[replacement.key])
    }

    @Test
    fun notificationUpdateKeepsTheTappedVersionActionAvailable() {
        val store = LockedNotificationStore()
        val firstVersion = notification("instagram-1", "com.instagram.android", text = "first")
        store.put(firstVersion)
        val tappedAction = store.tapAction(firstVersion.key, firstVersion.captureToken)!!
        val updatedVersion = notification("instagram-1", "com.instagram.android", text = "updated")

        store.put(updatedVersion)

        assertSame(updatedVersion, store[updatedVersion.key])
        assertSame(tappedAction, store.tapAction(firstVersion.key, firstVersion.captureToken))
        assertTrue(store.claimTapAction(tappedAction))
    }

    @Test
    fun authenticatedTapAutomaticallyDispatchesOnceAndRestoresOnlyItsPackage() {
        val store = LockedNotificationStore()
        val tapped = notification("instagram-1", "com.instagram.android")
        val anotherInstagram = notification("instagram-2", "com.instagram.android")
        val snapchat = notification("snapchat-1", "com.snapchat.android")
        store.put(tapped)
        store.put(anotherInstagram)
        store.put(snapchat)

        var authorizedPackage: String? = null
        var dispatchedAction: NotificationTapAction? = null
        var dispatchCount = 0
        val dispatcher = AuthenticatedNotificationActionDispatcher(store)

        val result = dispatcher.dispatch(
            packageName = tapped.packageName,
            notificationKey = tapped.key,
            captureToken = tapped.captureToken,
            restorePackage = {
                store.forPackage(tapped.packageName).forEach { store.remove(it.key) }
                true
            },
            preAuthorize = { authorizedPackage = it },
            revokeAuthorization = { authorizedPackage = null },
            sendAction = {
                dispatchedAction = it
                dispatchCount++
                NotificationActionDispatchResult.DISPATCHED
            },
        )

        assertEquals(NotificationActionDispatchResult.DISPATCHED, result)
        assertEquals(1, dispatchCount)
        assertEquals(tapped.packageName, authorizedPackage)
        assertEquals(tapped.captureToken, dispatchedAction?.captureToken)
        assertEquals(listOf(snapchat), store.forPackage(snapchat.packageName))
        assertEquals(NotificationActionDispatchResult.UNAVAILABLE, dispatcher.dispatch(
            packageName = tapped.packageName,
            notificationKey = tapped.key,
            captureToken = tapped.captureToken,
            restorePackage = { true },
            preAuthorize = { authorizedPackage = it },
            revokeAuthorization = { authorizedPackage = null },
            sendAction = {
                dispatchCount++
                NotificationActionDispatchResult.DISPATCHED
            },
        ))
        assertEquals(1, dispatchCount)
    }

    @Test
    fun failedRestoreDoesNotAuthorizeOrDispatchAction() {
        val store = LockedNotificationStore()
        val notification = notification("instagram-1", "com.instagram.android")
        store.put(notification)
        var authorized = false
        var dispatched = false

        val result = AuthenticatedNotificationActionDispatcher(store).dispatch(
            packageName = notification.packageName,
            notificationKey = notification.key,
            captureToken = notification.captureToken,
            restorePackage = { false },
            preAuthorize = { authorized = true },
            revokeAuthorization = { authorized = false },
            sendAction = {
                dispatched = true
                NotificationActionDispatchResult.DISPATCHED
            },
        )

        assertEquals(NotificationActionDispatchResult.RESTORE_FAILED, result)
        assertFalse(authorized)
        assertFalse(dispatched)
    }

    @Test
    fun dispatchRejectsPackageMismatch() {
        val store = LockedNotificationStore()
        val notification = notification("instagram-1", "com.instagram.android")
        store.put(notification)
        var dispatched = false

        val result = AuthenticatedNotificationActionDispatcher(store).dispatch(
            packageName = "com.snapchat.android",
            notificationKey = notification.key,
            captureToken = notification.captureToken,
            restorePackage = { true },
            preAuthorize = {},
            revokeAuthorization = {},
            sendAction = {
                dispatched = true
                NotificationActionDispatchResult.DISPATCHED
            },
        )

        assertEquals(NotificationActionDispatchResult.UNAVAILABLE, result)
        assertFalse(dispatched)
    }

    private fun notification(
        key: String,
        packageName: String,
        text: String = "message",
    ) = LockedNotification(
        key = key,
        captureToken = "$key:$text",
        packageName = packageName,
        originalId = key.hashCode(),
        originalTag = null,
        appLabel = packageName,
        smallIcon = null,
        sourceAppIcon = null,
        sourceAppLargeIcon = null,
        title = "title",
        text = text,
        contentIntent = null,
        postedAtMillis = key.hashCode().toLong(),
        groupKey = null,
        isGroupSummary = false,
        number = 0,
        category = null,
    )
}
