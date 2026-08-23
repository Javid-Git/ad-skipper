# What this app accesses, and what it does

An accessibility service is a genuinely privileged thing to install, so this
document is precise about the boundaries. Everything marked **verified** below
was checked against the running app on a device, and the command used is shown
so you can re-check it yourself.

## The short version

> It watches one app, reads what is on screen while that app is in the
> foreground (including YouTube picture-in-picture), and performs exactly one
> click on the Skip Ad button.
> Its four permissions exist only to show an ongoing notification and the
> platform's own battery dialog, and grant no access to data. It does not have
> `INTERNET`, so the Android kernel will not let it open a network connection,
> and nothing it sees can leave the phone.

## What it can access

| | |
| --- | --- |
| **Apps it sees** | `com.google.android.youtube`, and nothing else. `packageNames` filters events from every other app, and the adaptive monitor checks a window's package before traversing its node tree. |
| **When it runs** | A low-rate package check runs about once per second; while a YouTube or YouTube PiP window exists, the node tree is scanned about every 300ms. |
| **What it reads** | The accessibility node tree of YouTube windows: the text, content descriptions, view IDs, bounds and clickable/enabled flags of on-screen views. This is the same information TalkBack reads aloud. |
| **What it changes** | One thing: a node click, or one exact-bounds tap, on a single skip control it identified. |
| **What it shows** | One silent, ongoing notification, for exactly as long as the accessibility service is connected. It carries no screen content and does no work; it exists to be visible. See [Resource cost](#resource-cost). |

`flagIncludeNotImportantViews` is set, which widens the tree it reads *within
YouTube* to include views marked unimportant for accessibility. It is needed
because the skip button's clickable ancestor is sometimes marked that way, and
would otherwise be invisible to the service.

## What it cannot access

| Boundary | Status |
| --- | --- |
| Network | **Verified impossible.** The app does not declare `INTERNET`, so it never gets GID `3003` (`AID_INET`). Android enforces `INTERNET` at the kernel level via that group, so socket creation fails regardless of what the code asks for. |
| Any permission that reads data | **None.** The four it declares — `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — exist to show the keep-alive notification and the platform's own battery-exemption dialog. None maps to a supplementary GID, and none is a runtime data permission. |
| Screenshots | Requires `canTakeScreenshot` / `ACTION_TAKE_SCREENSHOT`. Not declared, not used. |
| Notifications | Requires the `typeNotificationStateChanged` event type. Not registered. |
| Other apps' screens | Events are excluded by `packageNames`; polled windows are package-checked before their trees are traversed or clicked. |
| Typing, swiping, scrolling, back/home | The code performs no gesture other than the one exact-bounds fallback tap on the matched skip control. |
| Files, contacts, accounts, clipboard, camera, mic, location | All require permissions it does not declare. |
| Backup / cloud sync of app data | `allowBackup="false"` in the manifest. |
| Your screen contents in logs | Off by default. Node text is logged only after you explicitly opt in (see below). |

Verify the first two yourself:

```bash
adb shell dumpsys package dev.javid.adskipper | grep -i requestedPermissions
adb shell 'for p in $(pidof dev.javid.adskipper); do grep -i groups /proc/$p/status; done'
```

The first prints exactly four entries — `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — and no `INTERNET`. The
second prints a group list with no `3003` in it, which is the check that
actually matters: the kernel, not the manifest, is what makes the network
unreachable.

### Being straight about the privilege

The *platform* would allow an accessibility service to do much more than this —
read every app, log keystrokes, press buttons anywhere. What limits this one is
its configuration (one package), its permission set (four, none of which reach
data, and no `INTERNET`), and the roughly 1,000 lines of code — comments
included — that you can read in one sitting. That is a meaningful boundary, but it is a
boundary you are trusting **this build** to hold. Which is exactly why you
should build it yourself and never sideload someone else's APK of it.

## The flow

```
YouTube UI event or adaptive monitor tick
        │
        │  event package filter / window package check: YouTube?   ─── no ──▶ inspect next window
        ▼
scan active and interactive windows
        │
        ├─ in a backoff period?          ─── yes ──▶ ignore
        ├─ clicked in the last 1.5s?     ─── yes ──▶ ignore
        ├─ scanned in the last 200ms?    ─── yes ──▶ ignore
        ▼
read a YouTube window's node tree
        │
        ├─ is this window's package YouTube?  ─── no ──▶ inspect the next window
        ▼
find the skip control
        │
        ├─ 1. by view ID: skip_ad_button, ...        ◀── optional when present
        ├─ 2. by unambiguous label: "skip ad", ...
        └─ 3. by generic label "skip", with a control-like node/ancestor
        │
        ├─ nothing matched?  ─── ▶ wait for the next 300ms scan
        ▼
walk up to the nearest clickable ancestor (max 8 hops)
        │
        ├─ none found?  ─── ▶ wait for the next 300ms scan
        ▼
performAction(ACTION_CLICK)
        │
        ├─ succeeds ──▶ record the click, start cooldown, count it against the guard
        └─ refused or unavailable ──▶ tap the matched node's exact bounds, then record it
```

View IDs are optional. YouTube server-side experiments and device builds may
render the same ad control with no resource ID, leaving only text or a content
description to match.

## What-ifs

| If this happens | What the app does |
| --- | --- |
| A node goes stale mid-scan (YouTube tears down a window while we read it) | The exception is caught and that one scan is dropped; the next monitor scan retries. An uncaught exception would crash the app, and **Android switches an accessibility service off when it crashes**, leaving you to re-enable it by hand. |
| Something worse than an exception escapes a scan (`StackOverflowError` on a pathological tree, `OutOfMemoryError`) | The next scan is rescheduled from a `finally` block, so the monitor loop survives anything throwable and the Error still propagates. Without that, the loop would stop for good while the service stayed bound and the keep-alive notification kept claiming it was working. |
| A match fires on something that is not the skip button, and keeps re-appearing | The runaway guard trips after 6 clicks in a minute and stands the service down for a minute, with a warning in the log. It does not sit there poking the UI. |
| The same event arrives twice, or YouTube is slow to remove the overlay | The 1.5s cooldown prevents a second click landing on whatever replaced the button. |
| A video is literally titled "Skip" | Not clicked. A bare `skip` match requires a clickable node, a skip-named ID, or a described control with a clickable ancestor, which a plain video title is not. |
| YouTube renames the button in an update | Skipping silently stops. Nothing breaks or crashes. See the maintenance table in the [README](README.md#when-youtube-changes-its-ui). |
| YouTube plays in picture-in-picture and another app is foreground | Interactive-window lookup can still find YouTube's PiP tree and uses that window's bounds. |
| The ad is not skippable | There is no button, so no match, so nothing happens. Bumper and non-skippable ads are unaffected. |
| The screen is off but audio is still playing | It still skips. This is deliberate — the hands-off case is the whole point. |
| `ACTION_CLICK` is refused by the node | A single exact-bounds tap is attempted on the matched skip node; if that is unavailable, the failure is logged and retried on the next monitor scan. |
| The device is out of memory | The process is killable. Android re-binds enabled accessibility services automatically, so it comes back. The keep-alive notification raises the process out of the "nothing user-visible here" bucket, but does not make it unkillable. |
| A device memory cleaner sweeps the app (a "clear all apps" action) | Those are force stops. The binding dies, the stored setting does not, and a stopped package cannot be re-bound until you open the app. The app screen detects exactly this and says the service was stopped by the system rather than showing a false OFF. See [Keeping it running](README.md#keeping-it-running). |
| Accessibility settings show the toggle ON but nothing is skipped | The setting and the binding have desynced — that is the state above. Toggle the service off and back on to rebind it. |
| The service is bound with full capabilities but the platform serves it nothing | Happens after a force stop: the component lands in the platform's crashed set and on some vendor ROMs that flag survives the rebind. Nothing else on the device reveals it — it still appears under `Bound services` with `capabilities=33`. The service detects it by the window count (a healthy service always sees at least the status and navigation bars; a blind one sees zero), and changes both the notification and the app screen to say it has stopped working. Confirmed over three consecutive reports — about 20s, since the first fires on connect — and only while the screen is on, so a transient blip cannot raise a false alarm. Toggle off and on to clear it. |
| You deny the notification permission | The service still runs, but on Android 13+ its notification is not displayed. Skipping is unaffected; most of the protection against cleaners is lost, since what they skip over is the *visible* ongoing notification. The app asks once per launch and never nags. |
| The platform refuses to promote the keep-alive service | Logged at warn level and the service stops itself rather than lingering as an invisible background service. Skipping is unaffected. |
| Accessibility settings won't open on a vendor ROM | The button shows a toast with the manual path instead of crashing. |
| The app is reinstalled or updated | The service stays enabled — the component name is unchanged. Verified across an `adb install -r`. |

## Logging

By default the app logs only that a skip happened, never what was on screen:

```
I AdSkipper: Skipped ad (enable `adb shell setprop log.tag.AdSkipper DEBUG` for details)
```

Screen text and view IDs appear only after you opt in for a debugging session:

```bash
adb shell setprop log.tag.AdSkipper DEBUG
adb logcat -s AdSkipper:V
```

That is also how you find the current view ID when YouTube changes it. The
property resets on reboot.

## Resource cost

Measured on a real device with the service enabled, YouTube visible, and the
300ms polling interval active for approximately 188 seconds:

Re-measured on a POCO X3 Pro (`vayu`, HyperOS) with the service enabled, the
keep-alive notification showing, and YouTube in the foreground.

| Metric | Value | What it means |
| --- | --- | --- |
| **CPU** | **1.56 CPU seconds / ~188 wall seconds (~0.83% of one core)** | Measured while 300ms polling was active. Polling continues while YouTube is visible, including feed browsing, not only during ads. Unchanged — the scanning work did not change. |
| **Total PSS** | **25.3 MB** | Whole app in one process. Not directly comparable with the 15.3 MB recorded before: that figure covered the separate `:accessibility` process **alone** and excluded the launcher activity's process entirely. |
| **Total RSS** | **142.4 MB** | Resident pages including shared Android pages; expected to be far higher than PSS. |
| **Java heap** | **8.7 MB** | Java-managed heap reported by the device. |
| **Process** | **`dev.javid.adskipper`** | Single process. A separate `:accessibility` process was tried and removed: vendor cleaners sweep per package, so it was killed anyway, and the keep-alive notification can only protect the process it lives in. |
| **oom band** | **`vis` (visible, adj ≈ 100), flags `F/S/FGS`** | See below — this did **not** improve when the foreground service was added. |

That last row is worth being blunt about. The foreground service did **not**
move the process into a better kill band: an accessibility service is bound by
`system_server` and was already sitting at `vis`/100 before the change. So the
keep-alive buys nothing against low-memory reclaim that was not already there.
Its entire value is the *visible ongoing notification* that vendor cleaner
heuristics skip over. If you are weighing whether the permissions are
worth it, weigh them against that and nothing else.

Reproduce the band with:

```bash
adb shell dumpsys activity oom | grep -i adskipper
```

The no-network claim was verified on the same device at the same time. The
process runs with `gids={50441, 20441, 9997}` — app-specific groups and
`AID_EVERYBODY`, with **no `3003`**, so the kernel refuses it a socket.

Reproduce it:

```bash
adb shell dumpsys meminfo dev.javid.adskipper
```

For scale: 25.3 MB PSS is small compared with the memory footprint of YouTube
itself, but the CPU measurement should be read together with the battery tradeoff:
the service deliberately polls throughout the time a YouTube window is visible.
It does not claim that battery impact is zero; battery drain was not measured in
this run.

The service has no wake lock, no network, and no dependencies. Outside YouTube
it performs only a low-rate package/window check; while YouTube is visible it
scans every 300ms so sparse accessibility events do not make the skip window
disappear unnoticed.

It does hold one silent, ongoing notification while enabled — see
[Keeping it running](README.md#keeping-it-running) for why, and for what that
does and does not protect against. The notification service does no work of its
own: it exists to be visible, and stops as soon as the accessibility service
disconnects.
