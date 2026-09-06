/* Licensed under GNU GPL v. 2 or later. Independently implemented. */
package org.telegram.messenger.camera;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.util.Range;
import android.util.Size;

import java.util.Arrays;
import java.util.List;

@TargetApi(21)
final class Camera2AutoOptimizer {
    private volatile Attempt current;
    private volatile CameraHardwareBenchmark.Recording recordingBenchmark;
    private volatile boolean photoBenchmarks, photoFrozen;
    private volatile int photoCandidate;

    void allowPhotoBenchmarks(boolean allow) { photoBenchmarks = allow; }
    void recordingBenchmark(CameraHardwareBenchmark.Recording run) { recordingBenchmark = run; }
    void invalidateRecording(String reason) {
        CameraHardwareBenchmark.Recording run = recordingBenchmark;
        if (run != null) run.invalidate(reason);
    }
    void freezePhoto() {
        photoFrozen = true;
        Attempt a = current;
        if (a != null) a.cancelProbe("user shutter");
    }
    void resumePhoto() { photoFrozen = false; }
    int photoCandidate() { return photoCandidate; }

    void stop() {
        Attempt a = current;
        current = null;
        if (a != null) a.cancelProbe("camera stopped");
        CameraHardwareBenchmark.Recording run = recordingBenchmark;
        if (run != null) run.cameraStopped();
    }

    private static boolean canSet(CameraCharacteristics characteristics, CaptureRequest.Key<?> key) {
        List<CaptureRequest.Key<?>> keys = characteristics.getAvailableCaptureRequestKeys();
        return keys != null && keys.contains(key);
    }

    private static <T> void set(CaptureRequest.Builder builder, CameraCharacteristics characteristics,
                                CaptureRequest.Key<T> key, T value) {
        if (value != null && canSet(characteristics, key)) builder.set(key, value);
    }

    private static boolean has(int[] values, int value) {
        return CameraOptimizationPolicy.chooseMode(values, value) != null;
    }

    private static String tune(CaptureRequest.Builder builder, CameraCharacteristics characteristics,
                               Size size, boolean video, boolean still) {
        // Only continuous AF is safe to force here: Camera2Session has no
        // tap-to-focus and never sends CONTROL_AF_TRIGGER, so CONTROL_AF_MODE_AUTO
        // would park the lens at its default position and never focus again.
        // Without a continuous mode we keep whatever the capture template chose.
        Integer af = CameraOptimizationPolicy.chooseMode(
                characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                video ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        if (af == null) {
            CameraAutoOptimizer.log("camera2 " + (video ? "video" : "photo") + " " + size
                    + " no continuous AF mode advertised; keeping template focus"
                    + " (no AF trigger available in this session)");
        }
        set(builder, characteristics, CaptureRequest.CONTROL_AF_MODE, af);
        Integer awb = CameraOptimizationPolicy.chooseMode(
                characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), CaptureRequest.CONTROL_AWB_MODE_AUTO);
        set(builder, characteristics, CaptureRequest.CONTROL_AWB_MODE, awb);
        Integer anti = CameraOptimizationPolicy.chooseMode(
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES),
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO);
        set(builder, characteristics, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, anti);

        Range<Integer> chosen = null;
        long minFrameDuration = 0;
        int budget = CameraOptimizationPolicy.TARGET_VIDEO_FPS;
        if (!still) {
            StreamConfigurationMap streams = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (streams != null) {
                try {
                    minFrameDuration = streams.getOutputMinFrameDuration(SurfaceTexture.class, size);
                    if (minFrameDuration > 0) {
                        budget = Math.min(budget, Math.max(1,
                                (int) Math.floor(1_000_000_000d / minFrameDuration + 0.01d)));
                    }
                } catch (RuntimeException unsupportedDuration) {
                    // Some HALs do not implement this optional metadata query.
                    // Keep using the advertised FPS ranges, as WebRTC does.
                    minFrameDuration = 0;
                }
            }
            Range<Integer>[] advertised = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (advertised != null) {
                int[][] ranges = new int[advertised.length][];
                for (int i = 0; i < ranges.length; i++) {
                    if (advertised[i] != null) ranges[i] = new int[]{advertised[i].getLower(), advertised[i].getUpper()};
                }
                int index = CameraOptimizationPolicy.chooseFpsRange(ranges,
                        budget * CameraOptimizationPolicy.camera2FpsScale(ranges), video);
                if (index >= 0) chosen = advertised[index];
            }
            set(builder, characteristics, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen);
        }

        int[] oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        int[] eisModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        boolean canOis = canSet(characteristics, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE);
        boolean canEis = canSet(characteristics, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE);
        boolean ois = video && canOis && has(oisModes, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        // Do not request EIS unless OIS can be explicitly disabled or is unavailable.
        boolean eis = video && !ois && canEis && has(eisModes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                && (!has(oisModes, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    || canOis && has(oisModes, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF));
        if (canEis && has(eisModes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    eis ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        } else if (ois && has(eisModes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
            // Unable to guarantee mutual exclusion: leave both at template defaults.
            ois = false;
        }
        if (video && canOis && has(oisModes, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    ois ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        // Report the request values, not modes merely listed in metadata.
        return "af=" + builder.get(CaptureRequest.CONTROL_AF_MODE)
                + " awb=" + builder.get(CaptureRequest.CONTROL_AWB_MODE)
                + " antibanding=" + builder.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE)
                + " fps=" + builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                + " ois=" + builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
                + " eis=" + builder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)
                + " minFrameDurationNs=" + minFrameDuration
                + " fpsBudget=" + budget
                + " stabilizationPlan=" + (ois ? "ois" : eis ? "eis" : "none")
                + " canOis=" + canOis + " canEis=" + canEis;
    }

    private static String requestSignature(CaptureRequest request) {
        return "af=" + request.get(CaptureRequest.CONTROL_AF_MODE)
                + ",awb=" + request.get(CaptureRequest.CONTROL_AWB_MODE)
                + ",anti=" + request.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE)
                + ",fps=" + request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                + ",ois=" + request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
                + ",eis=" + request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE);
    }

    /** One additional advertised configuration, never fabricated FPS or AF triggers. */
    private static void alternative(CaptureRequest.Builder builder, CameraCharacteristics c, boolean video) {
        if (video) {
            int[] ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            int[] eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            if (has(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF))
                set(builder, c, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            if (has(eis, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF))
                set(builder, c, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        } else {
            Range<Integer> current = builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
            Range<Integer>[] advertised = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (current != null && advertised != null) {
                int[][] ranges = new int[advertised.length][];
                for (int i = 0; i < ranges.length; i++) if (advertised[i] != null)
                    ranges[i] = new int[]{advertised[i].getLower(), advertised[i].getUpper()};
                int best = CameraOptimizationPolicy.chooseFpsRange(ranges, current.getUpper(), true);
                if (best >= 0) set(builder, c, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, advertised[best]);
            }
        }
    }

    private static String list(int[] values) {
        return values == null ? "null" : Arrays.toString(values);
    }

    /** Everything the HAL advertises for this stream, so a log explains each choice. */
    private static String describe(CameraCharacteristics characteristics, Size size,
                                   boolean video, boolean still) {
        Range<Integer>[] advertised = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        int[][] ranges = null;
        if (advertised != null) {
            ranges = new int[advertised.length][];
            for (int i = 0; i < ranges.length; i++) {
                if (advertised[i] != null) ranges[i] = new int[]{advertised[i].getLower(), advertised[i].getUpper()};
            }
        }
        long minFrameDuration = -1;
        StreamConfigurationMap streams = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (streams != null) {
            try {
                minFrameDuration = streams.getOutputMinFrameDuration(SurfaceTexture.class, size);
            } catch (RuntimeException unsupportedDuration) {
                minFrameDuration = -1; // Optional metadata; -1 means "not advertised".
            }
        }
        return "request=" + (still ? "still" : "repeating") + " video=" + video + " stream=" + size
                + " afModes=" + list(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES))
                + " awbModes=" + list(characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES))
                + " antibandingModes=" + list(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES))
                + " oisModes=" + list(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION))
                + " eisModes=" + list(characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES))
                + " fpsRanges=" + Arrays.toString(advertised)
                + " fpsScale=" + CameraOptimizationPolicy.camera2FpsScale(ranges)
                + " minFrameDurationNs=" + minFrameDuration
                + " canSetAf=" + canSet(characteristics, CaptureRequest.CONTROL_AF_MODE)
                + " canSetFps=" + canSet(characteristics, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                + " canSetOis=" + canSet(characteristics, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
                + " canSetEis=" + canSet(characteristics, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE);
    }

    void repeating(CameraCaptureSession session, CaptureRequest.Builder builder,
                   CameraCharacteristics characteristics, Size size, String cameraId,
                   boolean video, boolean bypass, Handler handler) throws CameraAccessException {
        Attempt attempt = new Attempt(session, builder, characteristics, size, cameraId,
                video, false, bypass, handler);
        Attempt previous = current;
        if (previous != null) previous.cancelProbe("request replaced");
        current = attempt;
        attempt.start();
    }

    /**
     * @param video pass the session's current recording state. A still capture that
     *              contradicts the AF mode of the running repeating request makes the
     *              HAL switch AF mode for one frame, which shows up as a refocus hunt
     *              in the video being recorded.
     */
    void capture(CameraCaptureSession session, CaptureRequest.Builder builder,
                 CameraCharacteristics characteristics, Size size, String cameraId,
                 boolean video, boolean bypass, Handler handler) throws CameraAccessException {
        new Attempt(session, builder, characteristics, size, cameraId,
                video, true, bypass, handler).start();
    }

    private final class Attempt extends CameraCaptureSession.CaptureCallback {
        final CameraCaptureSession session;
        final CaptureRequest baseline;
        final CameraAutoOptimizer.Profile profile;
        final Handler handler;
        final boolean still;
        CaptureRequest expected;
        String summary;
        boolean optimized;
        boolean fallback;
        boolean completed;
        int failures;
        final String cameraId;
        final String stream;
        final boolean video;
        CaptureRequest[] candidates;
        int mask;
        String candidateSignature;
        CameraHardwareBenchmark.Probe measured;
        int trials;
        CameraHardwareBenchmark.Book rejectedBook;
        int rejectedCandidate;
        long rejectedPhotoEpoch;
        boolean rejectedPhoto;
        boolean photoWaiting;
        long requestPhotoEpoch = CameraHardwareBenchmark.photoEpochIfIdle();

        boolean canCacheFallback() {
            return video || (!photoWaiting && CameraHardwareBenchmark.photoEpochCurrent(requestPhotoEpoch));
        }
        boolean canMeasure;
        final Runnable watchdog = this::benchmarkTimeout;

        Attempt(CameraCaptureSession session, CaptureRequest.Builder builder,
                CameraCharacteristics characteristics, Size size, String cameraId,
                boolean video, boolean still, boolean bypass, Handler handler) {
            this.session = session;
            this.handler = handler;
            this.still = still;
            this.video = video;
            this.cameraId = cameraId;
            this.stream = size.toString();
            baseline = builder.build(); // Immutable snapshot before touching optional keys.
            expected = baseline;
            profile = CameraAutoOptimizer.profile("camera2", cameraId,
                    still ? (video ? "photo-still-recording" : "photo-still")
                            : video ? "video" : "photo-preview", size.toString());
            try {
                profile.logOnce("capabilities", "capabilities " + describe(characteristics, size, video, still));
            } catch (RuntimeException e) {
                CameraAutoOptimizer.error(profile.label + " capability logging failed", e);
            }
            if (bypass) {
                profile.logOnce("bypass", "bypassed: barcode/night scene mode keeps template values");
            } else if (!CameraAutoOptimizer.isEnabled()) {
                profile.logOnce("kill-switch", "kill switch is off; keeping template values");
            }
            if (!bypass && CameraAutoOptimizer.isEnabled() && !profile.disabled()) {
                try {
                    summary = tune(builder, characteristics, size, video, still);
                    expected = builder.build();
                    optimized = true;
                    if (still && !video && photoCandidate == CameraHardwareBenchmark.TEMPLATE) {
                        expected = baseline;
                        optimized = false;
                    } else if (!still) {
                        candidates = new CaptureRequest[]{expected, expected, baseline};
                        alternative(builder, characteristics, video);
                        candidates[1] = builder.build();
                        mask = 7;
                        String a = requestSignature(candidates[0]), b = requestSignature(candidates[1]), c = requestSignature(baseline);
                        if (a.equals(b)) mask &= ~2;
                        if (a.equals(c) || b.equals(c)) mask &= ~4;
                        candidateSignature = a + "/" + b + "/" + c;
                        canMeasure = true;
                    }
                } catch (RuntimeException e) {
                    fallback = true;
                    CameraAutoOptimizer.error(profile.label + " optional request construction failed", e);
                }
            }
        }

        void send() throws CameraAccessException {
            if (still) session.capture(expected, this, handler);
            else session.setRepeatingRequest(expected, this, handler);
        }

        void start() throws CameraAccessException {
            if (canMeasure) {
                if (video) {
                    CameraHardwareBenchmark.Recording run = recordingBenchmark;
                    if (run != null) measured = run.bind(stream, mask, candidateSignature);
                } else if (photoBenchmarks && !photoFrozen) {
                    measured = CameraHardwareBenchmark.photo(cameraId, stream, mask, candidateSignature);
                    photoWaiting = measured == null;
                }
            }
            try {
                if (measured != null) {
                    if (!video) {
                        if (sendPhotoCandidate(measured)) return;
                        choose(CameraHardwareBenchmark.BALANCED); // Initial preview, not a trial step.
                    } else {
                        choose(measured.candidate);
                        handler.postDelayed(watchdog, 5500);
                    }
                }
                send();
            } catch (CameraAccessException | RuntimeException e) {
                if (measured != null) { restoreMeasured(e); return; }
                if (!optimized) { cancelProbe("baseline unavailable"); throw e; }
                cancelProbe("request rejected");
                invalidateRecording("request rejected");
                CameraAutoOptimizer.error(profile.label + " optional request rejected; retry template", e);
                expected = baseline;
                optimized = false;
                fallback = canCacheFallback();
                if (!video) photoCandidate = CameraHardwareBenchmark.TEMPLATE;
                if (!fallback) CameraAutoOptimizer.log(profile.label + " template recovery during interference; blacklist unchanged");
                send();
            }
        }

        boolean sendPhotoCandidate(CameraHardwareBenchmark.Probe probe) throws CameraAccessException {
            // The gate is checked atomically with submission, not only at probe creation.
            synchronized (CameraHardwareBenchmark.PHOTO_LOCK) {
                if (current == this && !photoFrozen && CameraHardwareBenchmark.photoCurrent(probe)) {
                    requestPhotoEpoch = probe.photoGeneration;
                    choose(probe.candidate);
                    send();
                    handler.postDelayed(watchdog, 5500);
                    photoWaiting = false;
                    return true;
                }
            }
            suspendPhoto("recording gate revoked pending submission");
            return false;
        }

        void suspendPhoto(String reason) {
            cancelProbe(reason);
            measured = null;
            photoWaiting = true;
            CameraAutoOptimizer.log(profile.label + " photo probes paused: " + reason);
        }

        void restoreMeasured(Exception error) throws CameraAccessException {
            rejectedBook = measured.book;
            rejectedCandidate = measured.candidate;
            rejectedPhoto = !video;
            rejectedPhotoEpoch = measured.photoGeneration;
            cancelProbe("candidate rejected");
            measured = null;
            invalidateRecording("candidate rejected; fallback must not be scored as the original candidate");
            CameraAutoOptimizer.error(profile.label + " measured candidate rejected; restore working request", error);
            choose(rejectedCandidate == CameraHardwareBenchmark.BALANCED
                    ? CameraHardwareBenchmark.TEMPLATE : CameraHardwareBenchmark.BALANCED);
            fallback = false; // A failed alternative does not disable ALL optional tuning.
            try { send(); }
            catch (CameraAccessException | RuntimeException retry) {
                if (expected == baseline) { rejectedBook = null; throw retry; }
                choose(CameraHardwareBenchmark.TEMPLATE);
                try { send(); }
                catch (CameraAccessException | RuntimeException unavailable) { rejectedBook = null; throw unavailable; }
            }
        }

        void choose(int index) {
            expected = candidates[index];
            optimized = index != CameraHardwareBenchmark.TEMPLATE;
            completed = false;
            summary = requestSignature(expected) + " measuredCandidate=" + index;
            if (!video) photoCandidate = index;
        }

        void cancelProbe(String reason) {
            handler.removeCallbacks(watchdog);
            if (measured != null && !video) measured.cancel(reason);
        }

        void benchmarkTimeout() {
            if (current != this || measured == null || measured.done) return;
            if (!video && !CameraHardwareBenchmark.photoCurrent(measured)) {
                suspendPhoto("recording gate canceled watchdog");
                return;
            }
            if (!measured.ready()) measured.invalidate("no complete sensor window before timeout");
            if (!video && !photoFrozen) advancePhoto();
        }

        void advancePhoto() {
            handler.removeCallbacks(watchdog);
            if (measured == null) return;
            if (!CameraHardwareBenchmark.photoCurrent(measured)) { suspendPhoto("recording began during sample"); return; }
            long epoch = measured.photoGeneration;
            measured.finishPhoto();
            int best = measured.book.best();
            boolean complete = measured.book.complete();
            measured = null;
            if (current != this || photoFrozen) return;
            if (!CameraHardwareBenchmark.photoEpochCurrent(epoch)) { suspendPhoto("recording interrupted sample completion"); return; }
            if (++trials < 3 && !complete && photoBenchmarks) {
                measured = CameraHardwareBenchmark.photo(cameraId, stream, mask, candidateSignature);
            }
            try {
                if (measured != null) {
                    sendPhotoCandidate(measured);
                } else {
                    synchronized (CameraHardwareBenchmark.PHOTO_LOCK) {
                        if (!CameraHardwareBenchmark.photoEpochCurrent(epoch)) { photoWaiting = true; return; }
                        requestPhotoEpoch = epoch;
                        choose(best);
                        send();
                    }
                    CameraAutoOptimizer.log(profile.label + " probe sequence stopped; selected=" + best
                            + " allCandidatesMeasured=" + complete + " (continue next camera open if incomplete)");
                }
            } catch (CameraAccessException | RuntimeException e) {
                try {
                    if (measured != null) restoreMeasured(e);
                    else { choose(CameraHardwareBenchmark.TEMPLATE); send(); }
                } catch (CameraAccessException | RuntimeException unavailable) {
                    cancelProbe("camera unavailable during probe");
                    CameraAutoOptimizer.error(profile.label + " template restore failed", unavailable);
                }
            }
        }

        void measure(TotalCaptureResult result) {
            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
            Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
            long exposureNs = exposure == null ? -1 : exposure;
            int sensitivity = iso == null ? -1 : iso;
            int aeState = ae == null ? -1 : ae;
            CameraHardwareBenchmark.captureLight(cameraId, exposureNs, sensitivity, aeState);
            if (!video) {
                if (photoFrozen) return;
                if (measured != null && !CameraHardwareBenchmark.photoCurrent(measured))
                    suspendPhoto("recording gate interrupted this camera's trial");
                if (measured == null && photoWaiting && canMeasure && photoBenchmarks
                        && !CameraHardwareBenchmark.recordingActive()) {
                    measured = CameraHardwareBenchmark.photo(cameraId, stream, mask, candidateSignature);
                    if (measured != null) {
                        trials = 0;
                        try { sendPhotoCandidate(measured); }
                        catch (CameraAccessException | RuntimeException e) {
                            try { restoreMeasured(e); }
                            catch (CameraAccessException | RuntimeException unavailable) {
                                cancelProbe("resume failed");
                                CameraAutoOptimizer.error(profile.label + " photo probe resume failed", unavailable);
                            }
                        }
                    }
                    return; // This frame belongs to the request BEFORE resume.
                }
            }
            if (measured == null || measured.done) return;
            measured.frame(timestamp == null ? -1 : timestamp, af == null ? -1 : af, aeState, exposureNs, sensitivity);
            if (measured.ready()) {
                handler.removeCallbacks(watchdog);
                if (!video) advancePhoto();
            }
        }

        private boolean active(CaptureRequest request) {
            return (still || current == this) && request == expected;
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            if (!active(request)) return;
            failures = 0;
            if (rejectedBook != null) {
                synchronized (CameraHardwareBenchmark.PHOTO_LOCK) {
                    if (!rejectedPhoto || CameraHardwareBenchmark.photoEpochCurrent(rejectedPhotoEpoch))
                        rejectedBook.reject(rejectedCandidate);
                }
                rejectedBook = null;
            }
            if (!completed) {
                completed = true;
                if (fallback) {
                    synchronized (CameraHardwareBenchmark.PHOTO_LOCK) {
                        if (canCacheFallback()) profile.disable("template produced a capture result");
                        else CameraAutoOptimizer.log(profile.label + " fallback confirmation interrupted; blacklist unchanged");
                    }
                } else if (optimized) profile.accept(summary + " (capture result received)");
            }
            if (!still) measure(result);
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            if (!active(request) || failure.getReason() != CaptureFailure.REASON_ERROR) return;
            if (!still && !video && measured != null && !CameraHardwareBenchmark.photoCurrent(measured))
                suspendPhoto("recording gate interrupted trial before capture error");
            if (measured != null) {
                synchronized (measured) { measured.sensor.errors++; measured.invalidate("capture error"); }
            }
            if (still) {
                // Never automatically retry an accepted still capture: could duplicate a photo.
                CameraAutoOptimizer.log(profile.label + " still capture failed; no automatic recapture");
                return;
            }
            if (measured != null) {
                if (++failures < 3) return;
                failures = 0;
                try { restoreMeasured(new IllegalStateException("three consecutive capture errors")); }
                catch (CameraAccessException | RuntimeException e) {
                    CameraAutoOptimizer.error(profile.label + " measured fallback unavailable", e);
                }
                return;
            }
            if (!optimized || ++failures < 3) return;
            cancelProbe("capture errors");
            invalidateRecording("capture errors");
            CameraAutoOptimizer.log(profile.label + " three consecutive capture errors; retry template once");
            expected = baseline;
            optimized = false;
            fallback = canCacheFallback();
            if (!video) photoCandidate = CameraHardwareBenchmark.TEMPLATE;
            if (!fallback) CameraAutoOptimizer.log(profile.label + " template recovery during interference; blacklist unchanged");
            completed = false;
            failures = 0;
            try {
                send();
            } catch (CameraAccessException | RuntimeException e) {
                CameraAutoOptimizer.error(profile.label + " template retry failed; cache unchanged", e);
            }
        }
    }
}
