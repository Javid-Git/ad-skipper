package dev.javid.adskipper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Single screen whose only job is to report whether the skip service is running
 * and to open the settings page where it can be turned on.
 *
 * <p>Android deliberately gives an app no way to enable its own accessibility
 * service, so this screen can lead the user there but not do it for them.
 */
public class MainActivity extends Activity {

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);

        Button openSettings = findViewById(R.id.open_settings);
        openSettings.setOnClickListener(v -> openAccessibilitySettings());
    }

    /**
     * Not every ROM resolves {@link Settings#ACTION_ACCESSIBILITY_SETTINGS} —
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

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read on every resume so returning from Settings reflects the change.
        statusView.setText(isSkipServiceEnabled()
                ? R.string.status_enabled
                : R.string.status_disabled);
    }

    /**
     * @return whether this app's own accessibility service is currently enabled
     */
    private boolean isSkipServiceEnabled() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager == null) {
            return false;
        }

        ComponentName self = new ComponentName(this, SkipAdAccessibilityService.class);
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabled == null) {
            return false;
        }

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
}
