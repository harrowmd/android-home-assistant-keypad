# HA Keypad

A simple, always-on Android wall keypad for [Home Assistant](https://www.home-assistant.io/).
Big buttons to switch lights, fans, heaters and dehumidifiers (Sonoff, Tuya or anything else
in Home Assistant), plus tiles that show temperature and humidity.

Made for an old phone mounted on a wall: it stays on, dims when nobody is using it, and can
replace the phone's home screen so it always comes back.

## Features

- Big buttons in a grid that fills the screen. **Amber = on, grey = off, dark = offline.**
- Presses are confirmed: each one waits for Home Assistant's answer. If none comes within
  2.5 s, the connection is rebuilt and the press sent again (as "turn on"/"turn off", so it is
  safe to repeat). A press made while offline is sent as soon as the connection is back
  (within 60 s), otherwise the keypad says it was not switched.
- Live state: Home Assistant's WebSocket API, so a button changes the moment the device does,
  even if it was switched from the wall, the eWeLink/Smart Life app or an automation.
- Sensor tiles (temperature, humidity, any `sensor.*`) that are shown but not pressable.
- Full screen, screen kept on, dims after a set time. The first touch on a dimmed screen only
  wakes it, so nothing is switched by accident.
- Can be set as the phone's **Home app**, so it returns after a reboot or a Home press.
  Back does nothing.
- Settings are hidden: hold the title for 2 seconds, with an optional PIN.
- Choose buttons from a list loaded from Home Assistant, reorder them and give them short labels.
- Everything is set on the phone. No server address, token or device names are built into the app.

Android 10 or newer.

## Setup

1. In Home Assistant, create a user for the keypad (a normal user, not an administrator).
2. Log in as that user (for example in the Home Assistant app on the keypad phone), open
   **Profile → Security → Long-lived access tokens → Create token**, and copy the token.
3. Install `m21-ha-keypad.apk` from the [latest release](../../releases/latest).
4. Open **HA Keypad**, tap the message (or hold the title for 2 s) and enter:
   - the server address, for example `https://home.example.com:8123`
   - the token (paste it)
5. Tap **Test connection**, then **Choose buttons**, tick the devices you want and tap **Done**.
6. Set the title (for example "Master bedroom"), then **Save**.
7. Optional: **Make this the Home app** and choose HA Keypad, so it is always on screen.

## Wi-Fi guard

In a house with several access points that share one network name, Android often stays on a
far-away access point, or keeps trying one that refuses it, and a wall keypad goes offline.
HA Keypad looks after its Wi-Fi:

- Every 15 s it checks the Wi-Fi, the signal and that Home Assistant answers.
- If Home Assistant does not answer but the router does, the Wi-Fi is fine (Home Assistant is
  restarting), so it leaves the Wi-Fi alone.
- If the phone drops off Wi-Fi for 15 s, Home Assistant can't be reached for 45 s, or the
  signal is weak and another access point of the same network is much stronger, it moves the
  phone to the access point most likely to give a steady link and keeps it there: the
  strongest, with 2.4 GHz preferred (it reaches further; speed doesn't matter for a keypad).
  Nothing needs setting up: it works with whatever access points are in reach, anywhere.
  (Optionally, Settings → Wi-Fi → Nearby APs can list preferred access points; leave it empty
  for automatic.)
- When the phone is back on Wi-Fi, the keypad reconnects to Home Assistant at once.
- After 1 minute without Home Assistant the keypad turns red, so you know to look.
- Everything it does is written to Settings → Wi-Fi → Wi-Fi log (and to logcat, tag `HAKeypad.Wifi`).

### Device owner

Since Android 10, only a *device owner* app may choose the access point or reconnect. Without it,
the guard only watches and logs. To make HA Keypad device owner (the phone must have no
accounts, e.g. no Google account):

```
adb shell dpm set-device-owner com.manytwo.hakeypad/.KeypadAdmin
```

To undo it: Settings → Wi-Fi → **Stop being device owner**.

Limits on Android 10 (Android 11+ is less strict):

- No app can switch Wi-Fi on. If someone switches it off, the keypad says so.
- Tying the network to one access point can't be undone by an app. If you stop the guard, the phone
  stays on the last access point it chose. To free it: Android Settings → Wi-Fi → your network →
  Forget, then join it again with the password.
- A device-owner app can't be force-stopped or uninstalled until device owner is removed.

## Build

```
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/m21-ha-keypad.apk`.
