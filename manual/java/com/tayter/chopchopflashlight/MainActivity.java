package com.tayter.chopchopflashlight;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    public static final String PREFS = "chopchopflashlight";
    public static final String KEY_GESTURE_ENABLED = "gesture_enabled";
    public static final String KEY_BUZZ = "buzz_enabled";
    public static final String TH_PEAK = "thresh_peak";
    public static final String TH_VALLEY = "thresh_valley";
    public static final String TH_WINDOW = "thresh_window";
    public static final String TH_COOLDOWN = "thresh_cooldown";
    public static final String TH_DIR_X = "thresh_dir_x";
    public static final String TH_DIR_Y = "thresh_dir_y";
    public static final String TH_DIR_Z = "thresh_dir_z";
    public static final String TH_DIR_CONF = "thresh_dir_conf";
    public static final String TH_CONE_COS = "thresh_cone_cos";

    private Switch switchGesture;
    private Switch switchBuzz;
    private TextView txtStatus;
    private TextView txtTorch;
    private TextView txtVersion;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable torchPoller = new Runnable() {
        @Override
        public void run() {
            refreshTorch();
            uiHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        padForSystemBars(this, R.id.rootMain);

        switchGesture = findViewById(R.id.switchGesture);
        switchBuzz = findViewById(R.id.switchBuzz);
        txtStatus = findViewById(R.id.txtStatus);
        txtTorch = findViewById(R.id.txtTorch);
        txtVersion = findViewById(R.id.txtVersion);

        findViewById(R.id.btnRecalibrate).setOnClickListener(
                v -> startActivity(new Intent(this, CalibrationActivity.class)));

        // Gesture defaults ON: a fresh install starts listening immediately.
        SharedPreferences p = prefs();
        if (!p.contains(KEY_GESTURE_ENABLED)) {
            p.edit().putBoolean(KEY_GESTURE_ENABLED, true).apply();
        }
        if (!MotionService.isRunning && p.getBoolean(KEY_GESTURE_ENABLED, true)) {
            startMotionService();
        }

        switchGesture.setChecked(MotionService.isRunning);
        switchBuzz.setChecked(p.getBoolean(KEY_BUZZ, true));
        switchGesture.setOnCheckedChangeListener(gestureListener());
        switchBuzz.setOnCheckedChangeListener(buzzListener());

        try {
            txtVersion.setText("v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {
        }
        refreshUi();
    }

    /** Shared with CalibrationActivity: target SDK 36 enforces edge-to-edge,
     *  so draw behind the system bars and pad content with the real insets. */
    static void padForSystemBars(Activity activity, int rootId) {
        if (Build.VERSION.SDK_INT < 30) return;
        activity.getWindow().setDecorFitsSystemWindows(false);
        View root = activity.findViewById(rootId);
        final int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(pad, bars.top + pad, pad, bars.bottom + pad);
            return insets;
        });
    }

    private CompoundButton.OnCheckedChangeListener gestureListener() {
        return (CompoundButton button, boolean checked) -> {
            // Remember the choice so a reboot can restore it.
            prefs().edit().putBoolean(KEY_GESTURE_ENABLED, checked).apply();
            if (checked && !MotionService.isRunning) {
                startMotionService();
            } else if (!checked && MotionService.isRunning) {
                stopMotionService();
            }
            refreshUi();
        };
    }

    private CompoundButton.OnCheckedChangeListener buzzListener() {
        return (CompoundButton button, boolean checked) -> {
            prefs().edit().putBoolean(KEY_BUZZ, checked).apply();
        };
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Keep the switches honest if the service died while we were away.
        syncSwitches();
        refreshUi();
        uiHandler.post(torchPoller);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(torchPoller);
    }

    /**
     * Sets both switches from the real state without firing their listeners.
     * Needed because the service starts asynchronously — reading isRunning
     * immediately after startForegroundService() still returns false.
     */
    private void syncSwitches() {
        switchGesture.setOnCheckedChangeListener(null);
        switchGesture.setChecked(MotionService.isRunning);
        switchGesture.setOnCheckedChangeListener(gestureListener());
        switchBuzz.setOnCheckedChangeListener(null);
        switchBuzz.setChecked(prefs().getBoolean(KEY_BUZZ, true));
        switchBuzz.setOnCheckedChangeListener(buzzListener());
    }

    private void refreshUi() {
        Context ctx = this;
        if (MotionService.isRunning) {
            txtStatus.setText(R.string.status_running);
            txtStatus.setTextColor(ctx.getColor(R.color.status_on));
        } else {
            txtStatus.setText(R.string.status_stopped);
            txtStatus.setTextColor(ctx.getColor(R.color.status_off));
        }
        refreshTorch();
    }

    private void refreshTorch() {
        boolean on = MotionService.torchOn;
        txtTorch.setText(on ? R.string.torch_on : R.string.torch_off);
        txtTorch.setBackgroundTintList(ColorStateList.valueOf(
                getColor(on ? R.color.torch_on_bg : R.color.torch_off_bg)));
        txtTorch.setTextColor(
                getColor(on ? R.color.torch_on_text : R.color.torch_off_text));
    }

    private void startMotionService() {
        Intent i = new Intent(this, MotionService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        // The service reports isRunning from its own onCreate, which lands
        // after this returns — re-sync once it has had a moment to start.
        switchGesture.postDelayed(() -> {
            syncSwitches();
            refreshUi();
        }, 600);
    }

    private void stopMotionService() {
        stopService(new Intent(this, MotionService.class));
        switchGesture.postDelayed(() -> {
            syncSwitches();
            refreshUi();
        }, 600);
    }
}
