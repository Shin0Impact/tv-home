# TV Home

A small, fast home screen for Android TV / Google TV. No ads, no accounts, no analytics, no
background work, and no dependencies beyond the Android framework.

Built for a TCL 55C655 Pro (Google TV, Android 12, ~2 GB RAM), where heavier launchers were laggy.

## What it does

- **Hero cards** at the top: the live media session of the app you are on, its recent history, and
  Play Store top apps when the Play Store tile is highlighted. Cards slide sideways when you move
  to another app.
- **App tiles** below, pinned apps first. Long-press OK on a tile for *Move to front*, *Add to
  folder*, *Force stop*, *App info* and *Uninstall* (the uninstall button turns red and asks
  "Are you sure?").
- **Folders** on the home screen: make one from the long-press menu, then open, rename, take apps
  out, move or delete it. The folder tile shows up to four of its apps.
- **Quick menu** (remote Settings / notification button): clock, round shortcut buttons,
  notifications. It fades out for half a second when the TV's volume bar appears.
- **Netflix cards**: highlighting the Netflix tile shows what is new and popular on Netflix in your country (from TMDB), and OK opens Netflix's search for that title.
- **Double-press Home** to go back to the last app.
- **Themes** (Dark, Gray, OLED, Light, Colour, Hero art), wallpaper and search.

## Permissions

| Permission | Why |
|---|---|
| `READ_EPG_DATA` | Read Watch Next entries. This TV publishes none, so it is mostly unused. |
| `INTERNET` | Poster images, Play Store top apps. HTTPS only. |
| `SYSTEM_ALERT_WINDOW` | Draw the quick menu over other apps. |
| `KILL_BACKGROUND_PROCESSES` | The *Force stop* action. |
| Accessibility service (`KeyService`) | Remote buttons (Settings, Profile, double-Home) and tracking the current app. |
| Notification listener (`MediaListener`) | Live media cards and the notification list. |

## Credits

Netflix cards use the TMDB API but are not endorsed or certified by TMDB. The key is saved on the TV with
`set-tmdb.ps1` (it is never part of this repository).

## Get the APK (no Android Studio needed)

1. Push to GitHub. The workflow in `.github/workflows/build.yml` (source copy: `build-workflow.yml`)
   builds on every push and publishes a "Latest build" release.
2. New source files need `git add -A`.
3. `.\install.ps1 -Tv <tv-ip>` downloads and installs the latest build.

## Set up on the TV

With ADB connected (`adb connect <tv-ip>:5555`):

```
adb shell cmd package set-home-activity app.tvhome/.HomeActivity
adb shell settings put secure enabled_accessibility_services app.tvhome/app.tvhome.KeyService
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener app.tvhome/app.tvhome.MediaListener
```

Re-run the notification line after every reinstall. Also allow "Display over other apps" for
TV Home in the TV's settings.

On this TV, becoming the real Home screen also needs **both**
`com.google.android.tungsten.setupwraith` and `com.google.android.apps.tv.launcherx` disabled
(disabling only the second gives a black screen). Do not toggle setupwraith again: that once made
the TV forget its Wi-Fi password. Restore with `launcher-restore.ps1`.

### Go back to Google's launcher

```
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
```

## Troubleshooting

- **Home opens another launcher:** `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
- **Remote buttons do nothing:** check `adb shell settings get secure enabled_accessibility_services`.
- **Build fails in Actions:** open the failed run and read the red error lines.

## Status

v0.4.x. There is no local compile in the author's setup, so errors only show up in the Actions log.
