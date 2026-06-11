package uz.kiosk.browser

import android.app.admin.DeviceAdminReceiver

/**
 * Required so the app can become a Device Owner / Admin and silently enter
 * lock-task (true kiosk) mode. Provision via adb:
 *
 *   adb shell dpm set-device-owner uz.kiosk.browser/.KioskDeviceAdminReceiver
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver()
