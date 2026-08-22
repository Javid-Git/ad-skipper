# What this app accesses, and what it does

An accessibility service is a genuinely privileged thing to install, so this
document is precise about the boundaries. Everything marked **verified** below
was checked against the running app on a device, and the command used is shown
so you can re-check it yourself.

## The short version

> It watches one app, reads what is on screen while that app is in the
> foreground, and performs exactly one gesture: a click on the Skip Ad button.
> It has no permissions at all, and the Android kernel will not let it open a
> network connection, so nothing it sees can leave the phone.

## What it can access

| | |
| --- | --- |
| **Apps it sees** | `com.google.android.youtube`, and nothing else. Set by `packageNames` in [accessibility_service_config.xml](app/src/main/res/xml/accessibility_service_config.xml) — the Android framework drops events from every other app before this app's code runs. |
| **When it runs** | Only when YouTube generates a UI change. No polling, no timers, no background loop. With YouTube closed the process sits at 0% CPU. |
| **What it reads** | The accessibility node tree of the foreground YouTube window: the text, content descriptions, view IDs, bounds and clickable/enabled flags of on-screen views. This is the same information TalkBack reads aloud. |
| **What it changes** | One thing: `ACTION_CLICK` on a single node it has identified as the skip control. |

`flagIncludeNotImportantViews` is set, which widens the tree it reads *within
YouTube* to include views marked unimportant for accessibility. It is needed
because the skip button's clickable ancestor is sometimes marked that way, and
would otherwise be invisible to the service.

## What it cannot access

| Boundary | Status |
| --- | --- |
| Network | **Verified impossible.** The app declares zero permissions, so it never gets GID `3003` (`AID_INET`). Android enforces `INTERNET` at the kernel level via that group, so socket creation fails regardless of what the code asks for. |
| Any permission at all | **Verified none.** Neither requested nor granted. |
| Screenshots | Requires `canTakeScreenshot` / `ACTION_TAKE_SCREENSHOT`. Not declared, not used. |
| Notifications | Requires the `typeNotificationStateChanged` event type. Not registered. |
| Other apps' screens | Excluded by `packageNames`, and re-checked in code before every click. |
| Typing, swiping, scrolling, back/home | The code performs no gesture other than a node click. |
| Files, contacts, accounts, clipboard, camera, mic, location | All require permissions. It has none. |
| Backup / cloud sync of app data | `allowBackup="false"` in the manifest. |
| Your screen contents in logs | Off by default. Node text is logged only after you explicitly opt in (see below). |

Verify the first two yourself:

```bash
adb shell dumpsys package dev.javid.adskipper | grep -i requestedPermissions
adb shell 'for p in $(pidof dev.javid.adskipper); do grep -i groups /proc/$p/status; done'
```

The first prints nothing. The second prints a group list with no `3003` in it.

### Being straight about the privilege

The *platform* would allow an accessibility service to do much more than this —
read every app, log keystrokes, press buttons anywhere. What limits this one is
its configuration (one package), its permission set (empty), and the ~270 lines
of code you can read in one sitting. That is a meaningful boundary, but it is a
boundary you are trusting **this build** to hold. Which is exactly why you
should build it yourself and never sideload someone else's APK of it.

## The flow

```
YouTube's UI changes
        │
        │  framework filters: is this com.google.android.youtube?   ─── no ──▶ dropped, code never runs
        ▼
onAccessibilityEvent
        │
        ├─ in a backoff period?          ─── yes ──▶ ignore
        ├─ clicked in the last 1.5s?     ─── yes ──▶ ignore
        ├─ scanned in the last 200ms?    ─── yes ──▶ ignore
        ▼
read the foreground window's node tree
        │
        ├─ is the foreground package still YouTube?  ─── no ──▶ stop
        ▼
find the skip control
        │
        ├─ 1. by view ID: skip_ad_button, ...        ◀── this is the path that fires in practice
        ├─ 2. by unambiguous label: "skip ad", ...
        └─ 3. by generic label "skip", only if the node is itself clickable
        │
        ├─ nothing matched?  ─── ▶ stop. This is the normal case for almost every event.
        ▼
walk up to the nearest clickable ancestor (max 8 hops)
        │
        ├─ none found?  ─── ▶ log and stop
        ▼
performAction(ACTION_CLICK)
        │
        └─ record the click, start the 1.5s cooldown, count it against the runaway guard
```

Step 5 is worth knowing about: on the YouTube build this was tested against, the
skip button is a `FrameLayout` with view ID `skip_ad_button` and **no text and no
content description at all**. Only the view-ID path finds it. The label tiers
exist as a fallback for other YouTube versions.

## What-ifs

| If this happens | What the app does |
| --- | --- |
| A node goes stale mid-scan (YouTube tears down a window while we read it) | The exception is caught and that one event is dropped. This matters more than it sounds: an uncaught exception would crash the app, and **Android switches an accessibility service off when it crashes**, leaving you to re-enable it by hand. |
| A match fires on something that is not the skip button, and keeps re-appearing | The runaway guard trips after 6 clicks in a minute and stands the service down for a minute, with a warning in the log. It does not sit there poking the UI. |
| The same event arrives twice, or YouTube is slow to remove the overlay | The 1.5s cooldown prevents a second click landing on whatever replaced the button. |
| A video is literally titled "Skip" | Not clicked. A bare `skip` match is only trusted when the node is itself clickable or its view ID mentions skipping, which a video title is not. |
| YouTube renames the button in an update | Skipping silently stops. Nothing breaks or crashes. See the maintenance table in the [README](README.md#when-youtube-changes-its-ui). |
| YouTube plays in the background or picture-in-picture and another app is foreground | The foreground-package check fails and nothing is clicked. |
| The ad is not skippable | There is no button, so no match, so nothing happens. Bumper and non-skippable ads are unaffected. |
| The screen is off but audio is still playing | It still skips. This is deliberate — the hands-off case is the whole point. |
| `ACTION_CLICK` is refused by the node | Logged, and retried on the next event after the cooldown. |
| The device is out of memory | The process is killable (`oom_score_adj` 100). Android re-binds enabled accessibility services automatically, so it comes back. |
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

Measured on a running device with the service enabled and YouTube installed:

| Metric | Value | What it means |
| --- | --- | --- |
| **Private dirty** | **~12 MB** | Memory that is genuinely this app's alone. The honest "cost to your phone" number. |
| Total PSS | ~26 MB | Includes its proportional share of shared Android framework pages, which every process on the device pays. |
| CPU, YouTube not in foreground | **0.0%** | Measured as 0 scheduler ticks over 8s and again over 10s. The `packageNames` filter means the process receives no events at all, so it is not merely idle — it is not running. |
| CPU, lifetime | **0.22 seconds** | Total CPU consumed by the service process since it started, including startup. |
| `oom_score_adj` | 100 | Standard service band: killable under memory pressure, and re-bound by the system afterwards. |

Reproduce it:

```bash
adb shell dumpsys meminfo dev.javid.adskipper
```

For scale: 12 MB private dirty is roughly what a single idle system service
costs, and it is a rounding error against the ~2 GB a phone running YouTube is
already using. There is no ongoing cost while you are not watching YouTube.

One caveat: these were taken on an emulator, so treat them as indicative rather
than exact. An earlier reading on the same emulator while it was thrashing came
out at 14 MB private / 39 MB PSS, with 7.7 MB of the app swapped out — if you
measure during memory pressure, expect inflated figures.

The reason the cost is this low is structural rather than clever: there is no
polling loop, no wake lock, no foreground-service notification, no network, and
no dependencies — the APK is 47,488 bytes and the whole app is the platform plus
about 270 lines of Java.
