# TV Home

A tiny, fast, ad-free home screen for Android TV / Google TV. Dark background, a clock, and a grid of
your apps. No recommendations, no accounts, no network access, no background work.

Built for a TCL 55C655 Pro (Google TV, Android 12, ~2 GB RAM), where heavier launchers were laggy.

## Design goals

- **Light:** zero dependencies (plain Android framework only), one activity, no polling.
- **Snappy:** the only animation is a 110 ms scale on the focused tile.
- **Private:** the app declares no INTERNET permission, so it cannot send anything anywhere.
- **Simple:** long-press OK on a tile for *Move to front*, *Hide from home*, *App info*.

## Get the APK (no Android Studio needed)

1. Push this repo to GitHub.
2. Open the **Actions** tab, pick the latest **Build APK** run (it runs on every push, or press
   *Run workflow*).
3. Download the `tv-home-debug-apk` artifact and unzip it to get `app-debug.apk`.

## Install on the TV

With ADB connected (`adb connect <tv-ip>:5555`):

```
adb install -r app-debug.apk
adb shell cmd package set-home-activity app.tvhome/.HomeActivity
```

Press Home. You should land on TV Home.

### Go back to Google's launcher

```
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
```

## Safety rule

**Do not disable `com.google.android.apps.tv.launcherx`** unless this launcher is confirmed to win the
Home button first. On this TV, with the stock launcher disabled, Android picked
`com.google.android.tungsten.setupwraith/.RecoveryActivity` for Home and the button went dead.
If that ever happens: `adb shell pm enable com.google.android.apps.tv.launcherx`.

## Troubleshooting

- **Home still opens Google's launcher:** the TV may open it by name. Check which activity answers Home:
  `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
- **An app is missing:** it has no launcher entry. Sideloaded apps normally do.
- **Build fails in Actions:** open the failed run, copy the red error lines, and fix or ask for help.

## Status

First version, written without a local build. Expect small fixes after the first CI run.
