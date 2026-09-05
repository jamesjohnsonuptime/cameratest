/*
 * Capability-based camera defaults with a persistent, firmware-scoped fallback.
 * Licensed under GNU GPL v. 2 or later. No decompiled code is included.
 */
package org.telegram.messenger.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public final class CameraAutoOptimizer {
    private static final String TAG = "CameraAutoOpt/" + CameraOptimizationPolicy.VERSION;
    private static final String PREFS = "camera_auto_optimization";
    private static final HashMap<String, Profile> profiles = new HashMap<>();

    private CameraAutoOptimizer() {}

    static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Developer kill switch. Reopen the camera after changing it. No new UI. */
    public static boolean isEnabled() {
        try {
            return preferences().getBoolean("enabled", true);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void setEnabled(boolean enabled) {
        preferences().edit().putBoolean("enabled", enabled).apply();
        log("enabled=" + enabled + "; reopen camera to reset applied defaults");
    }

    /**
     * Resolves the camera API for a preview owner and logs why, so a logcat line
     * explains every fallback to camera1 instead of leaving it unexplained.
     *
     * Camera2Session.create() only queries getOutputSizes(SurfaceTexture.class) on
     * API 23+, so on API 21-22 it always returns null and the caller keeps a black
     * preview. Those devices stay on camera1 regardless of the user setting.
     */
    public static boolean useCamera2(int account, String owner) {
        boolean setting;
        try {
            setting = SharedConfig.isUsingCamera2(account);
        } catch (RuntimeException e) {
            error(owner + " camera2 setting unavailable; keeping camera1", e);
            return false;
        }
        String remote;
        try {
            remote = String.valueOf(MessagesController.getInstance(account).androidDisableRoundCamera2);
        } catch (RuntimeException e) {
            remote = "unavailable";
        }
        final boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
        final boolean result = setting && supported;
        log(owner + " api=" + (result ? "camera2" : "camera1")
                + " setting=" + setting
                + " userForced=" + SharedConfig.useCamera2Force
                + " remoteDisableRoundCamera2=" + remote
                + " sdk=" + Build.VERSION.SDK_INT
                + " camera2Usable=" + supported);
        return result;
    }

    /** Reset only this feature's state, never Telegram's camera/user settings. */
    public static synchronized void resetProfiles() {
        boolean enabled = isEnabled();
        preferences().edit().clear().putBoolean("enabled", enabled).apply();
        profiles.clear();
        log("profiles reset; reopen camera");
    }

    static synchronized Profile profile(String api, String id, String mode, String stream) {
        // Hash is an identity/cache key, not a security primitive. Reuse Utilities.
        String key = Utilities.MD5(CameraOptimizationPolicy.VERSION + "|" + Build.FINGERPRINT
                + "|" + api + "|" + id + "|" + mode + "|" + stream);
        Profile result = profiles.get(key);
        if (result == null) {
            result = new Profile(key, api + " camera=" + id + " mode=" + mode + " stream=" + stream);
            profiles.put(key, result);
        }
        return result;
    }

    static final class Profile {
        final String key;
        final String label;
        private final HashSet<String> notes = new HashSet<>();
        private boolean disabled;
        private String accepted;
        private boolean logged;

        /** Logs a tag at most once per profile: zoom/flash churn rebuilds requests. */
        synchronized void logOnce(String tag, String message) {
            if (!notes.add(tag)) return;
            log(label + " " + message);
        }

        Profile(String key, String label) {
            this.key = key;
            this.label = label;
            try {
                disabled = preferences().getBoolean(key + ".disabled", false);
                accepted = preferences().getString(key + ".accepted", null);
            } catch (RuntimeException e) {
                error(label + " cache read failed", e);
            }
        }

        synchronized boolean disabled() {
            if (disabled && !logged) {
                log(label + " using cached device defaults");
                logged = true;
            }
            return disabled;
        }

        synchronized void accept(String summary) {
            if (!logged) {
                log(label + " accepted " + summary);
                logged = true;
            }
            if (summary.equals(accepted)) return;
            accepted = summary;
            try {
                preferences().edit().putString(key + ".accepted", summary).apply();
            } catch (RuntimeException e) {
                error(label + " cache write failed", e);
            }
        }

        synchronized void disable(String reason) {
            if (disabled) return;
            disabled = true;
            log(label + " fallback confirmed; disabled optional tuning: " + reason);
            try {
                preferences().edit().putBoolean(key + ".disabled", true).remove(key + ".accepted").apply();
            } catch (RuntimeException e) {
                error(label + " fallback cache write failed", e);
            }
        }
    }

    static void log(String message) {
        if (BuildVars.LOGS_ENABLED) FileLog.d(TAG + " " + message);
    }

    static void error(String message, Throwable error) {
        FileLog.e(TAG + " " + message, error);
    }

    private static String fpsRanges(List<int[]> ranges) {
        if (ranges == null) return "null";
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < ranges.size(); i++) {
            int[] range = ranges.get(i);
            if (i > 0) result.append(", ");
            result.append(range == null || range.length < 2 ? "?" : range[0] + "-" + range[1]);
        }
        return result.append("]").toString();
    }

    private static String sizes(List<Camera.Size> values) {
        if (values == null) return "null";
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            Camera.Size size = values.get(i);
            if (i > 0) result.append(", ");
            result.append(size == null ? "?" : size.width + "x" + size.height);
        }
        return result.append("]").toString();
    }

    private static String focus(List<String> supported, boolean video) {
        if (supported == null) return null;
        String preferred = video ? Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                : Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
        if (supported.contains(preferred)) return preferred;
        if (supported.contains(Camera.Parameters.FOCUS_MODE_AUTO)) return Camera.Parameters.FOCUS_MODE_AUTO;
        // Fixed-focus front cameras must keep their own supported default.
        return null;
    }

    /**
     * Apply on the caller's existing camera path. No camera opens, test captures,
     * preview callbacks, flash/zoom/exposure overrides or background recordings.
     */
    static void applyLegacy(Camera camera, Camera.Parameters parameters, int cameraId,
                            boolean video, boolean bypass) {
        if (bypass || !isEnabled()) {
            camera.setParameters(parameters);
            return;
        }
        Camera.Size preview = parameters.getPreviewSize();
        Camera.Size picture = parameters.getPictureSize();
        if (preview == null || picture == null) {
            log("camera1 camera=" + cameraId + " missing stream metadata; keep caller defaults");
            camera.setParameters(parameters);
            return;
        }
        Profile state = profile("camera1", String.valueOf(cameraId), video ? "video" : "photo",
                preview.width + "x" + preview.height + "/" + picture.width + "x" + picture.height);
        try {
            state.logOnce("capabilities", "capabilities focusModes=" + parameters.getSupportedFocusModes()
                    + " wbModes=" + parameters.getSupportedWhiteBalance()
                    + " antibandingModes=" + parameters.getSupportedAntibanding()
                    + " fpsRanges=" + fpsRanges(parameters.getSupportedPreviewFpsRange())
                    + " videoSizes=" + sizes(parameters.getSupportedVideoSizes())
                    + " previewSizes=" + sizes(parameters.getSupportedPreviewSizes())
                    + " stabilizationSupported=" + parameters.isVideoStabilizationSupported());
        } catch (RuntimeException e) {
            // Logging must never break the camera.
            error(state.label + " capability logging failed", e);
        }
        if (state.disabled()) {
            camera.setParameters(parameters);
            return;
        }
        final String baseline = parameters.flatten();
        String summary;
        try {
            String focus = focus(parameters.getSupportedFocusModes(), video);
            if (focus != null) parameters.setFocusMode(focus);
            List<String> whiteBalance = parameters.getSupportedWhiteBalance();
            if (whiteBalance != null && whiteBalance.contains(Camera.Parameters.WHITE_BALANCE_AUTO)) {
                parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
            }
            List<String> antibanding = parameters.getSupportedAntibanding();
            if (antibanding != null && antibanding.contains(Camera.Parameters.ANTIBANDING_AUTO)) {
                parameters.setAntibanding(Camera.Parameters.ANTIBANDING_AUTO);
            }
            List<int[]> advertised = parameters.getSupportedPreviewFpsRange();
            int[][] ranges = advertised == null ? null : advertised.toArray(new int[advertised.size()][]);
            int index = CameraOptimizationPolicy.chooseFpsRange(ranges,
                    CameraOptimizationPolicy.TARGET_VIDEO_FPS * 1000, video);
            if (index >= 0) parameters.setPreviewFpsRange(ranges[index][0], ranges[index][1]);
            if (parameters.isVideoStabilizationSupported()) parameters.setVideoStabilization(video);
            summary = "af=" + parameters.getFocusMode() + " wb=" + parameters.getWhiteBalance()
                    + " antibanding=" + parameters.getAntibanding() + " fps="
                    + (index < 0 ? "device" : ranges[index][0] + "-" + ranges[index][1])
                    + " eis=" + parameters.getVideoStabilization();
            camera.setParameters(parameters);
        } catch (RuntimeException optimizedError) {
            error(state.label + " optional parameters rejected; retry original parameters", optimizedError);
            parameters.unflatten(baseline);
            // If this also throws, propagate it and do NOT poison the profile cache.
            camera.setParameters(parameters);
            state.disable("original setParameters succeeded");
            return;
        }
        // Camera1 acknowledges setParameters, not measured frame rate/quality.
        state.accept(summary + " (setParameters accepted, not a benchmark)");
    }

    /** Prefer a real video profile; JPEG sizes are not valid video capabilities. */
    static CamcorderProfile recorderProfile(int cameraId, int high, int quality, Camera.Parameters params) {
        int[] candidates = quality != 1 ? new int[]{CamcorderProfile.QUALITY_LOW}
                : high == CamcorderProfile.QUALITY_480P
                ? new int[]{high, CamcorderProfile.QUALITY_LOW}
                : new int[]{CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P,
                        high, CamcorderProfile.QUALITY_LOW};
        List<Camera.Size> sizes = params == null ? null : params.getSupportedVideoSizes();
        if (sizes == null && params != null) sizes = params.getSupportedPreviewSizes();
        for (int candidate : candidates) {
            try {
                if (!CamcorderProfile.hasProfile(cameraId, candidate)) continue;
                CamcorderProfile profile = CamcorderProfile.get(cameraId, candidate);
                if (profile.videoFrameWidth <= 0 || profile.videoFrameHeight <= 0
                        || profile.videoFrameRate <= 0
                        || profile.videoFrameRate > CameraOptimizationPolicy.TARGET_VIDEO_FPS
                        || profile.videoBitRate <= 0) continue;
                boolean supported = sizes == null;
                if (sizes != null) {
                    for (Camera.Size size : sizes) {
                        if (size.width == profile.videoFrameWidth && size.height == profile.videoFrameHeight) {
                            supported = true;
                            break;
                        }
                    }
                }
                if (!supported) continue;
                log("camera1 camera=" + cameraId + " recorder profile=" + candidate + " "
                        + profile.videoFrameWidth + "x" + profile.videoFrameHeight
                        + " fps=" + profile.videoFrameRate + " bitrate=" + profile.videoBitRate);
                return profile;
            } catch (RuntimeException e) {
                error("camera1 camera=" + cameraId + " recorder profile=" + candidate + " unavailable", e);
            }
        }
        throw new IllegalStateException("No supported camera video profile");
    }
}
