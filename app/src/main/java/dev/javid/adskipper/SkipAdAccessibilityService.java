package dev.javid.adskipper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * Watches YouTube's UI, clicks the "Skip Ad" control as soon as it appears, and
 * silences the music stream while an ad that has no such control is playing.
 *
 * <p>The service uses accessibility events for prompt wakeups and an adaptive
 * foreground monitor as a safety net. It scans about once per second while
 * YouTube is absent and every 300ms while a YouTube window exists, including a
 * picture-in-picture window.
 *
 * <p>The service first uses {@link AccessibilityNodeInfo#ACTION_CLICK} on the
 * matched control. If a YouTube release exposes the control but refuses that
 * action, it falls back to one short tap at the matched node's own bounds.
 *
 * <h2>What this service can and cannot reach</h2>
 * <ul>
 *   <li>Accessibility events are limited to {@code com.google.android.youtube};
 *       the framework filters every other app out before this code runs. The
 *       low-rate monitor checks only the active window package, then reads a
 *       tree only when it finds YouTube.</li>
 *   <li>It reads the on-screen node tree. It never types, scrolls, or swipes.
 *       It clicks the skip control it identified (using the node action or that
 *       node's exact bounds), and — for the overlay card that covers the video
 *       after an ad — the card's own options control and the Dismiss entry in the
 *       menu that opens, plus Back to close that menu if Dismiss is not there.
 *       Those three are the only actions it ever performs; see
 *       {@link OverlayAdDismisser} for how narrowly each is identified.</li>
 *   <li>While an ad is on screen it mutes the music stream, and unmutes it when
 *       the ad ends. That is the only thing it changes outside YouTube's own UI,
 *       it needs no permission, and it touches no other stream — not the ringer,
 *       not notifications, not alarms. See {@link AdMuter}.</li>
 *   <li>The app declares no permission that grants access to data, and notably
 *       not {@code INTERNET}, so nothing it reads can leave the device. The
 *       three it does declare exist only so {@link KeepAliveService} can hold
 *       an ongoing notification.</li>
 *   <li>Screen text is written to the log only when someone explicitly turns
 *       debug logging on; see {@link #contentLoggingEnabled()}.</li>
 * </ul>
 */
public class SkipAdAccessibilityService extends AccessibilityService {

    private static final String TAG = "AdSkipper";

    /**
     * Also enforced by {@code packageNames} in accessibility_service_config.xml.
     * Re-checked here because the active window can change between an event
     * being queued and us reading the tree. This is what guarantees the service
     * never clicks anything in another app.
     */
    static final String TARGET_PACKAGE = "com.google.android.youtube";

    /**
     * An optional fast path when present: YouTube's own view IDs. Checked
     * before text matching and unaffected by device language, but some real
     * device ad overlays expose no resource ID at all. Requires
     * {@code flagReportViewIds} in the service config.
     */
    private static final String[] SKIP_VIEW_IDS = {
            "skip_ad_button",
            "skip_ad_button_container",
            "skip_button",
    };

    /**
     * Player view IDs that exist only while an ad is on screen. Proof of an ad
     * on their own, and the <em>earliest</em> proof available: they are there
     * from the first frame, where the skip control does not appear for about
     * five seconds and an unskippable ad never grows one at all.
     *
     * <p>Without these the only thing that could prove an ad was the skip
     * control itself, so on a build that shows no countdown the mute could not
     * engage until the instant the ad was already being skipped — measured at
     * 83ms before the click, which is inaudible — and for an unskippable ad it
     * never engaged at all.
     *
     * <p>Matched by ID rather than by the word "Sponsored" on purpose. That word
     * also labels the promoted cards in the home feed; those nodes carry no view
     * ID, so keying on the ID cannot mute someone who is merely scrolling.
     */
    private static final String[] CERTAIN_AD_VIEW_IDS = {
            "ad_progress_text",             // "Sponsored", "Sponsored · 0:16"
            "player_learn_more_button",
    };

    /**
     * Labels that cannot plausibly mean anything but the ad-skip control, so a
     * match here is trusted on its own. Compared as a prefix of the normalised
     * text, which also covers decorated variants like "Skip Ads (1 of 2)".
     *
     * <p>Maintenance: YouTube changes these between app versions and localises
     * them. If skipping stops working, dump the live tree with
     * {@code adb shell uiautomator dump} and add what you find here.
     */
    private static final String[] UNAMBIGUOUS_SKIP_LABELS = {
            "skip ad",      // also matches "skip ads"
            "skip advert",  // also matches "skip advertisement"
    };

    /**
     * Labels too generic to trust by themselves, since a video merely titled
     * "Skip" would match. These only count when the node also looks like a
     * control; see {@link #isSkipTarget}.
     */
    private static final String[] AMBIGUOUS_SKIP_LABELS = {
            "skip",
    };

    /**
     * Labels that only appear while an ad is on screen, matched as a prefix of
     * the normalised text so decorated variants ("Skip ad in 3", "Skip ads (1 of
     * 2)") match too.
     *
     * <p>These drive muting, not clicking, and that is why they are a separate
     * list from the skip labels above: the ads worth muting are precisely the
     * ones with no skip control to find.
     *
     * <p>Maintenance is the same as for the skip lists. YouTube renames and
     * localises these, so if muting stops working, turn debug logging on, dump
     * the tree, and add what is actually there.
     *
     * <p>"Sponsored" is deliberately <b>not</b> here, and that is not an
     * oversight. It is the label on the overlay card that appears <em>over
     * normal playback</em> once a video ad is gone, so trusting it silenced the
     * video the card was covering until the mute ceiling tripped. It identifies
     * that card instead — see {@link OverlayAdDismisser}. What is left are labels
     * that only exist while an ad is actually playing: the skip control, and
     * YouTube's own countdown wording.
     */
    private static final String[] UNAMBIGUOUS_AD_LABELS = {
            "skip ad",                  // a skippable ad, before and while the button is live
            "skip advert",
            "ad will end",
            "advert will end",
            "video will play after",    // "Video will play after ad"
            "video will resume after",
    };

    /**
     * The bare ad badge. Far too generic to trust on its own — a video can be
     * titled "Ad", and one titled "Ad Astra" would match it as a prefix — so an
     * exact match here counts only alongside an advertiser call to action from
     * {@link #AD_CTA_LABELS}. One coincidence on a screen is ordinary; two
     * unrelated ones at the same moment is not.
     */
    private static final String[] AD_BADGE_LABELS = {
            "ad",
            "ads",
            "advertisement",
            // "sponsored" deliberately absent. It was added here as a text
            // fallback for builds whose ad_progress_text shows no countdown, on
            // the assumption that a promoted card in the home feed labels itself
            // with the whole advertiser sentence and so could not match an exact
            // comparison. That is not true: a feed card carries a bare
            // "Sponsored" node of its own, and sits next to a "Shop now" button
            // from AD_CTA_LABELS. Together those satisfied badge && cta and
            // muted the stream for 90 seconds of ordinary scrolling, until the
            // mute ceiling released it.
            //
            // CERTAIN_AD_VIEW_IDS covers the case this was meant to, and cannot
            // fire on the feed because those cards carry no view ID.
    };

    /**
     * Advertiser call-to-action labels, chosen for what a video ad shows and an
     * ordinary YouTube screen does not. Bare "download" and "install" are
     * deliberately absent: YouTube's own player offers both, so they would
     * corroborate nothing.
     */
    private static final String[] AD_CTA_LABELS = {
            "visit advertiser",
            "visit site",
            "visit website",
            "learn more",
            "shop now",
            "install now",
    };

    /** Floor on how often the tree is scanned while a YouTube window exists. */
    private static final long SCAN_THROTTLE_MS = 200L;

    /** Poll interval while YouTube is present, including picture-in-picture. */
    private static final long TARGET_POLL_INTERVAL_MS = 300L;

    /** Low-rate monitor used to notice YouTube opening without an event. */
    private static final long IDLE_POLL_INTERVAL_MS = 1_000L;

    /**
     * Quiet period after a successful click. Without it the same button can be
     * clicked twice from an already-queued event before YouTube tears the
     * overlay down, and the second click lands on whatever replaced it.
     */
    private static final long CLICK_COOLDOWN_MS = 1_500L;

    /** Bounds each scan so a pathological tree cannot stall the service. */
    private static final int MAX_NODES_VISITED = 800;

    /** How far up from the label to look for something clickable. */
    private static final int MAX_ANCESTOR_HOPS = 8;

    /** Short, human-like tap used only when YouTube refuses ACTION_CLICK. */
    private static final long FALLBACK_TAP_DURATION_MS = 80L;

    /**
     * Runaway guard. Watching real ads produces at most a couple of clicks a
     * minute, so anything past this means a match is firing on something that
     * is not the skip button and re-appearing after every click. Rather than
     * keep poking the UI in a loop, the service stands down for
     * {@link #BACKOFF_MS} and says so in the log.
     */
    private static final int MAX_CLICKS_PER_WINDOW = 6;
    private static final long CLICK_WINDOW_MS = 60_000L;
    private static final long BACKOFF_MS = 60_000L;

    /** The debug tree snapshot is verbose, so it is rate limited hard. */
    private static final long SNAPSHOT_THROTTLE_MS = 3_000L;

    /** Cap on how many nodes one snapshot lists. */
    private static final int SNAPSHOT_MAX_NODES = 60;

    private long lastScanAt;
    private long lastClickAt;
    private long lastSnapshotAt;
    private long clickWindowStartAt;
    private int clicksInWindow;
    private long backoffUntil;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pollScheduled;

    /**
     * Owns the ad mute. Created on connect, before the monitor loop starts, so
     * nothing that runs from the loop has to check it for null.
     */
    private AdMuter muter;

    /** Owns the overlay-card sequence. Created alongside {@link #muter}. */
    private OverlayAdDismisser dismisser;

    /**
     * How often the counters below are reported. They distinguish a quiet event
     * stream from monitor scans that cannot find a readable YouTube window.
     */
    private static final long COUNTER_REPORT_MS = 10_000L;

    private int eventsSeen;
    private int windowUnreadable;
    private long lastCounterReportAt;

    /**
     * Last sample of how many windows {@link #getWindows()} returned, and how
     * many of those would hand over a root node. {@code -1} means not sampled
     * yet; only the slow path samples them, because the fast path never calls
     * {@code getWindows()}.
     *
     * <p>These exist to separate two states that are otherwise identical from
     * in here, both showing zero events and a climbing {@link #windowUnreadable}:
     *
     * <ul>
     *   <li><b>Blind.</b> The platform has put this service in its crashed set
     *       — which survives a rebind on some vendor ROMs — and refuses it
     *       window content even though it still appears bound with full
     *       capabilities.</li>
     *   <li><b>Idle.</b> YouTube simply is not in the foreground, so a service
     *       scoped to YouTube by {@code packageNames} correctly sees nothing.
     *       Working exactly as intended.</li>
     * </ul>
     *
     * <p>Counts only. Never window titles, which are screen content.
     */
    private int lastWindowCount = -1;
    private int lastWindowsWithRoot = -1;

    /**
     * How many consecutive counter reports found the platform serving no
     * windows at all.
     *
     * <p>Three reports is 30 seconds. Deliberately slow to raise, because a
     * false "it stopped working" warning is worse than none — it teaches people
     * to ignore the real one.
     */
    private static final int BLIND_REPORTS_TO_CONFIRM = 3;

    private int blindReports;

    /**
     * Whether the platform is currently refusing this service any window
     * content, leaving it connected but unable to see the screen.
     *
     * <p>{@code static} so {@link MainActivity} can read it: both now live in
     * the same process. It resets to {@code false} with the process, which is
     * correct — a service that is not running is not blind, it is gone, and
     * MainActivity reports that case separately.
     */
    private static volatile boolean blind;

    /** @return whether the service is connected but being served no windows */
    static boolean isBlind() {
        return blind;
    }

    /**
     * The single source of truth for what the notification says.
     *
     * <p>Order matters. Blind wins over unconfigured, because a service that
     * cannot see the screen is broken now, whereas an unconfigured one is
     * working but fragile. Both outrank claiming to be active.
     */
    static KeepAliveService.Status currentStatus(android.content.Context context) {
        if (blind) {
            return KeepAliveService.Status.INACTIVE;
        }
        if (!Preflight.isConfigured(context)) {
            return KeepAliveService.Status.NOT_CONFIGURED;
        }
        return KeepAliveService.Status.ACTIVE;
    }

    /** Last status pushed to the notification, so it is only rewritten on change. */
    private KeepAliveService.Status lastStatus;

    /**
     * Preflight involves a handful of binder calls, so it is not re-run on
     * every 10s report. A minute is far quicker than anyone can change a
     * setting and come back to look at the notification.
     */
    private static final int REPORTS_PER_PREFLIGHT = 6;

    private int reportsSincePreflight = REPORTS_PER_PREFLIGHT;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Service connected; watching " + TARGET_PACKAGE);
        // A fresh connection starts assumed-healthy. Set directly rather than
        // through setBlind, which would try to update a notification that the
        // next line has not created yet.
        blind = false;
        blindReports = 0;
        lastStatus = null;
        reportsSincePreflight = REPORTS_PER_PREFLIGHT;
        // Tie the keep-alive notification to this service's lifetime, so it
        // exists exactly while there is something for it to protect. The start
        // can be refused under Android 12's background-start rules; MainActivity
        // retries from a resumed activity, where it is always permitted.
        KeepAliveService.start(this);
        // Before the first scan, and before anything can mute: if the previous
        // process was killed mid-ad, the device is still muted right now and
        // this is what gives the audio back.
        muter = new AdMuter(this);
        muter.recoverStaleMute();
        dismisser = new OverlayAdDismisser(this);
        schedulePoll(0L);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Reached when the user turns the service off in Accessibility settings.
        shutDown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutDown();
        super.onDestroy();
    }

    /** Idempotent: onUnbind and onDestroy both fire in the normal disable path. */
    private void shutDown() {
        handler.removeCallbacks(pollRunnable);
        pollScheduled = false;
        blind = false;
        blindReports = 0;
        if (muter != null) {
            // Explicit, because the monitor tick that would normally have
            // released this was just cancelled two lines up. Turning the service
            // off must never leave the device silent.
            muter.release("the service was switched off");
        }
        if (dismisser != null) {
            dismisser.reset();
        }
        KeepAliveService.stop(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Node access races with YouTube destroying windows: a node can go stale
        // between being handed to us and being read, which throws. An uncaught
        // exception here would crash the app, and Android responds by switching
        // the accessibility service off — leaving the user to re-enable it by
        // hand in Settings. Dropping one event is a far better outcome.
        try {
            eventsSeen++;
            // The adaptive monitor is always alive; an event also makes sure
            // it is scheduled if a vendor interrupted the callback lifecycle.
            schedulePoll(0L);
        } catch (RuntimeException e) {
            Log.w(TAG, "Ignoring error while scheduling a YouTube scan", e);
        }
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollScheduled = false;
            boolean youtubeVisible = false;
            try {
                youtubeVisible = scanCurrentWindows();
            } catch (RuntimeException e) {
                Log.w(TAG, "Ignoring error while polling YouTube", e);
            } finally {
                // In a finally block, not after the catch, because the catch
                // only covers RuntimeException. An Error escaping the scan —
                // StackOverflowError on a pathological tree, OutOfMemoryError —
                // would otherwise skip the reschedule and stop the loop for
                // good, while the service stayed bound and the keep-alive
                // notification kept claiming it was working. This way the loop
                // always survives, and the Error still propagates.
                //
                // youtubeVisible is false on that path, so the next scan comes
                // at the idle interval rather than the fast one.
                //
                // The mute is released from here rather than from the scan, and
                // in the same finally block, for exactly the reason above: a
                // scan can be skipped by a throttle, a cooldown, a backoff, an
                // unreadable window or an Error, and the audio has to come back
                // in every one of those cases. This tick is the only thing that
                // is guaranteed to run.
                //
                // The overlay-card sequence is finished from here for the same
                // reason: once its menu is up, that menu is the active window
                // and the card that started the sequence is no longer on screen
                // for a scan to hand over.
                if (muter != null) {
                    long tickedAt = SystemClock.uptimeMillis();
                    muter.tick(tickedAt);
                    dismisser.tick(tickedAt);
                }
                schedulePoll(youtubeVisible ? TARGET_POLL_INTERVAL_MS : IDLE_POLL_INTERVAL_MS);
            }
        }
    };

    private void schedulePoll(long delayMs) {
        if (pollScheduled) {
            return;
        }
        pollScheduled = true;
        handler.postDelayed(pollRunnable, delayMs);
    }

    /** Scans the active root and, when needed, all interactive windows for PiP. */
    private boolean scanCurrentWindows() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (isTargetRoot(root)) {
            reportCounters(SystemClock.uptimeMillis(), root);
            scanRoot(root);
            return true;
        }

        // In PiP the launcher or another app may own the active window even
        // though YouTube's small window is still visible and interactive.
        List<AccessibilityWindowInfo> windows = getWindows();
        lastWindowCount = windows == null ? 0 : windows.size();
        lastWindowsWithRoot = 0;
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) {
                    continue;
                }
                AccessibilityNodeInfo windowRoot = window.getRoot();
                if (windowRoot != null) {
                    lastWindowsWithRoot++;
                }
                if (isTargetRoot(windowRoot)) {
                    reportCounters(SystemClock.uptimeMillis(), windowRoot);
                    scanRoot(windowRoot);
                    return true;
                }
            }
        }

        windowUnreadable++;
        reportCounters(SystemClock.uptimeMillis(), root);
        return false;
    }

    private static boolean isTargetRoot(AccessibilityNodeInfo root) {
        return root != null && TARGET_PACKAGE.equals(asString(root.getPackageName()));
    }

    private void scanRoot(AccessibilityNodeInfo root) {

        final long now = SystemClock.uptimeMillis();
        // A backoff stands the whole service down, audio included. A matcher
        // firing often enough to trip the runaway guard is not one that should
        // be deciding when to mute either, and skipping the scan stops the ad
        // signal being refreshed, so the mute lapses on its own within a second.
        if (now < backoffUntil || now - lastScanAt < SCAN_THROTTLE_MS) {
            return;
        }
        lastScanAt = now;

        // Nodes are deliberately not recycled. recycle() is deprecated as of
        // API 33 and the platform pools these itself; releasing a node that is
        // still referenced throws, which is a worse failure than the churn.
        Scan scan = scan(root);

        // Deliberately ahead of the click cooldown rather than behind it. The
        // cooldown exists to stop a second click landing on whatever replaced
        // the button, and has nothing to say about audio: an ad that starts
        // during those 1.5s should still be muted. The cost is that a scan now
        // happens during the cooldown, where before it was skipped outright.
        if (scan.adPlaying()) {
            muter.onAdSignal(now);
        }

        // Also ahead of the cooldown, and independent of it: the overlay card is
        // a different piece of UI from the skip button, so a recent skip click
        // has nothing to say about whether it should be dismissed. Its own
        // cooldowns are in OverlayAdDismisser.
        if (scan.banner != null) {
            dismisser.onBanner(scan.banner, now);
        }

        if (now - lastClickAt < CLICK_COOLDOWN_MS) {
            return;
        }

        AccessibilityNodeInfo label = scan.skip;
        if (label == null) {
            logTreeSnapshot(root, now);
            return;
        }

        AccessibilityNodeInfo clickable = nearestClickable(label);
        if (clickable != null
                && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            recordSuccessfulClick(clickable, "ACTION_CLICK");
            return;
        }

        // Some YouTube releases expose the button's label but do not mark the
        // label or any nearby ancestor clickable. Try the matched node itself
        // before falling back to its exact on-screen bounds.
        if (clickable != label
                && label.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            recordSuccessfulClick(label, "ACTION_CLICK on matched node");
            return;
        }

        if (dispatchFallbackTap(label)) {
            recordSuccessfulClick(label, "coordinate fallback");
        } else if (clickable == null) {
            Log.d(TAG, "Found a skip label but no clickable ancestor within "
                    + MAX_ANCESTOR_HOPS + " hops and its bounds could not be tapped "
                    + describe(label));
        } else {
            Log.d(TAG, "ACTION_CLICK refused and coordinate fallback was unavailable "
                    + describe(clickable));
        }
    }

    /**
     * Reports how many events have arrived and how many monitor scans yielded no
     * readable YouTube window. Logged at info level, and deliberately carries
     * no screen content — only the package name of the refused root.
     */
    private void reportCounters(long now, AccessibilityNodeInfo root) {
        if (now - lastCounterReportAt < COUNTER_REPORT_MS) {
            return;
        }
        lastCounterReportAt = now;
        Log.i(TAG, "Events seen " + eventsSeen + ", unreadable window "
                + windowUnreadable + ", windows " + lastWindowCount
                + " (" + lastWindowsWithRoot + " readable), last root package "
                + (root == null ? "null" : asString(root.getPackageName())));
        updateBlindState(root);
    }

    /**
     * Detects the state where this service is connected and looks healthy from
     * outside, but the platform serves it nothing.
     *
     * <p>It happens after the process is force-stopped: the platform puts the
     * component in its crashed set, and on some vendor ROMs that flag survives
     * the rebind. The service then appears in {@code Bound services} with full
     * capabilities while every window query comes back empty. Nothing visible
     * to the user says anything is wrong, which is exactly why this exists.
     *
     * <p>The signal is positive rather than an absence of evidence. A healthy
     * service always sees <em>something</em> — at minimum the status and
     * navigation bars — whatever app is in front. Measured on a device:
     * healthy-but-idle reports 3 windows, all readable; blind reports 0. That
     * distinction is what separates "broken" from "you just have not opened
     * YouTube", which the event counter alone cannot do.
     */
    private void updateBlindState(AccessibilityNodeInfo root) {
        boolean sawSomething = root != null || lastWindowCount > 0;

        // With the screen off there may genuinely be no windows, which would
        // otherwise be indistinguishable from being blind.
        PowerManager power = getSystemService(PowerManager.class);
        boolean screenOn = power == null || power.isInteractive();

        if (sawSomething || !screenOn) {
            blindReports = 0;
            setBlind(false);
            return;
        }

        // Asymmetric on purpose: three reports to raise the alarm, a single
        // good one to clear it. Slow to worry, quick to forgive.
        blindReports++;
        if (blindReports >= BLIND_REPORTS_TO_CONFIRM) {
            setBlind(true);
        }
    }

    private void setBlind(boolean nowBlind) {
        if (blind != nowBlind) {
            blind = nowBlind;
            if (nowBlind) {
                Log.w(TAG, "Connected but the platform is serving no windows at all."
                        + " Ad skipping cannot work in this state.");
            } else {
                Log.i(TAG, "Window content is being served again.");
            }
            // A blindness change must reach the notification immediately, not
            // wait out the preflight interval.
            reportsSincePreflight = REPORTS_PER_PREFLIGHT;
        }
        pushStatus();
    }

    /**
     * Recomputes the notification status and pushes it only when it changes.
     *
     * <p>Preflight is re-evaluated at most once a minute; blindness is already
     * current every time this is called.
     */
    private void pushStatus() {
        if (++reportsSincePreflight < REPORTS_PER_PREFLIGHT && lastStatus != null) {
            return;
        }
        reportsSincePreflight = 0;

        KeepAliveService.Status status = currentStatus(this);
        if (status == lastStatus) {
            return;
        }
        lastStatus = status;
        Log.i(TAG, "Notification status is now " + status);
        KeepAliveService.setStatus(this, status);
    }

    private void recordSuccessfulClick(AccessibilityNodeInfo node, String method) {
        lastClickAt = SystemClock.uptimeMillis();
        recordClick(lastClickAt);
        Log.i(TAG, "Skipped ad via " + method + " " + describe(node));

        // Give the audio back now rather than waiting for the ad signals to go
        // stale. A successful click means this ad is gone, and the release grace
        // would otherwise silence the first 1.5s of the video the user actually
        // wanted to hear — every single skip.
        //
        // The grace exists to hold one continuous mute across the gap between
        // two ads in a break, and releasing here does give that up: in a
        // back-to-back break, up to one poll interval of the second ad is
        // audible before the mute is retaken. 300ms of an ad is a better trade
        // than 1.5s of the video, and it only costs anything on a multi-ad
        // break, where the current behaviour costs on every skip.
        muter.release("the ad was skipped");
    }

    /**
     * Performs a tap at the matched node's bounds. This is needed for custom
     * YouTube controls that are visible to accessibility but do not expose a
     * clickable node. The target is still constrained to a matched YouTube
     * skip node; this is not a general screen-tapping fallback.
     */
    boolean dispatchFallbackTap(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty() || bounds.centerX() < 0 || bounds.centerY() < 0) {
            return false;
        }

        Path tapPath = new Path();
        tapPath.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(
                        tapPath, 0L, FALLBACK_TAP_DURATION_MS))
                .build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.d(TAG, "Fallback tap was cancelled " + describe(node));
            }
        }, null);
    }

    /**
     * Nothing to abandon: this service announces no feedback and holds no
     * long-running work.
     *
     * <p>Deliberately does <em>not</em> cancel the poll loop. onInterrupt means
     * "stop whatever you are announcing", not "shut down" — the platform calls
     * it while the service stays connected. Tearing the loop down here left
     * scanning permanently stopped until the next YouTube accessibility event
     * happened to arrive, which is precisely the silent-death this service is
     * supposed to avoid. Teardown belongs in {@link #onUnbind} / {@link
     * #onDestroy}.
     */
    @Override
    public void onInterrupt() {
    }

    /**
     * Counts clicks in a rolling window and trips a backoff if the rate is too
     * high to be genuine ad-skipping.
     */
    private void recordClick(long now) {
        if (now - clickWindowStartAt > CLICK_WINDOW_MS) {
            clickWindowStartAt = now;
            clicksInWindow = 0;
        }
        clicksInWindow++;
        if (clicksInWindow > MAX_CLICKS_PER_WINDOW) {
            backoffUntil = now + BACKOFF_MS;
            Log.w(TAG, "Clicked " + clicksInWindow + " times in under a minute, which does not"
                    + " look like real ads. Standing down for " + (BACKOFF_MS / 1000) + "s."
                    + " Turn on debug logging to see what is being matched.");
        }
    }

    /**
     * What one traversal of a YouTube window found: the skip control to click,
     * if there is one, and whether anything on screen says an ad is playing.
     *
     * <p>The two answers come from one walk on purpose. Muting needs to know
     * about ads with no skip control, so it cannot reuse the skip result — but a
     * second traversal would double the cost of a scan that already runs every
     * 300ms, to learn things the first walk had in front of it.
     */
    private static final class Scan {

        /** First skip control found, or {@code null} if this screen has none. */
        AccessibilityNodeInfo skip;

        /** A label that cannot mean anything other than an ad being on screen. */
        boolean certainAd;

        /** The bare "Ad" badge, which means nothing without {@link #cta}. */
        boolean badge;

        /** An advertiser call to action. */
        boolean cta;

        /**
         * The "Sponsored" label of an in-video overlay card, if one is on screen.
         *
         * <p>Nothing to do with the ad signals above — this card appears over
         * normal playback, so it is a thing to dismiss, not a thing to mute.
         */
        AccessibilityNodeInfo banner;

        /**
         * @return whether an ad is on screen: either a label that can only mean
         *         an ad, or the bare badge corroborated by a call to action
         */
        boolean adPlaying() {
            return certainAd || (badge && cta);
        }
    }

    /**
     * Locates the skip control and the ad signals, using view IDs when available
     * and otherwise falling back to text and content descriptions.
     */
    private Scan scan(AccessibilityNodeInfo root) {
        Scan scan = new Scan();

        // Ad markers before the skip control, because they appear first. This
        // is the difference between muting an ad and muting the 83ms of it that
        // remain once the skip button has arrived.
        for (String viewId : CERTAIN_AD_VIEW_IDS) {
            if (hasVisibleNode(root, viewId)) {
                scan.certainAd = true;
                break;
            }
        }

        for (String viewId : SKIP_VIEW_IDS) {
            List<AccessibilityNodeInfo> hits =
                    root.findAccessibilityNodeInfosByViewId(TARGET_PACKAGE + ":id/" + viewId);
            if (hits == null) {
                continue;
            }
            for (AccessibilityNodeInfo hit : hits) {
                if (hit != null && hit.isVisibleToUser()) {
                    scan.skip = hit;
                    // A visible skip control is proof of an ad in its own right,
                    // so this needs no corroborating label.
                    scan.certainAd = true;
                    return scan;
                }
            }
        }

        walkTree(root, scan);
        return scan;
    }

    /**
     * @return whether the tree holds a visible node carrying this YouTube view ID
     */
    private boolean hasVisibleNode(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> hits =
                root.findAccessibilityNodeInfosByViewId(TARGET_PACKAGE + ":id/" + viewId);
        if (hits == null) {
            return false;
        }
        for (AccessibilityNodeInfo hit : hits) {
            if (hit != null && hit.isVisibleToUser()) {
                return true;
            }
        }
        return false;
    }

    /** Breadth-first text and description scan, bounded by {@link #MAX_NODES_VISITED}. */
    private void walkTree(AccessibilityNodeInfo root, Scan scan) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES_VISITED) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            visited++;

            if (node.isVisibleToUser()) {
                String text = normalize(node.getText());
                String description = normalize(node.getContentDescription());
                if (!text.isEmpty() || !description.isEmpty()) {
                    classifyAdSignal(text, description, scan);
                    if (scan.banner == null
                            && OverlayAdDismisser.isBannerLabel(text, description)) {
                        scan.banner = node;
                    }
                    if (isSkipTarget(node, text, description)) {
                        scan.skip = node;
                        // The click path only needs the first match, and a
                        // visible skip control already settles the ad question,
                        // so the rest of the tree has nothing left to add.
                        scan.certainAd = true;
                        return;
                    }
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
    }

    /**
     * Folds one node's labels into the ad signals for this scan.
     *
     * <p>Nothing here reads the node itself. An ad badge is a label on the
     * player, not a control, so unlike {@link #isSkipTarget} there is no
     * clickable-ancestor test available to lean on — which is why the weak
     * signals need each other instead.
     */
    private static void classifyAdSignal(String text, String description, Scan scan) {
        if (scan.certainAd) {
            return;
        }
        if (startsWithAny(text, UNAMBIGUOUS_AD_LABELS)
                || startsWithAny(description, UNAMBIGUOUS_AD_LABELS)
                || isAdCounter(text)
                || isAdCounter(description)) {
            scan.certainAd = true;
            return;
        }
        if (equalsAny(text, AD_BADGE_LABELS) || equalsAny(description, AD_BADGE_LABELS)) {
            scan.badge = true;
        }
        if (startsWithAny(text, AD_CTA_LABELS) || startsWithAny(description, AD_CTA_LABELS)) {
            scan.cta = true;
        }
    }

    /**
     * Matches the ad badge when it carries a counter or a countdown: "Ad 1 of 2",
     * "Ads 2 of 3", "Ad · 0:12", which normalise to "ad 1 of 2", "ads 2 of 3"
     * and "ad 0 12".
     *
     * <p>Structural rather than a prefix match, and that is the whole point. A
     * prefix of {@code "ad "} would also match a video called "Ad Astra";
     * requiring every word after the first to be digits or "of" does not.
     */
    private static boolean isAdCounter(String normalized) {
        int start;
        if (normalized.startsWith("ad ")) {
            start = 3;
        } else if (normalized.startsWith("ads ")) {
            start = 4;
        } else {
            return false;
        }

        boolean sawNumber = false;
        for (String word : normalized.substring(start).split(" ")) {
            if (word.isEmpty() || "of".equals(word)) {
                continue;
            }
            if (!isAllDigits(word)) {
                return false;
            }
            sawNumber = true;
        }
        return sawNumber;
    }

    private static boolean isAllDigits(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isDigit(word.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decides whether a node is the skip control.
     *
     * <p>Unambiguous labels ("Skip Ad") are trusted outright. Generic ones
     * ("Skip") are only trusted when the node looks like a control, including a
     * described node with a clickable ancestor, which separates it from a plain
     * video title.
     *
     * <p>The caller has already established that the node is visible and that
     * at least one of the two labels is non-empty, and normalised both.
     */
    private boolean isSkipTarget(AccessibilityNodeInfo node, String text, String description) {
        if (startsWithAny(text, UNAMBIGUOUS_SKIP_LABELS)
                || startsWithAny(description, UNAMBIGUOUS_SKIP_LABELS)) {
            return true;
        }

        for (String label : AMBIGUOUS_SKIP_LABELS) {
            if (label.equals(text) || label.equals(description)) {
                // A custom YouTube control may expose only a short content
                // description on a non-clickable label. In that case the
                // clickable ancestor is still enough to identify the control.
                return node.isClickable()
                        || mentionsSkip(node.getViewIdResourceName())
                        || (label.equals(description) && nearestClickable(node) != null);
            }
        }
        return false;
    }

    static boolean startsWithAny(String normalized, String[] labels) {
        if (normalized.isEmpty()) {
            return false;
        }
        for (String label : labels) {
            if (normalized.startsWith(label)) {
                return true;
            }
        }
        return false;
    }

    static boolean equalsAny(String normalized, String[] labels) {
        for (String label : labels) {
            if (label.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks up from the label to the first node that will accept a click. The
     * text view carrying "Skip Ad" is usually not the clickable element itself.
     *
     * @return the node to click, or {@code null} if nothing nearby is clickable
     */
    AccessibilityNodeInfo nearestClickable(AccessibilityNodeInfo from) {
        AccessibilityNodeInfo node = from;
        for (int hop = 0; node != null && hop <= MAX_ANCESTOR_HOPS; hop++) {
            if (node.isClickable() && node.isEnabled()) {
                return node;
            }
            node = node.getParent();
        }
        return null;
    }

    private static boolean mentionsSkip(String viewIdResourceName) {
        return viewIdResourceName != null
                && viewIdResourceName.toLowerCase(Locale.US).contains("skip");
    }

    /**
     * Folds a label down to lowercase words separated by single spaces, so that
     * "Skip Ad  &gt;" and "SKIP AD" both become "skip ad".
     */
    static String normalize(CharSequence raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        String lowered = raw.toString().toLowerCase(Locale.US);
        StringBuilder out = new StringBuilder(lowered.length());
        boolean pendingSpace = false;
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(c);
            } else {
                pendingSpace = true;
            }
        }
        return out.toString();
    }

    private static String asString(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    /**
     * Logs what the current YouTube window actually contains when nothing
     * matched, so the real labels and view IDs of the skip control can be read
     * off a live device and added to the lists above.
     *
     * <p>Gated behind content logging, and throttled, because it is verbose and
     * puts on-screen text into logcat. This exists because
     * {@code adb shell uiautomator dump} refuses to run on some vendor builds
     * (it fails outright on some vendor builds), which otherwise leaves no way to inspect the
     * tree on the device where skipping is actually broken.
     */
    private void logTreeSnapshot(AccessibilityNodeInfo root, long now) {
        if (!contentLoggingEnabled() || now - lastSnapshotAt < SNAPSHOT_THROTTLE_MS) {
            return;
        }
        lastSnapshotAt = now;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int visited = 0;
        int withViewId = 0;
        int shown = 0;
        StringBuilder detail = new StringBuilder();

        while (!queue.isEmpty() && visited < MAX_NODES_VISITED) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            visited++;
            if (node.getViewIdResourceName() != null) {
                withViewId++;
            }

            boolean worthShowing = !TextUtils.isEmpty(node.getText())
                    || !TextUtils.isEmpty(node.getContentDescription())
                    || node.isClickable();
            if (worthShowing && shown < SNAPSHOT_MAX_NODES) {
                shown++;
                detail.append("\n  clickable=").append(node.isClickable())
                        .append(' ').append(describe(node));
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }

        Log.d(TAG, "No skip control matched: " + visited + " nodes, " + withViewId
                + " carrying a view id, listing " + shown + " with text/description/clickable:"
                + detail);
    }

    /**
     * Whether it is OK to put on-screen text into the log.
     *
     * <p>Off by default, so normal operation never records what is on the
     * YouTube screen. Turn it on for a debugging session with:
     * {@code adb shell setprop log.tag.AdSkipper DEBUG}
     */
    private static boolean contentLoggingEnabled() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    /**
     * Node summary for logcat. Returns nothing identifying unless content
     * logging has been explicitly switched on, because the text and content
     * description of a node are whatever happens to be on the user's screen.
     */
    private static String describe(AccessibilityNodeInfo node) {
        if (!contentLoggingEnabled()) {
            return "(enable `adb shell setprop log.tag." + TAG + " DEBUG` for details)";
        }
        return "[id=" + node.getViewIdResourceName()
                + " class=" + node.getClassName()
                + " text=" + node.getText()
                + " desc=" + node.getContentDescription()
                + "]";
    }
}
