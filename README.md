# QuickLock: App Lock for Samsung (One UI)

Kotlin + Jetpack Compose, no root, no internet access at all.

## 1. What Android does and doesn't allow (read this first)

Android has no public "lock this app" API. Apps are sandboxed, and one app can't draw over or intercept another
without help from the OS. Every non-root app locker therefore works the same way:

1. **Notice** that a protected app just came to the foreground.
2. **Cover** it with its own full-screen lock screen.

For step 1 the options are:

| Approach | Verdict |
|---|---|
| `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED`) | **Used here.** Event-driven, so the CPU is idle between events. Reports the foreground package instantly. Needs a user-granted accessibility permission. |
| `UsageStatsManager` polling | Works, but needs a timer every ~100-500 ms (battery drain, noticeable delay) plus the "usage access" permission. Rejected. |
| `getRunningTasks` / `ActivityManager` | Removed for third-party apps since Android 5. |
| Overlay (`SYSTEM_ALERT_WINDOW`) | Only useful for step 2; adds a scarier permission and is unreliable on Android 12+. Not needed. |
| Device Owner / MDM / root | Not available to ordinary apps. |

For step 2, the service starts `LockActivity` (a separate task, excluded from recents, `FLAG_SECURE`). Accessibility
services are allowed to start activities from the background; this is the only reason the approach works on Android 10+.

### Things this app **cannot** do (and doesn't pretend to)

* **Cannot prevent force-stop, or turning off the accessibility service.** The user can always do this in Settings.
  Mitigation: **Protect Settings & uninstall** (on by default) puts system Settings and the package-installer dialogs
  behind the same lock, so a casual user hits an authentication wall first. It does not stop the Quick Settings
  accessibility shortcut, safe mode, `adb`, or a factory reset.
* **Cannot prevent uninstall.** The same protection covers the uninstall *confirmation dialog*, nothing more. The
  OS decides who may uninstall an app. A Device Admin app only blocks uninstall while admin is active, and the
  user can deactivate it. I deliberately did not add that extra permission.
* **Cannot cover the app before its first frame.** The lock appears a moment after the protected app's window
  does (typically tens of milliseconds). The lock theme paints an opaque background immediately to keep this short, but a
  frame of the app may flash on slower devices.
* **Notification filtering is optional and requires explicit notification access.** When enabled, QuickLock removes
  notifications from apps in the protected-app list. Removed notifications are not saved or restored when you unlock
  an app. QuickLock cannot hide protected apps' recents thumbnails.
* **Cannot survive being killed by the OS.** If Samsung force-stops the process, state resets to "everything
  locked" (the safe default), but the accessibility service may stay disabled until re-enabled. See the battery steps below.
* After a keyguard unlock, Android may not emit a window event for the app that reappears. QuickLock handles this
  by listening for `ACTION_USER_PRESENT` and re-checking the last foreground app. If your device behaves differently,
  that's the first place to look (`AppLockService`).

## 2. Setup

### Build
1. Open the `QuickLock` folder in **Android Studio** (Ladybug or newer). JDK 17 is bundled.
2. Let Gradle sync. The wrapper pins Gradle 8.9; Android Studio downloads it. (To get `./gradlew`, run `gradle wrapper` once.)
3. Plug in a Samsung phone (USB debugging on), pick the `app` configuration, press **Run**.
   * If the install or `adb` fails: *Settings > Security and privacy > Auto Blocker* and turn off
     "Block commands by USB cable" while developing.
4. `./gradlew test` runs the lock-engine unit tests. `./gradlew assembleRelease` builds a minified, locally-signed APK.

> I wasn't able to compile or run this in my authoring environment (no Android SDK or Maven access there).
> The code was desk-checked carefully, but expect to fix a stray version or import if Android Studio flags one.
> `compileSdk`/`targetSdk` are 35; bump them and the AGP/Compose versions in `build.gradle.kts` as you like.

### First run on the phone
1. Create a **PIN or pattern**.
2. **Settings tab > Accessibility service > Enable**, then pick *QuickLock* and switch it on.
   *Sideloaded build on Android 13+ and the switch is greyed out?* Open **App info > ⋮ > Allow restricted settings**
   (the in-app *Open app info* button takes you there), then retry.
3. **Settings tab > Hide locked-app notifications > Enable** to open Android's Notification access screen, then allow
  *QuickLock Hide Locked-App Notifications*. This is optional; Android warns that notification listeners can access
  notification content. QuickLock only checks the posting app and removes notifications for protected packages.
4. **Samsung battery settings** (important for reliability):
   * *Settings > Battery > Background usage limits*: turn **off** "Put unused apps to sleep" (or add QuickLock to **Never sleeping apps**).
   * *Settings > Apps > QuickLock > Battery* > **Unrestricted**. (The *Fix* button opens the list.)
5. **Apps tab**: flip the switch on every app to protect. Protected apps move to the top of the list. Choose the **Relock** behaviour in Settings.

## 3. How it works

```
AppLockService (AccessibilityService, main thread, event-driven)
  └─ TYPE_WINDOW_STATE_CHANGED → package name
        ├─ ignore: our LockActivity, keyboards, System UI/shade, permission & share dialogs, Samsung edge panel
        └─ LockEngine.onForeground(pkg)  (pure Kotlin, HashMap lookups, unit-tested)
              └─ true → start LockActivity(pkg)
LockActivity: AuthPanel (PIN auto-submits on last digit / pattern) + BiometricPrompt (fingerprint, STRONG only)
              → engine.onUnlocked(pkg) → finish → app is underneath
LockedAppNotificationListener: removes posted notifications from packages in the protected-app list (if access is enabled)
Screen off → all sessions cleared. Keyguard dismissed → current app is re-checked.
```

**Relock modes:** immediately after leaving the app, 15 s / 30 s / 1 min / 5 min grace, or only when the screen turns off.

**Reboot:** Android re-binds enabled accessibility services after boot, so no boot receiver or extra permission is needed.
State lives in memory only, so a reboot or process kill means everything is locked again.

**QuickLock's own UI** is behind the same authentication (30 s grace after you leave it), so nobody can browse or
edit the protected list or settings. Opening system Settings from inside QuickLock grants a one-shot, 8-second pass so you aren't prompted twice.

### Security design
* PIN/pattern → `HMAC-SHA256(Keystore key, PBKDF2-HMAC-SHA256(secret, random salt))`. The key is non-exportable and
  StrongBox/Knox-Vault-backed when available, so a copied preferences file can't be brute-forced offline.
* 5 wrong attempts → 30 s lockout, doubling each further 5 failures, capped at 1 h.
* Fingerprint uses `BiometricPrompt` with `BIOMETRIC_STRONG` only (Samsung face unlock is Class 2 and is excluded).
* Authentication uses the QuickLock PIN/pattern or optional strong fingerprint only; the phone's screen-lock credential is not used as a fallback. If you forget your QuickLock PIN/pattern, there is no in-app recovery.
* `FLAG_SECURE` on all screens, tapjacking protection (`filterTouchesWhenObscured`), `allowBackup=false`, no logging.

### Permissions
| Permission | Why |
|---|---|
| `USE_BIOMETRIC` | Fingerprint prompt |
| `BIND_ACCESSIBILITY_SERVICE` (service-level, granted by the user in Settings) | Foreground-app detection. Window events only, **no content access**. |
| `BIND_NOTIFICATION_LISTENER_SERVICE` (user-granted special access) | Optionally removes notifications posted by apps in the protected-app list. |

No `INTERNET`, no `QUERY_ALL_PACKAGES` (a `<queries>` launcher intent is used instead), no overlay, no usage stats, no notifications posted by QuickLock, no boot receiver.

## 4. Performance notes
* Zero polling, zero background threads (one short Keystore warm-up at service start), no foreground service or wake locks.
* Hot path per event: one `when`, a few `HashMap`/`Set` lookups, nothing allocated for the common case.
* Settings use synchronous in-memory `SharedPreferences`, so the service has correct state on its very first event (no DataStore race).
* Lock screen is its own light activity: no animations, no navigation host, no ViewModel, PIN verification on `Dispatchers.Default`.
* App list icons decode lazily off-thread into an `LruCache`. `LazyColumn` uses stable keys.
* R8 + resource shrinking enabled in release.

## 5. Manual test checklist
- [ ] Lock an app → open it → lock screen appears; correct PIN/pattern/fingerprint opens it.
- [ ] Locked apps move to the top of the Apps list as soon as their switches are enabled.
- [ ] With notification access enabled, notifications from locked apps disappear; notifications from unlocked apps remain.
- [ ] Home, then reopen (relock = immediately) → locked again. With 30 s grace → open within 30 s, no prompt.
- [ ] Show the keyboard / pull the notification shade inside an unlocked app → no relock.
- [ ] Screen off/on with a protected app in front → locked after unlock.
- [ ] Reboot → locked; the accessibility service is still enabled.
- [ ] Settings > Accessibility while *Protect Settings* is on → prompts first.
- [ ] 5 wrong PINs → lockout countdown.
- [ ] Dark/light mode, split-screen, pop-up view, and Samsung DeX.

## 6. Publishing note
Google Play requires an accessibility-API declaration and a prominent in-app disclosure for apps using
`AccessibilityService`. This one's use (foreground-app detection for an app lock) is a disclosed, narrow use. Replace
the debug `signingConfig` in `app/build.gradle.kts` with your release keystore.
