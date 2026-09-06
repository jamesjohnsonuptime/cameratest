/* GPL v2 or later. Paired exposure/ISO measurements, independent of Android. */
package org.telegram.messenger.camera;

import java.util.Arrays;

final class CameraIllumination {
    private static final int SIZE = 9;
    private final double[] products = new double[SIZE];
    private final long[] times = new long[SIZE];
    private final double[] sorted = new double[SIZE];
    private int count, next;
    private long last = -1;
    private double median;
    private boolean stable;

    boolean add(long exposureNs, int iso, int ae, long now) {
        // Searching/precapture frames do not establish a lighting category.
        // Absent AE metadata is not treated as converged: require stable pairs instead.
        if (exposureNs <= 0 || iso <= 0 || ae == 1 || ae == 5) {
            count = next = 0; stable = false; return false;
        }
        if (last >= 0 && (now <= last || now - last > 300_000_000L)) count = next = 0;
        last = now;
        products[next] = exposureNs / 1e6 * iso / 100.0;
        times[next] = now;
        next = (next + 1) % SIZE;
        count = Math.min(SIZE, count + 1);
        stable = false;
        if (count < SIZE) return false;
        System.arraycopy(products, 0, sorted, 0, SIZE);
        Arrays.sort(sorted);
        median = sorted[SIZE / 2];
        long oldest = times[next];
        stable = now - oldest >= 100_000_000L && median > 0
                && (sorted[SIZE - 1] - sorted[0]) / median <= .25;
        return stable;
    }

    double product() { return median; }

    static String bucket(double product, String previous) {
        if (!(product > 0) || Double.isNaN(product) || Double.isInfinite(product)) return "unknown";
        // Nominal boundaries 10/100; a 20-25% dead band prevents threshold chatter.
        if ("bright".equals(previous) && product <= 12.5) return "bright";
        if ("normal".equals(previous) && product >= 8 && product <= 125) return "normal";
        if ("low".equals(previous) && product >= 80) return "low";
        return product < 10 ? "bright" : product < 100 ? "normal" : "low";
    }
}
