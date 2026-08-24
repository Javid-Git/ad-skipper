package dev.javid.adskipper;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.Log;

/**
 * Silences the music stream while a YouTube ad is on screen and hands it back
 * as soon as the ad is gone.
 *
 * <p>This exists because the skip path cannot help with the ads people actually
 * complain about. A bumper or a non-skippable ad has no button to press, so
 * there is nothing for {@link SkipAdAccessibilityService} to click — but the
 * complaint was never really the button. It was thirty seconds of shouting at a
 * phone left on the kitchen counter, which is fixable without a button.
 *
 * <h2>How the mute is taken</h2>
 *
 * <p>{@link AudioManager#ADJUST_MUTE} on {@link AudioManager#STREAM_MUSIC},
 * rather than reading the volume index, setting it to zero and putting it back
 * afterwards. A real mute is the platform's own mechanism: it preserves the
 * user's volume index for us, so unmuting restores exactly what was there
 * without this app storing it, and pressing a volume key unmutes the stream —
 * which is how the user overrides this without having to argue with it.
 *
 * <p>It needs no permission. {@code adjustStreamVolume} requires notification
 * policy access only for changes that would toggle Do Not Disturb, which means
 * the ringer and notification streams; the music stream is not one of them. So
 * this feature adds nothing to the manifest, and in particular nothing that
 * reaches data.
 *
 * <p>Audio focus was the other option and is the wrong tool: requesting it would
 * make YouTube <em>pause</em> or duck of its own accord, which is a different
 * behaviour with a different failure mode. This changes one stream's volume and
 * touches nothing else — not the ringer, not notifications, not alarms.
 *
 * <h2>Giving it back</h2>
 *
 * <p>Every path out of a mute is covered, because the failure this must never
 * produce is a phone left silent with nothing on screen to explain why:
 *
 * <ul>
 *   <li><b>The ad ended.</b> {@link #tick} releases once no ad signal has been
 *       seen for {@link #RELEASE_GRACE_MS}. The grace is what makes two ads in
 *       one break a single continuous mute instead of a burst of audio between
 *       them, and it also covers YouTube being closed, backgrounded or made
 *       unreadable mid-ad: the signal simply stops arriving.</li>
 *   <li><b>The user disagreed.</b> A volume key unmutes the stream, so if the
 *       stream is no longer muted while this class thinks it owns the mute, the
 *       user has overruled it. It drops the claim and stays out of the way for
 *       the rest of that ad.</li>
 *   <li><b>The signal is stuck.</b> {@link #MAX_MUTE_MS} is an absolute ceiling.
 *       If something on screen keeps looking like an ad for longer than any real
 *       ad break, the stream comes back and this stands down, rather than
 *       leaving a video silent for as long as it plays.</li>
 *   <li><b>The feature was switched off</b> while a mute was held.</li>
 *   <li><b>The service was switched off</b>, or the process is shutting down.</li>
 *   <li><b>The process died mid-ad.</b> The one case a running instance cannot
 *       handle, so the claim is written to disk when the mute is taken.
 *       {@link #recoverStaleMute()} finds it on the next connection and restores
 *       the stream.</li>
 * </ul>
 *
 * <p>A mute is never taken over a stream the user has already muted. Claiming it
 * would mean unmuting a phone that had been deliberately silenced, the moment
 * the ad ended.
 */
final class AdMuter {

    private static final String TAG = "AdSkipper";

    private static final String PREFS = "audio";

    /** The user-facing toggle. */
    private static final String KEY_ENABLED = "mute_ads";

    /**
     * Written while the mute belongs to this app. On disk rather than in memory
     * alone, because the one thing an in-memory flag cannot survive is the case
     * that matters: the process being killed between muting and unmuting.
     */
    private static final String KEY_HOLDING = "mute_held";

    /**
     * On by default. Silencing an ad is the same job as skipping one, so anyone
     * who installed this app has already asked for it, and every way out of a
     * mute is automatic, so the default cannot leave someone stuck.
     */
    static final boolean ENABLED_BY_DEFAULT = true;

    /**
     * How long the ad signal may be missing before the stream is released.
     *
     * <p>Long enough to bridge the gap between two ads in one break, and the
     * moment YouTube spends tearing an overlay down; short enough that the worst
     * case — the first instant of a video being silent — is barely noticeable.
     * The asymmetry is deliberate. A fraction of a second of silence over
     * content is a much better error than a fraction of a second of ad at full
     * volume, which is the exact thing this is here to prevent.
     */
    private static final long RELEASE_GRACE_MS = 900L;

    /**
     * Absolute ceiling on a single mute.
     *
     * <p>Two non-skippable ads back to back is about a minute, so this is
     * generous for anything real. It is not the timer that ends a normal mute —
     * the ad signal disappearing does that — it is the backstop for a signal
     * that never disappears, which would otherwise mean a silent video rather
     * than a silent ad.
     */
    private static final long MAX_MUTE_MS = 90_000L;

    private static final int STREAM = AudioManager.STREAM_MUSIC;

    private final AudioManager audio;
    private final SharedPreferences prefs;

    /** Whether the current mute on {@link #STREAM} is this app's doing. */
    private boolean holding;

    private long lastSignalAt;
    private long mutedAt;

    /**
     * Set after this class gives up on the ad currently on screen — because the
     * user overrode it, or because the mute hit its ceiling. It stops the next
     * scan re-muting the same thing immediately, and clears once the ad signal
     * has actually gone.
     */
    private boolean standDown;

    private boolean fixedVolumeReported;

    AdMuter(Context context) {
        this.audio = context.getSystemService(AudioManager.class);
        this.prefs = prefs(context);
        if (audio == null) {
            Log.w(TAG, "No AudioManager on this device; ads will be skipped but not muted.");
        }
    }

    /**
     * @return whether the user wants ads muted. Read live rather than cached:
     *         the toggle lives in the same process as the service, so a change
     *         on the app screen takes effect on the next scan.
     */
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
     * Restores a mute that outlived the process which took it.
     *
     * <p>Called on every connection. If the app was killed with an ad playing —
     * a force stop, a vendor cleaner, low memory — the stream is still muted and
     * nothing in memory remembers why. This is the one repair that cannot be
     * made by the instance that caused it.
     */
    void recoverStaleMute() {
        if (!prefs.getBoolean(KEY_HOLDING, false)) {
            return;
        }
        Log.i(TAG, "A mute from a previous run was still held; restoring the music stream.");
        holding = true;
        release("recovered after a restart");
    }

    /**
     * Records that an ad is on screen right now, and mutes unless this app is
     * already holding the stream.
     *
     * @param now {@code SystemClock.uptimeMillis()}, passed in so that one scan
     *            works from one clock reading throughout
     */
    void onAdSignal(long now) {
        lastSignalAt = now;

        if (holding || standDown || audio == null || !isEnabled()) {
            return;
        }
        if (audio.isVolumeFixed()) {
            reportFixedVolumeOnce();
            return;
        }
        if (audio.isStreamMute(STREAM)) {
            // Already silent, and not by us. Taking the claim here would mean
            // unmuting a phone the user had deliberately muted as soon as the ad
            // finished.
            return;
        }
        if (!adjust(AudioManager.ADJUST_MUTE)) {
            return;
        }

        holding = true;
        mutedAt = now;
        prefs.edit().putBoolean(KEY_HOLDING, true).apply();
        Log.i(TAG, "Muted the music stream for an ad.");
    }

    /**
     * Runs on every monitor tick, ad or no ad, and owns every automatic way out
     * of a mute.
     *
     * <p>Deliberately driven by the monitor rather than by the scan: a scan can
     * be skipped by a throttle, a cooldown or a backoff, and the one thing that
     * must never be skippable is giving the audio back.
     */
    void tick(long now) {
        boolean signalStale = now - lastSignalAt > RELEASE_GRACE_MS;
        if (signalStale) {
            // Whatever this stood down over is gone, so the next ad gets a fresh
            // decision.
            standDown = false;
        }
        if (!holding) {
            return;
        }
        if (!isEnabled()) {
            release("muting was switched off");
            return;
        }
        if (audio != null && !audio.isStreamMute(STREAM)) {
            // A volume key unmutes the stream, so this is the user saying no.
            // Drop the claim without touching the volume they just set, and
            // leave the rest of this ad alone.
            Log.i(TAG, "The music stream was unmuted from outside this app; leaving it be.");
            forget();
            standDown = true;
            return;
        }
        if (signalStale) {
            release("the ad is over");
            return;
        }
        if (now - mutedAt > MAX_MUTE_MS) {
            Log.w(TAG, "Something has looked like an ad for over " + (MAX_MUTE_MS / 1000)
                    + "s, which no real ad break does. Restoring the music stream and"
                    + " leaving the audio alone until it clears.");
            release("mute ceiling reached");
            standDown = true;
        }
    }

    /**
     * Gives the stream back now, if this app is the one holding it.
     *
     * <p>Idempotent, and safe to call when nothing is muted: the persisted claim
     * is cleared either way, so a stale flag cannot outlive a release.
     *
     * @param reason logged, so the log says why the audio came back
     */
    void release(String reason) {
        boolean wasHolding = holding;
        forget();
        if (!wasHolding) {
            return;
        }
        if (adjust(AudioManager.ADJUST_UNMUTE)) {
            Log.i(TAG, "Restored the music stream (" + reason + ").");
        }
    }

    /** Drops the claim, on disk and in memory, without touching the stream. */
    private void forget() {
        holding = false;
        mutedAt = 0L;
        prefs.edit().remove(KEY_HOLDING).apply();
    }

    private boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, ENABLED_BY_DEFAULT);
    }

    private boolean adjust(int direction) {
        if (audio == null) {
            return false;
        }
        try {
            audio.adjustStreamVolume(STREAM, direction, 0 /* no volume UI */);
            return true;
        } catch (RuntimeException e) {
            // Documented for adjustments that would toggle Do Not Disturb, which
            // a music-stream mute does not. A vendor build that refuses it anyway
            // must not take the service down with it: an uncaught exception here
            // would crash the app, and Android switches a crashed accessibility
            // service off.
            Log.w(TAG, "This device refused a music-stream volume change", e);
            return false;
        }
    }

    /**
     * Some devices — TV boxes, docks, a few tablets — run at a fixed volume and
     * ignore every mute API. Worth saying once, because skipping still works and
     * only the muting is silently unavailable.
     */
    private void reportFixedVolumeOnce() {
        if (fixedVolumeReported) {
            return;
        }
        fixedVolumeReported = true;
        Log.i(TAG, "This device implements a fixed volume policy, so ads cannot be muted."
                + " Skipping is unaffected.");
    }
}
