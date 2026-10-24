# TV Home

A tiny, fast, ad-free home screen for Android TV / Google TV, in the spirit of tvOS:

- a row of large app tiles (pinned apps first),
- a **Continue watching** shelf fed by Android TV's standard *Watch Next* list (hidden when empty),
- the rest of your apps in a smaller grid.

No ads, no accounts, no analytics, no background work.

Built for a TCL 55C655 Pro (Google TV, Android 12, ~2 GB RAM), where heavier launchers were laggy.

## Design goals

- **Light:** zero dependencies (plain Android framework only), one activity, no polling.
- **Snappy:** the only animation is a 110 ms scale on the focused tile.
- **Private:** see "Permissions" below. Every permission is listed and explained.
- **Simple:** long-press OK on an app tile for *Move to front*, *Hide from home*, *App info*.

## Permissions

| Permission | Why |
|---|---|
| `READ_EPG_DATA` | Read the Watch Next list that apps like Netflix and YouTube publish. Needs a one-time `adb` grant. Without it the shelf stays hidden. |
| `INTERNET` | Download the poster images that those Watch Next entries point to. **HTTPS only**; plain `http` is refused. Nothing else is ever sent or fetched, and there is no analytics. |

If you do not want any network access, delete the `INTERNET` line in `AndroidManifest.xml`. The shelf will
then only show posters that apps provide as local `content://` images.

## Get the APK (no Android Studio needed)

1. Push this repo to GitHub.
2. Open the **Actions** tab, pick the latest **Build APK** run (it runs on every push, or press
   *Run workflow*).
3. Download the `tv-home-debug-apk` artifact and unzip it to get `app-debug.apk`.

The workflow file is saved as `build-workflow.yml`; move it to `.github/workflows/build.yml`.

## Install on the TV

With ADB connected (`adb connect <tv-ip>:5555`):

```
adb install -r app-debug.apk
adb shell cmd package set-home-activity app.tvhome/.HomeActivity
adb shell pm grant app.tvhome com.android.providers.tv.permission.READ_EPG_DATA
```

Press Home. The last command is only for the shelf; if it errors, the shelf just stays hidden.

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

- **Home still opens Google's launcher:** check which activity answers Home:
  `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
- **No "Continue watching" shelf:** nothing is in Watch Next yet (watch something in Netflix or YouTube first),
  or the grant failed. Check with
  `adb shell "content query --uri content://android.media.tv/watch_next_program --projection title:package_name"`.
- **An app is missing:** it has no launcher entry. Sideloaded apps normally do.
- **Build fails in Actions:** open the failed run, copy the red error lines, and fix or ask for help.

## Status

v0.2, written without a local build. Expect small fixes after each CI run.
