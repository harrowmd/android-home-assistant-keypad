package com.manytwo.hakeypad

import android.app.admin.DeviceAdminReceiver

/**
 * Lets HA Keypad be the phone's "device owner", which Android 10+ requires before an app may
 * repair Wi-Fi (choose the access point, reconnect, restart Wi-Fi). Set it once with adb:
 *   adb shell dpm set-device-owner com.manytwo.hakeypad/.KeypadAdmin
 * and remove it again from Settings → Wi-Fi → "Stop being device owner".
 */
class KeypadAdmin : DeviceAdminReceiver()
