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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Status screen and setup gate.
 *
 * <p>The screen will not hand over the route to Accessibility settings until
 * every precondition in {@link Preflight} holds. Turning the service on before
 * the device is set up to keep it running is how you end up with an app that
 * works for ten minutes and then silently stops — which is worse than one that
 * never started, because you stop checking.
 *
 * <p>The gate covers this screen only. It cannot prevent someone enabling the
 * service directly in system Settings, and does not try to; that route is
 * detected instead, and reported as {@link KeepAliveService.Status#NOT_CONFIGURED}
 * in the notification.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_POST_NOTIFICATIONS = 1;

    private TextView statusView;
    private TextView gateExplanationView;
    private LinearLayout checklistView;
    private CheckBox attestView;
    private Button openSettingsButton;

    /** Asked at most once per launch, so returning from Settings does not nag. */
    private boolean notificationPermissionRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        gateExplanationView = findViewById(R.id.gate_explanation);
        checklistView = findViewById(R.id.checklist);
        attestView = findViewById(R.id.attest);
        openSettingsButton = findViewById(R.id.open_settings);

        openSettingsButton.setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.open_app_info).setOnClickListener(v -> openAppInfo());

        attestView.setOnCheckedChangeListener((v, checked) -> {
            Preflight.setAttested(this, checked);
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read everything on every resume, so returning from any settings
        // screen reflects what was just changed.
        refresh();

        if (isServiceBound()) {
            maybeRequestNotificationPermission();
            // Retry path for the keep-alive: a start from the accessibility
            // service can be refused under Android 12's background-start rules,
            // but a start from a resumed activity never is.
            KeepAliveService.start(this);
        }
    }

    private void refresh() {
        boolean bound = isServiceBound();
        statusView.setText(statusTextRes(bound));

        attestView.setChecked(Preflight.isAttested(this));
        buildChecklist();

        // The gate. Everything verifiable has to pass and the two unverifiable
        // steps have to be confirmed before this screen will lead anyone to the
        // switch that turns the service on.
        boolean configured = Preflight.isConfigured(this);
        openSettingsButton.setEnabled(configured);
        gateExplanationView.setText(configured
                ? R.string.preflight_gate_open
                : R.string.preflight_gate_blocked);
    }

    /** Rebuilds the checklist rows from a fresh evaluation. */
    private void buildChecklist() {
        checklistView.removeAllViews();
        addRow(R.string.preflight_youtube, Preflight.isYouTubeInstalled(this), null);
        addRow(R.string.preflight_notifications, Preflight.areNotificationsUsable(this),
                v -> openNotificationSettings());
        addRow(R.string.preflight_battery, Preflight.isBatteryUnrestricted(this),
                v -> openBatterySettings());
    }

    /**
     * One row: a pass/fail marker, the requirement, and — when it is failing
     * and there is somewhere to send the user — a button that goes straight
     * there.
     */
    private void addRow(int labelRes, boolean passing, View.OnClickListener fix) {
        TextView label = new TextView(this);
        label.setText(getString(passing ? R.string.preflight_row_pass
                : R.string.preflight_row_fail, getString(labelRes)));
        label.setTextSize(15f);
        label.setPadding(0, dp(8), 0, 0);
        checklistView.addView(label);

        if (passing || fix == null) {
            return;
        }
        Button button = new Button(this);
        button.setText(R.string.preflight_fix);
        button.setOnClickListener(fix);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.START;
        button.setLayoutParams(params);
        checklistView.addView(button);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Four states, because "enabled", "running" and "working" are three
     * different facts, and every gap between them is a way this app can look
     * fine while doing nothing.
     */
    private int statusTextRes(boolean bound) {
        if (bound) {
            if (SkipAdAccessibilityService.isBlind()) {
                return R.string.status_blind;
            }
            return Preflight.isConfigured(this)
                    ? R.string.status_enabled
                    : R.string.status_not_configured;
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
            // The short form ("pkg/.Class") is also valid and
            // unflattenFromString leaves the leading dot in place.
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

    private void openAccessibilitySettings() {
        // Belt and braces: the button is disabled when preflight fails, but a
        // gate that is only enforced in the view is not a gate.
        if (!Preflight.isConfigured(this)) {
            Toast.makeText(this, R.string.preflight_gate_blocked, Toast.LENGTH_LONG).show();
            return;
        }
        start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), R.string.settings_unavailable);
    }

    /**
     * Opens this app's App info page, where every vendor skin hangs its own
     * per-app power controls.
     */
    private void openAppInfo() {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)),
                R.string.app_info_unavailable);
    }

    /**
     * Asks the platform for the battery-optimisation exemption directly.
     *
     * <p>{@code ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} sets exactly the
     * value {@link Preflight#isBatteryUnrestricted} reads, so the fix and the
     * check are the same thing. The plain
     * {@code ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS} list was worse on two
     * counts: it drops the user into an unfiltered list to find the app
     * themselves, and on skins that keep their own separate battery screen it
     * is not the control they are shown elsewhere — so the check could stay
     * red after they had apparently just fixed it.
     */
    private void openBatterySettings() {
        start(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.fromParts("package", getPackageName(), null)),
                R.string.battery_settings_unavailable);
    }

    private void openNotificationSettings() {
        start(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()),
                R.string.notification_settings_unavailable);
    }

    /**
     * Not every ROM resolves every settings action — some vendor skins bury
     * them, and restricted profiles hide them entirely. An unhandled
     * ActivityNotFoundException would crash the app on tap.
     */
    private void start(Intent intent, int unavailableMessage) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, unavailableMessage, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The keep-alive notification has to be visible to be worth anything, and
     * on Android 13+ that needs a runtime grant.
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            refresh();
        }
    }
}
