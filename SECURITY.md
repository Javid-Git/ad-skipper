# What this app accesses, and what it does

An accessibility service is a genuinely privileged thing to install, so this
document is precise about the boundaries. Everything marked **verified** below
was checked against the running app on a device, and the command used is shown
so you can re-check it yourself.

## The short version

> It watches one app, reads what is on screen while that app is in the
> foreground (including YouTube picture-in-picture), and performs exactly one
> click on the Skip Ad button.
> It has no permissions at all, and the Android kernel will not let it open a
> network connection, so nothing it sees can leave the phone.

## What it can access

| | |
| --- | --- |
| **Apps it sees** | `com.google.android.youtube`, and nothing else. `packageNames` filters events from every other app, and the adaptive monitor checks a window's package before traversing its node tree. |
| **When it runs** | A low-rate package check runs about once per second; while a YouTube or YouTube PiP window exists, the node tree is scanned about every 300ms. |
| **What it reads** | The accessibility node tree of YouTube windows: the text, content descriptions, view IDs, bounds and clickable/enabled flags of on-screen views. This is the same information TalkBack reads aloud. |
| **What it changes** | One thing: a node click, or one exact-bounds tap, on a single skip control it identified. |

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
| Other apps' screens | Events are excluded by `packageNames`; polled windows are package-checked before their trees are traversed or clicked. |
| Typing, swiping, scrolling, back/home | The code performs no gesture other than the one exact-bounds fallback tap on the matched skip control. |
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
its configuration (one package), its permission set (empty), and the roughly 500 lines
of code you can read in one sitting. That is a meaningful boundary, but it is a
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
| A match fires on something that is not the skip button, and keeps re-appearing | The runaway guard trips after 6 clicks in a minute and stands the service down for a minute, with a warning in the log. It does not sit there poking the UI. |
| The same event arrives twice, or YouTube is slow to remove the overlay | The 1.5s cooldown prevents a second click landing on whatever replaced the button. |
| A video is literally titled "Skip" | Not clicked. A bare `skip` match requires a clickable node, a skip-named ID, or a described control with a clickable ancestor, which a plain video title is not. |
| YouTube renames the button in an update | Skipping silently stops. Nothing breaks or crashes. See the maintenance table in the [README](README.md#when-youtube-changes-its-ui). |
| YouTube plays in picture-in-picture and another app is foreground | Interactive-window lookup can still find YouTube's PiP tree and uses that window's bounds. |
| The ad is not skippable | There is no button, so no match, so nothing happens. Bumper and non-skippable ads are unaffected. |
| The screen is off but audio is still playing | It still skips. This is deliberate — the hands-off case is the whole point. |
| `ACTION_CLICK` is refused by the node | A single exact-bounds tap is attempted on the matched skip node; if that is unavailable, the failure is logged and retried on the next monitor scan. |
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

Measured on a real device with the service enabled, YouTube visible, and the
300ms polling interval active for approximately 188 seconds:

| Metric | Value | What it means |
| --- | --- | --- |
| **CPU** | **1.56 CPU seconds / ~188 wall seconds (~0.83% of one core)** | Measured while 300ms polling was active. Polling continues while YouTube is visible, including feed browsing, not only during ads. |
| **Total PSS** | **15.3 MB** | The most useful overall process-footprint measure because it accounts for shared pages proportionally. |
| **Total RSS** | **105 MB** | Resident pages, including shared Android pages; this is expected to be higher than PSS. |
| **Java heap** | **9.1 MB** | Java-managed heap reported by the device. |
| **Process** | **`dev.javid.adskipper:accessibility`** | The accessibility service runs separately from the launcher activity, isolating service lifetime from the app screen. |
| `oom_score_adj` | 100 | Standard service band: killable under memory pressure, and re-bound by the system afterwards. |

Reproduce it:

```bash
adb shell dumpsys meminfo dev.javid.adskipper
```

For scale: 15.3 MB PSS is small compared with the memory footprint of YouTube
itself, but the CPU measurement should be read together with the battery tradeoff:
the service deliberately polls throughout the time a YouTube window is visible.
It does not claim that battery impact is zero; battery drain was not measured in
this run.

The service has no wake lock, foreground-service notification, network, or
dependencies. Outside YouTube it performs only a low-rate package/window check;
while YouTube is visible it scans every 300ms so sparse accessibility events do
not make the skip window disappear unnoticed.
