/* GPL v2 or later. Independent implementation; no decompiled code. */
package org.telegram.messenger.camera;

import java.util.Arrays;
import java.util.Locale;

/** Bounded-memory, clock-origin-independent measurements. Not an image-quality metric. */
public final class CameraFrameStats {
    public static final long WARMUP_NS = 700_000_000L;
    public static final long SAMPLE_NS = 2_000_000_000L;
    private final long started;
    private final long warmupNs;
    private long first = -1, last = -1, firstArrival, lastArrival;
    private long totalBytes, maxGap;
    private double mean, m2;
    private final long[] gaps = new long[256];
    private int gapCount;
    public int frames, errors, nonMonotonic, aeKnown, aeSettled, afKnown, afSettled;
    public long exposureTotal;
    public int isoTotal, exposureSamples;
    private double exposureIsoTotal;

    public CameraFrameStats(long now) { this(now, WARMUP_NS); }
    public CameraFrameStats(long now, long warmupNs) {
        started = now;
        this.warmupNs = Math.max(0, warmupNs);
    }

    public boolean add(long timestampNs, long arrivalNs, int bytes) {
        if (arrivalNs - started < warmupNs || timestampNs < 0) return false;
        if (first < 0) {
            first = last = timestampNs;
            firstArrival = lastArrival = arrivalNs;
            frames = 1;
            totalBytes = Math.max(0, bytes);
            return true;
        }
        if (timestampNs <= last || arrivalNs < lastArrival) {
            nonMonotonic++;
            return false;
        }
        long gap = timestampNs - last;
        last = timestampNs;
        lastArrival = arrivalNs;
        totalBytes += Math.max(0, bytes);
        frames++;
        gapCount++;
        double delta = gap - mean;
        mean += delta / gapCount;
        m2 += delta * (gap - mean);
        gaps[(gapCount - 1) % gaps.length] = gap;
        maxGap = Math.max(maxGap, gap);
        return true;
    }

    /** Values are Camera2 AF/AE state enums; -1 means absent, not a successful result. */
    public void metadata(int af, int ae, long exposureNs, int iso) {
        if (af > 0) { afKnown++; if (af == 2 || af == 4) afSettled++; }
        if (ae > 0) { aeKnown++; if (ae == 2 || ae == 3 || ae == 4) aeSettled++; }
        if (exposureNs > 0 && iso > 0) {
            exposureTotal += exposureNs;
            isoTotal += iso;
            exposureSamples++;
            exposureIsoTotal += exposureNs / 1e6 * iso / 100.0;
        }
    }

    public boolean ready() {
        return frames >= 12 && last - first >= SAMPLE_NS && lastArrival - firstArrival >= SAMPLE_NS;
    }
    public boolean valid() { return ready() && errors == 0 && nonMonotonic == 0; }
    public double fps() { return frames < 2 ? 0 : (frames - 1) * 1e9 / (last - first); }
    public double deliveryFps() { return frames < 2 || lastArrival <= firstArrival ? 0 : (frames - 1) * 1e9 / (lastArrival - firstArrival); }
    public double jitter() { return gapCount < 2 || mean <= 0 ? 0 : Math.sqrt(m2 / gapCount) / mean; }
    public double p95GapMs() {
        int n = Math.min(gapCount, gaps.length);
        if (n == 0) return 0;
        long[] copy = Arrays.copyOf(gaps, n);
        Arrays.sort(copy);
        return copy[Math.max(0, (int) Math.ceil(n * .95) - 1)] / 1e6;
    }
    public double bitrate() { return frames < 2 ? 0 : totalBytes * 8e9 / (last - first); }
    public long estimatedMissing(int targetFps) {
        return frames < 2 ? 0 : Math.max(0, Math.round((last - first) * targetFps / 1e9) - (frames - 1));
    }
    public String light() {
        if (exposureSamples == 0) return "unknown";
        // Mean of paired products, not product of means (AE trades exposure for ISO).
        return CameraIllumination.bucket(lightProduct(), "unknown");
    }
    public double lightProduct() { return exposureSamples == 0 ? -1 : exposureIsoTotal / exposureSamples; }
    public double score(boolean photo) {
        // Explicit policy: cadence/3A stability, NOT a claim of maximum visual quality.
        double target = photo ? 15 : CameraOptimizationPolicy.TARGET_VIDEO_FPS;
        double rate = Math.min(fps(), deliveryFps());
        double score = 100 * Math.min(1, rate / target) - Math.min(45, jitter() * 30);
        if (afKnown >= 12) score -= (photo ? 20 : 10) * (1 - afSettled / (double) afKnown);
        if (aeKnown >= 12) score -= 15 * (1 - aeSettled / (double) aeKnown);
        return score;
    }
    public String describe() {
        return String.format(Locale.US,
                "frames=%d fps=%.2f deliveryFps=%.2f jitter=%.3f p95RecentGapMs=%.2f maxGapMs=%.2f estimatedMissingAt30=%d errors=%d nonMonotonic=%d ae=%d/%d af=%d/%d meanExposureMs=%.2f meanIso=%.1f pairedExposureIso=%.2f bitrate=%.0f",
                frames, fps(), deliveryFps(), jitter(), p95GapMs(), maxGap / 1e6,
                estimatedMissing(30), errors, nonMonotonic, aeSettled, aeKnown, afSettled, afKnown,
                exposureSamples == 0 ? -1 : exposureTotal / (double) exposureSamples / 1e6,
                exposureSamples == 0 ? -1 : isoTotal / (double) exposureSamples, lightProduct(), bitrate());
    }
}
