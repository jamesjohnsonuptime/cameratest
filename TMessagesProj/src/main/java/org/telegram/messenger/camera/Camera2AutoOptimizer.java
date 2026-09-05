/* Licensed under GNU GPL v. 2 or later. Independently implemented. */
package org.telegram.messenger.camera;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.util.Range;
import android.util.Size;

import java.util.List;

@TargetApi(21)
final class Camera2AutoOptimizer {
    private volatile Attempt current;

    void stop() {
        current = null;
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
        if (!still) {
            int budget = CameraOptimizationPolicy.TARGET_VIDEO_FPS;
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
                + " minFrameDurationNs=" + minFrameDuration;
    }

    void repeating(CameraCaptureSession session, CaptureRequest.Builder builder,
                   CameraCharacteristics characteristics, Size size, String cameraId,
                   boolean video, boolean bypass, Handler handler) throws CameraAccessException {
        Attempt attempt = new Attempt(session, builder, characteristics, size, cameraId,
                video, false, bypass, handler);
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

        Attempt(CameraCaptureSession session, CaptureRequest.Builder builder,
                CameraCharacteristics characteristics, Size size, String cameraId,
                boolean video, boolean still, boolean bypass, Handler handler) {
            this.session = session;
            this.handler = handler;
            this.still = still;
            baseline = builder.build(); // Immutable snapshot before touching optional keys.
            expected = baseline;
            profile = CameraAutoOptimizer.profile("camera2", cameraId,
                    still ? (video ? "photo-still-recording" : "photo-still")
                            : video ? "video" : "photo-preview", size.toString());
            if (!bypass && CameraAutoOptimizer.isEnabled() && !profile.disabled()) {
                try {
                    summary = tune(builder, characteristics, size, video, still);
                    expected = builder.build();
                    optimized = true;
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
            try {
                send();
            } catch (CameraAccessException | RuntimeException e) {
                if (!optimized) throw e;
                CameraAutoOptimizer.error(profile.label + " optional request rejected; retry template", e);
                expected = baseline;
                optimized = false;
                fallback = true;
                send(); // One retry. No persistent blacklist until a real result arrives.
            }
        }

        private boolean active(CaptureRequest request) {
            return (still || current == this) && request == expected;
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            if (!active(request)) return;
            failures = 0;
            if (completed) return;
            completed = true;
            if (fallback) profile.disable("template produced a capture result");
            else if (optimized) profile.accept(summary + " (capture result received)");
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            if (!active(request) || failure.getReason() != CaptureFailure.REASON_ERROR) return;
            if (still) {
                // Never automatically retry an accepted still capture: could duplicate a photo.
                CameraAutoOptimizer.log(profile.label + " still capture failed; no automatic recapture");
                return;
            }
            if (!optimized || ++failures < 3) return;
            CameraAutoOptimizer.log(profile.label + " three consecutive capture errors; retry template once");
            expected = baseline;
            optimized = false;
            fallback = true;
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
