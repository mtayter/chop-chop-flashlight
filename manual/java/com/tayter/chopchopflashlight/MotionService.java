package com.tayter.chopchopflashlight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Foreground service that listens to the accelerometer and fires the
 * chop-chop gesture to toggle the flashlight, even with the screen off
 * (foreground service + partial wake lock).
 */
public class MotionService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "listening";
    private static final String LEGACY_CHANNEL_ID = "gestures";
    private static final int NOTIF_ID = 1;

    public static volatile boolean isRunning = false;
    // Set when the service creates its detector; lets the UI fetch the last
    // trigger trace for debugging. (Debug builds only.)
    public static volatile ChopDetector detectorForDebug = null;
    public static volatile boolean torchOn = false;

    private SensorManager sensorManager;
    private CameraManager cameraManager;
    private PowerManager.WakeLock wakeLock;

    private String cameraId;

    private ChopDetector chopDetector;

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChopChopFlashlight:motion");
        wakeLock.acquire(12 * 60 * 60 * 1000L /* 12h, released in onDestroy */);
        createChannel();
        cameraId = findFlashCameraId();
        android.content.SharedPreferences prefs =
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        chopDetector = new ChopDetector(this::toggleFlashlight,
                prefs.getFloat(MainActivity.TH_PEAK, ChopDetector.DEFAULT_PEAK),
                prefs.getFloat(MainActivity.TH_VALLEY, ChopDetector.DEFAULT_VALLEY),
                prefs.getLong(MainActivity.TH_WINDOW, ChopDetector.DEFAULT_WINDOW_MS),
                prefs.getLong(MainActivity.TH_COOLDOWN, ChopDetector.DEFAULT_COOLDOWN_MS));
        // Baked direction by default (the developer's calibration); a recalibration
        // overrides it. Reset clears the prefs, restoring these defaults.
        chopDetector.setDirection(
                prefs.getFloat(MainActivity.TH_DIR_X, ChopDetector.DEFAULT_DIR_X),
                prefs.getFloat(MainActivity.TH_DIR_Y, ChopDetector.DEFAULT_DIR_Y),
                prefs.getFloat(MainActivity.TH_DIR_Z, ChopDetector.DEFAULT_DIR_Z));
        chopDetector.setConeCos(
                prefs.getFloat(MainActivity.TH_CONE_COS, ChopDetector.DEFAULT_CONE_COS));
        detectorForDebug = chopDetector;
        isRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, listeningNotification());
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) {
            sensorManager.unregisterListener(this);
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sensorManager.unregisterListener(this);
        if (wakeLock.isHeld()) wakeLock.release();
        isRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            chopDetector.onSample(event.values[0], event.values[1], event.values[2]);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private String findFlashCameraId() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                Boolean hasFlash = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) return id;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void toggleFlashlight() {
        if (cameraId == null) return;
        try {
            torchOn = !torchOn;
            cameraManager.setTorchMode(cameraId, torchOn);
            buzz();
        } catch (Exception e) {
            torchOn = !torchOn; // revert on failure
        }
    }

    /**
     * Triple-pulse haptic tick on every toggle, on or off.
     * Controlled by the "Buzz when toggling" switch (default on).
     */
    private void buzz() {
        if (!getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_BUZZ, true)) {
            return;
        }
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return;
            vibrator.vibrate(VibrationEffect.createWaveform(
                    new long[]{0, 45, 70, 45, 70, 45}, -1));
        } catch (Exception ignored) {
        }
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID); // remove the louder v1.0 channel
        // IMPORTANCE_MIN: no sound, no status-bar icon, minimized in the shade —
        // the quietest a foreground-service notification is allowed to be.
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN));
    }

    private Notification listeningNotification() {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.listening))
                .setContentIntent(pi)
                .setShowWhen(false)
                .setOngoing(true)
                .build();
    }
}
