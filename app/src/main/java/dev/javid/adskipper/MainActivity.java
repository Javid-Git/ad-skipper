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
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
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
 * detected instead and reported as
 * {@link KeepAliveService.Status#NOT_CONFIGURED}.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_POST_NOTIFICATIONS = 1;

    /** What the screen is currently reporting. Drives the pill and the body text. */
    private enum UiState {
        ACTIVE(R.string.status_label_active, R.string.status_enabled,
                R.drawable.ic_state_ok, R.color.state_ok, R.color.state_ok_bg),
        NOT_CONFIGURED(R.string.status_label_not_configured, R.string.status_not_configured,
                R.drawable.ic_state_warn, R.color.state_warn, R.color.state_warn_bg),
        INACTIVE(R.string.status_label_inactive, R.string.status_blind,
                R.drawable.ic_state_bad, R.color.state_bad, R.color.state_bad_bg),
        STOPPED(R.string.status_label_stopped, R.string.status_not_running,
                R.drawable.ic_state_bad, R.color.state_bad, R.color.state_bad_bg),
        OFF(R.string.status_label_off, R.string.status_disabled,
                R.drawable.ic_state_bad, R.color.state_bad, R.color.state_bad_bg);

        final int labelRes;
        final int bodyRes;
        final int iconRes;
        final int colorRes;
        final int pillColorRes;

        UiState(int labelRes, int bodyRes, int iconRes, int colorRes, int pillColorRes) {
            this.labelRes = labelRes;
            this.bodyRes = bodyRes;
            this.iconRes = iconRes;
            this.colorRes = colorRes;
            this.pillColorRes = pillColorRes;
        }
    }

    private TextView statusView;
    private TextView statusLabelView;
    private TextView gateExplanationView;
    private ImageView statusIconView;
    private ImageView attestIconView;
    private View statusPillView;
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
        statusLabelView = findViewById(R.id.status_label);
        statusIconView = findViewById(R.id.status_icon);
        statusPillView = findViewById(R.id.status_pill);
        attestIconView = findViewById(R.id.attest_icon);
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
        UiState state = currentUiState();
        statusLabelView.setText(state.labelRes);
        statusLabelView.setTextColor(getColor(state.colorRes));
        statusView.setText(state.bodyRes);
        statusIconView.setImageResource(state.iconRes);
        // mutate() so tinting this pill does not recolour every other view
        // sharing the same drawable instance.
        statusPillView.getBackground().mutate().setTint(getColor(state.pillColorRes));

        boolean attested = Preflight.isAttested(this);
        attestView.setChecked(attested);
        attestIconView.setImageResource(
                attested ? R.drawable.ic_state_ok : R.drawable.ic_state_warn);

        buildChecklist();

        // The gate. Everything verifiable has to pass and the step that cannot
        // be verified has to be confirmed before this screen will lead anyone
        // to the switch that turns the service on.
        boolean configured = Preflight.isConfigured(this);
        openSettingsButton.setEnabled(configured);
        gateExplanationView.setText(configured
                ? R.string.preflight_gate_open
                : R.string.preflight_gate_blocked);
    }

    private UiState currentUiState() {
        if (isServiceBound()) {
            if (SkipAdAccessibilityService.isBlind()) {
                return UiState.INACTIVE;
            }
            return Preflight.isConfigured(this) ? UiState.ACTIVE : UiState.NOT_CONFIGURED;
        }
        return isListedInSecureSetting() ? UiState.STOPPED : UiState.OFF;
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
     * One row: a tinted state icon, the requirement, and — when it is failing
     * and there is somewhere to send the user — a button that goes straight
     * there.
     */
    private void addRow(int labelRes, boolean passing, View.OnClickListener fix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, passing || fix == null ? dp(14) : dp(8));

        ImageView icon = new ImageView(this);
        icon.setImageResource(passing ? R.drawable.ic_state_ok : R.drawable.ic_state_bad);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.addView(icon);

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(15f);
        label.setLineSpacing(dp(2), 1f);
        label.setTextColor(getColor(passing ? R.color.text_secondary : R.color.text_primary));
        // Set on the LayoutParams, not via a style: layout_* attributes come
        // from the parent's LayoutParams and a style passed to the View
        // constructor never supplies them, which left the text touching the icon.
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.leftMargin = dp(12);
        label.setLayoutParams(labelParams);
        row.addView(label);

        checklistView.addView(row);

        if (passing || fix == null) {
            return;
        }
        Button button = new Button(this);
        button.setText(R.string.preflight_fix);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTextColor(getColor(R.color.text_on_accent));
        button.setBackgroundResource(R.drawable.bg_button_primary);
        button.setStateListAnimator(null);
        button.setOnClickListener(fix);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        params.leftMargin = dp(34);
        params.bottomMargin = dp(14);
        button.setLayoutParams(params);
        checklistView.addView(button);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        // the distinction currentUiState depends on.
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
     * Opens this app's App info page, where every skin hangs its own per-app
     * power controls.
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
     * check are the same thing. The plain settings-list action was worse on two
     * counts: it drops the user into an unfiltered list to find the app
     * themselves, and on skins that keep their own separate battery screen it
     * is not the control they are shown elsewhere — so the check could stay red
     * after they had apparently just fixed it.
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
     * Not every ROM resolves every settings action — some skins bury them, and
     * restricted profiles hide them entirely. An unhandled
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
