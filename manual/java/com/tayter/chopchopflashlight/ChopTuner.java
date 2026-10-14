package com.tayter.chopchopflashlight;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives personal chop-chop detection thresholds from a recorded session.
 *
 * Pure Java (no Android dependencies) so the algorithm can be unit-tested on
 * the desktop against real recordings.
 *
 * Method: find candidate chop spikes on the raw magnitudes, learn the chop
 * direction from them, then re-analyze through the direction gate — the same
 * signal the live detector sees. Magnitude alone often can't separate chops
 * from strong between-chop motion (a fast, smooth chop style); direction
 * can. Thresholds are derived from the gated signal: PEAK midway between the
 * strongest gated inter-chop ringing and the weakest gated chop spike,
 * VALLEY above the deepest gated inter-chop dip, WINDOW/COOLDOWN from the
 * observed intra/inter-pair timing. Finally the exact shipped pipeline
 * (direction gate + thresholds) is simulated over the recording and accepted
 * only if every pair is detected exactly once.
 */
public class ChopTuner {

    public static class TuneResult {
        public boolean ok;
        public String error = "";
        public float peak;
        public float valley;
        public long windowMs;
        public long cooldownMs;
        public int pairs;
        // Learned chop direction: mean of the normalized linear-acceleration
        // vectors at the detected spikes, in phone frame. dirConfidence is the
        // mean resultant length (0..1) over the gated spikes: near 1 means
        // the chops the gate passes all point the same way.
        public float dirX;
        public float dirY;
        public float dirZ;
        public float dirConfidence;
        // Adaptive direction cone (cosine of the half-angle), derived from
        // dirConfidence: consistent choppers get a tight cone (fewer false
        // positives), inconsistent-but-passing choppers get a wide one (more
        // forgiving, more false-positive risk). Bounded to [0.25, 0.85].
        public float coneCos;
        // Human-readable strictness label for the results screen.
        public String strictness;
        // Human-readable diagnostic dump (spikes, candidates, pairs, replay)
        // for the "copy diagnostics" button on calibration failure.
        public String diagnostics = "";
    }

    private static final long MIN_SPIKE_SEP_MS = 200;
    private static final long PAIR_GAP_MS = 800; // two spikes this close = one chop-chop

    public static TuneResult tune(long[] tMs, float[] ax, float[] ay, float[] az) {
        TuneResult r = new TuneResult();
        int n = tMs.length;
        if (n < 200) {
            r.error = "Recording too short — aim for about 10 chop-chops.";
            return r;
        }

        float[][] lin = ChopDetector.linearVectors(ax, ay, az);
        float[] mag = new float[n];
        for (int s = 0; s < n; s++) {
            float[] v = lin[s];
            mag[s] = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        }
        float sessionMax = 0;
        for (float m : mag) sessionMax = Math.max(sessionMax, m);
        if (sessionMax < 20) {
            r.error = "Motion too gentle — chop harder, with an abrupt stop at the bottom.";
            return r;
        }

        // Bootstrap: spikes and pairs on the raw magnitudes. Between-chop
        // motion may be as strong as the chops here and corrupt pairing —
        // separation comes next, via the direction gate.
        List<long[]> spikes = findSpikes(tMs, lin, mag, 0.5f * sessionMax);
        if (spikes.size() < 6) {
            r.error = "Found only " + spikes.size() + " chop spikes — do about 10 chop-chops (20 sharp chops).";
            return r;
        }
        List<long[]> pairFirst = new ArrayList<>();
        List<long[]> pairSecond = new ArrayList<>();
        pairSpikes(spikes, pairFirst, pairSecond);
        if (pairFirst.size() < 3) {
            r.error = "Found only " + pairFirst.size()
                    + " clear chop-chop pairs — chop twice in quick succession, with a pause between pairs.";
            return r;
        }

        // Learn candidate chop directions from the bootstrap spikes: the
        // largest tight clusters of spike vectors. The chops are the most
        // repeated consistent motion in the recording; strong junk
        // (repositioning, re-grips, returns) is scattered or forms smaller
        // groups. Each candidate is tried through the full pipeline below;
        // the replay is the final judge.
        List<DirCand> candidates = directionCandidates(spikes, lin);
        if (candidates.isEmpty()) {
            r.error = "Your chops point in different directions — keep the same grip and chop the same way each time."
                    + " (Saw " + spikes.size() + " strong spikes but no consistent direction group.)";
            return r;
        }

        TuneResult best = null;
        for (DirCand cand : candidates) {
            TuneResult attempt = tuneWithDirection(
                    tMs, ax, ay, az, lin, mag, n, cand.d, cand.count, cand.tight);
            if (attempt.ok) return attempt;
            if (best == null || attempt.pairs > best.pairs) best = attempt;
        }
        // Build the diagnostic dump for the "copy diagnostics" button.
        StringBuilder diag = new StringBuilder();
        diag.append("BOOTSTRAP thr=").append(String.format("%.1f", 0.5f * sessionMax)).append("\n");
        dumpSpikes(diag, "spikes", spikes, lin);
        diag.append("CANDIDATES: ").append(candidates.size()).append("\n");
        for (DirCand cand : candidates) {
            diag.append(String.format("  D=(%.2f,%.2f,%.2f) count=%d tight=%.2f\n",
                    cand.d[0], cand.d[1], cand.d[2], cand.count, cand.tight));
        }
        if (best != null && !best.diagnostics.isEmpty()) {
            diag.append("BEST ATTEMPT:\n").append(best.diagnostics);
        }
        r.diagnostics = diag.toString();

        StringBuilder sb = new StringBuilder();
        // Only blame direction when the spikes are actually scattered. If
        // there's one good group but the pipeline still failed, say what
        // actually failed.
        boolean scattered = candidates.size() != 1 || candidates.get(0).tight < 0.8f;
        if (scattered) {
            sb.append("Your chops point in different directions — keep the same grip and chop the same way each time.");
            sb.append(" (Saw ").append(spikes.size()).append(" strong spikes in ")
                    .append(candidates.size()).append(candidates.size() == 1 ? " direction group" : " direction groups")
                    .append("; largest ").append(candidates.get(0).count)
                    .append(" spikes, tightness ")
                    .append(String.format("%.2f", candidates.get(0).tight)).append(".)");
        } else {
            sb.append("Couldn't tune to your chops (saw ").append(spikes.size())
                    .append(" strong spikes, ").append(candidates.get(0).count)
                    .append(" in one tight direction group).");
        }
        if (best != null && !best.error.isEmpty()) sb.append(" ").append(best.error);
        r.error = sb.toString();
        return r;
    }

    /**
     * Full pipeline for one candidate chop direction: gate the signal,
     * re-detect spikes and pairs, derive thresholds, and replay the exact
     * shipped pipeline. Returns ok only if every gated pair is detected
     * exactly once. dirCount/dirtight are only used for diagnostics.
     */
    private static TuneResult tuneWithDirection(long[] tMs, float[] ax, float[] ay, float[] az,
                                                float[][] lin, float[] mag, int n,
                                                float[] dir, int dirCount, float dirTight) {
        TuneResult r = new TuneResult();
        // Re-analyze through the direction gate: this is the signal the live
        // detector actually sees. Off-direction between-chop motion drops out.
        // Use a wide cone for spike-finding (don't miss real chops during
        // analysis); the adaptive cone applies at live-detection time.
        float[] gmag = ChopDetector.applyDirectionGate(lin, mag, dir[0], dir[1], dir[2], 0.25f);
        float gSessionMax = 0;
        for (float m : gmag) gSessionMax = Math.max(gSessionMax, m);
        if (gSessionMax < 20) {
            r.error = "The direction check filtered out nearly all motion — chop the same way each time.";
            return r;
        }
        List<long[]> gspikes = findSpikes(tMs, lin, gmag, 0.5f * gSessionMax);
        List<long[]> gpairFirst = new ArrayList<>();
        List<long[]> gpairSecond = new ArrayList<>();
        pairSpikes(gspikes, gpairFirst, gpairSecond);
        r.pairs = gpairFirst.size();
        if (gpairFirst.size() < 3) {
            r.error = "Only " + gpairFirst.size()
                    + " clear pairs pointed this way — do about 10 chop-chops (20 sharp chops).";
            return r;
        }

        // Per-pair stats on the gated signal.
        int P = gpairFirst.size();
        float[] pairFloor = new float[P];
        float[] pairMin = new float[P];
        float[] pairRing = new float[P];
        float pMinSpike = Float.MAX_VALUE;
        float pMaxRing = 0;
        for (int k = 0; k < P; k++) {
            long t1 = gpairFirst.get(k)[0];
            long t2 = gpairSecond.get(k)[0];
            float v1 = gpairFirst.get(k)[1] / 1000f;
            float v2 = gpairSecond.get(k)[1] / 1000f;
            float pvmin = Math.min(v1, v2);
            pairMin[k] = pvmin;
            pMinSpike = Math.min(pMinSpike, pvmin);
            float floor = Float.MAX_VALUE;
            float ring = 0;
            float ringThr = 0.8f * pvmin;
            // Ringing is measured in the middle third of the inter-chop interval,
            // so the shoulders of the chop spikes themselves don't inflate it.
            long ringStart = t1 + (t2 - t1) / 3;
            long ringEnd = t2 - (t2 - t1) / 3;
            for (int s = 0; s < n; s++) {
                long t = tMs[s];
                if (t <= t1 || t >= t2) continue;
                floor = Math.min(floor, gmag[s]);
                if (t > ringStart && t < ringEnd && s > 0 && s < n - 1
                        && gmag[s] >= gmag[s - 1] && gmag[s] > gmag[s + 1]
                        && gmag[s] < ringThr) {
                    ring = Math.max(ring, gmag[s]);
                }
            }
            pairFloor[k] = floor;
            pairRing[k] = ring;
            pMaxRing = Math.max(pMaxRing, ring);
        }
        float pPeak = (pMaxRing + pMinSpike) / 2f;

        // A pair is clean if the motion genuinely dips between its chops:
        // the floor must reach below half the peak. Pairs polluted by
        // in-cone junk (or an overly continuous chop style) are excluded
        // from threshold derivation — one bad pair must not veto nine good
        // ones. The replay below still has to account for every clean pair.
        List<Integer> clean = new ArrayList<>();
        for (int k = 0; k < P; k++) {
            if (pairFloor[k] < 0.5f * pPeak) clean.add(k);
        }
        r.pairs = clean.size();
        if (clean.size() < 3) {
            r.error = "The dip between your two chops isn't deep enough — make each chop a separate sharp flick.";
            return r;
        }

        // Thresholds from the clean pairs only.
        float minSpike = Float.MAX_VALUE;
        float maxRing = 0;
        float maxFloor = 0;
        long maxIntraGap = 0;
        long[] interGaps = new long[Math.max(0, clean.size() - 1)];
        for (int j = 0; j < clean.size(); j++) {
            int k = clean.get(j);
            long t1 = gpairFirst.get(k)[0];
            long t2 = gpairSecond.get(k)[0];
            minSpike = Math.min(minSpike, pairMin[k]);
            maxRing = Math.max(maxRing, pairRing[k]);
            maxFloor = Math.max(maxFloor, pairFloor[k]);
            maxIntraGap = Math.max(maxIntraGap, t2 - t1);
            if (j > 0) {
                int pk = clean.get(j - 1);
                interGaps[j - 1] = t1 - gpairSecond.get(pk)[0];
            }
        }
        // Robust typical inter-pair gap (median): a single spurious
        // junk-junk or junk-chop pair must not dictate the cooldown.
        java.util.Arrays.sort(interGaps);
        long gapMed = interGaps[interGaps.length / 2];

        if (!(maxRing < minSpike)) {
            r.error = "Chop spikes aren't clearly stronger than the motion between them — make each chop sharp and distinct.";
            return r;
        }
        float peak = (maxRing + minSpike) / 2f;
        // Valley is learned from the user's actual dip: above the deepest
        // measured dip (with margin for live variation), floored at 30% of
        // peak so re-arming stays comfortably above the sensor noise floor
        // even when the gated dip is near zero, and below the peak threshold
        // (hysteresis). The self-check below is the final judge of whatever
        // valley results.
        float valley = Math.min(Math.max(maxFloor * 1.6f, peak * 0.3f), peak * 0.85f);
        if (!(valley > maxFloor * 1.1f)) {
            r.error = "The dip between your two chops isn't deep enough — make each chop a separate sharp flick.";
            return r;
        }
        long windowMs = Math.min(2000, Math.max(500, (long) (maxIntraGap * 2.5)));
        long cooldownMs = (long) (gapMed * 0.7);
        if (cooldownMs < 400) cooldownMs = 400;
        if (gapMed <= 400) {
            r.error = "Your chop-chop pairs are too close together — leave a clearer pause between pairs.";
            return r;
        }

        float dirConfidence = confidence(gspikes, lin);
        float coneCos = coneCosForConfidence(dirConfidence);
        // The live gate accepts two orientations (D and its 180°-yaw flip),
        // so the tuner must insist the calibration itself is directionally
        // coherent — otherwise scattered junk can slip through the wider
        // gate. The developer's real calibration scores 0.88; scattered scores ~0.4.
        if (dirConfidence < 0.7f) {
            r.error = "Your chops point in different directions — keep the same grip and chop the same way each time.";
            return r;
        }

        // Diagnostic dump for the "copy diagnostics" button (populated
        // before the replay so it's available on failure too).
        StringBuilder ddiag = new StringBuilder();
        ddiag.append(String.format("D=(%.2f,%.2f,%.2f) dirCount=%d dirTight=%.2f\n",
                dir[0], dir[1], dir[2], dirCount, dirTight));
        dumpSpikes(ddiag, "gated", gspikes, lin);
        ddiag.append("pairs: ").append(P).append(" clean: ").append(clean.size()).append("\n");
        for (int k = 0; k < P; k++) {
            long t1 = gpairFirst.get(k)[0];
            long t2 = gpairSecond.get(k)[0];
            ddiag.append(String.format("  pair %d t1=%d t2=%d min=%.1f floor=%.1f ring=%.1f %s\n",
                    k, t1, t2, pairMin[k], pairFloor[k], pairRing[k],
                    clean.contains(k) ? "clean" : "dirty"));
        }
        ddiag.append(String.format("thresh peak=%.1f valley=%.1f window=%d cooldown=%d gapMed=%d\n",
                peak, valley, windowMs, cooldownMs, gapMed));
        r.diagnostics = ddiag.toString();

        // Self-check: the exact shipped pipeline (direction gate +
        // thresholds) must detect every clean pair exactly once, at the
        // pair's second chop. Detection timestamps are matched to pairs so
        // a right count from wrong pairs can't slip through.
        final long[] detTimes = new long[n];
        final int[] detCount = {0};
        final long[] curT = {0};
        ChopDetector sim = new ChopDetector(() -> {
            detTimes[detCount[0]++] = curT[0];
        }, peak, valley, windowMs, cooldownMs);
        sim.setDirection(dir[0], dir[1], dir[2]);
        sim.setConeCos(coneCos); // replay with the exact adaptive cone that will ship
        for (int s = 0; s < n; s++) {
            curT[0] = tMs[s];
            sim.onSampleAt(tMs[s], ax[s], ay[s], az[s]);
        }
        // Append replay detection times to the diagnostics.
        StringBuilder dtimes = new StringBuilder("replay detections: ").append(detCount[0]);
        for (int d = 0; d < detCount[0]; d++) dtimes.append(" t=").append(detTimes[d]);
        r.diagnostics = r.diagnostics + dtimes.toString() + "\n";
        boolean replayOk = detCount[0] == clean.size();
        if (replayOk) {
            boolean[] matched = new boolean[clean.size()];
            for (int d = 0; d < detCount[0] && replayOk; d++) {
                boolean found = false;
                for (int j = 0; j < clean.size(); j++) {
                    if (matched[j]) continue;
                    long t2 = gpairSecond.get(clean.get(j))[0];
                    if (Math.abs(detTimes[d] - t2) <= 300) {
                        matched[j] = true;
                        found = true;
                        break;
                    }
                }
                if (!found) replayOk = false;
            }
        }
        if (!replayOk) {
            r.error = "Couldn't find clean thresholds (detected " + detCount[0]
                    + " of " + clean.size() + " clean pairs) — try again.";
            return r;
        }

        r.ok = true;
        r.peak = peak;
        r.valley = valley;
        r.windowMs = windowMs;
        r.cooldownMs = cooldownMs;
        r.dirX = dir[0];
        r.dirY = dir[1];
        r.dirZ = dir[2];
        r.dirConfidence = dirConfidence;
        r.coneCos = coneCos;
        r.strictness = strictnessForConfidence(dirConfidence);
        return r;
    }

    /**
     * Adaptive direction cone: consistent choppers get a tight cone, looser
     * choppers get a wider one. Monotonic in confidence, bounded so the cone
     * is never narrower than ~32° nor wider than ~75°.
     */
    static float coneCosForConfidence(float confidence) {
        return Math.max(0.25f, Math.min(0.85f, confidence - 0.25f));
    }

    /** Human-readable strictness for the calibration results screen. */
    static String strictnessForConfidence(float confidence) {
        float coneCos = coneCosForConfidence(confidence);
        int degrees = Math.round((float) Math.toDegrees(Math.acos(coneCos)));
        if (confidence >= 0.9f) return "strict (" + degrees + "° cone)";
        if (confidence >= 0.8f) return "normal (" + degrees + "° cone)";
        return "lenient (" + degrees + "° cone)";
    }

    /**
     * Local maxima above spikeThr; each entry is [timeMs, value*1000, sampleIndex].
     * Two maxima within MIN_SPIKE_SEP_MS are merged (keeping the larger):
     * at this timescale they are the same physical impact (e.g. a chop whose
     * peak splits in two as the phone twists), not two distinct motions — no
     * human reverses direction in under 200ms. The direction check that used
     * to live here split real double-peaked chops into phantom "pairs".
     */
    private static List<long[]> findSpikes(long[] tMs, float[][] lin, float[] mag, float spikeThr) {
        int n = tMs.length;
        List<long[]> spikes = new ArrayList<>();
        for (int i = 1; i < n - 1; i++) {
            if (mag[i] > spikeThr && mag[i] >= mag[i - 1] && mag[i] > mag[i + 1]) {
                long t = tMs[i];
                if (!spikes.isEmpty() && t - spikes.get(spikes.size() - 1)[0] < MIN_SPIKE_SEP_MS) {
                    long[] prev = spikes.get(spikes.size() - 1);
                    if (mag[i] * 1000 > prev[1]) {
                        prev[0] = t;
                        prev[1] = (long) (mag[i] * 1000);
                        prev[2] = i;
                    }
                } else {
                    spikes.add(new long[]{t, (long) (mag[i] * 1000), i});
                }
            }
        }
        return spikes;
    }

    /** True when the two vectors point within 60° of each other. */
    private static boolean sameDirection(float[] a, float[] b) {
        float ma = (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        float mb = (float) Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        if (ma == 0 || mb == 0) return true;
        return (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (ma * mb) >= 0.5f;
    }

    /** Pair consecutive spikes at most PAIR_GAP_MS apart; advances by two on a pair. */
    private static void pairSpikes(List<long[]> spikes, List<long[]> first, List<long[]> second) {
        int i = 0;
        while (i < spikes.size() - 1) {
            long gap = spikes.get(i + 1)[0] - spikes.get(i)[0];
            if (gap <= PAIR_GAP_MS) {
                first.add(spikes.get(i));
                second.add(spikes.get(i + 1));
                i += 2;
            } else {
                i += 1;
            }
        }
    }

    /** One candidate chop direction: the mean of a tight cluster of spike vectors. */
    private static class DirCand {
        final float[] d = new float[3]; // unit vector
        int count;   // spikes in the cluster
        float tight; // mean resultant length (0..1)
    }

    /**
     * Candidate chop directions: the largest tight clusters of spike vectors
     * (each spike votes for the spikes within 60° of it), largest first.
     * Greedy: take the largest cluster with at least 4 spikes and tightness
     * at least 0.8, remove its members, repeat (up to 3 candidates).
     *
     * No majority requirement: strong junk motion (repositioning, re-grips)
     * can outnumber the chops in the bootstrap set without forming a tight
     * cluster of its own. A plain mean would cancel to garbage when the
     * recording mixes chops with strong opposite motion (e.g. reversals).
     * The full pipeline replay judges each candidate, so a wrong guess here
     * is rejected rather than shipped.
     */
    private static List<DirCand> directionCandidates(List<long[]> spikes, float[][] lin) {
        List<DirCand> out = new ArrayList<>();
        int s = spikes.size();
        float[][] u = new float[s][3];
        for (int k = 0; k < s; k++) {
            float[] v = lin[(int) spikes.get(k)[2]];
            float m = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            if (m > 0) {
                u[k][0] = v[0] / m;
                u[k][1] = v[1] / m;
                u[k][2] = v[2] / m;
            }
        }
        boolean[] used = new boolean[s];
        for (int round = 0; round < 3; round++) {
            int best = -1, bestCount = 0;
            for (int a = 0; a < s; a++) {
                if (used[a]) continue;
                int c = 0;
                for (int b = 0; b < s; b++) {
                    if (used[b]) continue;
                    if (u[a][0] * u[b][0] + u[a][1] * u[b][1] + u[a][2] * u[b][2] >= 0.5f) c++;
                }
                if (c > bestCount) {
                    bestCount = c;
                    best = a;
                }
            }
            if (best < 0 || bestCount < 4) break;
            float dx = 0, dy = 0, dz = 0;
            for (int b = 0; b < s; b++) {
                if (used[b]) continue;
                if (u[best][0] * u[b][0] + u[best][1] * u[b][1] + u[best][2] * u[b][2] >= 0.5f) {
                    dx += u[b][0];
                    dy += u[b][1];
                    dz += u[b][2];
                }
            }
            float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dl == 0 || dl / bestCount < 0.8f) break; // remaining groups are loose
            DirCand dc = new DirCand();
            dc.d[0] = dx / dl;
            dc.d[1] = dy / dl;
            dc.d[2] = dz / dl;
            dc.count = bestCount;
            dc.tight = dl / bestCount;
            out.add(dc);
            for (int b = 0; b < s; b++) {
                if (!used[b]
                        && u[best][0] * u[b][0] + u[best][1] * u[b][1] + u[best][2] * u[b][2] >= 0.5f) {
                    used[b] = true;
                }
            }
        }
        return out;
    }

    /** Mean resultant length (0..1) of the normalized vectors at the spike instants. */
    private static float confidence(List<long[]> spikes, float[][] lin) {
        float dx = 0, dy = 0, dz = 0;
        for (long[] s : spikes) {
            float[] v = lin[(int) s[2]];
            float m = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            if (m > 0) {
                dx += v[0] / m;
                dy += v[1] / m;
                dz += v[2] / m;
            }
        }
        float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dl / spikes.size();
    }

    /** Compact diagnostic dump of spikes: "t=1230 mag=45.2 dir=(0.05,0.98,0.12)". */
    private static void dumpSpikes(StringBuilder sb, String label,
                                   List<long[]> spikes, float[][] lin) {
        sb.append(label).append(": ").append(spikes.size()).append(" spikes\n");
        for (long[] s : spikes) {
            float[] v = lin[(int) s[2]];
            float m = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            float dx = m > 0 ? v[0] / m : 0;
            float dy = m > 0 ? v[1] / m : 0;
            float dz = m > 0 ? v[2] / m : 0;
            sb.append(String.format("  t=%d mag=%.1f dir=(%.2f,%.2f,%.2f)\n",
                    s[0], s[1] / 1000f, dx, dy, dz));
        }
    }
}
