# HA Keypad

A simple, always-on Android wall keypad for [Home Assistant](https://www.home-assistant.io/).
Big buttons to switch lights, fans, heaters and dehumidifiers (Sonoff, Tuya or anything else
in Home Assistant), plus tiles that show temperature and humidity.

Made for an old phone mounted on a wall: it stays on, dims when nobody is using it, and can
replace the phone's home screen so it always comes back.

## Features

- Big buttons in a grid that fills the screen. **Amber = on, grey = off, dark = offline.**
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

## Build

```
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/m21-ha-keypad.apk`.
