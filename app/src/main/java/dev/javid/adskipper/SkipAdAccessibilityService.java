package dev.javid.adskipper;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * Watches YouTube's UI and clicks the "Skip Ad" control as soon as it appears.
 *
 * <p>No timer is involved. YouTube does not put the skip control in the view
 * hierarchy until the ad becomes skippable (~5s in), so the service naturally
 * fires at the first moment a click can succeed.
 *
 * <p>The click is {@link AccessibilityNodeInfo#ACTION_CLICK} on the node itself,
 * not a synthesised screen tap, so it cannot land somewhere unintended if the
 * player is laid out differently than expected.
 *
 * <h2>What this service can and cannot reach</h2>
 * <ul>
 *   <li>It is woken only for {@code com.google.android.youtube}; the framework
 *       filters every other app out before this code runs.</li>
 *   <li>It reads the on-screen node tree. It never types, scrolls, or swipes,
 *       and the only action it performs is a click on a node it identified as
 *       the skip control.</li>
 *   <li>The app declares <em>no permissions at all</em> — notably not
 *       {@code INTERNET} — so nothing it reads can leave the device.</li>
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
     * The most reliable signal when present: YouTube's own view IDs. Checked
     * before any text matching, and unaffected by device language. Requires
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

    /** Floor on how often the tree is scanned. YouTube emits events in bursts. */
    private static final long SCAN_THROTTLE_MS = 200L;

    /**
     * Quiet period after a successful click. Without it the same button can be
     * clicked twice from an already-queued event before YouTube tears the
     * overlay down, and the second click lands on whatever replaced it.
     */
    private static final long CLICK_COOLDOWN_MS = 1_500L;

    /** Bounds the per-event scan so a pathological tree cannot stall the UI. */
    private static final int MAX_NODES_VISITED = 800;

    /** How far up from the label to look for something clickable. */
    private static final int MAX_ANCESTOR_HOPS = 8;

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

    private long lastScanAt;
    private long lastClickAt;
    private long clickWindowStartAt;
    private int clicksInWindow;
    private long backoffUntil;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Service connected; watching " + TARGET_PACKAGE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Node access races with YouTube destroying windows: a node can go stale
        // between being handed to us and being read, which throws. An uncaught
        // exception here would crash the app, and Android responds by switching
        // the accessibility service off — leaving the user to re-enable it by
        // hand in Settings. Dropping one event is a far better outcome.
        try {
            handleEvent(event);
        } catch (RuntimeException e) {
            Log.w(TAG, "Ignoring error while scanning for the skip button", e);
        }
    }

    private void handleEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

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
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !TARGET_PACKAGE.equals(asString(root.getPackageName()))) {
            return;
        }

        AccessibilityNodeInfo label = findSkipNode(root);
        if (label == null) {
            return;
        }

        AccessibilityNodeInfo clickable = nearestClickable(label);
        if (clickable == null) {
            Log.d(TAG, "Found a skip label but no clickable ancestor within "
                    + MAX_ANCESTOR_HOPS + " hops " + describe(label));
            return;
        }

        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastClickAt = SystemClock.uptimeMillis();
            recordClick(lastClickAt);
            Log.i(TAG, "Skipped ad " + describe(clickable));
        } else {
            Log.d(TAG, "ACTION_CLICK refused " + describe(clickable));
        }
    }

    @Override
    public void onInterrupt() {
        // Nothing to abandon: the service holds no long-running work.
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
     * Locates the skip control, preferring view IDs over text.
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
     * ("Skip") are only trusted when the node is itself clickable or its view ID
     * mentions skipping, which is enough to separate a real button from a video
     * whose title happens to be the same word.
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
                return node.isClickable() || mentionsSkip(node.getViewIdResourceName());
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
