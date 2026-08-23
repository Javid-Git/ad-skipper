package dev.javid.adskipper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
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
 * Watches YouTube's UI and clicks the "Skip Ad" control as soon as it appears.
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
 *   <li>It reads the on-screen node tree. It never types, scrolls, or swipes,
 *       and the only action it performs is a click on a node it identified as
 *       the skip control (using the node action or that node's exact bounds).</li>
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
    private static final String TARGET_PACKAGE = "com.google.android.youtube";

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
     * How often the counters below are reported. They distinguish a quiet event
     * stream from monitor scans that cannot find a readable YouTube window.
     */
    private static final long COUNTER_REPORT_MS = 10_000L;

    private int eventsSeen;
    private int windowUnreadable;
    private long lastCounterReportAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Service connected; watching " + TARGET_PACKAGE);
        // Tie the keep-alive notification to this service's lifetime, so it
        // exists exactly while there is something for it to protect. The start
        // can be refused under Android 12's background-start rules; MainActivity
        // retries from a resumed activity, where it is always permitted.
        KeepAliveService.start(this);
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
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) {
                    continue;
                }
                AccessibilityNodeInfo windowRoot = window.getRoot();
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
        if (now < backoffUntil
                || now - lastClickAt < CLICK_COOLDOWN_MS
                || now - lastScanAt < SCAN_THROTTLE_MS) {
            return;
        }
        lastScanAt = now;

        // Nodes are deliberately not recycled. recycle() is deprecated as of
        // API 33 and the platform pools these itself; releasing a node that is
        // still referenced throws, which is a worse failure than the churn.
        AccessibilityNodeInfo label = findSkipNode(root);
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
                + windowUnreadable + ", last root package "
                + (root == null ? "null" : asString(root.getPackageName())));
    }

    private void recordSuccessfulClick(AccessibilityNodeInfo node, String method) {
        lastClickAt = SystemClock.uptimeMillis();
        recordClick(lastClickAt);
        Log.i(TAG, "Skipped ad via " + method + " " + describe(node));
    }

    /**
     * Performs a tap at the matched node's bounds. This is needed for custom
     * YouTube controls that are visible to accessibility but do not expose a
     * clickable node. The target is still constrained to a matched YouTube
     * skip node; this is not a general screen-tapping fallback.
     */
    private boolean dispatchFallbackTap(AccessibilityNodeInfo node) {
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
     * Locates the skip control, using view IDs when available and otherwise
     * falling back to text and content descriptions.
     *
     * @return the matching node, or {@code null} if this screen has no skip control
     */
    private AccessibilityNodeInfo findSkipNode(AccessibilityNodeInfo root) {
        for (String viewId : SKIP_VIEW_IDS) {
            List<AccessibilityNodeInfo> hits =
                    root.findAccessibilityNodeInfosByViewId(TARGET_PACKAGE + ":id/" + viewId);
            if (hits == null) {
                continue;
            }
            for (AccessibilityNodeInfo hit : hits) {
                if (hit != null && hit.isVisibleToUser()) {
                    return hit;
                }
            }
        }
        return findByLabel(root);
    }

    /** Breadth-first text and description scan, bounded by {@link #MAX_NODES_VISITED}. */
    private AccessibilityNodeInfo findByLabel(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES_VISITED) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            visited++;

            if (isSkipTarget(node)) {
                return node;
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    /**
     * Decides whether a node is the skip control.
     *
     * <p>Unambiguous labels ("Skip Ad") are trusted outright. Generic ones
     * ("Skip") are only trusted when the node looks like a control, including a
     * described node with a clickable ancestor, which separates it from a plain
     * video title.
     */
    private boolean isSkipTarget(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser()) {
            return false;
        }

        String text = normalize(node.getText());
        String description = normalize(node.getContentDescription());
        if (text.isEmpty() && description.isEmpty()) {
            return false;
        }

        for (String label : UNAMBIGUOUS_SKIP_LABELS) {
            if (text.startsWith(label) || description.startsWith(label)) {
                return true;
            }
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

    /**
     * Walks up from the label to the first node that will accept a click. The
     * text view carrying "Skip Ad" is usually not the clickable element itself.
     *
     * @return the node to click, or {@code null} if nothing nearby is clickable
     */
    private AccessibilityNodeInfo nearestClickable(AccessibilityNodeInfo from) {
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
    private static String normalize(CharSequence raw) {
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
     * (it fails outright on MIUI), which otherwise leaves no way to inspect the
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
