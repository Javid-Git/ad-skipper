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
2. On each UI change it looks for the skip control, preferring YouTube's own view
   IDs (language-independent) and falling back to matching the label text and
   content description.
3. Because the label itself usually isn't the clickable element, it walks up the
   node tree to the nearest clickable ancestor.
4. It calls `ACTION_CLICK` on that node — a programmatic click on a specific
   view, not a simulated tap at screen coordinates.

No artificial delay is needed. YouTube doesn't put the skip control in the view
hierarchy until the ad is actually skippable (~5s in), so the service naturally
fires at the first moment a click can succeed.

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

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`, and comes out
around 47 KB. Android Studio uses the wrapper by default, so opening the project
directory there works too.

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
~270 lines become a screen scraper that phones home. Nothing here is novel — it
is a documented Android API — but if you are reading a *fork* of this project,
diff it against upstream before you build it, and check the manifest for added
permissions.

What this build actually does is scoped as tightly as the platform allows: it
sees YouTube's package only, re-checks the foreground app before every click, and
declares **zero permissions** — which means it never receives GID `3003`, and the
kernel refuses to let it open a network socket. Nothing it reads can leave the
device.

[SECURITY.md](SECURITY.md) documents the full access boundary, the
event-to-click flow, every failure mode, and the measured RAM and CPU cost, with
a command under each claim so you can verify it rather than believe it.

## Install and enable

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then turn the service on. Android does not allow an app to enable its own
accessibility service — this step is deliberately manual:

**Settings → Accessibility → Installed apps → Ad Skipper → On**

Launching the app shows whether the service is currently running and has a
button that takes you straight to that settings page.

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

On the YouTube build this was tested against, the skip button is a `FrameLayout`
with view ID `skip_ad_button` and **no text and no content description at all**
— so only `SKIP_VIEW_IDS` matches it, and the label tiers below never fire. Check
the view ID first; it is the one that does the work in practice.

Find the skip button in `ui.xml` and add what you see to whichever list fits, in
[SkipAdAccessibilityService.java](app/src/main/java/dev/javid/adskipper/SkipAdAccessibilityService.java):

| List | Use it for | Trusted on its own? |
| --- | --- | --- |
| `SKIP_VIEW_IDS` | `resource-id` values, e.g. `skip_ad_button` | Yes — most stable, survives translation |
| `UNAMBIGUOUS_SKIP_LABELS` | Text that can only mean ad-skip, e.g. `skip ad` | Yes — matched as a prefix of normalised text |
| `AMBIGUOUS_SKIP_LABELS` | Generic words like `skip` | Only when the node is also clickable, or its view ID mentions skipping |

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
│   └── MainActivity.java                     # status screen, opens settings
└── res/
    ├── xml/accessibility_service_config.xml  # scopes the service to YouTube
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
