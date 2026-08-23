# Ad Skipper

An Android accessibility service that clicks YouTube's **Skip Ad** button for you.
Built for hands-off moments — cooking, showering — when reaching for the phone to
tap "Skip Ad" isn't practical.

**Published as source, not as an app.** There are no prebuilt APKs in this
repository and there never will be — see [Installing this safely](#installing-this-safely)
for why that matters more here than in most projects. Build it for your own
device and use it there.

## How it works

1. The service is registered against `com.google.android.youtube` only. Events
   from every other app on the device never reach it.
2. It monitors YouTube's accessibility windows at a low rate, increasing to
   roughly every 300ms while YouTube or its picture-in-picture window exists.
3. Each scan looks for the skip control, using YouTube view IDs when available
   and falling back to label text and content descriptions. IDs are optional;
   some real-device ad overlays expose none.
4. Because the label itself usually isn't the clickable element, it walks up the
   node tree to the nearest clickable ancestor.
5. It calls `ACTION_CLICK` on that node, then on the matched node itself if the
   ancestor refuses. If YouTube exposes the control but refuses both, it falls
   back to one tap at that matched node's exact screen bounds.
6. While the service is enabled it holds a silent, ongoing notification, which
   makes the process a less attractive target for the memory cleaners some
   vendor ROMs ship. See [Keeping it running](#keeping-it-running).

The monitor does not depend on one perfectly timed content-change event. It scans
the current YouTube window and interactive PiP windows until the control appears.

## Requirements

- JDK 17
- Gradle 9.0+ (or just use the bundled wrapper)
- Android SDK with platform 36 and build-tools 36.1.0
- A physical device running Android 8.0 (API 26) or newer — emulators rarely
  serve realistic ads

Toolchain: AGP 9.3.1 on Gradle 9.7.1. The build has no dependencies at all, and
`app/build.gradle` excludes `kotlin-stdlib` because AGP 9's built-in Kotlin
support would otherwise package ~2.4 MB of Kotlin dex into a pure-Java app.

## Build

Either the wrapper or a `gradle` on your PATH works, as long as it's Gradle 9.0
or newer — AGP 9.x requires that, and `settings.gradle` checks up front and says
so rather than failing with an unreadable stack trace deep inside plugin
application.

```bash
gradle clean assembleDebug
```

The wrapper pins a known-good Gradle (9.7.1) if you'd rather not depend on
whatever is installed:

```bash
gradlew.bat assembleDebug     # Windows
./gradlew assembleDebug       # macOS / Linux
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`, and the current
debug build comes out around 56 KB. Android Studio uses the wrapper by default,
so opening the project directory there works too.

If you'd rather use Android Studio, just open the project directory — it's a
standard Gradle project. `local.properties` is machine-specific and gitignored;
recreate it with your own `sdk.dir` if you clone this elsewhere.

## Installing this safely

**Build it from source. Do not install a prebuilt APK of this from anyone —
including from me.**

That is not boilerplate caution. An accessibility service can read everything on
your screen and act on your UI. Handing that privilege to a binary you did not
compile is handing over your banking app, your messages, and your passwords, on
trust. There is no way to tell from the outside whether an APK matches the source
next to it. So this repo ships no releases, and if you ever find one claiming to
be this project, don't run it.

The same reasoning cuts the other way, too: **this code is a short edit away from
being spyware.** Widen `packageNames` to cover every app, add
`<uses-permission android:name="android.permission.INTERNET"/>`, and the same
roughly 1,000 lines become a screen scraper that phones home. Nothing here is novel — it
is a documented Android API — but if you are reading a *fork* of this project,
diff it against upstream before you build it, and check the manifest for added
permissions.

What this build actually does is scoped as tightly as the platform allows: it
reads only windows whose package is YouTube, and re-checks that package before
every scan and click.

It declares exactly three permissions, all of them for the keep-alive
notification described under [Keeping it running](#keeping-it-running):

| Permission | Why | Grants access to |
| --- | --- | --- |
| `FOREGROUND_SERVICE` | run the keep-alive service | nothing |
| `FOREGROUND_SERVICE_SPECIAL_USE` | required for its service type on API 34+ | nothing |
| `POST_NOTIFICATIONS` | show the ongoing notification | nothing |

None of the three maps to a supplementary GID or to a runtime data permission.
**`INTERNET` is deliberately absent**, which is the one that matters: without it
the app never receives GID `3003`, and the kernel refuses to let it open a
network socket. Nothing it reads can leave the device, and that is enforced
below the app rather than promised by it.

That pairing is also why `INTERNET` stays out permanently. Accessibility plus
network is the exact shape of the Android banking-trojan family — read the
screen, ship it off-device — and it is the combination scanners weight most
heavily. Keeping the socket impossible is worth more than any feature it
would buy.

[SECURITY.md](SECURITY.md) documents the full access boundary, the
monitor-to-click flow, every failure mode, and the measured RAM, CPU, and battery
tradeoff, with a command under each claim so you can verify it rather than
believe it.

## Install and enable

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then turn the service on. Android does not allow an app to enable its own
accessibility service — this step is deliberately manual:

**Settings → Accessibility → Installed apps → Ad Skipper → On**

Launching the app shows whether the service is currently running and has a
button that takes you straight to that settings page.

The app screen reports one of three states, because *enabled* and *running* are
different facts:

| Screen says | Means |
| --- | --- |
| **ON** | the platform holds a live binding to the service |
| **Stopped by the system** | your consent is still stored, but nothing is bound — see below |
| **OFF** | not enabled |

That middle state is the confusing one, and it is why the check exists.
`ENABLED_ACCESSIBILITY_SERVICES` is a persisted setting recording your consent;
it survives the process being killed, so **Accessibility settings will still
show the toggle as ON** while nothing is actually running. The app compares that
setting against `getEnabledAccessibilityServiceList()`, which reflects live
bindings, and says so plainly when the two disagree. Toggle the service off and
back on to rebind it.

The accessibility service scans every 300ms while any YouTube window is
visible, not only while an ad is playing. That is a small CPU cost, but a
deliberate battery tradeoff while browsing YouTube. See
[SECURITY.md](SECURITY.md#resource-cost) for the measured figures.

## Keeping it running

If skipping works and then quietly stops, the service was almost certainly
killed. This is worth understanding before you go looking for a bug in the
matching logic.

An accessibility service is bound by `system_server`, not started by the app.
When its process dies, that binding dies with it, but the stored setting does
not — so the toggle still reads ON. On stock Android the platform re-binds
within seconds and you never notice. What breaks that is a **force stop**: it
marks the package stopped, and a stopped package cannot be started by anything
except you opening it. Several vendor skins implement their memory cleaners as
force stops, which is why this shows up on those devices and rarely on a Pixel.

Two things help.

**The keep-alive notification.** While the service is enabled the app holds a
silent, ongoing notification. Cleaner heuristics generally skip a process that
has something user-visible attached, so this reduces how often it gets swept up.
It costs the three permissions listed above and none of them can move data off
the device. It does **not** survive a deliberate Force stop, and nothing an
ordinary app can do would.

**Per-vendor settings.** These are not standardised — there is no AOSP API for
"lock this app," and vendor settings activities get renamed between OS versions,
so the app shows instructions for your device rather than pretending it can deep
link into them. The **Open App info** button goes to the one page AOSP does
guarantee, which is where every skin hangs its own battery controls.

| Device | What to do |
| --- | --- |
| **Xiaomi / Redmi / POCO** | Recents → pull down on the card → **padlock** (this is the important one; it exempts the app from one-key clean and swipe-up clear). Then Security → Permissions → **Autostart** on, and App info → Battery saver → **No restrictions**. |
| **Samsung** | Settings → Battery → Background usage limits → **Never sleeping apps** → add it. App info → Battery → **Unrestricted**. |
| **OnePlus / OPPO / realme** | App info → Battery usage → **Allow background activity**, optimisation **Don't optimise**. Lock the card in Recents. |
| **Huawei / Honor** | Settings → Battery → **App launch** → Ad Skipper → **Manage manually**, all three switches on. |
| **vivo / iQOO** | Settings → Battery → **High background power consumption** → allow. Settings → Apps → **Autostart** → on. |
| **Stock / Pixel / Motorola / Nothing** | Usually nothing. The platform re-binds on its own. |

[dontkillmyapp.com](https://dontkillmyapp.com) tracks these per brand and per OS
version, and is a better reference than anything pinned here.

What deliberately is **not** implemented: watching for YouTube launching and
starting the service on demand. An app cannot start its own accessibility
service — that needs `WRITE_SECURE_SETTINGS`, which is `signature|privileged` —
so the only achievable outcome would be a notification saying it died, at the
cost of `PACKAGE_USAGE_STATS` (your full app-usage history) and a permanent
polling loop. The poll is also the exact behaviour vendor battery heuristics
look for, so it would make the kills more likely, not less.

## Verify it works

Open YouTube, start a video that runs a skippable ad, and watch logcat:

```bash
adb logcat -s AdSkipper:V
```

A successful skip logs `Skipped ad (enable ... for details)`. The details are
withheld on purpose — they would contain whatever text is on your screen. To see
them for a debugging session:

```bash
adb shell setprop log.tag.AdSkipper DEBUG
```

Then a skip logs the full node: `Skipped ad [id=... class=... text=... desc=...]`.
The property resets on reboot. See [SECURITY.md](SECURITY.md) for exactly what
the app reads, what it cannot reach, and what it costs in RAM.

## When YouTube changes its UI

This is the expected maintenance burden. YouTube renames view IDs and relabels
buttons between app versions, and localises the text. If skipping stops working,
turn on debug logging and dump the live hierarchy while the skip button is on
screen:

```bash
adb shell setprop log.tag.AdSkipper DEBUG
```

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
```

View IDs are useful when present, but real devices and YouTube server-side
experiments may expose an ad overlay with `resource-id=""`. In that case the
label/content-description tiers are the only available signal. Dump the live
tree on the affected device and use the actual control description or text.

Find the skip button in `ui.xml` and add what you see to whichever list fits, in
[SkipAdAccessibilityService.java](app/src/main/java/dev/javid/adskipper/SkipAdAccessibilityService.java):

| List | Use it for | Trusted on its own? |
| --- | --- | --- |
| `SKIP_VIEW_IDS` | `resource-id` values, e.g. `skip_ad_button` | Yes when present; some overlays have none |
| `UNAMBIGUOUS_SKIP_LABELS` | Text that can only mean ad-skip, e.g. `skip ad` | Yes — matched as a prefix of normalised text |
| `AMBIGUOUS_SKIP_LABELS` | Generic words like `skip` | Only for a clickable node, a skip-named ID, or a described node with a clickable ancestor |

That last distinction matters: a bare `skip` match would otherwise fire on a
video merely *titled* "Skip" and open it. Labels are normalised to lowercase
words before comparison, so `SKIP AD` and `Skip Ad ›` both reduce to `skip ad`
and neither needs its own entry.

## Limitations

- **Skippable ads only.** Non-skippable and bumper ads have no button to click,
  so nothing here can affect them.
- **English labels out of the box.** View-ID matching is language-independent and
  usually carries it, but a non-English device may need labels added.
- **Not a network-level ad blocker.** The ad still loads and plays for its first
  few seconds; this only presses the button you could have pressed yourself.
- **A force stop always wins.** Clearing Recents on ROMs that implement it as a
  force stop — Xiaomi's one-key clean and swipe-up clear among them — stops the
  service until you open the app again. The keep-alive notification does not
  change that, and nothing an ordinary app can do would. See
  [Keeping it running](#keeping-it-running).

## Possible next steps

None of these are implemented:

- A quick-settings tile or notification toggle, to pause skipping without
  digging through accessibility settings
- A counter of ads skipped
- A settings screen for editing label variants without touching code
- Support for other apps' skip buttons

## Project layout

```
app/src/main/
├── AndroidManifest.xml                       # service + activity declarations
├── java/dev/javid/adskipper/
│   ├── SkipAdAccessibilityService.java       # detection + click logic
│   ├── KeepAliveService.java                 # ongoing notification, nothing else
│   └── MainActivity.java                     # status screen, opens settings
└── res/
    ├── xml/accessibility_service_config.xml  # scopes the service to YouTube
    ├── drawable/ic_notification.xml          # status-bar glyph
    ├── layout/activity_main.xml
    └── values/strings.xml
```

## License

MIT — see [LICENSE](LICENSE).

## Legal

This automates a tap you can already perform yourself, on hardware you own. It
modifies nothing, redistributes nothing, bundles no part of YouTube, and
circumvents no access control or DRM.

That said: YouTube's Terms of Service prohibit automated interaction with the
service, so whether you run this is a matter between you and YouTube. Decide for
yourself. Nothing here is legal advice, and the MIT licence's warranty
disclaimer applies — you run this at your own risk.

Not affiliated with, endorsed by, or connected to Google or YouTube. "YouTube"
is a trademark of Google LLC, used here only to name the app this interoperates
with.
