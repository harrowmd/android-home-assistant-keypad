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
- Full screen, screen kept on, day/night brightness, wakes on room light, a wave or activity.
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

## Screen: day and night

The screen stays on while the keypad is open (it never keeps the screen on in the background,
so on a phone you carry around it costs nothing once you leave it).

- **Day** (default 07:00–23:00): in use, the brightness follows the room light (light sensor),
  or the phone's own setting, or a fixed level. After 60 s without a touch it drops to a
  medium level (30 %).
- **Night**: idle it is very dim (3 %) with the tiles darkened too; woken, 20 %.
- Besides a touch, it brightens when the **room light goes on**, when **someone moves nearby**
  (a shadow changes the light on the light sensor; sensitivity adjustable), when a **hand comes
  within about 5 cm of the top of the phone** (proximity sensor), or when a **device on the keypad is switched
  elsewhere** (someone is in the room). Each can be switched off.
- The first touch on a very dim screen only wakes it, so nothing is switched by accident.
- All times and levels are in Settings → Screen.

## Colours

Settings → Colours: **Like the phone** (follows Android's Dark/Light setting), **Dark** or
**Light**, and optional own colours for the background, the text and the buttons (off and on),
from a palette or as #RRGGBB. Text on an ON button is black or white, whichever reads best.

## Wall keypad

For a phone that stays on the wall (needs device owner, see below; leave off on a phone you
carry around):

- **Open the keypad when the phone starts**: after a power cut or reboot it comes back by
  itself, without being the Home app.
- **Kiosk**: the keypad stays on screen; Home, Recents and the status bar do nothing. Settings
  (hold the title, optional PIN) still opens, and turning kiosk off there frees the phone.
- With either on, the keypad shows over the lock screen and turns the screen on, the swipe lock
  screen is switched off, and the screen stays on while charging. (Without this, a keypad that
  opened behind the lock screen after a reboot had its screen go off, and Android then cut it
  off from the Wi-Fi details and Home Assistant until someone woke the phone.)
- **Battery**: the level, charging and temperature go to the debug log every 10 minutes. Set an
  `input_number` (0–100) in Settings to send the level to Home Assistant. A phone on charge all
  the time ages its battery fast; with a smart plug on the charger, a Home Assistant automation
  can keep it between about 40 and 80 %.

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

## Debug log

Everything the keypad does is written to **Documents/HAKeypad/ha-keypad-log.txt** on the phone
(open it with any file manager, or `adb pull /sdcard/Documents/HAKeypad/ha-keypad-log.txt`):

- every press: the tile, what was sent, when Home Assistant confirmed it and when the device
  changed, or that it was held while offline, sent again, or given up
- state changes of the keypad's devices, also when they are switched elsewhere
- the connection to Home Assistant: connecting, connected, every failure with the reason
- every failed Home Assistant check (every 15 s), with the access point, signal and whether
  the router answers, plus a summary line each minute: access point (BSSID), signal,
  frequency, link speed, checks OK/lost, average and worst response time
- Wi-Fi changes and everything the Wi-Fi guard does
- app start, screen on/off, dim/wake, the red offline panel, crashes

At 4 MB the file becomes ha-keypad-log-old.txt and a new one is started. The token is never
written to the log.

## About and updates

Settings → About shows the version, the phone and where the log is. **Check for update** looks
up the latest release here on GitHub and, if it is newer, downloads and installs it (settings
are kept). A device-owner keypad updates without a question and opens again by itself; on
other phones Android asks you to confirm, and the first time to allow HA Keypad to install apps.

## Build

```
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/m21-ha-keypad.apk`.
