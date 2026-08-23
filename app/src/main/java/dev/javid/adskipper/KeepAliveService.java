package dev.javid.adskipper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Holds an ongoing notification for exactly as long as the skip service is
 * connected, so the app's process is a less attractive target for the memory
 * cleaners several vendor ROMs ship.
 *
 * <h2>What this actually buys, and what it does not</h2>
 * <ul>
 *   <li>It does <em>not</em> survive a user-invoked <b>Force stop</b>, nor the
 *       one-key / swipe-up cleaners on skins that implement those as a force
 *       stop. Nothing an ordinary app can do survives those.</li>
 *   <li>It does keep the process out of the "nothing user-visible here" bucket
 *       that cleaner heuristics and low-memory reclaim both favour. Those
 *       heuristics generally skip a process holding a visible ongoing
 *       notification, which is the whole reason this class exists.</li>
 *   <li>An accessibility service is bound by {@code system_server} and already
 *       has reasonable standing against plain low-memory kills, so the gain
 *       here is mostly against vendor cleaners rather than against the kernel
 *       LMK.</li>
 * </ul>
 *
 * <h2>Why it is not started from anywhere else</h2>
 * <p>The lifetime is tied to {@link SkipAdAccessibilityService} rather than to
 * the launcher activity, so the notification appears when — and only when —
 * there is actually a service running to protect. A user who has not enabled
 * the accessibility service never sees it.
 *
 * <p>This class adds no data access. It needs {@code FOREGROUND_SERVICE},
 * {@code FOREGROUND_SERVICE_SPECIAL_USE} and {@code POST_NOTIFICATIONS}; none
 * of the three map to a supplementary GID or to any runtime data permission,
 * and {@code INTERNET} is still absent.
 */
public class KeepAliveService extends Service {

    private static final String TAG = "AdSkipper";

    private static final String CHANNEL_ID = "keep_alive";
    private static final int NOTIFICATION_ID = 1;

    /**
     * Starts the keep-alive service, tolerating the background-start rules.
     *
     * <p>Android 12 forbids most background foreground-service starts and
     * throws {@code ForegroundServiceStartNotAllowedException}. Whether an
     * active accessibility service is exempt is not something to rely on, so
     * the failure is caught rather than allowed to crash: {@link MainActivity}
     * retries from a resumed activity, which is unambiguously permitted.
     */
    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
        } catch (RuntimeException e) {
            // Includes ForegroundServiceStartNotAllowedException (an
            // IllegalStateException) and SecurityException.
            Log.d(TAG, "Keep-alive start refused; will retry when the app is opened", e);
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, KeepAliveService.class));
        } catch (RuntimeException e) {
            Log.d(TAG, "Ignoring error while stopping the keep-alive service", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            promote();
        } catch (RuntimeException e) {
            // If the platform refuses the promotion there is nothing useful
            // left for this service to do, and lingering as a plain background
            // service would only waste the process. Skipping still works; only
            // the resilience is lost.
            Log.w(TAG, "Could not promote the keep-alive service to foreground", e);
            stopSelf();
        }
        // Deliberately not sticky. If the process dies, system_server re-binds
        // the accessibility service, and its onServiceConnected starts this
        // again — so the notification can never outlive the thing it protects.
        return START_NOT_STICKY;
    }

    private void promote() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /**
     * IMPORTANCE_LOW: present and silent. The notification has to be visible to
     * do its job — a minimised or hidden one defeats the point — but it should
     * never make a sound or peek.
     */
    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.keep_alive_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.keep_alive_title))
                .setContentText(getString(R.string.keep_alive_text))
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setShowWhen(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Without this the platform may defer showing the notification for
            // up to 10 seconds, which is exactly the window a cleaner sweep
            // would see the process as having nothing user-visible.
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
