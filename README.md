# Kiosk Browser (Android)

A fully-functional kiosk web browser for Android — a lightweight, open alternative
to *Fully Kiosk Browser*. It opens a configured website in a locked-down,
full-screen WebView that users cannot exit, with a hidden PIN-protected admin panel.

## 📥 Download

Pre-built, signed APKs are published as releases here:
**[github.com/Abdunazar7/kiosk-release/releases/latest](https://github.com/Abdunazar7/kiosk-release/releases/latest)**

## Features

- **First-run setup** — on first launch the app asks for the single URL to lock to (no default search engine); only that URL opens.
- **Fullscreen toggle** — hides the status bar **and** navigation bar (immersive). Hiding the nav bar also removes easy Back/Recents access, making it much harder to leave. Toggle it in settings.
- **Kiosk Mode toggle** — a switch in settings drives screen pinning. Turn it **off to leave the kiosk**.
- **Hidden admin access** — **tap anywhere on the screen 8× quickly**, then enter the PIN (default `1234`).
- **HTTP control endpoint** — Home Assistant / curl can send commands (`screenOn`, `loadUrl`, …) — see below.
- **In-app updater** — admin menu / settings → *Check for updates* downloads and installs the latest release APK from GitHub.
- **Auto-start at boot** — relaunches after reboot. On Android 10+/Xiaomi this needs *Display over other apps* + *Autostart* (buttons in Settings → Startup &amp; permissions).
- **Screen control left to the device** — brightness and sleep follow the tablet's own settings; remote `screenOn` wakes it.
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
    MainActivity.kt            # WebView + kiosk lock + fullscreen + admin (tap 5x)
    SetupActivity.kt           # First-run screen: asks for the locked URL + PIN
    SettingsActivity.kt        # PIN-protected settings (PreferenceFragment)
    Prefs.kt                   # Typed settings access
    KioskWebViewClient.kt      # Navigation control + host whitelist
    KioskChromeClient.kt       # Progress, web permissions, file upload
    WebChromeFileResult.kt     # File-chooser result parsing
    KioskHttpService.kt        # Foreground HTTP control endpoint (Home Assistant)
    RemoteControl.kt           # RemoteCommand + service<->activity bridge
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
To remove: open the admin menu (tap screen 5× + PIN) → **Unlock & Exit**, or
`adb shell dpm remove-active-admin uz.kiosk.browser/.KioskDeviceAdminReceiver`.

## Remote HTTP control (Home Assistant)

The app runs a small HTTP endpoint (default port **2323**, password **1234**)
so home automation can control it. Configure both in **Settings → Remote
Control (HTTP)**.

```
http://<device-ip>:2323/?cmd=screenOn&type=json&password=1234
```

| `cmd`          | Action                                            |
|----------------|---------------------------------------------------|
| `screenOn`     | Wake the screen and show the kiosk                |
| `screenOff`    | Turn the screen off (needs Device Admin enabled)  |
| `loadUrl`      | Load `&url=…` in the WebView                       |
| `loadStartUrl` | Return to the configured start URL                |
| `reload`       | Reload the current page                           |
| `getInfo`      | Return device/app info as JSON                     |

Every request must include `&password=…`. Add `&type=json` for a JSON response.

### Home Assistant example

```yaml
rest_command:
  domofon_tablet_wake:
    url: "http://192.168.1.76:2323/?cmd=screenOn&type=json&password=1234"
    method: GET

automation:
  - alias: "Домофон — разбудить планшет"
    triggers:
      - trigger: state
        entity_id: binary_sensor.domofon_vyzov
        to: "on"
    actions:
      - action: rest_command.domofon_tablet_wake
    mode: single
```

> Tip: give the tablet a static IP (here `192.168.1.76`). The endpoint works
> while the screen is off because it runs as a foreground service.

## Default settings

| Setting          | Default                      |
|------------------|------------------------------|
| Start URL        | _asked on first-run setup_   |
| Admin PIN        | `1234` (set on setup screen) |
| Kiosk Mode       | on (screen pinning)          |
| Keep screen on   | off (device controls screen) |
| Start on boot    | on                           |
| HTTP control     | on, port `2323`, pass `1234` |

On first launch a setup screen asks for the URL and PIN. Change everything later
in the admin panel (**tap the screen 8× → enter PIN → Open Settings**). To leave
the kiosk, turn **Kiosk Mode** off there.
