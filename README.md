# Ad Skipper

An Android accessibility service that presses YouTube's **Skip Ad** button on
your behalf, silences the ads that have no such button, and clears the
"Sponsored" card that parks itself over the video afterwards.

YouTube's player assumes you have a free hand every few minutes. Not everyone
does, and nobody does all of the time.

## Who this is for

The tap itself is trivial. What is not trivial is being required to produce it —
accurately, on a small target, on the player's schedule rather than your own —
every few minutes, for as long as you want to keep watching or listening.

- **Limited hand or arm movement, a tremor, or pain** that makes a small
  accurate tap expensive to produce, when the alternative is a video that
  effectively stops until you produce one.
- **Recovering in a bed or a chair**, where the phone is on a stand or a table
  and getting to it costs more than the ad does. After surgery, through an
  illness, late in a pregnancy — the shared part is that the phone should not be
  something you have to negotiate with.
- **Hands that are occupied or need to stay clean** — feeding an infant,
  changing a dressing, cooking, mid-shower.
- **Startle.** A phone that jumps from a quiet video to a loud ad is a minor
  irritation for most people and genuinely not that for everyone. Muting is the
  part of this app that answers it, and it is the part that works on the ads
  with no button at all.
- **Anyone who just cannot get to the phone right now.**

This is what the accessibility APIs exist for: performing an interaction for
someone who cannot perform it themselves. Every interaction here is one you were
already being asked to perform, on a control already on screen and already meant
to be pressed, in one app: the Skip button, and the Dismiss entry in the
"Sponsored" card's own menu. The third thing it does — muting an ad that has no
button — is the case where there is no interaction to perform at all, and reaching
the volume keys is its own small negotiation with the phone.

The service declares `isAccessibilityTool` because that is an accurate
description of it, not a convenient one.

**Source first.** Nothing binary is committed to this repository — `.gitignore`
excludes `*.apk`, `*.aab` and any signing material. Signed builds are attached to
[Releases](../../releases), but building it yourself is the better option and
[Installing this safely](#installing-this-safely) explains why that matters more
here than in most projects.

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

### Muting the ads it cannot skip

A bumper or non-skippable ad has no button, so there is nothing for the steps
above to click. The loudness is still the actual complaint, and that part does
not need a button.

The same scan that looks for the skip control also decides whether an ad is on
screen at all, and while one is, the **media stream is muted** and unmuted again
as the ad ends. It is one pass over the tree, not two — muting cannot reuse the
skip result, because the ads worth muting are exactly the ones with no skip
control to find.

- **It needs no permission.** `adjustStreamVolume` requires notification-policy
  access only for changes that would toggle Do Not Disturb, which means the
  ringer and notification streams. The music stream is not one of them, so the
  manifest is unchanged — still four permissions, still no `INTERNET`.
- **It is a real mute**, not volume-zero-and-restore. The platform keeps the
  user's volume index, so unmuting restores exactly what was there without this
  app storing it, and a volume key unmutes the stream — which is how you
  override it without arguing with it.
- **It never touches** the ringer, notifications or alarms, and it does not
  request audio focus, which would make YouTube pause or duck instead.
- **Every way out is automatic**: the ad ending, the ad signal going stale for
  0.9s, YouTube closing, a volume key, a 90s ceiling on any single mute, the
  toggle being switched off, the service being switched off, and — the one a
  running process cannot handle — being killed mid-ad, which is why the claim is
  written to disk and released on the next connect.

It helps with skippable ads too, which was not the point but is worth knowing:
the mute engages as soon as the ad is detected, so the five seconds you have to
sit through before the Skip button appears are silent as well.

There is a switch for it on the app screen, on by default. Detection is
deliberately conservative: a label that can only mean an ad ("Skip ad in 3", "Ad
1 of 2", "Video will play after ad") is trusted on its own, while the bare "Ad"
badge only counts alongside an advertiser call to action, because a video can be
*titled* "Ad".

"Sponsored" is deliberately not one of those labels, and the reason is the next
section.

### Dismissing the card it leaves behind

Once the ad is gone, YouTube slides a small **Sponsored** card over the bottom of
the player, on top of the video you are trying to watch. It has no close button.
It has a ⋮, which opens a menu containing "My Ad Center" and "Dismiss".

So this does that: opens the menu, taps Dismiss. Two clicks with a menu appearing
in between, which makes it the one feature here that is a state machine rather
than a match-and-click — and the one that needed the most care, because the
card's *other* clickable element is the advert itself. Clicking that would open
the advertiser's page and register as engagement, which is click fraud committed
on your behalf, not ad blocking.

Every rule in it exists to make that impossible:

- The ⋮ is only ever looked for **inside the card's own container**, found by
  walking outwards from the "Sponsored" label one ancestor at a time and stopping
  at the first hit. A menu button elsewhere in the player is never a candidate.
- A candidate has to **name itself** — an exact label like `more options`, or a
  view ID that mentions an overflow control. "Any clickable node in the card" is
  precisely the rule that would click the advert, so it is not the rule. Exact
  matching is also what stops `more` matching a "Learn more" call to action.
- Nothing in the opened menu is touched until the menu has **identified itself**
  as the ad menu by showing "My Ad Center" or similar. If something else opened,
  this walks away from it rather than clicking blind.
- **Back is pressed only** to close a menu this app opened and can currently see.
  Never speculatively — a blind Back could navigate YouTube out of the video,
  which is worse than any banner.
- Three attempts in two minutes without the card going away and it stands down
  for ten minutes.

If the ⋮ is not recognised on your build, nothing happens: the card stays exactly
as it is today, and with debug logging on the card's own nodes are dumped so you
can add the real content description or view ID. It has its own switch, also on
by default.

This is the piece most likely to need maintenance, because it depends on three
separate labels rather than one.

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
debug build comes out around 84 KB — 9 KB of that is ad muting and overlay
dismissal together, measured against
a build of the previous commit. Android Studio uses the wrapper by default,
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
next to it.

If you do install a release build, check who signed it before you trust it:

```bash
apksigner verify --print-certs ad-skipper-v1.1.apk
```

It must print `CN=Javid Alizada` with this certificate SHA-256:

```
59fe9a47f86b3e0533e5ab69606ef49a413e02485710d524b2de20f4480aefca
```

That fingerprint is the signing key, not the file, so it is the same for every
release and does not need re-publishing each time. An APK claiming to be this
project that reports anything else was not built by its author — do not install
it.

The same reasoning cuts the other way, too: **this code is a short edit away from
being spyware.** Widen `packageNames` to cover every app, add
`<uses-permission android:name="android.permission.INTERNET"/>`, and the same
roughly 2,800 lines become a screen scraper that phones home. Nothing here is novel — it
is a documented Android API — but if you are reading a *fork* of this project,
diff it against upstream before you build it, and check the manifest for added
permissions.

What this build actually does is scoped as tightly as the platform allows: it
reads only windows whose package is YouTube, and re-checks that package before
every scan and click.

It declares exactly four permissions, none of which reaches any data:

| Permission | Why | Grants access to |
| --- | --- | --- |
| `FOREGROUND_SERVICE` | run the keep-alive service | nothing |
| `FOREGROUND_SERVICE_SPECIAL_USE` | required for its service type on API 34+ | nothing |
| `POST_NOTIFICATIONS` | show the ongoing notification | nothing |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | show the platform's own battery-exemption dialog | nothing |

Ad muting adds nothing to that list. Muting the music stream needs no permission
at all — notification-policy access is required only for streams that can toggle
Do Not Disturb, and the music stream cannot.

None of the four maps to a supplementary GID or to a runtime data permission.
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

Then open the app. It runs a set of setup checks and **will not hand over the
route to Accessibility settings until they pass**:

| Check | How it is verified |
| --- | --- |
| The YouTube app is installed | `getPackageInfo`, via a `<queries>` entry |
| Notifications are allowed | `areNotificationsEnabled()` and channel importance |
| Battery optimisation is off | `isIgnoringBatteryOptimizations()` |
| Locked in Recents, and auto-start allowed | **not verifiable — you confirm it** |

Those last two are required, not optional — auto-start being blocked is the most
common reason the service ends up enabled but dead. They are in a separate
section on screen only because neither can be read: auto-start permissions are
vendor app-ops with no public name, and whether an app is pinned in Recents is
not exposed at all. The app says so rather than showing a tick it cannot stand
behind.

They are also a different thing from the battery check above it, which is worth
being explicit about because the two sound alike. The battery check is Android's
own Doze exemption — a standard API the app reads directly. The other two are
the device's protections against a package being force-closed, which is a
separate mechanism entirely, and no API reports them.

The gate exists because enabling the service before the device is set up to keep
it running produces an app that works for ten minutes and then stops silently —
which is worse than one that never started, because you stop checking.

It gates this screen only. Enabling the service directly from system Settings
still works, and is detected: the notification then reads **NOT CONFIGURED**.

Android does not allow an app to enable its own accessibility service, so the
final step is always manual:

**Settings → Accessibility → Installed apps → Ad Skipper → On**

Afterwards the app screen reports one of five states, because *enabled*,
*running* and *working* are three different facts:

| Screen says | Means |
| --- | --- |
| **ON** | bound, serving window content, all checks passed |
| **Setup incomplete** | working, but the checks have not been completed |
| **Cannot read the screen** | bound, but the platform serves it nothing — see below |
| **Stopped by the system** | your consent is still stored, but nothing is bound — see below |
| **OFF** | not enabled |

The notification carries the same distinction in three words: **active**,
**NOT CONFIGURED**, or **INACTIVE**.

That middle state is the confusing one, and it is why the check exists.
`ENABLED_ACCESSIBILITY_SERVICES` is a persisted setting recording your consent;
it survives the process being killed, so **Accessibility settings will still
show the toggle as ON** while nothing is actually running. The app compares that
setting against `getEnabledAccessibilityServiceList()`, which reflects live
bindings, and says so plainly when the two disagree. Toggle the service off and
back on to rebind it.

The third state is worse, because nothing else on the device reveals it. After a
force stop the platform puts the component in its crashed set, and on some
vendor ROMs that flag survives the rebind. The service then sits under `Bound
services` with full capabilities while every window query comes back empty — it
is connected, and blind. The service detects this by counting windows: a healthy
one always sees at least the status and navigation bars whatever app is in
front, and a blind one sees exactly zero. When that holds for three consecutive
reports with the screen on — about 20 seconds — the notification stops claiming
the app is active and says it has stopped working instead.

That last part is the point. A monitoring tool that quietly stops monitoring is
worse than no tool, because you go on trusting it.

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

Three things help.

**The keep-alive notification.** While the service is enabled the app holds a
silent, ongoing notification. Cleaner heuristics generally skip a process that
has something user-visible attached, so this reduces how often it gets swept up.
It costs the permissions listed above and none of them can move data off
the device. It does **not** survive a deliberate Force stop, and nothing an
ordinary app can do would.

**Letting it run in the background.** This is not standardised — there is no
AOSP API to read whether an app may auto-start, and none at all for the Recents
lock. The app deliberately contains no per-manufacturer code: vendor settings
activities get renamed between OS versions and are often unexported, so
hardcoding them fails silently on exactly the devices that need them. Instead it
uses the two destinations AOSP guarantees — the platform's own battery-exemption
dialog, and the **App info** page every skin hangs its own power controls off —
and asks you to confirm the step it cannot verify.

**The setup gate.** The app will not lead you to the accessibility switch until
the checks pass, because enabling the service on a device that will kill it is
how you end up trusting an app that stopped working days ago. It gates its own
screen only — enabling from system Settings still works, and is reported as
**NOT CONFIGURED** rather than pretended away.

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

Muting logs both ends of every mute, with the reason it was released, and never
any screen content:

```
I AdSkipper: Muted the music stream for an ad.
I AdSkipper: Restored the music stream (the ad is over).
```

To check it on a non-skippable ad specifically, watch for those two lines around
a bumper — there will be no `Skipped ad` between them, because there was no
button. If the first line never appears, the ad's labels are not in the ad lists
yet; dump the tree as below and add what is there.

The overlay card logs both halves of its sequence, so a partial failure is
obvious from which line is missing:

```
I AdSkipper: Opened the overlay ad's options menu.
I AdSkipper: Dismissed the overlay ad.
```

If neither appears, the card's ⋮ was not recognised — turn debug logging on and
the card's own nodes are dumped with their IDs and descriptions, ready to be
added to `OVERFLOW_LABELS` or `OVERFLOW_ID_HINTS`. If only the first appears, the
menu opened but never identified itself as the ad menu, or had no entry matching
`DISMISS_LABELS`.

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

Muting has its own three lists in the same file, for the same reason and with the
same maintenance story. They answer a different question — *is an ad on screen at
all* — which is why they are separate from the skip lists above:

| List | Use it for | Trusted on its own? |
| --- | --- | --- |
| `UNAMBIGUOUS_AD_LABELS` | Text that can only appear during an ad, e.g. `skip ad in 3`, `ad will end` | Yes — matched as a prefix |
| `AD_BADGE_LABELS` | The bare badge: `ad`, `ads`, `advertisement` | No — exact match, and only alongside a call to action |
| `AD_CTA_LABELS` | Advertiser calls to action, e.g. `visit advertiser` | No — only as corroboration for the badge |

The badge is split off for the same reason `skip` is: a video can be *titled*
"Ad", and one titled "Ad Astra" would match `ad ` as a prefix. So the badge needs
a second, unrelated signal on the same screen, and the counter form ("Ad 1 of 2",
"Ad · 0:12") is matched structurally instead — every word after the first has to
be a number or `of`, which "Ad Astra" is not. Bare `download` and `install` are
deliberately **not** calls to action: YouTube's own player offers both, so they
would corroborate nothing.

`sponsored` is **not** in that first list, and putting it there is a mistake worth
naming because it was made here first. It is the overlay card's label, and that
card sits over *normal playback* — so trusting it as proof of an ad silenced the
video the card was covering, for the full 90 seconds of the mute ceiling. It
belongs to the dismissal path, not the muting path.

The overlay card's lists live in
[OverlayAdDismisser.java](app/src/main/java/dev/javid/adskipper/OverlayAdDismisser.java),
since that is where the whole sequence lives:

| List | Use it for | Trusted on its own? |
| --- | --- | --- |
| `BANNER_LABELS` | The card's own label: `sponsored` | Yes, to *find* the card — it never triggers a click by itself |
| `OVERFLOW_LABELS` | The ⋮ on the card, e.g. `more options` | Exact match only, and only within the card's container |
| `OVERFLOW_ID_HINTS` | View-ID fragments for the same control, e.g. `overflow` | Same, and language-independent when present |
| `AD_MENU_MARKERS` | Proof the opened menu is the ad menu, e.g. `my ad center` | Required before anything in the menu is clicked |
| `DISMISS_LABELS` | The entry to click, e.g. `dismiss` | Exact match, and only in an identified ad menu |

The `OVERFLOW_*` pair is the one to check first if dismissal stops working, and
the dump is easier here than elsewhere: with debug logging on, finding the card
but not its ⋮ logs every node around it with its ID, class, text and description.

## Limitations

- **Skipping is skippable ads only.** Non-skippable and bumper ads have no button
  to click, so nothing here can make them shorter. They are muted instead, which
  is the most an app outside YouTube can do about them.
- **Muting is the media stream, not YouTube.** It silences whatever is on the
  music stream for the length of the ad, which in practice is YouTube, since it
  is the app in front playing the ad.
- **English labels out of the box.** View-ID matching is language-independent and
  usually carries the skip control, but ad detection is text-only, so a
  non-English device needs labels added before muting works. The overlay card
  needs three labels rather than one, so it is the first thing to break on a
  non-English build.
- **The overlay card is removed, not blocked.** It appears, and a second or so
  later it is dismissed. There is no way to stop it being drawn in the first
  place from outside YouTube.
- **Not a network-level ad blocker.** The ad still loads and plays for its first
  few seconds; this only presses the button you could have pressed yourself.
- **A force stop always wins.** Some devices implement "clear all apps" as a
  force stop, which stops the service until you open the app again. The
  keep-alive notification does not change that, and nothing an ordinary app can
  do would. See [Keeping it running](#keeping-it-running).

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
├── AndroidManifest.xml                        # permissions, queries, components
├── java/dev/javid/adskipper/
│   ├── SkipAdAccessibilityService.java        # detection + click logic, blind detection
│   ├── AdMuter.java                           # mutes the media stream during ads
│   ├── OverlayAdDismisser.java                 # dismisses the Sponsored card over the video
│   ├── KeepAliveService.java                  # ongoing notification, three states
│   ├── Preflight.java                         # setup checks, and what cannot be checked
│   └── MainActivity.java                      # status screen + setup gate
└── res/
    ├── xml/accessibility_service_config.xml   # scopes the service to YouTube
    ├── layout/activity_main.xml               # one screen, plain platform widgets
    ├── layout/dialog_recents_lock.xml         # body of the Recents-lock help dialog
    ├── color/button_primary_text.xml          # enabled/disabled label colour
    ├── drawable/
    │   ├── ic_launcher_foreground.xml         # skip glyph, white on black
    │   ├── ic_notification.xml                # status-bar glyph (monochrome by requirement)
    │   ├── ic_state_ok.xml                    # check / cross / exclamation, tinted
    │   ├── ic_state_bad.xml                   #   at the point of use rather than
    │   ├── ic_state_warn.xml                  #   baked with a colour
    │   ├── illus_recents_lock.xml             # drawn, not screenshotted (see below)
    │   ├── bg_card.xml                        # rounded card + outline
    │   ├── bg_status_pill.xml                 # pill behind the status label
    │   ├── bg_button_primary.xml              # filled, with a real disabled state
    │   └── bg_button_secondary.xml            # outlined
    └── values/
        ├── strings.xml
        ├── styles.xml                         # AppTheme (no action bar) + headings
        ├── colors.xml                         # light palette
        └── ../values-night/colors.xml         # dark palette
```

The UI uses plain platform widgets throughout — no Material Components, no
CardView, no AndroidX at all — because the build has no dependencies and adding
one for rounded corners is not a trade worth making. Cards are `LinearLayout`s
with a shape drawable; the state markers are vectors tinted at the point of use.

The Recents-lock illustration is a diagram rather than a screenshot, on
purpose. That control is a padlock on some devices, a pin on others, and a menu
item on others again — a photograph of any one of them would be wrong for most
people looking at it, and would also be the first raster asset in the project.
The dialog says as much underneath the drawing.

The theme is `DeviceDefault.DayNight`, so **every colour is defined twice**, in
`values/colors.xml` and `values-night/colors.xml`. A colour added to only one of
them renders unreadable in the other mode. The state colours are separately
tuned rather than reused: the light-mode greens and reds lose contrast against
dark backgrounds.

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
