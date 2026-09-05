/* GPL v2 or later. Independent hardware configurator for Telegram, no ProShot code. */
package org.telegram.messenger.camera;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Small real-camera trials. Photo preview can try variants while idle; a recorded
 * clip pins ONE variant and is scored only after sensor + actual codec output
 * windows and successful completion. No hidden files or microphone recordings.
 */
public final class CameraHardwareBenchmark {
    public static final String PHOTO = "photo", VIDEO = "video", ROUND = "round";
    public static final int BALANCED = 0, ALTERNATIVE = 1, TEMPLATE = 2;
    private static final int REQUIRED_SAMPLES = 2;
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final HashSet<Object> cameras = new HashSet<>();
    private static final HashMap<String, String> lights = new HashMap<>();
    private static Object photoLease;

    private CameraHardwareBenchmark() {}
    static long now() { return SystemClock.elapsedRealtimeNanos(); }
    public static synchronized void cameraOpened(Object token) { cameras.add(token); }
    public static synchronized void cameraClosed(Object token) { cameras.remove(token); }
    static synchronized int cameraCount() { return cameras.size(); }
    static synchronized String lightFor(String id) { String s = lights.get(id); return s == null ? "unknown" : s; }
    static synchronized void noteLight(String id, String light) { if (!"unknown".equals(light)) lights.put(id, light); }
    private static synchronized void release(Object token) { if (photoLease == token) photoLease = null; }

    static void captureLight(String camera, long exposureNs, int iso) {
        if (exposureNs <= 0 || iso <= 0) return;
        double product = exposureNs / 1e6 * iso / 100;
        noteLight(camera, product < 10 ? "bright" : product < 100 ? "normal" : "low");
    }

    static boolean allowed() {
        if (!CameraAutoOptimizer.isEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                PowerManager p = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
                if (p != null && p.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) return false;
            } catch (RuntimeException e) {
                // Missing thermal telemetry is unknown; it is not a performance success.
                return false;
            }
        }
        return true;
    }

    static String name(int index, String useCase) {
        return index == TEMPLATE ? "template" : index == BALANCED ? "balanced"
                : PHOTO.equals(useCase) ? "fixed-preview-fps" : "stabilization-off";
    }

    /** Three finite candidates, two valid windows each, firmware/stream/scene scoped. */
    static final class Book {
        final CameraAutoOptimizer.Profile profile;
        final int mask;
        private long epoch = System.currentTimeMillis();
        final int[] samples = new int[3];
        final double[] scores = new double[3];
        Book(String camera, String useCase, String stream, String light, int mask, String signature) {
            this.mask = mask;
            profile = CameraAutoOptimizer.profile("camera2-measured", camera, useCase,
                    stream + "|light=" + light + "|parallel=" + cameraCount() + "|mask=" + mask + "|" + signature);
            try {
                String encoded = CameraAutoOptimizer.preferences().getString(profile.key + ".measured", null);
                if (encoded == null) return;
                String[] parts = encoded.split(";");
                if (parts.length != 7) return;
                long age = System.currentTimeMillis() - Long.parseLong(parts[0]);
                if (age < 0 || age > TTL_MS) return;
                epoch = Long.parseLong(parts[0]);
                int[] n = new int[3]; double[] s = new double[3];
                for (int i = 0; i < 3; i++) {
                    n[i] = Integer.parseInt(parts[1 + i * 2]); s[i] = Double.parseDouble(parts[2 + i * 2]);
                    if (n[i] < 0 || n[i] > REQUIRED_SAMPLES || Double.isNaN(s[i]) || Double.isInfinite(s[i])) return;
                }
                System.arraycopy(n, 0, samples, 0, 3); System.arraycopy(s, 0, scores, 0, 3);
            } catch (RuntimeException e) { CameraAutoOptimizer.error("benchmark cache invalid; recalibrate", e); }
        }
        boolean complete() {
            for (int i = 0; i < 3; i++) if ((mask & (1 << i)) != 0 && samples[i] < REQUIRED_SAMPLES) return false;
            return true;
        }
        int best() {
            int best = BALANCED;
            double value = -Double.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                // A small tie margin retains balanced defaults (incl. stabilization).
                if ((mask & (1 << i)) != 0 && samples[i] > 0 && scores[i] > value + 5) {
                    best = i; value = scores[i];
                }
            }
            return best;
        }
        int next() {
            int next = -1, count = REQUIRED_SAMPLES;
            for (int i = 0; i < 3; i++) if ((mask & (1 << i)) != 0 && samples[i] < count) {
                next = i; count = samples[i];
            }
            return next >= 0 ? next : best();
        }
        void reject(int candidate) {
            // Only called after a different request actually produced a frame.
            // This is a tested incompatibility, NOT a successful performance sample.
            samples[candidate] = 1;
            scores[candidate] = -100;
            save(candidate, -100);
            CameraAutoOptimizer.log(profile.label + " rejected candidate=" + candidate + " working fallback confirmed");
        }
        void save(int candidate, double value) {
            if ((mask & (1 << candidate)) == 0 || Double.isNaN(value) || Double.isInfinite(value)) return;
            int n = samples[candidate];
            scores[candidate] = n == 0 ? value : (scores[candidate] + value) / 2;
            samples[candidate] = Math.min(REQUIRED_SAMPLES, n + 1);
            StringBuilder s = new StringBuilder().append(epoch);
            for (int i = 0; i < 3; i++) s.append(';').append(samples[i]).append(';').append(scores[i]);
            try { CameraAutoOptimizer.preferences().edit().putString(profile.key + ".measured", s.toString()).apply(); }
            catch (RuntimeException e) { CameraAutoOptimizer.error("benchmark cache write failed", e); }
            CameraAutoOptimizer.log(profile.label + " measured candidate=" + candidate + " score="
                    + String.format(Locale.US, "%.2f", value) + " samples=" + samples[candidate]
                    + " state=" + (complete() ? "calibrated" : "probing") + " best=" + best());
        }
    }

    static final class Probe {
        final String camera, useCase, light;
        final Book book;
        final int candidate;
        final int parallel = cameraCount();
        final CameraFrameStats sensor = new CameraFrameStats(now());
        final long started = now();
        boolean invalid, done;
        String reason;
        Probe(String camera, String useCase, String stream, int mask, String signature, boolean explore) {
            this.camera = camera; this.useCase = useCase; light = lightFor(camera);
            book = new Book(camera, useCase, stream, light, mask, signature);
            candidate = explore ? book.next() : book.best();
            CameraAutoOptimizer.log(book.profile.label + " probe start candidate=" + name(candidate, useCase)
                    + " warmupMs=700 sampleMs=2000 state=" + (book.complete() ? "verify-cache" : "calibrating"));
        }
        synchronized void frame(long timestamp, int af, int ae, long exposure, int iso) {
            if (done || sensor.ready()) return;
            if (parallel != cameraCount()) invalidate("camera concurrency changed during sample");
            long time = now();
            if (sensor.add(timestamp, time, 0)) sensor.metadata(af, ae, exposure, iso);
            noteLight(camera, sensor.light());
        }
        synchronized boolean ready() { return sensor.ready(); }
        boolean timedOut() { return now() - started > 5_000_000_000L; }
        synchronized void invalidate(String why) { invalid = true; if (reason == null) reason = why; }
        synchronized String problem() {
            if (invalid) return reason;
            if (!allowed()) return "disabled or thermal guard";
            if (!sensor.valid()) return "incomplete/invalid sensor window";
            if (!light.equals(sensor.light())) return "lighting bucket changed " + light + " -> " + sensor.light();
            return "none";
        }
        synchronized boolean valid() { return "none".equals(problem()); }
        synchronized void finishPhoto() {
            if (done) return;
            done = true;
            boolean valid = valid();
            CameraAutoOptimizer.log(book.profile.label + " photo probe candidate=" + name(candidate, useCase)
                    + " valid=" + valid + " reason=" + problem() + " light=" + sensor.light() + " " + sensor.describe());
            if (valid) book.save(candidate, sensor.score(true));
            release(this);
        }
        synchronized void cancel(String why) { invalidate(why); done = true; release(this); }
    }

    static synchronized Probe photo(String camera, String stream, int mask, String signature) {
        if (photoLease != null || !allowed()) return null;
        Probe p = new Probe(camera, PHOTO, stream, mask, signature, true);
        photoLease = p;
        return p;
    }

    /** A pinned clip trial. Owned by the VideoRecorder, not a mutable camera pointer. */
    public static final class Recording {
        final String camera, useCase, output;
        Probe probe;
        CameraFrameStats encoded, lifetime;
        boolean finished, invalid, sampleDone;
        String reason;
        String codec = "unknown";
        public Recording(String camera, String useCase, int width, int height, int bitrate) {
            this.camera = camera; this.useCase = useCase;
            output = "encoded=" + width + "x" + height + "|bitrate=" + bitrate + "|fps=30";
        }
        synchronized Probe bind(String stream, int mask, String signature) {
            if (finished || invalid || !allowed()) return null;
            if (probe == null) {
                probe = new Probe(camera, useCase, stream + "|" + output, mask, signature + "|codec=" + codec, true);
                encoded = new CameraFrameStats(now()); lifetime = new CameraFrameStats(now());
            } else if (probe.book.mask != mask) invalidate("candidate set changed");
            return probe;
        }
        public synchronized void cameraStopped() {
            if (probe == null || !probe.ready()) invalidate("camera stopped before sample completed");
        }
        public synchronized void codec(String name) {
            if (probe != null && !codec.equals(name)) invalidate("codec changed after probe binding");
            codec = name;
        }
        public synchronized void frame(long presentationUs, int bytes) {
            if (finished || probe == null) return;
            long time = now();
            if (!sampleDone) {
                encoded.add(presentationUs * 1000, time, bytes);
                sampleDone = encoded.ready();
            }
            lifetime.add(presentationUs * 1000, time, bytes);
        }
        public synchronized void invalidate(String why) {
            invalid = true; if (reason == null) reason = why;
            if (probe != null) probe.invalidate(why);
        }
        public synchronized void finish(boolean success) {
            if (finished) return;
            finished = true;
            if (probe == null) {
                CameraAutoOptimizer.log("benchmark " + useCase + " camera=" + camera + " unmeasured: no camera/codec binding");
                return;
            }
            boolean valid = success && !invalid && probe.valid() && encoded.valid();
            CameraAutoOptimizer.log(probe.book.profile.label + " encoder probe candidate=" + name(probe.candidate, useCase)
                    + " valid=" + valid + " reason=" + (reason != null ? reason : !success ? "clip canceled" : !encoded.valid() ? "incomplete/invalid encoder window" : probe.problem()) + " codec=" + codec + " sensor{" + probe.sensor.describe()
                    + "} sample{" + encoded.describe() + "} recording{" + lifetime.describe() + "}");
            if (valid) probe.book.save(probe.candidate, Math.min(probe.sensor.score(false), encoded.score(false)));
            probe.done = true;
        }
    }

    public static final class Shot {
        private final long start = now();
        private final String camera;
        private final int candidate;
        Shot(String camera, int candidate) { this.camera = camera; this.candidate = candidate; }
        public void finish(int bytes, int rotation, boolean saved) {
            CameraAutoOptimizer.log("benchmark photo camera=" + camera + " jpegCompletionMs=" + (now() - start) / 1e6
                    + " candidate=" + name(candidate, PHOTO) + " bytes=" + bytes + " exifRotation=" + rotation
                    + " saved=" + saved + " (real JPEG; not a sharpness/noise benchmark)");
        }
    }
}
