# Bulkhead

A compartmentalized browser for Android, in the spirit of Qubes OS: instead of
one browser that knows everything about you, you get separate compartments
(Personal, Work, Banking, Untrusted, plus throwaway disposables) that cannot
see each other's cookies, logins, storage or cache.

Android 9 or newer. No third-party libraries; the whole app is about 2,400
lines of Java on top of the system WebView.

## How the isolation works

| Qubes OS | Bulkhead |
| --- | --- |
| Each qube is a Xen virtual machine | Each compartment is a separate Android process with its own WebView profile directory |
| Colored window borders show which qube a window belongs to | A colored frame, status bar and name chip show which compartment you are in |
| Disposable VMs | Disposable compartments, erased from storage when closed |
| "Open in another qube" | Long-press a link, or use the menu, to send a page to another compartment or a new disposable |
| dom0 manages qubes and never browses | The launcher process manages compartments and never creates a WebView |

What is separated per compartment:

- **Process.** The manifest declares eight browser slots, each pinned to its own
  OS process (`:c0` to `:c7`). A slot's process serves exactly one compartment
  for its whole life; before a slot is reused, its old process is killed and
  confirmed dead.
- **Storage.** Before any WebView exists, the process calls
  `WebView.setDataDirectorySuffix(compartmentId)`. Cookies, logins, localStorage,
  IndexedDB, service workers and the HTTP cache all live in a directory that
  belongs to that compartment alone.
- **Links.** Nothing crosses by itself. When you send a link to another
  compartment only the address goes: no cookies, no referrer. Links arriving
  from other apps always ask which compartment to use, so an outside app cannot
  aim a link at the compartment holding your bank session.
- **Recents.** Each open compartment is its own entry in the Recents screen.

Per-compartment settings: JavaScript, third-party cookies, HTTPS-only (asks
before loading an unencrypted page), an ad-blocker opt-out, and desktop mode. Pages never get file access, location, camera or microphone,
and certificate errors are never bypassed.

### Ad blocker

One switch on the launcher turns ad blocking on or off in every compartment,
including ones that are already running (they pick the change up when you
return to them). It refuses connections to known ad and tracker servers and
hides the empty boxes they leave behind. A single compartment can opt out in
its settings if a site breaks there.

The host list is Steven Black's unified hosts list
(https://github.com/StevenBlack/hosts, MIT license), downloaded and packed by
the GitHub build each time it runs, so every new build has a current list. A
build made without it (for example in Android Studio, unless you run
`tools/make_blocklist.py` yourself) falls back to a built-in list of about 120
hosts.

### What it does not do

Be clear-eyed about this before relying on it:

- **It is not a virtual machine.** A normal Android app cannot run a hypervisor.
  All compartments belong to one app and one Android user ID, so the boundary is
  Android's process model plus the WebView renderer sandbox. That stops sites,
  trackers and stolen-session attacks from reaching across compartments. It
  would not stop an exploit that fully escapes the WebView sandbox, which could
  then read other compartments' files. Qubes survives that case; this does not.
- **One network identity.** Every compartment uses the same connection and IP
  address, and the same device fingerprint. Sites can still correlate you that way.
- **Downloads are shared.** They go to the normal Downloads folder.
- **The ad blocker works by server name.** Ads served from the same server as
  the page itself, such as YouTube's video ads, get through, and some sites
  detect blocking and complain.
- **Swiping a disposable away from Recents** (instead of using Close and
  destroy) leaves its files on the device until Bulkhead next runs, when they
  are erased.

For a stronger boundary on Android, pair this with a separate user profile or
Private Space, which gives real per-user kernel isolation.

## Using it

- Tap a compartment to open it. Tap the colored name chip in the browser to
  return to the list and switch.
- **New disposable** opens an empty compartment that is erased when you choose
  **Close and destroy** from its menu.
- Long-press a link for: open in new tab, open in new disposable, open in
  another compartment, copy.
- The menu's **Open this page in…** moves the current page somewhere else.
- In the list, the three-dot menu on a compartment lets you edit its settings,
  stop it, wipe its data, or delete it.
- To be asked which compartment to use for every link you tap in other apps,
  set Bulkhead as the default browser in Android's settings.
- Up to eight compartments can run at once.

## Building the APK

You need the Android SDK, which the build downloads parts of on first run.

**GitHub, no computer needed.** Put this folder in a GitHub repository on the
`main` branch. The included workflow (`.github/workflows/build.yml`) builds the
app and publishes `Bulkhead.apk` under the repository's Releases as "Latest
build". Open that page on your phone and download it.

**Android Studio.** Open this folder, let it sync, then Build > Generate App
Bundles or APKs > Generate APKs.

**Command line.** With JDK 17 or newer and the Android SDK installed
(`ANDROID_HOME` set):

    ./gradlew assembleRelease

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

### Signing

Builds are signed with the key in `keystore/bulkhead.keystore` (password
`bulkhead`), so each new build installs over the previous one without losing
your compartments. That key is in this folder in plain sight, so it protects
nothing: before publishing the app anywhere, generate your own and update
`app/build.gradle`.

## Status

The app builds with the real Android toolchain on GitHub, and the
slot-allocation, wipe and ad-block matching logic is covered by scenario tests
run against stand-ins for the Android classes. It has had little testing on
real devices, so expect rough edges.

## Layout

    app/src/main/AndroidManifest.xml     processes, tasks and exported surface
    app/src/main/java/app/bulkhead/
        App.java              per-process startup
        MainActivity.java     launcher: list, add, edit, stop, wipe, delete
        BrowserActivity.java  the browser that runs inside each compartment
        Slot.java             the eight per-process subclasses
        Slots.java            slot assignment, teardown, cleanup
        Store.java            compartment list and data wiping
        Compartment.java      the model
        RouterActivity.java   links from other apps: always asks where to open
        OpenActivity.java     internal hand-off between compartments
        CleanupReceiver.java  erases a compartment after its process has died
        Blocklist.java        ad blocker host lists
    tools/make_blocklist.py              packs a hosts file into the app's list format
        Ui.java               view and URL helpers
