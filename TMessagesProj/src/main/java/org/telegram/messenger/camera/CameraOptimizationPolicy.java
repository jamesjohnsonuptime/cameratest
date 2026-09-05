/*
 * Camera optimization policy. Licensed under GNU GPL v. 2 or later.
 * Independently implemented; contains no decompiled ProShot code.
 */
package org.telegram.messenger.camera;

/** Pure policy, shared by Camera1 (millifps) and Camera2 (fps). */
public final class CameraOptimizationPolicy {
    public static final String VERSION = "0.1.2";
    public static final int TARGET_VIDEO_FPS = 30;

    private CameraOptimizationPolicy() {}

    /**
     * Return an index into the advertised ranges, never fabricate a range.
     * Stay at/below the encoder budget. Video prefers stable cadence; photo
     * preview prefers exposure latitude at the same maximum frame rate.
     * -1 means keep the device/template default, NOT use an invented fallback.
     */
    public static int chooseFpsRange(int[][] ranges, int target, boolean video) {
        if (ranges == null || target <= 0) return -1;
        int best = -1;
        for (int i = 0; i < ranges.length; i++) {
            int[] range = ranges[i];
            if (range == null || range.length != 2 || range[0] <= 0
                    || range[1] < range[0] || range[1] > target) continue;
            if (best < 0 || range[1] > ranges[best][1]
                    || range[1] == ranges[best][1]
                    && (video ? range[0] > ranges[best][0] : range[0] < ranges[best][0])) {
                best = i;
            }
        }
        return best;
    }

    /** A few legacy Camera2 HALs advertise millifps instead of fps. */
    public static int camera2FpsScale(int[][] ranges) {
        boolean found = false;
        if (ranges == null) return 1;
        for (int[] range : ranges) {
            if (range == null || range.length != 2 || range[0] <= 0 || range[1] < range[0]) continue;
            if (range[1] < 1000) return 1;
            found = true;
        }
        return found ? 1000 : 1;
    }

    public static Integer chooseMode(int[] supported, int... preferred) {
        if (supported != null && preferred != null) {
            for (int value : preferred) {
                for (int option : supported) {
                    if (value == option) return value;
                }
            }
        }
        return null;
    }
}
