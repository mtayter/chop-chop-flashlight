package com.tayter.chopchopflashlight;

import java.util.Random;

/** Scratch: which realistic recording shapes break the current tuner? */
public class ScratchDiag {
    static final float[] TRI = {20f, 40f, 60f, 40f, 20f};

    public static void main(String[] args) {
        testA_basic();
        testB_drift();
        testC_jerky();
        testD_heavyJunk();
        testE_smoothWeakChops();
        testF_structuredJunk();
        testG_fewPairsJunk();
    }

    static float[][] base(Random rnd, int n) {
        long[] t = new long[n];
        float[] ax = new float[n], ay = new float[n], az = new float[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 20L;
            ax[i] = (rnd.nextFloat() - 0.5f) * 3f;
            ay[i] = 9.8f + (rnd.nextFloat() - 0.5f) * 3f;
            az[i] = (rnd.nextFloat() - 0.5f) * 3f;
        }
        return new float[][]{toFloat(t), ax, ay, az};
    }

    static float[] toFloat(long[] t) {
        float[] f = new float[t.length];
        for (int i = 0; i < t.length; i++) f[i] = t[i];
        return f;
    }

    /** pulse along direction (dx,dy,dz), amplitude amp, starting at sample s */
    static void pulse(float[] ax, float[] ay, float[] az, int s,
                      float dx, float dy, float dz, float amp) {
        for (int j = 0; j < 5 && s + j < ax.length; j++) {
            float a = TRI[j] * amp / 60f;
            ax[s + j] += dx * a;
            ay[s + j] += dy * a;
            az[s + j] += dz * a;
        }
    }

    static void report(String name, long[] t, float[] ax, float[] ay, float[] az) {
        ChopTuner.TuneResult r = ChopTuner.tune(t, ax, ay, az);
        System.out.printf("%-28s ok=%-5b pairs=%-3d peak=%6.1f valley=%5.1f conf=%.3f%s%n",
                name, r.ok, r.pairs, r.peak, r.valley, r.dirConfidence,
                r.ok ? "" : "  ERR=" + r.error);
    }

    /** A: best model of the developer — 2 strong +y chops/pair, moderate -y return, weak repositioning */
    static void testA_basic() {
        Random rnd = new Random(7);
        int n = 1100;
        float[][] b = base(rnd, n);
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = i * 20L;
        float[] ax = b[1], ay = b[2], az = b[3];
        for (int k = 0; k < 10; k++) {
            int s1 = 60 + k * 95;
            int s2 = s1 + 18;
            pulse(ax, ay, az, s1, 0, 1, 0, 60);      // chop 1 +y
            pulse(ax, ay, az, s1 + 8, 0, -1, 0, 25); // return -y (moderate)
            pulse(ax, ay, az, s2, 0, 1, 0, 60);      // chop 2 +y
            // weak repositioning between pairs: small random pulses
            for (int w = 0; w < 3; w++) {
                double ang = rnd.nextDouble() * 2 * Math.PI;
                pulse(ax, ay, az, s2 + 25 + w * 12,
                        (float) Math.cos(ang), (float) Math.sin(ang), 0, 12);
            }
        }
        report("A basic", t, ax, ay, az);
    }

    /** B: chop direction drifts (grip rotation) +/-35 deg across the recording */
    static void testB_drift() {
        Random rnd = new Random(7);
        int n = 1100;
        float[][] b = base(rnd, n);
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = i * 20L;
        float[] ax = b[1], ay = b[2], az = b[3];
        for (int k = 0; k < 10; k++) {
            double ang = Math.toRadians(-35 + 70.0 * k / 9); // drift in xy-plane
            float dx = (float) Math.sin(ang), dy = (float) Math.cos(ang);
            int s1 = 60 + k * 95;
            int s2 = s1 + 18;
            pulse(ax, ay, az, s1, dx, dy, 0, 60);
            pulse(ax, ay, az, s2, dx, dy, 0, 60);
        }
        report("B drift+-35deg", t, ax, ay, az);
    }

    /** C: jerky — strong -y spikes (45) interleaved with +y chops (60) */
    static void testC_jerky() {
        Random rnd = new Random(7);
        int n = 1100;
        float[][] b = base(rnd, n);
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = i * 20L;
        float[] ax = b[1], ay = b[2], az = b[3];
        for (int k = 0; k < 10; k++) {
            int s1 = 60 + k * 95;
            int s2 = s1 + 18;
            pulse(ax, ay, az, s1 - 6, 0, -1, 0, 45); // start-of-chop -y (strong)
            pulse(ax, ay, az, s1, 0, 1, 0, 60);
            pulse(ax, ay, az, s1 + 8, 0, -1, 0, 45); // return -y (strong)
            pulse(ax, ay, az, s2 - 6, 0, -1, 0, 45);
            pulse(ax, ay, az, s2, 0, 1, 0, 60);
            pulse(ax, ay, az, s2 + 8, 0, -1, 0, 45);
        }
        report("C jerky", t, ax, ay, az);
    }

    /** D: 20 tight +y chops + 25 strong random-direction junk spikes */
    static void testD_heavyJunk() {
        Random rnd = new Random(7);
        int n = 1100;
        float[][] b = base(rnd, n);
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = i * 20L;
        float[] ax = b[1], ay = b[2], az = b[3];
        for (int k = 0; k < 10; k++) {
            int s1 = 60 + k * 95;
            int s2 = s1 + 18;
            pulse(ax, ay, az, s1, 0, 1, 0, 60);
            pulse(ax, ay, az, s2, 0, 1, 0, 60);
        }
        for (int w = 0; w < 25; w++) {
            int s = 40 + rnd.nextInt(n - 60);
            double ang = rnd.nextDouble() * 2 * Math.PI;
            double ang2 = rnd.nextDouble() * 2 * Math.PI;
            pulse(ax, ay, az, s,
                    (float) (Math.cos(ang) * Math.cos(ang2)),
                    (float) (Math.sin(ang) * Math.cos(ang2)),
                    (float) Math.sin(ang2), 55);
        }
        report("D heavyJunk", t, ax, ay, az);
    }

    /** E: smooth weak chops (30) + strong repositioning flicks (55) between pairs */
    static void testE_smoothWeakChops() {
        Random rnd = new Random(7);
        int n = 1100;
        float[][] b = base(rnd, n);
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = i * 20L;
        float[] ax = b[1], ay = b[2], az = b[3];
        for (int k = 0; k < 10; k++) {
            int s1 = 60 + k * 95;
            int s2 = s1 + 18;
            pulse(ax, ay, az, s1, 0, 1, 0, 30); // smooth weak chops
            pulse(ax, ay, az, s2, 0, 1, 0, 30);
            // strong repositioning flick between pairs, random direction
            double ang = rnd.nextDouble() * 2 * Math.PI;
            pulse(ax, ay, az, s2 + 30,
                    (float) Math.cos(ang), (float) Math.sin(ang), 0, 55);
        }
        report("E smoothWeakChops", t, ax, ay, az);
    }

    /** F: structured in-cone between-pair junk (raise-starts: +y, 70% of chop,
     *  ~700ms after chop2) + a few random re-grips. Realistic developer profile. */
    static void testF_structuredJunk() {
        Random rnd = new Random(7);
        int n = 1100;
        float[][] b = base(rnd, n);
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = i * 20L;
        float[] ax = b[1], ay = b[2], az = b[3];
        for (int k = 0; k < 10; k++) {
            int s1 = 60 + k * 95;
            int s2 = s1 + 18;
            pulse(ax, ay, az, s1, 0, 1, 0, 60);       // chop 1 +y
            pulse(ax, ay, az, s1 + 8, 0, -1, 0, 25);  // return -y (moderate)
            pulse(ax, ay, az, s2, 0, 1, 0, 60);       // chop 2 +y
            pulse(ax, ay, az, s2 + 35, 0, 1, 0, 42);  // raise-start +y, 700ms later
            pulse(ax, ay, az, s2 + 45, 0, -1, 0, 30); // raise-stop -y
            // a random re-grip between pairs
            double ang = rnd.nextDouble() * 2 * Math.PI;
            pulse(ax, ay, az, s2 + 60, (float) Math.cos(ang), (float) Math.sin(ang), 0, 35);
        }
        report("F structuredJunk", t, ax, ay, az);
    }

    /** G: fewer pairs (7), varying chop strength, moderate junk — the plausible developer profile. */
    static void testG_fewPairsJunk() {
        Random rnd = new Random(11);
        int n = 900;
        float[][] b = base(rnd, n);
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = i * 20L;
        float[] ax = b[1], ay = b[2], az = b[3];
        for (int k = 0; k < 7; k++) {
            int s1 = 60 + k * 105;
            int s2 = s1 + 16 + rnd.nextInt(8);
            float amp = 40 + rnd.nextFloat() * 25; // varying 40-65
            pulse(ax, ay, az, s1, 0, 1, 0, amp);
            pulse(ax, ay, az, s1 + 8, 0, -1, 0, 20 + rnd.nextFloat() * 15);
            pulse(ax, ay, az, s2, 0, 1, 0, amp * (0.85f + rnd.nextFloat() * 0.3f));
            // repositioning junk between pairs, sometimes in-cone
            for (int w = 0; w < 2; w++) {
                double ang = rnd.nextDouble() * 2 * Math.PI;
                float jamp = 25 + rnd.nextFloat() * 25;
                // bias some junk toward +y (in-cone)
                float dx = (float) Math.cos(ang) * 0.7f;
                float dy = (float) (Math.sin(ang) * 0.7f + 0.3f);
                pulse(ax, ay, az, s2 + 30 + w * 15 + rnd.nextInt(10), dx, dy, 0, jamp);
            }
        }
        report("G fewPairsJunk", t, ax, ay, az);
    }
}
