package dev.javid.adskipper;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Dismisses YouTube's in-video overlay ad: the small "Sponsored" card that slides
 * over the bottom of the player once a video ad is out of the way, and then sits
 * on top of the thing you were trying to watch.
 *
 * <p>It cannot be done the way the skip button is done. The card has no close
 * control of its own — only a ⋮ that opens a menu containing "My Ad Center" and
 * "Dismiss" — so removing it takes two clicks with a menu appearing in between,
 * which makes this a small state machine rather than a match-and-click.
 *
 * <h2>Why it is built this suspiciously</h2>
 *
 * <p>Two clicks on someone else's UI is a much bigger thing to get wrong than
 * one, and the specific way it could go wrong here is bad: the card's *other*
 * clickable element is the advert itself. Clicking that would open the
 * advertiser's page and register as engagement — which is click fraud committed
 * on the user's behalf, not ad blocking. So:
 *
 * <ul>
 *   <li>The ⋮ is only ever looked for <b>inside the card's own container</b>,
 *       found by walking up from the "Sponsored" label one ancestor at a time.
 *       A menu button elsewhere in the player is never a candidate.</li>
 *   <li>A candidate has to <b>name itself</b> — a self-describing phrase from
 *       {@link #OVERFLOW_LABEL_PREFIXES}, one of the short exact forms in
 *       {@link #OVERFLOW_LABELS}, or a view ID hinting at an overflow control.
 *       "Any clickable node in the card" is exactly the rule that would click
 *       the advert, so it is not the rule, and neither list can match a call to
 *       action.</li>
 *   <li>Nothing is clicked in the opened menu until the menu has
 *       <b>identified itself</b> as the ad menu by showing one of
 *       {@link #AD_MENU_MARKERS}. If the wrong thing opened, this walks away
 *       from it.</li>
 *   <li>Back is pressed <b>only</b> to close a menu this class opened and has
 *       positively identified, never speculatively. A blind Back would risk
 *       navigating YouTube out of the video, which is worse than any banner.</li>
 *   <li>Attempts are capped and cooled down, so a card that cannot be dismissed
 *       is left alone instead of being poked once every 300ms.</li>
 * </ul>
 *
 * <p>The failure mode this is tuned for is doing nothing. Every unrecognised
 * step ends the attempt.
 */
final class OverlayAdDismisser {

    private static final String TAG = "AdSkipper";

    private static final String PREFS = "overlay";

    /** The user-facing toggle. */
    private static final String KEY_ENABLED = "dismiss_overlay_ads";

    /** On by default: the card exists to cover content, and this removes it. */
    static final boolean ENABLED_BY_DEFAULT = true;

    /**
     * The card's own label, matched exactly because it is the entire text of
     * that view.
     *
     * <p>Deliberately <em>not</em> a mute signal. This card appears over normal
     * playback, so treating "Sponsored" as proof of an ad — which an earlier
     * version did — silenced the video the card was covering.
     */
    private static final String[] BANNER_LABELS = {
            "sponsored",
    };

    /**
     * The ⋮ on the card, matched as a <em>prefix</em> of the description.
     *
     * <p>Prefixes because that is what the device actually reports. YouTube names
     * these buttons after what they act on — a real one reads "Action menu for
     * How Orange Is The New Black DESTROYED Its Cast & Television" — so an exact
     * comparison against "action menu" matches none of them. Read off a live
     * device from the feed's own ⋮ buttons; the card's is described the same way.
     *
     * <p>Every entry here is a phrase that cannot begin a call to action, which
     * is what makes prefix matching safe. Note that it is specifically prefix and
     * not <em>contains</em>: "Learn more" does not start with "more", so the
     * advert's own button still cannot match.
     */
    private static final String[] OVERFLOW_LABEL_PREFIXES = {
            "action menu",
            "more options",
            "more actions",
            "ad options",
            "ad choices",
            "overflow menu",
    };

    /**
     * The short forms, matched exactly, because as prefixes they are too generic
     * to be safe: "more" would then reach "More info about this advertiser".
     */
    private static final String[] OVERFLOW_LABELS = {
            "more",
            "options",
            "menu",
    };

    /**
     * View-ID fragments for the same control, for builds that expose an ID.
     * Language-independent when present, which text matching never is.
     *
     * <p>Deliberately does not include "menu": on a real device that matches
     * {@code id/menu_item_view}, which is what the toolbar's Notifications and
     * Search buttons use. Container scoping would keep that out of reach anyway,
     * but a hint that demonstrably matches unrelated buttons is not a hint.
     */
    private static final String[] OVERFLOW_ID_HINTS = {
            "overflow",
            "options",
            "kebab",
            "three_dot",
    };

    /**
     * Proof that the menu now on screen is the ad menu, and not some other menu
     * that a mis-identified ⋮ happened to open. Nothing is clicked without one.
     */
    private static final String[] AD_MENU_MARKERS = {
            "my ad center",
            "ad center",
            "about this ad",
            "why this ad",
    };

    /** The entry to click once the menu has identified itself. */
    private static final String[] DISMISS_LABELS = {
            "dismiss",
            "hide this ad",
            "stop seeing this ad",
    };

    /**
     * How long the menu is given to appear and be recognised. Generous: the
     * sheet animates in, and the poll that looks for it runs every 300ms.
     */
    private static final long MENU_WAIT_MS = 2_500L;

    /** Pause after an attempt that did not end in a dismiss. */
    private static final long RETRY_DELAY_MS = 5_000L;

    /**
     * Much longer pause after opening a menu that turned out to be the ad menu
     * with no Dismiss in it.
     *
     * <p>That outcome has a specific meaning: this was a feed ad, whose ⋮ opens
     * the full My Ad Center sheet — Block, Report, Get started — and never offers
     * Dismiss. Retrying it in five seconds means opening that sheet over the
     * user's feed again, and three of those inside two minutes spends the runaway
     * guard on a card that was never dismissible, standing the whole feature down
     * for ten minutes including for the overlay card it is actually for.
     *
     * <p>A minute is longer than the two-minute guard window divided by its
     * attempt cap, so this outcome alone can no longer trip it.
     */
    private static final long NO_DISMISS_BACKOFF_MS = 60_000L;

    /**
     * Pause after a successful dismiss. Not a cap — YouTube is entitled to show
     * another card later and that one deserves dismissing too — just long enough
     * that the card being torn down cannot be read as a fresh one.
     */
    private static final long SETTLE_MS = 15_000L;

    /**
     * Runaway guard, in the same spirit as the skip guard: a card that will not
     * go away is a matcher problem, and the answer is to stop, not to keep
     * opening menus over someone's video.
     */
    private static final int MAX_ATTEMPTS_PER_WINDOW = 3;
    private static final long ATTEMPT_WINDOW_MS = 120_000L;
    private static final long STAND_DOWN_MS = 600_000L;

    /** Bounds the searches, like the main scan's own node budget. */
    private static final int MAX_NODES_VISITED = 400;

    /** How far up from the "Sponsored" label the card's container is looked for. */
    private static final int MAX_CONTAINER_HOPS = 4;

    /**
     * How far up to look for the scrollable ancestor that marks a card as a feed
     * row rather than the in-player overlay.
     *
     * <p>This number is the whole discriminator, and it is a judgement rather
     * than a measurement: a feed item's {@code RecyclerView} is a handful of
     * levels above its label, while the overlay card's ancestors are the player's
     * own containers, which do not scroll. Too generous and it would find the
     * scrolling column that the watch page's player sits in, and then decline to
     * dismiss the very card this exists for.
     *
     * <p>Which is why {@link #looksLikeAFeedRow} logs the ancestor it found under
     * debug logging. If dismissal starts declining on the player card, that line
     * says exactly which container lied and what this number should be.
     */
    private static final int MAX_LIST_HOPS = 6;

    /** The debug dump of a card whose ⋮ was not recognised is verbose. */
    private static final long SNAPSHOT_THROTTLE_MS = 10_000L;

    private final SkipAdAccessibilityService service;
    private final SharedPreferences prefs;

    /** When the menu was opened, or 0 when no attempt is in flight. */
    private long menuOpenedAt;

    private long nextAttemptAt;
    private long standDownUntil;
    private long attemptWindowStartAt;
    private int attemptsInWindow;
    private long lastSnapshotAt;

    /** Separate from {@link #lastSnapshotAt}: the two dumps diagnose different failures. */
    private long lastMenuSnapshotAt;

    OverlayAdDismisser(SkipAdAccessibilityService service) {
        this.service = service;
        this.prefs = prefs(service);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, ENABLED_BY_DEFAULT);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Whether one node's labels are the overlay card's marker.
     *
     * <p>Called from the main scan's single walk over the tree, so that spotting
     * the card costs one string comparison rather than a second traversal.
     */
    static boolean isBannerLabel(String text, String description) {
        return SkipAdAccessibilityService.equalsAny(text, BANNER_LABELS)
                || SkipAdAccessibilityService.equalsAny(description, BANNER_LABELS);
    }

    /**
     * Called when a scan found the overlay card. Opens its options menu, if this
     * is a good moment to and if the ⋮ can be identified.
     *
     * @param banner the node carrying the card's "Sponsored" label
     */
    void onBanner(AccessibilityNodeInfo banner, long now) {
        if (menuOpenedAt != 0L || !isEnabled()) {
            return;
        }
        if (now < nextAttemptAt || now < standDownUntil) {
            return;
        }
        if (looksLikeAFeedRow(banner)) {
            return;
        }

        AccessibilityNodeInfo overflow = findOverflowNear(banner);
        if (overflow == null) {
            logCardContents(banner, now);
            return;
        }
        if (!allowAttempt(now)) {
            return;
        }
        logAncestry(banner);
        if (!click(overflow)) {
            Log.d(TAG, "The overlay ad's options control refused a click.");
            nextAttemptAt = now + RETRY_DELAY_MS;
            return;
        }
        menuOpenedAt = now;
        Log.i(TAG, "Opened the overlay ad's options menu.");
    }

    /**
     * Runs on every monitor tick and owns the second half of the sequence.
     *
     * <p>Driven by the tick rather than the scan for the same reason the mute is:
     * a scan can be skipped by a throttle or a backoff, and a menu this class
     * opened must be resolved either way. It finds the menu itself — by the time
     * it is up, it is the active window and the card that started this is no
     * longer on screen to be handed over.
     */
    void tick(long now) {
        if (menuOpenedAt == 0L) {
            return;
        }
        if (!isEnabled()) {
            // Switched off mid-sequence. Leave the menu alone rather than
            // clicking in it, and stop tracking.
            menuOpenedAt = 0L;
            return;
        }

        boolean identified = false;
        AccessibilityNodeInfo dismiss = null;
        for (AccessibilityNodeInfo root : youtubeRoots()) {
            if (!identified && findLabel(root, AD_MENU_MARKERS) != null) {
                identified = true;
            }
            if (dismiss == null) {
                dismiss = findLabel(root, DISMISS_LABELS);
            }
        }

        boolean expired = now - menuOpenedAt > MENU_WAIT_MS;

        if (!identified) {
            // Either the sheet is still animating in, or whatever opened is not
            // the ad menu. Never press Back here: nothing has confirmed there is
            // a menu on screen to close, and a Back into YouTube could leave the
            // video entirely.
            if (expired) {
                Log.d(TAG, "No ad options menu appeared; leaving whatever is on screen alone.");
                menuOpenedAt = 0L;
                nextAttemptAt = now + RETRY_DELAY_MS;
            }
            return;
        }

        if (dismiss == null) {
            // Not there *yet* is not the same as not there. The sheet animates
            // in, and on a real device its heading is in the tree a few hundred
            // milliseconds before the entries under it are — so giving up on the
            // first tick that saw the heading closed the menu about 400ms after
            // opening it, three times, and then spent the runaway guard. The
            // entry gets the same wait the heading got.
            if (!expired) {
                return;
            }
            logMenuContents(now);
            Log.d(TAG, "The ad options menu has no dismiss entry, so this was a feed ad rather"
                    + " than the card over the video. Closing it and leaving that card alone for "
                    + (NO_DISMISS_BACKOFF_MS / 1000) + "s.");
            closeMenu();
            menuOpenedAt = 0L;
            nextAttemptAt = now + NO_DISMISS_BACKOFF_MS;
            return;
        }

        if (click(dismiss)) {
            Log.i(TAG, "Dismissed the overlay ad.");
            menuOpenedAt = 0L;
            nextAttemptAt = now + SETTLE_MS;
            return;
        }
        if (expired) {
            Log.d(TAG, "The dismiss entry refused a click; closing the menu again.");
            closeMenu();
            menuOpenedAt = 0L;
            nextAttemptAt = now + RETRY_DELAY_MS;
        }
    }

    /**
     * Drops any in-flight attempt without touching the screen.
     *
     * <p>Used when the service is going away. It deliberately does not press
     * Back: at that point nothing has re-confirmed what is on screen, and this
     * class only ever closes a menu it can currently see.
     */
    void reset() {
        menuOpenedAt = 0L;
    }

    /**
     * Whether this "Sponsored" card is a row in a scrolling list, rather than the
     * card that sits over the video.
     *
     * <p>Both carry the same label and the same kind of ⋮, so without this the
     * feed's ads would be dismissed too. That sounds like a bonus and is not:
     * removing a row reflows the list under a thumb that is mid-scroll, and a
     * feed with a few ads in it would spend the runaway guard within seconds —
     * standing the whole thing down for ten minutes, including for the player
     * card this is actually for.
     *
     * <p>The signal is the scrollable ancestor. A feed row lives in a
     * {@code RecyclerView}; the in-player overlay lives in the player, which does
     * not scroll. See {@link #MAX_LIST_HOPS} for the part of this that is a
     * judgement call.
     */
    private static boolean looksLikeAFeedRow(AccessibilityNodeInfo banner) {
        AccessibilityNodeInfo node = banner;
        for (int hop = 0; node != null && hop < MAX_LIST_HOPS; hop++) {
            if (node.isScrollable()) {
                logFeedRow(hop, "a scrollable ancestor", node);
                return true;
            }
            if (describesAFeedTile(node)) {
                logFeedRow(hop, "the whole tile's own description", node);
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    /**
     * Whether a node is the clickable tile of a feed ad, which is a different
     * thing from the small "Sponsored" label inside it.
     *
     * <p>A feed ad describes itself in one long string covering the whole tile:
     * {@code "Sponsored - Make Life Delicious - 15 seconds - foodpanda Hong Kong
     * - play video"}. The overlay card over the video is not a tile you can
     * play, so it has no such ancestor.
     *
     * <p>This is the second half of the discriminator and it exists because the
     * first half was not enough. The scrollable ancestor sits at hop 4 for the
     * watch page's list and for search results, but on the home feed it is
     * further up than {@link #MAX_LIST_HOPS}, so three home-feed ads got through
     * and had their menus opened. Both signals now have to miss for a card to be
     * treated as the one over the video.
     *
     * <p>Both halves are required rather than just the description: the prefix
     * alone might one day match an overlay card that YouTube decides to describe
     * the same way, whereas "play video" says specifically that this is a tile
     * standing in for a video.
     */
    private static boolean describesAFeedTile(AccessibilityNodeInfo node) {
        String description = SkipAdAccessibilityService.normalize(node.getContentDescription());
        return description.startsWith("sponsored ") && description.contains("play video");
    }

    private static void logFeedRow(int hop, String signal, AccessibilityNodeInfo node) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) {
            return;
        }
        Log.d(TAG, "Ignoring a Sponsored card: " + signal + " at hop " + hop
                + " [class=" + node.getClassName()
                + " id=" + node.getViewIdResourceName()
                + "] makes it a feed row rather than the card over the video.");
    }

    /**
     * Looks for the ⋮ by widening the search one ancestor at a time, so the
     * closest enclosing container that actually holds a candidate wins.
     *
     * <p>Starting at the label's parent and stopping at the first hit is what
     * keeps this inside the card. Walking a fixed number of hops up first would
     * eventually reach a container holding the whole player, where the player's
     * own menu buttons would become candidates.
     */
    private AccessibilityNodeInfo findOverflowNear(AccessibilityNodeInfo banner) {
        AccessibilityNodeInfo container = banner == null ? null : banner.getParent();
        for (int hop = 0; container != null && hop < MAX_CONTAINER_HOPS; hop++) {
            AccessibilityNodeInfo overflow = findOverflowIn(container);
            if (overflow != null) {
                return overflow;
            }
            container = container.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo findOverflowIn(AccessibilityNodeInfo container) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(container);

        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES_VISITED) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            visited++;

            if (isOverflow(node)) {
                AccessibilityNodeInfo clickable = nearestClickable(node);
                if (clickable != null) {
                    return clickable;
                }
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
     * Whether a node names itself as the card's options control.
     *
     * <p>A ⋮ carries no text, so in practice this is the content description or
     * the view ID. Both are matched narrowly on purpose — see the class comment
     * on what the alternative would click.
     */
    private static boolean isOverflow(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        String text = SkipAdAccessibilityService.normalize(node.getText());
        String description = SkipAdAccessibilityService.normalize(node.getContentDescription());
        if (SkipAdAccessibilityService.startsWithAny(text, OVERFLOW_LABEL_PREFIXES)
                || SkipAdAccessibilityService.startsWithAny(description, OVERFLOW_LABEL_PREFIXES)) {
            return true;
        }
        if (SkipAdAccessibilityService.equalsAny(text, OVERFLOW_LABELS)
                || SkipAdAccessibilityService.equalsAny(description, OVERFLOW_LABELS)) {
            return true;
        }
        return mentionsOverflow(node.getViewIdResourceName());
    }

    private static boolean mentionsOverflow(String viewIdResourceName) {
        if (viewIdResourceName == null) {
            return false;
        }
        String id = viewIdResourceName.toLowerCase(java.util.Locale.US);
        for (String hint : OVERFLOW_ID_HINTS) {
            if (id.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** As in the skip path: the labelled node is usually not the clickable one. */
    private static AccessibilityNodeInfo nearestClickable(AccessibilityNodeInfo from) {
        AccessibilityNodeInfo node = from;
        for (int hop = 0; node != null && hop <= 4; hop++) {
            if (node.isClickable() && node.isEnabled()) {
                return node;
            }
            node = node.getParent();
        }
        return null;
    }

    /** Exact-label search, bounded, over one window's tree. */
    private static AccessibilityNodeInfo findLabel(AccessibilityNodeInfo root, String[] labels) {
        if (root == null) {
            return null;
        }
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
                String text = SkipAdAccessibilityService.normalize(node.getText());
                String description =
                        SkipAdAccessibilityService.normalize(node.getContentDescription());
                if (SkipAdAccessibilityService.equalsAny(text, labels)
                        || SkipAdAccessibilityService.equalsAny(description, labels)) {
                    return node;
                }
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
     * Every readable YouTube window, package-checked.
     *
     * <p>The menu is its own window, and which window is "active" while it
     * animates in is not something to rely on. The package check is repeated
     * here for the same reason it is repeated everywhere else in this app: it is
     * the guarantee that nothing outside YouTube is ever read or clicked.
     */
    private List<AccessibilityNodeInfo> youtubeRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>(3);

        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        if (isTarget(active)) {
            roots.add(active);
        }

        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null) {
            return roots;
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            if (isTarget(root) && !roots.contains(root)) {
                roots.add(root);
            }
        }
        return roots;
    }

    private static boolean isTarget(AccessibilityNodeInfo root) {
        return root != null
                && SkipAdAccessibilityService.TARGET_PACKAGE.equals(
                        String.valueOf(root.getPackageName()));
    }

    /**
     * Clicks a control the same way the skip path does, because a menu entry
     * needs exactly the same treatment and was not getting it.
     *
     * <p>{@code findLabel} returns the node carrying the text, and in a bottom
     * sheet that node is a {@code TextView} with {@code clickable=false} — the
     * row around it is what accepts the click. Firing ACTION_CLICK straight at
     * the label was therefore refused every time: the menu opened, the Dismiss
     * entry was found, the click bounced, and the runaway guard eventually stood
     * the whole feature down while the card stayed on screen.
     *
     * <ol>
     *   <li>the nearest clickable ancestor, which is the row;</li>
     *   <li>the node itself, for builds where the label <em>is</em> the control;</li>
     *   <li>a tap at the node's own bounds, for a control that is visible to
     *       accessibility but exposes no clickable node at all.</li>
     * </ol>
     *
     * @return whether any of the three landed
     */
    private boolean click(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        AccessibilityNodeInfo clickable = service.nearestClickable(node);
        if (clickable != null
                && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        return service.dispatchFallbackTap(node);
    }

    /** Closes a menu this class opened and has just seen on screen. */
    private void closeMenu() {
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            Log.d(TAG, "Back was refused, so the ad options menu is still open.");
        }
    }

    /**
     * @return whether another attempt is within the runaway guard, counting it
     *         if so
     */
    private boolean allowAttempt(long now) {
        if (now - attemptWindowStartAt > ATTEMPT_WINDOW_MS) {
            attemptWindowStartAt = now;
            attemptsInWindow = 0;
        }
        attemptsInWindow++;
        if (attemptsInWindow <= MAX_ATTEMPTS_PER_WINDOW) {
            return true;
        }
        standDownUntil = now + STAND_DOWN_MS;
        Log.w(TAG, "Tried to dismiss an overlay ad " + attemptsInWindow + " times in two"
                + " minutes without it going away. Standing down for "
                + (STAND_DOWN_MS / 60_000) + " minutes. Turn on debug logging to see what is"
                + " being matched.");
        return false;
    }

    /**
     * The ancestor chain above the card, carrying both things the discriminator
     * reads: whether anything up there scrolls, and whether anything up there
     * describes itself as a whole feed tile.
     *
     * <p>Logged before every attempt rather than only on failure, because the
     * in-player card's structure is the one thing no dump has captured yet — the
     * diagnostic that fires when the ⋮ cannot be found never fires when it can,
     * which is precisely the case that needed measuring.
     */
    private static void logAncestry(AccessibilityNodeInfo banner) {
        if (!Log.isLoggable(TAG, Log.DEBUG) || banner == null) {
            return;
        }
        StringBuilder ancestry = new StringBuilder();
        AccessibilityNodeInfo up = banner;
        for (int hop = 0; up != null && hop <= MAX_LIST_HOPS; hop++) {
            ancestry.append("\n  hop ").append(hop)
                    .append(" scrollable=").append(up.isScrollable())
                    .append(" children=").append(up.getChildCount())
                    .append(" class=").append(up.getClassName())
                    .append(" id=").append(up.getViewIdResourceName())
                    .append(" desc=").append(up.getContentDescription());
            up = up.getParent();
        }
        Log.d(TAG, "Overlay ad card ancestry:" + ancestry);
    }

    /**
     * Lists what the opened menu actually contains when no entry in it matched.
     *
     * <p>Only reached after the full wait has expired, so by this point the sheet
     * really is laid out and this is the real labels rather than a half-built
     * tree. Without it, "no recognised dismiss entry" is indistinguishable from
     * the timing bug that produced exactly the same line.
     */
    private void logMenuContents(long now) {
        if (!Log.isLoggable(TAG, Log.DEBUG) || now - lastMenuSnapshotAt < SNAPSHOT_THROTTLE_MS) {
            return;
        }
        lastMenuSnapshotAt = now;

        StringBuilder detail = new StringBuilder();
        for (AccessibilityNodeInfo root : youtubeRoots()) {
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited < MAX_NODES_VISITED) {
                AccessibilityNodeInfo node = queue.poll();
                if (node == null) {
                    continue;
                }
                visited++;
                if (node.isVisibleToUser()
                        && (!TextUtils.isEmpty(node.getText())
                                || !TextUtils.isEmpty(node.getContentDescription()))) {
                    detail.append("\n  clickable=").append(node.isClickable())
                            .append(" id=").append(node.getViewIdResourceName())
                            .append(" text=").append(node.getText())
                            .append(" desc=").append(node.getContentDescription());
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        }
        Log.d(TAG, "Ad options menu contents, with nothing matching DISMISS_LABELS:" + detail);
    }

    /**
     * Lists what the card actually contains when its ⋮ was not recognised, so
     * the real content description or view ID can be read off a live device and
     * added to the lists above.
     *
     * <p>Gated behind the same opt-in as every other content log, and throttled
     * hard, because this puts on-screen text into logcat.
     */
    private void logCardContents(AccessibilityNodeInfo banner, long now) {
        if (!Log.isLoggable(TAG, Log.DEBUG) || now - lastSnapshotAt < SNAPSHOT_THROTTLE_MS) {
            return;
        }
        lastSnapshotAt = now;

        // The widest container findOverflowNear would have reached, not just the
        // immediate parent. Dumping one level up listed two nodes and said
        // nothing at all about where the ⋮ actually was.
        AccessibilityNodeInfo container = banner;
        for (int hop = 0; hop < MAX_CONTAINER_HOPS && container != null; hop++) {
            AccessibilityNodeInfo parent = container.getParent();
            if (parent == null) {
                break;
            }
            container = parent;
        }
        if (container == null || container == banner) {
            Log.d(TAG, "Found an overlay ad label with no parent to search.");
            return;
        }

        logAncestry(banner);

        StringBuilder detail = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(container);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES_VISITED) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            visited++;
            detail.append("\n  clickable=").append(node.isClickable())
                    .append(" id=").append(node.getViewIdResourceName())
                    .append(" class=").append(node.getClassName())
                    .append(" text=").append(node.getText())
                    .append(" desc=").append(node.getContentDescription());
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        Log.d(TAG, "Overlay ad card found but no options control matched, listing "
                + visited + " nodes around it:" + detail);
    }

    private boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, ENABLED_BY_DEFAULT);
    }
}
