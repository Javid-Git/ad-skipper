# What this app accesses, and what it does

An accessibility service is a genuinely privileged thing to install, so this
document is precise about the boundaries. Everything marked **verified** below
was checked against the running app on a device, and the command used is shown
so you can re-check it yourself.

## The short version

> It watches one app, reads what is on screen while that app is in the
> foreground (including YouTube picture-in-picture), clicks the Skip Ad button,
> mutes the media stream while an ad is on screen, and dismisses the "Sponsored"
> card that covers the video by opening that card's own menu and tapping Dismiss.
> Its four permissions exist only to show an ongoing notification and the
> platform's own battery dialog, and grant no access to data. Muting needs none
> at all. It does not have `INTERNET`, so the Android kernel will not let it open
> a network connection, and nothing it sees can leave the phone.

## What it can access

| | |
| --- | --- |
| **Apps it sees** | `com.google.android.youtube`, and nothing else. `packageNames` filters events from every other app, and the adaptive monitor checks a window's package before traversing its node tree. |
| **When it runs** | A low-rate package check runs about once per second; while a YouTube or YouTube PiP window exists, the node tree is scanned about every 300ms. |
| **What it reads** | The accessibility node tree of YouTube windows: the text, content descriptions, view IDs, bounds and clickable/enabled flags of on-screen views. This is the same information TalkBack reads aloud. |
| **What it changes** | Three things. (1) A node click, or one exact-bounds tap, on a single skip control it identified. (2) The mute state of `STREAM_MUSIC` while an ad is on screen, released as the ad ends — not the ringer, notification or alarm streams, and not the volume index, which a real mute leaves to the platform. (3) For the overlay card: a click on that card's own options control, then a click on the Dismiss entry of the menu that opens, and a Back if that menu has no Dismiss entry. Nothing else, and no other app's settings. |
| **What it shows** | One silent, ongoing notification, for exactly as long as the accessibility service is connected. It carries no screen content and does no work; it exists to be visible, and to say which of three things is true: **active**, **NOT CONFIGURED**, or **INACTIVE**. See [Resource cost](#resource-cost). |

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
| Typing, swiping, scrolling, home, recents | Never. The only gesture is the one exact-bounds fallback tap on the matched skip control. |
| Back, other than to close its own menu | `GLOBAL_ACTION_BACK` is used in exactly one place: closing the ad options menu that this app opened, and only while that menu is confirmed on screen by one of its own labels. It is never pressed speculatively, because a blind Back could navigate YouTube out of the video. |
| Files, contacts, accounts, clipboard, camera, mic, location | All require permissions it does not declare. |
| Audio content, and every stream but one | Muting sets a stream's mute flag. It cannot read, record, capture or route audio — `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS` are both absent — and the only stream it touches is `STREAM_MUSIC`. It does not request audio focus either, which would let it pause other apps' playback. |
| Backup / cloud sync of app data | `allowBackup="false"` in the manifest. |
| Anything stored on disk | Four booleans in `SharedPreferences`: whether you confirmed the step the app cannot verify, whether ad muting is switched on, whether overlay-card dismissal is switched on, and whether a mute is currently held. The last one is on disk for one reason — a process killed mid-ad has to be able to give the sound back when it restarts, and an in-memory flag is exactly what does not survive that. Nothing else is written. |
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
data, and no `INTERNET`), and the roughly 2,800 lines of code — comments
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
        ├─ in a backoff period?          ─── yes ──▶ ignore (the mute lapses too)
        ├─ scanned in the last 200ms?    ─── yes ──▶ ignore
        ▼
read a YouTube window's node tree
        │
        ├─ is this window's package YouTube?  ─── no ──▶ inspect the next window
        ▼
one walk over the tree, answering three questions
        │
        ├── is an ad on screen?
        │      ├─ a label that can only mean one: "skip ad in 3", "ad will end"
        │      ├─ a counter: "Ad 1 of 2", "Ad · 0:12"      ◀── matched structurally
        │      └─ a bare "Ad" badge *and* an advertiser call to action
        │             │
        │             └──▶ mute STREAM_MUSIC, or leave it muted
        │
        ├── is the overlay card on screen?
        │      └─ a "Sponsored" label  ──▶ the two-step sequence below
        │
        └── where is the skip control?
               ├─ 1. by view ID: skip_ad_button, ...       ◀── optional when present
               ├─ 2. by unambiguous label: "skip ad", ...
               └─ 3. by generic label "skip", with a control-like node/ancestor
        │
        ├─ clicked in the last 1.5s?  ─── yes ──▶ no click this scan (muting still ran)
        ├─ nothing matched?           ─── ▶ wait for the next 300ms scan
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

The click cooldown sits *below* the ad check on purpose. It exists to stop a
second click landing on whatever replaced the button, and it has nothing to say
about audio: an ad starting inside those 1.5s should still be muted.

The same walk notes a `Sponsored` label, which is the in-video overlay card
rather than an ad being played. That runs its own two-step sequence, and every
unrecognised step ends it:

```
a "Sponsored" label was seen in the card
        │
        ├─ an attempt is already in flight, cooling down, or stood down?  ─── ▶ ignore
        ▼
find the ⋮, searching outwards from the label one ancestor at a time
        │
        ├─ candidate must name itself: exact label, or an overflow-ish view ID
        │     └─ never "any clickable node in the card" — the card's other
        │        clickable node is the advert, and clicking an advert is fraud
        │
        ├─ nothing named itself?  ─── ▶ do nothing, and log the card's contents
        ▼                              under debug logging so the real ID can be added
click it, and wait up to 2.5s for the menu
        │
        ├─ menu shows "My Ad Center" (or another ad-menu label)?
        │     ├─ no, and time is up   ──▶ give up. No Back: nothing has confirmed
        │     │                           a menu is on screen to close
        │     └─ yes
        │          ├─ a Dismiss entry?  ─── no ──▶ Back, to close the menu we opened
        │          ▼
        │        click Dismiss ──▶ done, and 15s before the next attempt
        ▼
3 attempts in 2 minutes without the card going away ──▶ stand down for 10 minutes
```

Releasing the mute is driven by the monitor tick rather than the scan, because a
scan can be skipped by any of the gates above and the audio has to come back
regardless:

```
every monitor tick (300ms with YouTube visible, 1s without)
        │
        ├─ no ad signal for 900ms?              ──▶ unmute
        ├─ stream no longer muted?              ──▶ the user overrode it: drop the claim
        ├─ muted for more than 90s?             ──▶ unmute and stand down
        ├─ muting switched off on the app screen? ──▶ unmute
        └─ service switched off / shutting down?  ──▶ unmute
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
| YouTube renames the button, or relabels its ad overlay, in an update | Skipping or muting silently stops. Nothing breaks or crashes, and a mute already held is still released normally. See the maintenance tables in the [README](README.md#when-youtube-changes-its-ui). |
| YouTube plays in picture-in-picture and another app is foreground | Interactive-window lookup can still find YouTube's PiP tree and uses that window's bounds. |
| The ad is not skippable | There is no button, so no click. It is muted for its duration instead, and unmuted as it ends — which is the whole reason muting exists, since these are the ads nothing outside YouTube can shorten. |
| You press a volume key while an ad is muted | The platform unmutes the stream, which is the user overruling this. The next tick sees the stream is no longer muted, drops the claim without touching the volume you just set, and stays out of the way until that ad is over. |
| The app is killed mid-ad | The device is left muted, and this is the one case a running instance cannot fix. The claim is written to `SharedPreferences` when the mute is taken, so the next connection finds it and restores the stream. Until the service reconnects the media volume stays muted — the same failure the keep-alive notification exists to make rarer. |
| You had already muted the phone yourself | The mute is not claimed, and so is never released. Taking it would mean unmuting a phone you had deliberately silenced, the moment the ad ended. |
| Something on screen looks like an ad but is not | Bounded twice over. The bare "Ad" badge is not trusted without a second, unrelated signal, and any single mute is released after 90s regardless, after which the audio is left alone until the signal clears. The worst case is a stretch of quiet, never a stuck mute. |
| A video is literally titled "Ad", or "Ad Astra" | Not muted on its own. The badge is matched exactly and needs an advertiser call to action alongside it; the counter form is matched structurally, so every word after "ad" has to be a number or "of", which "astra" is not. |
| The overlay card's ⋮ is not recognised | Nothing happens — the card stays, exactly as it does today. With debug logging on, the card's own nodes are dumped so the real content description or view ID can be added to `OverlayAdDismisser`. It never falls back to clicking an unidentified control, because the other clickable thing on that card is the advert. |
| A mis-identified ⋮ opens some other menu | Nothing is clicked in it. The sequence requires the menu to identify itself with an ad-menu label first, and gives up after 2.5s otherwise — without pressing Back, since nothing has confirmed there is a menu on screen to close. Whatever opened is left for you to close, which is the lesser of the two mistakes. |
| The ad menu opens but has no Dismiss entry | Back, to close the menu this app opened. A sheet across the video is worse than the card it was going to remove. |
| The card keeps coming back | Three attempts in two minutes and it stands down for ten, with a warning in the log. It does not sit there opening menus over your video. |
| The process dies with the menu open | The menu stays open until you tap it away. Nothing is left in a bad state — the in-flight attempt lives only in memory, and a restarted service starts from scratch. |
| You were mid-tap when it fired | Possible, and the reason both cooldowns exist. This runs at most one two-click sequence per card and then waits 15s. |
| The device runs at a fixed volume | `isVolumeFixed()` is true, every mute API is ignored by the platform, and this says so once in the log. Skipping is unaffected. |
| You switch muting off while an ad is muted | Released on the next tick, within about 300ms. The toggle and the service share a process, so there is nothing to propagate. |
| The screen goes off, or YouTube goes to the background, mid-ad | The ad signal stops arriving and the stream is released about a second later. Deliberately biased that way: leaving a device muted with nothing on screen to explain why is a worse outcome than a second of ad audio. |
| The screen is off but audio is still playing | It still skips. This is deliberate — the hands-off case is the whole point. |
| `ACTION_CLICK` is refused by the node | A single exact-bounds tap is attempted on the matched skip node; if that is unavailable, the failure is logged and retried on the next monitor scan. |
| The device is out of memory | The process is killable. Android re-binds enabled accessibility services automatically, so it comes back. The keep-alive notification raises the process out of the "nothing user-visible here" bucket, but does not make it unkillable. |
| A device memory cleaner sweeps the app (a "clear all apps" action) | Those are force stops. The binding dies, the stored setting does not, and a stopped package cannot be re-bound until you open the app. The app screen detects exactly this and says the service was stopped by the system rather than showing a false OFF. See [Keeping it running](README.md#keeping-it-running). |
| You enable the service from system Settings, bypassing the app's setup gate | It works, and says so honestly: the notification reads **NOT CONFIGURED** rather than **active**. The gate covers the app screen only — it cannot and does not try to block the system settings route. What it can do is refuse to claim everything is fine when the setup that keeps it running has not been done. |
| A setup check passes, then the user revokes it later | Re-evaluated at most once a minute while the service runs, and on every resume of the app screen. The notification drops to **NOT CONFIGURED** without needing a restart. |
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
I AdSkipper: Muted the music stream for an ad.
I AdSkipper: Restored the music stream (the ad is over).
I AdSkipper: Opened the overlay ad's options menu.
I AdSkipper: Dismissed the overlay ad.
```

These lines carry no screen content at any log level — not the matched label,
not the advertiser. The one place the overlay card's own text is logged is the
diagnostic dump for an unrecognised ⋮, which is behind the same opt-in as every
other content log.

They say that an ad was detected, why the audio came back, and how far the
dismissal sequence got — which is what you need to tell a working mute from a
stuck one, and a recognised card from an unrecognised one.

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
| **CPU** | **1.56 CPU seconds / ~188 wall seconds (~0.83% of one core)** | Measured while 300ms polling was active. Polling continues while YouTube is visible, including feed browsing, not only during ads. Measured **before** ad muting was added — see the note below the table. |
| **Total PSS** | **25.3 MB** | Whole app in one process. Not directly comparable with the 15.3 MB recorded before: that figure covered the separate `:accessibility` process **alone** and excluded the launcher activity's process entirely. |
| **Total RSS** | **142.4 MB** | Resident pages including shared Android pages; expected to be far higher than PSS. |
| **Java heap** | **8.7 MB** | Java-managed heap reported by the device. |
| **Process** | **`dev.javid.adskipper`** | Single process. A separate `:accessibility` process was tried and removed: vendor cleaners sweep per package, so it was killed anyway, and the keep-alive notification can only protect the process it lives in. |
| **oom band** | **`vis` (visible, adj ≈ 100), flags `F/S/FGS`** | See below — this did **not** improve when the foreground service was added. |

These figures predate ad muting and have not been re-measured with it. Two things
changed, both small and both worth stating rather than implying they are free:
the same single walk over the tree now also classifies each node's labels as an
ad signal, and a scan now runs during the 1.5s click cooldown where before it was
skipped outright — so a little more scanning happens per ad, not per minute of
browsing. Muting itself is two binder calls per ad and one preference write. None
of that was measured, so none of it is claimed.

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
