package dev.javid.adskipper;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

/**
 * The conditions that have to hold before this app can actually do its job,
 * and an honest account of which of them can be checked at all.
 *
 * <p>Everything here is platform API only. No vendor detection, no hardcoded
 * component names, no per-manufacturer behaviour — those break silently
 * between OS versions and cannot be tested.
 *
 * <h2>Why one condition has no check</h2>
 * <p>Whether the device permits this app to keep running in the background is
 * the single thing that decides if it works, and there is no way to read it:
 *
 * <ul>
 *   <li>Background-start and auto-start permissions are vendor app-ops with no
 *       public name for {@code unsafeCheckOpNoThrow}, and the reflection route
 *       into {@code checkOpNoThrow} is blocked by non-SDK interface
 *       enforcement at this target SDK.</li>
 *   <li>Whether an app is pinned in Recents has no API whatsoever. Recents is
 *       a system UI surface and apps have no visibility into it.</li>
 * </ul>
 *
 * <p>So that condition is <em>attested</em>, not verified: the user confirms
 * once, and {@link #isAttested} records the confirmation. It is deliberately
 * never inferred from anything else. A green tick the app cannot back up would
 * be worse than no tick, because it would be trusted.
 *
 * <p>Everything else here is genuinely checked on every call.
 */
final class Preflight {

    private static final String PREFS = "preflight";
    private static final String KEY_ATTESTED = "manual_steps_confirmed";

    private Preflight() {
    }

    /**
     * @return whether every condition holds — the verified ones and the
     *         attested ones. This is what separates "Active" from
     *         "Not Configured" in the notification.
     */
    static boolean isConfigured(Context context) {
        return isYouTubeInstalled(context)
                && areNotificationsUsable(context)
                && isBatteryUnrestricted(context)
                && isAttested(context);
    }

    /**
     * There is nothing to skip without it, and the service is scoped to this
     * one package. Requires the {@code <queries>} entry in the manifest to be
     * visible at all on API 30+.
     */
    static boolean isYouTubeInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(
                    SkipAdAccessibilityService.TARGET_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * The keep-alive notification has to be visible to do its job — both as the
     * thing vendor cleaners skip over, and as the only place the app can tell
     * you it has stopped working. A blocked channel silently removes both.
     */
    static boolean areNotificationsUsable(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(
                KeepAliveService.CHANNEL_ID);
        // A channel that does not exist yet has not been blocked; it is created
        // when the service first starts.
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /**
     * Doze and App Standby do not stop an accessibility service directly, but a
     * battery-restricted app is the first thing vendor power managers reach for.
     */
    static boolean isBatteryUnrestricted(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * @return whether the user has confirmed the two steps the app cannot check
     */
    static boolean isAttested(Context context) {
        return prefs(context).getBoolean(KEY_ATTESTED, false);
    }

    static void setAttested(Context context, boolean attested) {
        prefs(context).edit().putBoolean(KEY_ATTESTED, attested).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
