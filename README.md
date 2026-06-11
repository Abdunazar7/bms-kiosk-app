# Kiosk Browser (Android)

A fully-functional kiosk web browser for Android — a lightweight, open alternative
to *Fully Kiosk Browser*. It opens a configured website in a locked-down,
full-screen WebView that users cannot exit, with a hidden PIN-protected admin panel.

## Features

- **Full-screen immersive WebView** — status & navigation bars hidden.
- **Lock-task / screen pinning** — prevents leaving the app (true kiosk when set as Device Owner).
- **Configurable start URL** via the settings panel.
- **Hidden admin access** — tap the **top-right corner 7×**, then enter the PIN (default `1234`).
- **Admin menu** — open settings, reload, go to start URL, or unlock & exit.
- **Keep screen on** and show over the lock screen.
- **Auto-start on boot** (`BootReceiver`).
- **Idle reset** — return to the start URL after N seconds of inactivity.
- **Auto reload** — refresh the page on an interval.
- **Pull-to-refresh**, pinch-zoom toggle, desktop-mode user agent.
- **Web permissions** — camera, microphone, geolocation, and `<input type=file>` uploads.
- **Host whitelist** — restrict navigation to specific domains.
- **SSL-tolerant** — works behind captive portals / self-signed certs.

## Project layout

```
app/src/main/
  AndroidManifest.xml
  java/uz/kiosk/browser/
    MainActivity.kt            # WebView + kiosk lock + fullscreen + admin
    SettingsActivity.kt        # PIN-protected settings (PreferenceFragment)
    Prefs.kt                   # Typed settings access
    KioskWebViewClient.kt      # Navigation control + host whitelist
    KioskChromeClient.kt       # Progress, web permissions, file upload
    WebChromeFileResult.kt     # File-chooser result parsing
    BootReceiver.kt            # Auto-start after reboot
    KioskDeviceAdminReceiver.kt# Device-owner / admin hook
    KioskApp.kt                # Application class
  res/...                      # layouts, preferences, icons, themes
```

## Build

A self-contained toolchain was installed to `C:\kiosk-tools` (JDK 17, Gradle 8.7,
Android SDK 34). To rebuild:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

The APKs are produced at:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

Both are signed with the debug key, so they install directly on any device with
**Unknown sources** enabled.

## Install

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```
Or copy the `.apk` to the device and tap it.

## Enabling TRUE kiosk mode (Device Owner)

Screen pinning works out of the box (the system shows a confirmation). For a
locked, no-prompt kiosk, set the app as **Device Owner** on a freshly-reset
device with **no accounts**:

```powershell
adb shell dpm set-device-owner uz.kiosk.browser/.KioskDeviceAdminReceiver
```

Then lock-task mode engages silently and Home/Recents are blocked.
To remove: open the admin menu (corner 7× + PIN) → **Unlock & Exit**, or
`adb shell dpm remove-active-admin uz.kiosk.browser/.KioskDeviceAdminReceiver`.

## Default settings

| Setting        | Default                  |
|----------------|--------------------------|
| Start URL      | `https://www.google.com` |
| Admin PIN      | `1234`                   |
| Fullscreen     | on                       |
| Lock task      | on                       |
| Start on boot  | on                       |

Change them in the admin settings panel after first launch.
