package com.quicklock.applock.service

enum class NotificationActionDispatchResult {
    DISPATCHED,
    ALREADY_HANDLED,
    UNAVAILABLE,
    RESTORE_FAILED,
    ACTION_UNAVAILABLE,
    ACTION_CANCELED,
    ACTION_DENIED,
    DESTINATION_UNAVAILABLE,
}

class AuthenticatedNotificationActionDispatcher(
    private val store: LockedNotificationStore,
) {
    fun dispatch(
        packageName: String,
        notificationKey: String,
        captureToken: String,
        restorePackage: () -> Boolean,
        preAuthorize: (String) -> Unit,
        revokeAuthorization: (String) -> Unit,
        sendAction: (NotificationTapAction) -> NotificationActionDispatchResult,
    ): NotificationActionDispatchResult {
        val action = store.tapAction(notificationKey, captureToken)
            ?.takeIf { it.packageName == packageName }
            ?: return NotificationActionDispatchResult.UNAVAILABLE
        if (!store.claimTapAction(action)) return NotificationActionDispatchResult.ALREADY_HANDLED

        var authorized = false
        var dispatched = false
        try {
            if (!restorePackage()) return NotificationActionDispatchResult.RESTORE_FAILED
            preAuthorize(packageName)
            authorized = true
            val result = sendAction(action)
            dispatched = result == NotificationActionDispatchResult.DISPATCHED
            return result
        } finally {
            if (authorized && !dispatched) revokeAuthorization(packageName)
            store.finishTapAction(action)
        }
    }
}
