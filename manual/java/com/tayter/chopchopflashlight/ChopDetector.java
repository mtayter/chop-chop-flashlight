package com.tayter.chopchopflashlight;

/**
 * Detects a "chop-chop": two sharp linear-acceleration peaks close together,
 * separated by a dip (so one long shake doesn't count twice).
 * Orientation-independent: uses the magnitude of gravity-free acceleration.
 *
 * Thresholds are per-user tunable (see ChopTuner); defaults fit the
 * developer's own chop-chops.
 */
public class ChopDetector {

    // Baked defaults from the developer's 2026-10-01 calibration (10 pairs, 88%
    // consistent): these ship to every user; recalibration overrides them.
    public static final float DEFAULT_PEAK = 31.0f; // m/s^2 of linear accel counts as one chop
    public static final float DEFAULT_VALLEY = 9.3f; // must dip below this between the two chops
    public static final long DEFAULT_WINDOW_MS = 787L; // max time between the two chops
    public static final long DEFAULT_COOLDOWN_MS = 616L;
    public static final float DEFAULT_DIR_X = 0.91f;
    public static final float DEFAULT_DIR_Y = 0.07f;
    public static final float DEFAULT_DIR_Z = 0.42f;
    private static final float ALPHA = 0.8f; // low-pass factor for gravity estimate

    public interface Listener {
        void onGesture();
    }

    private final Listener listener;
    private final float peak;
    private final float valley;
    private final long windowMs;
    private final long cooldownMs;
    private final float[] gravity = new float[3];
    private int peaks = 0;
    private long firstPeakAt = 0L;
    private boolean armedForNext = true;
    private long cooldownUntil = 0L;

    // Learned chop direction (phone frame, unit vector). When set, samples
    // outside the cone around it can never count as peaks. The gate also
    // accepts the 180°-yaw flipped direction (-x, y, -z), so chopping with
    // the screen facing away works the same as screen facing you. However,
    // the two peaks of a single gesture must be in the SAME cone — a chop
    // followed by its rebound (opposite direction) is not a chop-chop.
    private float dirX, dirY, dirZ;
    private boolean dirSet = false;
    private boolean firstPeakWasFlipped = false;
    // Adaptive direction cone (cosine of the half-angle). Defaults to the
    // 60° cone; calibration sets it from the user's consistency via
    // setConeCos(). The baked default matches the developer's 0.88 confidence.
    private float coneCos = 0.5f;
    static final float DEFAULT_CONE_COS = 0.63f; // ~51°, from confidence 0.88
    // Two peaks closer than this are the same chop (double-peak/echo), not a pair.
    // No human does two distinct chops this fast; 159ms echoes seen in the wild.
    static final long DOUBLE_PEAK_MERGE_MS = 200L;
    // Trigger-trace ring buffer: keeps the last ~2s of samples so a false
    // trigger can be diagnosed. Snapshot is taken when a gesture fires.
    private static final int TRACE_LEN = 120;
    private final long[] traceT = new long[TRACE_LEN];
    private final float[] traceX = new float[TRACE_LEN];
    private final float[] traceY = new float[TRACE_LEN];
    private final float[] traceZ = new float[TRACE_LEN];
    private final float[] traceMag = new float[TRACE_LEN];
    private int traceHead = 0;
    private int traceCount = 0;
    private String lastTriggerTrace = null;

    public ChopDetector(Listener listener, float peak, float valley, long windowMs, long cooldownMs) {
        this.listener = listener;
        this.peak = peak;
        this.valley = valley;
        this.windowMs = windowMs;
        this.cooldownMs = cooldownMs;
    }

    /** Learn the user's chop direction (phone frame); enables the direction gate. */
    public void setDirection(float dx, float dy, float dz) {
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l > 0) {
            dirX = dx / l;
            dirY = dy / l;
            dirZ = dz / l;
            dirSet = true;
        }
    }

    /** Set the adaptive direction cone (cosine of the half-angle). */
    public void setConeCos(float coneCos) {
        this.coneCos = Math.max(0.25f, Math.min(0.85f, coneCos));
    }

    /** Forget the learned direction; back to orientation-independent detection. */
    public void clearDirection() {
        dirSet = false;
    }

    /** Gravity-free acceleration vectors for each sample (same filter the live detector uses). */
    public static float[][] linearVectors(float[] ax, float[] ay, float[] az) {
        float gx = 0, gy = 0, gz = 0;
        float[][] out = new float[ax.length][3];
        for (int i = 0; i < ax.length; i++) {
            gx = ALPHA * gx + (1f - ALPHA) * ax[i];
            gy = ALPHA * gy + (1f - ALPHA) * ay[i];
            gz = ALPHA * gz + (1f - ALPHA) * az[i];
            out[i][0] = ax[i] - gx;
            out[i][1] = ay[i] - gy;
            out[i][2] = az[i] - gz;
        }
        return out;
    }

    /** Gravity-free acceleration magnitude for each sample (same filter the live detector uses). */
    public static float[] magnitudes(float[] ax, float[] ay, float[] az) {
        float[][] lin = linearVectors(ax, ay, az);
        float[] out = new float[ax.length];
        for (int i = 0; i < ax.length; i++) {
            out[i] = (float) Math.sqrt(
                    lin[i][0] * lin[i][0] + lin[i][1] * lin[i][1] + lin[i][2] * lin[i][2]);
        }
        return out;
    }

    /**
     * Direction-gated magnitudes: samples whose linear acceleration points
     * outside the cone around (dx,dy,dz) — or its 180°-yaw flip — read as
     * zero. This is the signal the live detector's state machine sees when
     * the gate is enabled.
     */
    static float[] applyDirectionGate(float[][] lin, float[] mag, float dx, float dy, float dz,
                                      float coneCos) {
        float[] out = new float[mag.length];
        for (int i = 0; i < mag.length; i++) {
            float m = mag[i];
            if (m > 0 && !inDualCone(lin[i][0], lin[i][1], lin[i][2], m, dx, dy, dz, coneCos)) {
                m = 0f;
            }
            out[i] = m;
        }
        return out;
    }

    /**
     * True if (lx,ly,lz)/mag is within the cone around (dx,dy,dz) or around
     * its 180°-yaw flip (-dx,dy,-dz) — the latter is what the same physical
     * chop reads as when the phone is turned screen-away.
     */
    private static boolean inDualCone(float lx, float ly, float lz, float mag,
                                      float dx, float dy, float dz, float coneCos) {
        float cos = (lx * dx + ly * dy + lz * dz) / mag;
        if (cos >= coneCos) return true;
        float cosFlip = (-lx * dx + ly * dy - lz * dz) / mag;
        return cosFlip >= coneCos;
    }

    public void onSample(float x, float y, float z) {
        onSampleAt(System.currentTimeMillis(), x, y, z);
    }

    /** Sample with an explicit timestamp, so the tuner can replay recordings. */
    void onSampleAt(long now, float x, float y, float z) {
        gravity[0] = ALPHA * gravity[0] + (1f - ALPHA) * x;
        gravity[1] = ALPHA * gravity[1] + (1f - ALPHA) * y;
        gravity[2] = ALPHA * gravity[2] + (1f - ALPHA) * z;
        float lx = x - gravity[0];
        float ly = y - gravity[1];
        float lz = z - gravity[2];
        float mag = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (dirSet && mag > 0) {
            // Direction gate: motion outside both cones can never be a peak.
            // It still counts as a lull for valley/arming purposes. The two
            // peaks of a gesture must share a cone, so a chop+rebound (which
            // flips direction) doesn't false-trigger.
            float cos = (lx * dirX + ly * dirY + lz * dirZ) / mag;
            float cosFlip = (-lx * dirX + ly * dirY - lz * dirZ) / mag;
            boolean inPrimary = cos >= coneCos;
            boolean inFlipped = cosFlip >= coneCos;
            if (!inPrimary && !inFlipped) {
                mag = 0f;
            } else if (peaks == 0 && mag > peak) {
                // This sample will become the first peak; remember its cone.
                firstPeakWasFlipped = inFlipped && !inPrimary;
            } else if (peaks == 1 && mag > peak) {
                // Candidate second peak: must be in the same cone as the first.
                // If it's in both cones, accept (ambiguous, be permissive).
                boolean thisFlipped = inFlipped && !inPrimary;
                boolean both = inPrimary && inFlipped;
                if (!both && thisFlipped != firstPeakWasFlipped) {
                    mag = 0f;
                }
            }
        }
        // Record in the trigger-trace ring buffer (post-gate magnitude).
        traceT[traceHead] = now;
        traceX[traceHead] = x;
        traceY[traceHead] = y;
        traceZ[traceHead] = z;
        traceMag[traceHead] = mag;
        traceHead = (traceHead + 1) % TRACE_LEN;
        if (traceCount < TRACE_LEN) traceCount++;
        onMagnitude(now, mag);
    }

    /**
     * Returns a compact text dump of the ~2s of sensor data leading up to
     * the last detected gesture, or null if none has fired yet. Format per
     * line: tMs,ax,ay,az,gatedMag. For diagnosing false triggers.
     */
    public String getLastTriggerTrace() {
        return lastTriggerTrace;
    }

    private void snapshotTriggerTrace(long triggerAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("trigger at ").append(triggerAt).append("ms\n");
        sb.append("tMs,ax,ay,az,gatedMag\n");
        // Oldest first.
        int start = (traceHead - traceCount + TRACE_LEN * 2) % TRACE_LEN;
        for (int i = 0; i < traceCount; i++) {
            int idx = (start + i) % TRACE_LEN;
            sb.append(traceT[idx]).append(',')
              .append(String.format("%.2f", traceX[idx])).append(',')
              .append(String.format("%.2f", traceY[idx])).append(',')
              .append(String.format("%.2f", traceZ[idx])).append(',')
              .append(String.format("%.1f", traceMag[idx])).append('\n');
        }
        lastTriggerTrace = sb.toString();
    }

    /** State machine over precomputed magnitudes (used by the live path and the tuner). */
    void onMagnitude(long now, float mag) {
        if (now < cooldownUntil) return;

        if (peaks == 0) {
            if (mag > peak) {
                peaks = 1;
                firstPeakAt = now;
                armedForNext = false;
            }
            return;
        }

        if (!armedForNext && mag < valley) armedForNext = true;

        if (armedForNext && mag > peak) {
            if (now - firstPeakAt < DOUBLE_PEAK_MERGE_MS) {
                // Too close: this is the second peak of a double-peaked chop,
                // not a second chop. Fold it into the first: require a fresh
                // dip before arming again, and restart the window from here.
                armedForNext = false;
                firstPeakAt = now;
                return;
            }
            if (now - firstPeakAt <= windowMs) {
                peaks = 0;
                armedForNext = true;
                cooldownUntil = now + cooldownMs;
                snapshotTriggerTrace(now);
                listener.onGesture();
            } else {
                // Too slow: this peak starts a fresh attempt.
                peaks = 1;
                firstPeakAt = now;
                armedForNext = false;
            }
            return;
        }

        if (now - firstPeakAt > windowMs) {
            peaks = 0;
            armedForNext = true;
        }
    }
}
