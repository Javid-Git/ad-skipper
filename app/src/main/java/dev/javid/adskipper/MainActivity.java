package dev.javid.adskipper;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Single screen whose only job is to report whether the skip service is running
 * and to open the settings pages where it can be turned on and kept alive.
 *
 * <p>Android deliberately gives an app no way to enable its own accessibility
 * service, so this screen can lead the user there but not do it for them.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_POST_NOTIFICATIONS = 1;

    private TextView statusView;
    private TextView vendorTipsView;

    /** Asked at most once per launch, so returning from Settings does not nag. */
    private boolean notificationPermissionRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        vendorTipsView = findViewById(R.id.vendor_tips);

        Button openSettings = findViewById(R.id.open_settings);
        openSettings.setOnClickListener(v -> openAccessibilitySettings());

        Button openAppInfo = findViewById(R.id.open_app_info);
        openAppInfo.setOnClickListener(v -> openAppInfo());

        vendorTipsView.setText(vendorTipsRes());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read on every resume so returning from Settings reflects the change.
        boolean running = isServiceBound();
        statusView.setText(statusTextRes(running));

        if (running) {
            // Retry path for the keep-alive: a start from the accessibility
            // service can be refused under Android 12's background-start rules,
            // but a start from a resumed activity never is.
            maybeRequestNotificationPermission();
            KeepAliveService.start(this);
        }
    }

    /**
     * Three states, because "enabled" and "running" are different facts and the
     * gap between them is this app's most confusing failure mode.
     *
     * <p>{@code ENABLED_ACCESSIBILITY_SERVICES} is a persisted setting: it
     * records the user's consent and survives the process being killed, which
     * is why Accessibility settings can still show the toggle as on. The
     * binding is what actually does the work, and a vendor cleaner can destroy
     * it without touching the setting. When the two disagree, say so plainly
     * rather than reporting a bare "off" that the settings screen contradicts.
     */
    private int statusTextRes(boolean running) {
        if (running) {
            return R.string.status_enabled;
        }
        return isListedInSecureSetting()
                ? R.string.status_not_running
                : R.string.status_disabled;
    }

    /**
     * @return whether the platform currently holds a live binding to this app's
     *         accessibility service
     */
    private boolean isServiceBound() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager == null) {
            return false;
        }

        // Reflects bound services, not the stored setting -- which is exactly
        // the distinction statusTextRes depends on.
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabled == null) {
            return false;
        }

        ComponentName self = new ComponentName(this, SkipAdAccessibilityService.class);
        // Compared component-wise rather than against getId(), whose exact
        // string form is not part of the platform's contract.
        for (AccessibilityServiceInfo info : enabled) {
            ResolveInfo resolved = info.getResolveInfo();
            if (resolved == null || resolved.serviceInfo == null) {
                continue;
            }
            if (self.getPackageName().equals(resolved.serviceInfo.packageName)
                    && self.getClassName().equals(resolved.serviceInfo.name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether this component appears in the persisted enabled-services
     *         setting, regardless of whether anything is actually bound
     */
    private boolean isListedInSecureSetting() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }

        ComponentName self = new ComponentName(this, SkipAdAccessibilityService.class);
        for (String entry : enabled.split(":")) {
            ComponentName parsed = ComponentName.unflattenFromString(entry.trim());
            if (parsed == null) {
                continue;
            }
            // Entries are usually written in full form, but the short form
            // ("pkg/.Class") is also valid and unflattenFromString leaves the
            // leading dot in place rather than expanding it.
            String className = parsed.getClassName();
            if (className.startsWith(".")) {
                className = parsed.getPackageName() + className;
            }
            if (self.getPackageName().equals(parsed.getPackageName())
                    && self.getClassName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Not every ROM resolves {@link Settings#ACTION_ACCESSIBILITY_SETTINGS} --
     * some vendor skins bury it, and restricted profiles hide it entirely. An
     * unhandled ActivityNotFoundException would crash the app on tap, so fall
     * back to telling the user where to go instead.
     */
    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Opens this app's App info page.
     *
     * <p>This is the one battery-related destination AOSP guarantees, and every
     * vendor skin hangs its own per-app power controls off it: Samsung's
     * "Battery", Xiaomi's "Battery saver", ColorOS's "Battery usage". Launching
     * the vendors' own activities directly is not viable, because the component
     * names move between OS versions and several builds do not export them,
     * which raises SecurityException rather than the ActivityNotFoundException
     * a caller would think to catch.
     */
    private void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.app_info_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Picks the keep-alive instructions for this device.
     *
     * <p>Text rather than deep links, for the reason given in {@link
     * #openAppInfo()}. Instructions degrade gracefully when a vendor renames a
     * menu; a hardcoded intent to a renamed activity just fails.
     */
    private int vendorTipsRes() {
        String id = lower(Build.MANUFACTURER) + " " + lower(Build.BRAND);
        if (id.contains("xiaomi") || id.contains("redmi") || id.contains("poco")) {
            return R.string.vendor_tips_xiaomi;
        }
        if (id.contains("samsung")) {
            return R.string.vendor_tips_samsung;
        }
        if (id.contains("oneplus") || id.contains("oppo") || id.contains("realme")) {
            return R.string.vendor_tips_oppo;
        }
        if (id.contains("huawei") || id.contains("honor")) {
            return R.string.vendor_tips_huawei;
        }
        if (id.contains("vivo") || id.contains("iqoo")) {
            return R.string.vendor_tips_vivo;
        }
        return R.string.vendor_tips_generic;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    /**
     * The keep-alive notification has to be visible to be worth anything, and
     * on Android 13+ that needs a runtime grant. Declining it costs only the
     * resilience; skipping itself is unaffected.
     */
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || notificationPermissionRequested) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionRequested = true;
        requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_POST_NOTIFICATIONS);
    }
}
