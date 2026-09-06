package org.telegram.messenger.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import org.telegram.messenger.Bitmaps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Still JPEG only. The output pixels, not an editor-only flag, match the selfie preview. */
final class CameraPhotoProcessor {
    private CameraPhotoProcessor() {}

    static byte[] forPreview(byte[] jpeg, boolean mirrorFront) throws IOException {
        if (!mirrorFront) return jpeg; // Rear camera / disabled flip: byte-for-byte preservation.
        final long started = System.nanoTime();
        final ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
        final int tag = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        // Canonical EXIF transform: source horizontal reflection, then clockwise rotation.
        // Do not use the legacy editor's invert/rotation encoding for tags 5 and 7 here.
        final boolean exifFlip = tag == 2 || tag == 4 || tag == 5 || tag == 7;
        final int rotation = tag == 3 || tag == 4 ? 180 : tag == 6 || tag == 7 ? 90 : tag == 5 || tag == 8 ? 270 : 0;
        Bitmap source = null, result = null;
        try {
            source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (source == null) throw new IOException("Cannot decode front JPEG");
            final int sourceWidth = source.getWidth(), sourceHeight = source.getHeight();
            Matrix matrix = new Matrix();
            if (exifFlip) matrix.postScale(-1, 1);
            matrix.postRotate(rotation);
            // Mirror in UPRIGHT display coordinates, never in the raw 90/270-degree axes.
            matrix.postScale(-1, 1);
            result = Bitmaps.createBitmap(source, 0, 0, sourceWidth, sourceHeight, matrix, true);
            if (result == null) throw new IOException("Cannot transform front JPEG");
            if (result != source) {
                source.recycle(); // Release the full-resolution input before allocating encoded output.
                source = null;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!result.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                throw new IOException("Cannot encode front JPEG");
            }
            byte[] normalized = output.toByteArray();
            CameraAutoOptimizer.log("photo mirror normalized: sourceExif=" + tag
                    + " source=" + sourceWidth + "x" + sourceHeight
                    + " output=" + result.getWidth() + "x" + result.getHeight()
                    + " rotation=" + rotation + " sourceFlip=" + exifFlip
                    + " uprightMirror=true outputRotation=0 outputInvert=0 jpegQuality=95"
                    + " processingMs=" + (System.nanoTime() - started) / 1000000.0);
            return normalized;
        } finally {
            if (result != null && result != source) result.recycle();
            if (source != null) source.recycle();
        }
    }
}
