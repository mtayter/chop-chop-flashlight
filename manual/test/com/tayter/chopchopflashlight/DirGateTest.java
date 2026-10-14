package com.tayter.chopchopflashlight;

import java.util.Random;

/** Headless test for the learned direction gate. Not shipped in the APK. */
public class DirGateTest {

    static int failures = 0;

    static void check(boolean cond, String name) {
        System.out.println((cond ? "PASS " : "FAIL ") + name);
        if (!cond) failures++;
    }

    public static void main(String[] args) throws Exception {
        // Adaptive cone mapping: confidence -> cone cosine.
        check(Math.abs(ChopTuner.coneCosForConfidence(0.88f) - 0.63f) < 0.01f,
                "cone for 0.88 confidence is 0.63 (coneCos=" +
                ChopTuner.coneCosForConfidence(0.88f) + ")");
        check(Math.abs(ChopTuner.coneCosForConfidence(0.70f) - 0.45f) < 0.01f,
                "cone for 0.70 confidence is 0.45 (coneCos=" +
                ChopTuner.coneCosForConfidence(0.70f) + ")");
        check(Math.abs(ChopTuner.coneCosForConfidence(1.00f) - 0.75f) < 0.01f,
                "cone for 1.00 confidence is 0.75 (coneCos=" +
                ChopTuner.coneCosForConfidence(1.00f) + ")");
        // Bounds: never narrower than 0.85, never wider than 0.25.
        check(ChopTuner.coneCosForConfidence(1.5f) == 0.85f, "cone capped at 0.85");
        check(ChopTuner.coneCosForConfidence(0.0f) == 0.25f, "cone floored at 0.25");
        // Strictness labels.
        check(ChopTuner.strictnessForConfidence(0.95f).startsWith("strict"),
                "0.95 -> strict");
        check(ChopTuner.strictnessForConfidence(0.85f).startsWith("normal"),
                "0.85 -> normal");
        check(ChopTuner.strictnessForConfidence(0.72f).startsWith("lenient"),
                "0.72 -> lenient");

        // A tighter cone rejects off-axis motion that a wider cone accepts.
        {
            // Motion at 55° from +x: cos = 0.57. Passes the 0.45 cone (63°),
            // rejected by the 0.63 cone (51°).
            final int[] gWide = {0};
            ChopDetector detWide = new ChopDetector(() -> gWide[0]++,
                    30f, 9f, 800L, 600L);
            detWide.setDirection(1f, 0f, 0f);
            detWide.setConeCos(0.45f); // lenient, ~63°
            final int[] gTight = {0};
            ChopDetector detTight = new ChopDetector(() -> gTight[0]++,
                    30f, 9f, 800L, 600L);
            detTight.setDirection(1f, 0f, 0f);
            detTight.setConeCos(0.63f); // the developer's, ~51°
            for (int i = 0; i < 50; i++) {
                detWide.onSample(0, 9.8f, 0);
                detTight.onSample(0, 9.8f, 0);
                Thread.sleep(20);
            }
            // Chop at 55° from x-axis in the xy-plane: direction
            // (cos55°, sin55°, 0) = (0.57, 0.82, 0).
            float ax = 0.57f, ay = 0.82f;
            for (int rep = 0; rep < 2; rep++) {
                for (float a : new float[]{20f, 40f, 60f, 40f, 20f}) {
                    detWide.onSample(a * ax, 9.8f + a * ay, 0);
                    detTight.onSample(a * ax, 9.8f + a * ay, 0);
                    Thread.sleep(20);
                }
                for (int i = 0; i < 15; i++) {
                    detWide.onSample(0, 9.8f, 0);
                    detTight.onSample(0, 9.8f, 0);
                    Thread.sleep(20);
                }
            }
            Thread.sleep(300);
            check(gWide[0] == 1, "wide cone accepts 55°-off chops (gestures=" + gWide[0] + ")");
            check(gTight[0] == 0, "tight cone rejects 55°-off chops (gestures=" + gTight[0] + ")");
        }

        // Synthetic recording: 20 ms sampling, gravity (0, 9.8, 0) + noise,
        // 10 chop-chop pairs, all chops along +y (phone frame).
        Random rnd = new Random(42);
        int n = 900; // 18 s
        long[] t = new long[n];
        float[] ax = new float[n], ay = new float[n], az = new float[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 20L;
            ax[i] = (rnd.nextFloat() - 0.5f) * 3f;
            ay[i] = 9.8f + (rnd.nextFloat() - 0.5f) * 3f;
            az[i] = (rnd.nextFloat() - 0.5f) * 3f;
        }
        // triangular pulse, peak 60, 5 samples wide, along +y
        float[] tri = {20f, 40f, 60f, 40f, 20f};
        for (int k = 0; k < 10; k++) {
            int s1 = 50 + k * 75;      // first chop of pair k (1500 ms apart)
            int s2 = s1 + 15;          // second chop, 300 ms later
            for (int j = 0; j < 5; j++) {
                ay[s1 + j] += tri[j];
                ay[s2 + j] += tri[j];
            }
        }

        ChopTuner.TuneResult r = ChopTuner.tune(t, ax, ay, az);
        check(r.ok, "tune ok" + (r.ok ? "" : " (" + r.error + ")"));
        if (!r.ok) { System.out.println("FAILURES=" + failures); System.exit(1); }
        System.out.printf("  peak=%.1f valley=%.1f window=%d cooldown=%d pairs=%d%n",
                r.peak, r.valley, r.windowMs, r.cooldownMs, r.pairs);
        System.out.printf("  D=(%.3f, %.3f, %.3f) conf=%.3f%n",
                r.dirX, r.dirY, r.dirZ, r.dirConfidence);
        check(Math.abs(r.dirX) < 0.15 && Math.abs(r.dirZ) < 0.15 && r.dirY > 0.95,
                "learned D points along +y");
        check(r.dirConfidence > 0.9, "direction confidence high");

        // Live detector: off-axis chop-chop must NOT fire; on-axis must fire.
        final int[] gestures = {0};
        ChopDetector det = new ChopDetector(() -> gestures[0]++,
                r.peak, r.valley, r.windowMs, r.cooldownMs);
        det.setDirection(r.dirX, r.dirY, r.dirZ);

        // settle the gravity filter
        for (int i = 0; i < 50; i++) {
            det.onSample(ax[i], ay[i], az[i]);
            Thread.sleep(20);
        }
        // off-axis pair along +x (perpendicular to learned +y)
        chop(det, 1, 0, 0);
        Thread.sleep(300);
        chop(det, 1, 0, 0);
        Thread.sleep(300);
        check(gestures[0] == 0, "perpendicular chops rejected (gestures=" + gestures[0] + ")");

        Thread.sleep(1500); // past cooldown
        // on-axis pair along +y
        chop(det, 0, 1, 0);
        Thread.sleep(300);
        chop(det, 0, 1, 0);
        Thread.sleep(300);
        check(gestures[0] == 1, "learned-direction chops accepted (gestures=" + gestures[0] + ")");

        // uncalibrated detector (no direction): old behavior, off-axis fires
        final int[] g2 = {0};
        ChopDetector det2 = new ChopDetector(() -> g2[0]++,
                r.peak, r.valley, r.windowMs, r.cooldownMs);
        for (int i = 0; i < 50; i++) {
            det2.onSample(ax[i], ay[i], az[i]);
            Thread.sleep(20);
        }
        chop(det2, 1, 0, 0);
        Thread.sleep(300);
        chop(det2, 1, 0, 0);
        Thread.sleep(300);
        check(g2[0] == 1, "no-direction detector still fires (back-compat)");

        // Dual-orientation: D=(1,0,0.3), flipped is (-1,0,-0.3). Test the
        // static gate directly (isolated from gravity-filter transients).
        {
            float dx = 1f, dy = 0f, dz = 0.3f;
            float l = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
            dx /= l; dy /= l; dz /= l;
            // lin vectors (magnitude 50): flipped dir, primary dir, perpendicular
            float[][] lin = {
                {-dx*50f, -dy*50f, -dz*50f},
                {dx*50f, dy*50f, dz*50f},
                {0f, 50f, 0f},
            };
            float[] mag = {50f, 50f, 50f};
            float[] gated = ChopDetector.applyDirectionGate(lin, mag, dx, dy, dz, 0.5f);
            check(gated[0] > 0, "flipped-orientation passes the gate");
            check(gated[1] > 0, "primary orientation passes the gate");
            check(gated[2] == 0, "perpendicular rejected by dual cone");
        }

        // Chop+rebound: a chop in the primary cone followed by a rebound in
        // the flipped cone must NOT count as a chop-chop (same-cone rule).
        {
            final int[] g4 = {0};
            ChopDetector det4 = new ChopDetector(() -> g4[0]++,
                    30f, 9f, 800L, 600L);
            det4.setDirection(1f, 0f, 0f);
            // baseline to settle gravity
            for (int i = 0; i < 50; i++) {
                det4.onSample(0, 9.8f, 0);
                Thread.sleep(20);
            }
            // chop along +x
            chop(det4, 1, 0, 0);
            Thread.sleep(300);
            // rebound along -x (flipped cone)
            chop(det4, -1, 0, 0);
            Thread.sleep(300);
            check(g4[0] == 0, "chop+rebound does not trigger (gestures=" + g4[0] + ")");

            // two chops both along +x: must trigger (fresh detector to avoid
            // gravity-filter adaptation from the rebound phase)
            final int[] g5 = {0};
            ChopDetector det5 = new ChopDetector(() -> g5[0]++,
                    30f, 9f, 800L, 600L);
            det5.setDirection(1f, 0f, 0f);
            for (int i = 0; i < 50; i++) {
                det5.onSample(0, 9.8f, 0);
                Thread.sleep(20);
            }
            chop(det5, 1, 0, 0);
            // 300ms gap with live baseline samples (gravity recovers, as in real use)
            for (int i = 0; i < 15; i++) {
                det5.onSample(0, 9.8f, 0);
                Thread.sleep(20);
            }
            chop(det5, 1, 0, 0);
            Thread.sleep(300);
            check(g5[0] == 1, "two same-direction chops trigger (gestures=" + g5[0] + ")");

            // Double-peaked single chop with a deep dip between the peaks:
            // two peaks 30ms apart in the same cone must NOT count as a pair.
            final int[] g6 = {0};
            ChopDetector det6 = new ChopDetector(() -> g6[0]++,
                    30f, 9f, 800L, 600L);
            det6.setDirection(1f, 0f, 0f);
            for (int i = 0; i < 50; i++) {
                det6.onSample(0, 9.8f, 0);
                Thread.sleep(20);
            }
            // first peak of the double
            doublePeakChop(det6, 1, 0, 0);
            // let the window expire with baseline
            for (int i = 0; i < 50; i++) {
                det6.onSample(0, 9.8f, 0);
                Thread.sleep(20);
            }
            check(g6[0] == 0, "double-peaked single chop does not trigger (gestures=" + g6[0] + ")");
        }

        // The developer's chop profile: strong between-chop motion in the opposite
        // direction (-y), overlapping the chops in magnitude (humps peak 45,
        // chops alternate 60/40). No magnitude-only threshold can separate
        // them; the direction gate can.
        {
            int n2 = 900;
            long[] t2 = new long[n2];
            float[] bx = new float[n2], by = new float[n2], bz = new float[n2];
            for (int i = 0; i < n2; i++) {
                t2[i] = i * 20L;
                bx[i] = (rnd.nextFloat() - 0.5f) * 3f;
                by[i] = 9.8f + (rnd.nextFloat() - 0.5f) * 3f;
                bz[i] = (rnd.nextFloat() - 0.5f) * 3f;
            }
            for (int k = 0; k < 10; k++) {
                int s1 = 50 + k * 75;
                int s2 = s1 + 15;
                float amp = (k % 2 == 0) ? 60f : 40f; // alternating strong/weak pairs
                for (int j = 0; j < 5; j++) {
                    by[s1 + j] += triAt(j) * amp / 60f; // chop along +y
                    by[s2 + j] += triAt(j) * amp / 60f;
                }
                // smooth reversal hump along -y between the two chops
                for (int j = 0; j < 7; j++) {
                    by[s1 + 4 + j] -= (float) (45 * Math.sin(Math.PI * j / 6));
                }
            }
            ChopTuner.TuneResult rm = ChopTuner.tune(t2, bx, by, bz);
            System.out.printf("  smooth-chop: ok=%b pairs=%d peak=%.1f valley=%.1f conf=%.3f%s%n",
                    rm.ok, rm.pairs, rm.peak, rm.valley, rm.dirConfidence,
                    rm.ok ? "" : " err=" + rm.error);
            check(rm.ok, "strong opposite-direction between-motion tunes via the gate");
            if (rm.ok) {
                check(rm.pairs == 10, "all 10 pairs recovered (pairs=" + rm.pairs + ")");
                check(Math.abs(rm.dirX) < 0.2 && Math.abs(rm.dirZ) < 0.2 && rm.dirY > 0.9,
                        "learned D still along +y");
                check(rm.dirConfidence > 0.9, "gated direction confidence high");
            }
        }

        // Consistent chops OUTNUMBERED by junk: 10 pairs of +y chops (20 spikes)
        // plus 25 strong -y junk spikes at random times. No direction holds a
        // majority (20/45), so the tuner must try multiple direction
        // candidates and succeed with the +y one (the -y candidate is larger
        // but its spikes don't form chop-chop pairs).
        for (int i = 0; i < n; i++) {
            ax[i] = (rnd.nextFloat() - 0.5f) * 3f;
            ay[i] = 9.8f + (rnd.nextFloat() - 0.5f) * 3f;
            az[i] = (rnd.nextFloat() - 0.5f) * 3f;
        }
        for (int k = 0; k < 10; k++) {
            int s1 = 50 + k * 75;
            int s2 = s1 + 15;
            for (int j = 0; j < 5; j++) {
                ay[s1 + j] += triAt(j); // +y chop
                ay[s2 + j] += triAt(j);
            }
        }
        for (int w = 0; w < 25; w++) {
            // -y junk between pairs (never overlapping a chop): 2-3 per gap
            int k = w % 10;
            int s1 = 50 + k * 75;
            int s2 = s1 + 15;
            int s = s2 + 25 + (w / 10) * 12;
            for (int j = 0; j < 5 && s + j < n; j++) {
                ay[s + j] -= triAt(j) * 0.9f; // -y junk, slightly weaker
            }
        }
        ChopTuner.TuneResult rj = ChopTuner.tune(t, ax, ay, az);
        System.out.printf("  outnumbered: ok=%b pairs=%d conf=%.3f%s%n",
                rj.ok, rj.pairs, rj.dirConfidence, rj.ok ? "" : " err=" + rj.error);
        check(rj.ok, "chops outnumbered by junk tune via direction candidates");
        if (rj.ok) {
            check(rj.pairs == 10, "all 10 pairs recovered from outnumbered (pairs=" + rj.pairs + ")");
            check(rj.dirY > 0.9, "learned D is the chop direction (+y), not the junk (-y)");
        }

        // Scattered chops (random direction each time): no dominant spike
        // direction exists, so the tuner must reject the recording.
        for (int i = 0; i < n; i++) {
            ax[i] = (rnd.nextFloat() - 0.5f) * 3f;
            ay[i] = 9.8f + (rnd.nextFloat() - 0.5f) * 3f;
            az[i] = (rnd.nextFloat() - 0.5f) * 3f;
        }
        for (int k = 0; k < 10; k++) {
            int s1 = 50 + k * 75;
            int s2 = s1 + 15;
            for (int j = 0; j < 5; j++) {
                double ang = rnd.nextDouble() * 2 * Math.PI;
                float px = (float) Math.cos(ang) * triAt(j);
                float py = (float) Math.sin(ang) * triAt(j);
                ax[s1 + j] += px; ay[s1 + j] += py;
                ang = rnd.nextDouble() * 2 * Math.PI;
                px = (float) Math.cos(ang) * triAt(j);
                py = (float) Math.sin(ang) * triAt(j);
                ax[s2 + j] += px; ay[s2 + j] += py;
            }
        }
        ChopTuner.TuneResult rs = ChopTuner.tune(t, ax, ay, az);
        System.out.printf("  scattered: ok=%b conf=%.3f err=%s%n",
                rs.ok, rs.dirConfidence, rs.error);
        check(!rs.ok && rs.error.contains("different directions"),
                "scattered chops rejected by gated self-check");

        // Double-peaked chops (the developer's real 2026-10-01 recording): each chop
        // lands as two spikes ~30ms apart pointing in different directions
        // (the phone twists on impact). The tuner must merge them into one
        // chop event, not pair the two peaks as a phantom chop-chop.
        for (int i = 0; i < n; i++) {
            ax[i] = (rnd.nextFloat() - 0.5f) * 3f;
            ay[i] = 9.8f + (rnd.nextFloat() - 0.5f) * 3f;
            az[i] = (rnd.nextFloat() - 0.5f) * 3f;
        }
        for (int k = 0; k < 10; k++) {
            int s1 = 50 + k * 75;
            int s2 = s1 + 15;
            // Each chop: two 5-sample pulses 2 samples (~40ms) apart, second
            // pointing ~70° away from the first.
            for (int j = 0; j < 5; j++) {
                ay[s1 + j] += triAt(j);
                ax[s1 + 2 + j] += triAt(j) * 0.9f;
                az[s1 + 2 + j] += triAt(j) * 0.4f;
                ay[s2 + j] += triAt(j);
                ax[s2 + 2 + j] += triAt(j) * 0.9f;
                az[s2 + 2 + j] += triAt(j) * 0.4f;
            }
        }
        ChopTuner.TuneResult rd = ChopTuner.tune(t, ax, ay, az);
        System.out.printf("  doublepeak: ok=%b pairs=%d conf=%.3f%s%n",
                rd.ok, rd.pairs, rd.dirConfidence, rd.ok ? "" : " err=" + rd.error);
        check(rd.ok, "double-peaked chops tune (peaks merged, not mispaired)");
        if (rd.ok) {
            check(rd.pairs == 10, "all 10 pairs recovered from double-peak (pairs=" + rd.pairs + ")");
        }

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : "FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static float triAt(int j) {
        return new float[]{20f, 40f, 60f, 40f, 20f}[j];
    }

    /** One synthetic chop: 5-sample triangular pulse of amplitude ~60 along (dx,dy,dz). */
    static void chop(ChopDetector det, float dx, float dy, float dz) throws Exception {
        float[] tri = {20f, 40f, 60f, 40f, 20f};
        for (float a : tri) {
            det.onSample(dx * a, 9.8f + dy * a, dz * a);
            Thread.sleep(20);
        }
    }

    /** A double-peaked chop: two peaks ~30ms apart with a deep dip between. */
    static void doublePeakChop(ChopDetector det, float dx, float dy, float dz) throws Exception {
        float[] amps = {20f, 45f, 65f, 30f, 5f, 3f, 25f, 50f, 60f, 35f, 15f};
        for (float a : amps) {
            det.onSample(dx * a, 9.8f + dy * a, dz * a);
            Thread.sleep(15);
        }
    }
}
