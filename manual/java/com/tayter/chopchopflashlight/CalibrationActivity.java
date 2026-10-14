package com.tayter.chopchopflashlight;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the user's own chop-chops and derives personal detection
 * thresholds via ChopTuner. Applying restarts the listener service so the
 * new thresholds take effect immediately.
 */
public class CalibrationActivity extends Activity implements SensorEventListener {

    private static final long MAX_RECORD_MS = 30000;

    private TextView txtStatus;
    private TextView txtResult;
    private Button btnRecord;
    private Button btnApply;
    private Button btnDiscard;
    private Button btnReset;
    private Button btnCopyDiag;
    private View resultsPanel;

    private SensorManager sensorManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean recording = false;
    private long recordStart;
    private final List<Long> tList = new ArrayList<>();
    private final List<float[]> aList = new ArrayList<>();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            long s = (System.currentTimeMillis() - recordStart) / 1000;
            txtStatus.setText(getString(R.string.calibrate_recording, s, tList.size()));
            handler.postDelayed(this, 250);
        }
    };

    private final Runnable autoStop = new Runnable() {
        @Override
        public void run() {
            if (recording) stopRecording();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);
        MainActivity.padForSystemBars(this, R.id.rootCal);

        txtStatus = findViewById(R.id.txtCalStatus);
        txtResult = findViewById(R.id.txtCalResult);
        btnRecord = findViewById(R.id.btnCalRecord);
        btnApply = findViewById(R.id.btnCalApply);
        btnDiscard = findViewById(R.id.btnCalDiscard);
        btnReset = findViewById(R.id.btnCalReset);
        btnCopyDiag = findViewById(R.id.btnCalCopyDiag);
        resultsPanel = findViewById(R.id.calResults);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        btnRecord.setOnClickListener(v -> {
            if (recording) stopRecording();
            else startRecording();
        });
        btnDiscard.setOnClickListener(v -> {
            resultsPanel.setVisibility(View.GONE);
            btnCopyDiag.setVisibility(View.GONE);
            txtStatus.setText("");
        });
        btnReset.setOnClickListener(v -> {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .remove(MainActivity.TH_PEAK)
                    .remove(MainActivity.TH_VALLEY)
                    .remove(MainActivity.TH_WINDOW)
                    .remove(MainActivity.TH_COOLDOWN)
                    .remove(MainActivity.TH_DIR_X)
                    .remove(MainActivity.TH_DIR_Y)
                    .remove(MainActivity.TH_DIR_Z)
                    .remove(MainActivity.TH_DIR_CONF)
                    .apply();
            restartListenerService();
            Toast.makeText(this, R.string.calibrate_reset_done, Toast.LENGTH_SHORT).show();
        });
    }

    private void startRecording() {
        tList.clear();
        aList.clear();
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null) {
            Toast.makeText(this, "No accelerometer on this device.", Toast.LENGTH_LONG).show();
            return;
        }
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        recording = true;
        recordStart = System.currentTimeMillis();
        btnRecord.setText(R.string.calibrate_stop);
        resultsPanel.setVisibility(View.GONE);
        btnCopyDiag.setVisibility(View.GONE);
        handler.post(ticker);
        handler.postDelayed(autoStop, MAX_RECORD_MS);
    }

    private void stopRecording() {
        recording = false;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(autoStop);
        sensorManager.unregisterListener(this);
        btnRecord.setText(R.string.calibrate_start);
        txtStatus.setText("");

        int n = tList.size();
        long[] t = new long[n];
        float[] ax = new float[n], ay = new float[n], az = new float[n];
        for (int i = 0; i < n; i++) {
            t[i] = tList.get(i);
            float[] a = aList.get(i);
            ax[i] = a[0];
            ay[i] = a[1];
            az[i] = a[2];
        }
        ChopTuner.TuneResult r = ChopTuner.tune(t, ax, ay, az);
        if (!r.ok) {
            txtStatus.setText(r.error);
            if (r.diagnostics != null && !r.diagnostics.isEmpty()) {
                btnCopyDiag.setVisibility(View.VISIBLE);
                final String diag = r.diagnostics;
                btnCopyDiag.setOnClickListener(v -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("chop-diagnostics", diag));
                    Toast.makeText(this, R.string.calibrate_diag_copied, Toast.LENGTH_LONG).show();
                });
            } else {
                btnCopyDiag.setVisibility(View.GONE);
            }
            return;
        }
        btnCopyDiag.setVisibility(View.GONE);
        txtResult.setText(getString(R.string.calibrate_result,
                r.pairs, r.peak, r.valley, r.windowMs, r.cooldownMs,
                Math.round(r.dirConfidence * 100), r.dirX, r.dirY, r.dirZ,
                r.strictness));
        resultsPanel.setVisibility(View.VISIBLE);
        btnApply.setOnClickListener(v -> applyResult(r));
    }

    private void applyResult(ChopTuner.TuneResult r) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                .putFloat(MainActivity.TH_PEAK, r.peak)
                .putFloat(MainActivity.TH_VALLEY, r.valley)
                .putLong(MainActivity.TH_WINDOW, r.windowMs)
                .putLong(MainActivity.TH_COOLDOWN, r.cooldownMs)
                .putFloat(MainActivity.TH_DIR_X, r.dirX)
                .putFloat(MainActivity.TH_DIR_Y, r.dirY)
                .putFloat(MainActivity.TH_DIR_Z, r.dirZ)
                .putFloat(MainActivity.TH_DIR_CONF, r.dirConfidence)
                .putFloat(MainActivity.TH_CONE_COS, r.coneCos)
                .apply();
        restartListenerService();
        Toast.makeText(this, R.string.calibrate_saved, Toast.LENGTH_LONG).show();
        finish();
    }

    private void restartListenerService() {
        if (MotionService.isRunning) {
            stopService(new Intent(this, MotionService.class));
            Intent i = new Intent(this, MotionService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!recording || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        tList.add(System.currentTimeMillis() - recordStart);
        aList.add(event.values.clone());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recording) stopRecording();
    }
}
