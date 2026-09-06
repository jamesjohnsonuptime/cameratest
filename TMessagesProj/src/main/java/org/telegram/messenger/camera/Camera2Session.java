package org.telegram.messenger.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Session {

    private boolean isError;
    private boolean isSuccess;
    private volatile boolean isClosed;
    private final Camera2AutoOptimizer autoOptimizer = new Camera2AutoOptimizer();
    private volatile CameraHardwareBenchmark.Recording hardwareRecording;

    /** Called only after a real MediaCodec has started, so its identity is known. */
    public CameraHardwareBenchmark.Recording beginHardwareRecording(String useCase, int width, int height,
                                                                    int bitrate, String codec) {
        CameraHardwareBenchmark.Recording run = new CameraHardwareBenchmark.Recording(cameraId, useCase, width, height, bitrate);
        run.codec(codec);
        hardwareRecording = run;
        autoOptimizer.recordingBenchmark(run);
        recordingVideo = true;
        updateCaptureRequest();
        return run;
    }

    public void finishHardwareRecording(CameraHardwareBenchmark.Recording run, boolean success) {
        run.finish(success);
        if (hardwareRecording == run) {
            hardwareRecording = null;
            autoOptimizer.recordingBenchmark(null);
        }
    }

    private final CameraManager cameraManager;
    private final boolean isFront;
    // Same default and contract as CameraSession; captured once per shutter press.
    private volatile boolean flipFront = true;

    public void setFlipFront(boolean flip) {
        flipFront = flip;
        CameraAutoOptimizer.log("camera2 front-photo policy: camera=" + cameraId + " flipFront=" + flip);
    }
    public final String cameraId;
    private CameraCharacteristics cameraCharacteristics;

    private HandlerThread thread;
    private Handler handler;

    private CameraDevice cameraDevice;
    private SurfaceTexture surfaceTexture;
    private CameraCaptureSession captureSession;
    private Surface surface;

    private final CameraDevice.StateCallback cameraStateCallback;
    private final CameraCaptureSession.StateCallback captureStateCallback;
    private CaptureRequest.Builder captureRequestBuilder;
    private Rect sensorSize;
    private float maxZoom = 1f;
    private volatile float currentZoom = 1f;

    private final Size previewSize;

    private ImageReader imageReader;

    private long lastTime;
    private final long createdTime = System.currentTimeMillis();
    /** Last logged request state; keeps zoom gestures from spamming the log. */
    private String lastRequestSignature;

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight) {
        final Context context = ApplicationLoader.applicationContext;
        final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        float bestAspectRatio = 0;
        Size bestSize = null;
        String cameraId = null;
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (int i = 0; i < cameraIds.length; ++i) {
                final String id = cameraIds[i];
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                if (characteristics == null) continue;
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    continue;
                }
                StreamConfigurationMap confMap = (StreamConfigurationMap) characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                float cameraAspectRatio = pixelSize == null ? 0 : (float) pixelSize.getWidth() / pixelSize.getHeight();
                if ((viewWidth / (float) viewHeight >= 1f) != (cameraAspectRatio >= 1f)) {
                    cameraAspectRatio = 1f / cameraAspectRatio;
                }
                if (bestAspectRatio <= 0 || Math.abs((float) viewWidth / viewHeight - bestAspectRatio) > Math.abs((float) viewWidth / viewHeight - cameraAspectRatio)) {
                    if (confMap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Size[] commonSizes = commonOutputSizes(confMap.getOutputSizes(SurfaceTexture.class),
                                confMap.getOutputSizes(ImageFormat.JPEG));
                        Size size = chooseOptimalSize(commonSizes, viewWidth, viewHeight, false);
                        CameraAutoOptimizer.log("camera2 stream selection: camera=" + id
                                + " view=" + viewWidth + "x" + viewHeight
                                + " targetAxes=" + Math.max(viewWidth, viewHeight) + "x" + Math.min(viewWidth, viewHeight)
                                + " commonPreviewJpegSizes=" + commonSizes.length + " selected=" + size);
                        if (size != null) {
                            bestAspectRatio = cameraAspectRatio;
                            cameraId = id;
                            bestSize = size;
                        }
                    }
                } else {

                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (cameraId == null || bestSize == null) {
            CameraAutoOptimizer.log("camera2 create failed: front=" + front
                    + " view=" + viewWidth + "x" + viewHeight + " sdk=" + Build.VERSION.SDK_INT
                    + " cameraId=" + cameraId + " size=" + bestSize
                    + "; no camera advertised a common SurfaceTexture/JPEG size, preview would stay black");
            return null;
        }
        String hardwareLevel = "unknown";
        try {
            CameraCharacteristics chosen = cameraManager.getCameraCharacteristics(cameraId);
            hardwareLevel = String.valueOf(chosen == null ? null
                    : chosen.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
        } catch (Exception e) {
            CameraAutoOptimizer.error("camera2 hardware level query failed for camera #" + cameraId, e);
        }
        CameraAutoOptimizer.log("camera2 create camera #" + cameraId + " front=" + front
                + " preview=" + bestSize + " jpeg=" + bestSize + " view=" + viewWidth + "x" + viewHeight
                + " hardwareLevel=" + hardwareLevel
                + " (0=limited 1=full 2=legacy 3=level_3 4=external)");
        return new Camera2Session(context, front, cameraId, bestSize);
    }

    private Camera2Session(Context context, boolean isFront, String cameraId, Size size) {
        thread = new HandlerThread("tg_camera2");
        thread.start();
        handler = new Handler(thread.getLooper());

        cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                Camera2Session.this.lastTime = System.currentTimeMillis();
                FileLog.d("Camera2Session camera #" + cameraId + " opened");
                CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " device opened "
                        + (System.currentTimeMillis() - createdTime) + "ms after create");
                checkOpen();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                autoOptimizer.invalidateRecording("device disconnected");
                autoOptimizer.stop();
                CameraHardwareBenchmark.cameraClosed(Camera2Session.this);
                Camera2Session.this.cameraDevice = camera;
                FileLog.d("Camera2Session camera #" + cameraId + " disconnected");
                CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " device disconnected "
                        + (System.currentTimeMillis() - createdTime) + "ms after create");
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                autoOptimizer.invalidateRecording("device error");
                autoOptimizer.stop();
                CameraHardwareBenchmark.cameraClosed(Camera2Session.this);
                Camera2Session.this.cameraDevice = camera;
                FileLog.e("Camera2Session camera #" + cameraId + " received " + error + " error");
                CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " device error=" + error
                        + " (1=disabled 2=in_use 3=max_in_use 4=device 5=service)");
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        captureStateCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session configured");
                CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " capture session configured "
                        + (System.currentTimeMillis() - createdTime) + "ms after create; preview=" + previewSize);
                Camera2Session.this.lastTime = System.currentTimeMillis();
                try {
                    if (!updateCaptureRequest()) {
                        CameraAutoOptimizer.log("Camera2Session camera #" + cameraId
                                + " first capture request failed; session marked as error");
                        AndroidUtilities.runOnUIThread(() -> isError = true);
                        return;
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        isSuccess = true;
                        if (doneCallback != null) {
                            doneCallback.run();
                            doneCallback = null;
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session failed to configure");
                CameraAutoOptimizer.log("Camera2Session camera #" + cameraId
                        + " capture session failed to configure; preview=" + previewSize
                        + " (surface combination rejected by the HAL)");
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        this.isFront = isFront;
        this.cameraId = cameraId;
        this.previewSize = size;
        this.lastTime = System.currentTimeMillis();
        this.imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final Float value = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (value == null || value < 1f) ? 1f : value;
            CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " opening front=" + isFront
                    + " preview=" + size + " sensor=" + sensorSize + " maxZoom=" + maxZoom);
            CameraHardwareBenchmark.cameraOpened(this);
            cameraManager.openCamera(cameraId, cameraStateCallback, handler);
        } catch (Exception e) {
            CameraHardwareBenchmark.cameraClosed(this);
            CameraAutoOptimizer.error("Camera2Session camera #" + cameraId + " openCamera failed", e);
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    private Runnable doneCallback;
    public void whenDone(Runnable doneCallback) {
        if (isInitiated()) {
            doneCallback.run();
            this.doneCallback = null;
        } else {
            this.doneCallback = doneCallback;
        }
    }

    public void open(SurfaceTexture surfaceTexture) {
        handler.post(() -> {
            this.surfaceTexture = surfaceTexture;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(getPreviewWidth(), getPreviewHeight());
            }
            checkOpen();
        });
    }

    private boolean opened = false;
    private void checkOpen() {
        if (opened) return;
        if (surfaceTexture == null || cameraDevice == null) return;
        opened = true;

        surface = new Surface(surfaceTexture);

        try {
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            surfaces.add(imageReader.getSurface());
            CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " creating capture session with "
                    + surfaces.size() + " surfaces (preview " + previewSize + " + jpeg reader)");
            cameraDevice.createCaptureSession(surfaces, captureStateCallback, null);
        } catch (Exception e) {
            CameraAutoOptimizer.error("Camera2Session camera #" + cameraId + " createCaptureSession failed", e);
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    public boolean isInitiated() {
        return !isError && isSuccess && !isClosed;
    }

    public int getDisplayOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int displayOrientation;
            if (isFront) {
                displayOrientation = (sensorOrientation + degrees) % 360;
                displayOrientation = (360 - displayOrientation) % 360; // compensate the mirror
            } else { // back-facing
                displayOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return displayOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    private int getJpegOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int jpegOrientation;
            if (isFront) {
                jpegOrientation = (sensorOrientation + degrees) % 360;
                jpegOrientation = (360 - jpegOrientation) % 360; // compensate the mirror
            } else { // back-facing
                jpegOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return jpegOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    /**
     * Orientation for the saved JPEG. Separate from getJpegOrientation(), which
     * keeps its display/preview semantics for getWorldAngle() and
     * getCurrentOrientation(). See CameraOptimizationPolicy#stillJpegOrientation.
     */
    private int getStillJpegOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                CameraAutoOptimizer.log("Camera2Session camera #" + cameraId
                        + " still orientation: no application context, defaulting to 0");
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == null) {
                CameraAutoOptimizer.log("Camera2Session camera #" + cameraId
                        + " still orientation: SENSOR_ORIENTATION missing, defaulting to 0");
                return 0;
            }
            final int still = CameraOptimizationPolicy.stillJpegOrientation(sensorOrientation, degrees, isFront);
            CameraAutoOptimizer.log("Camera2Session camera #" + cameraId
                    + " still orientation chain: front=" + isFront
                    + " sensorOrientation=" + sensorOrientation
                    + " displayRotation=" + degrees
                    + " stillJpeg=" + still
                    + " displayAngle=" + getJpegOrientation()
                    + " requestUsesPreviewMirror=false");
            return still;
        } catch (Exception e) {
            CameraAutoOptimizer.error("Camera2Session camera #" + cameraId + " still orientation failed", e);
        }
        return 0;
    }

    public int getWorldAngle() {
        int displayOrientation = getDisplayOrientation();
        int jpegOrientation = getJpegOrientation();
        int diffOrientation = jpegOrientation - displayOrientation;
        if (diffOrientation < 0) {
            diffOrientation += 360;
        }
        return diffOrientation;
    }

    public int getCurrentOrientation() {
        return getJpegOrientation();
    }

    public void setZoom(float value) {
        if (!isInitiated()) return;
        if (captureRequestBuilder == null || cameraDevice == null || sensorSize == null) return;

        autoOptimizer.invalidateRecording("zoom changed during recording");
        currentZoom = Utilities.clamp(value, maxZoom, 1f);
        updateCaptureRequest();
    }

    private volatile boolean flashing;
    public void setFlash(boolean flash) {
        if (flashing != flash) {
            autoOptimizer.invalidateRecording("flash changed during recording");
            flashing = flash;
            updateCaptureRequest();
        }
    }
    public boolean getFlash() {
        return flashing;
    }

    public float getZoom() {
        return currentZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public float getMinZoom() {
        // TODO: support wide zoom camera switching
        return 1f;
    }

    public int getPreviewWidth() {
        return previewSize.getWidth();
    }

    public int getPreviewHeight() {
        return previewSize.getHeight();
    }

    public void destroy(boolean async) {
        destroy(async, null);
    }

    public void destroy(boolean async, Runnable afterCallback) {
        CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " destroy async=" + async
                + " alive=" + (System.currentTimeMillis() - createdTime) + "ms");
        isClosed = true;
        autoOptimizer.stop();
        CameraHardwareBenchmark.cameraClosed(this);
        if (async) {
            handler.post(() -> {
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
                thread.quitSafely();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        thread.join();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (afterCallback != null) {
                        afterCallback.run();
                    }
                });
            });
        } else {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            thread.quitSafely();
            try {
                thread.join();
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (afterCallback != null) {
                AndroidUtilities.runOnUIThread(afterCallback);
            }
        }
    }

    private volatile boolean recordingVideo;
    public void setRecordingVideo(boolean recording) {
        if (recordingVideo != recording) {
            recordingVideo = recording;
            updateCaptureRequest();
        }
    }

    private volatile boolean scanningBarcode;
    public void setScanningBarcode(boolean scanning) {
        if (scanningBarcode != scanning) {
            scanningBarcode = scanning;
            updateCaptureRequest();
        }
    }

    private volatile boolean nightMode;
    public void setNightMode(boolean enable) {
        if (nightMode != enable) {
            nightMode = enable;
            updateCaptureRequest();
        }
    }

    /**
     * @return false only when the request could not be applied and the caller should
     *         treat the session as failed. Work that is merely deferred to the camera
     *         thread returns true: onConfigured() turns false into isError = true, so
     *         reporting a deferral as a failure would kill a healthy camera.
     */
    private boolean updateCaptureRequest() {
        if (isClosed) return true;
        if (Looper.myLooper() != handler.getLooper()) {
            handler.post(this::updateCaptureRequest); // Deferred, not failed.
            return true;
        }
        if (cameraDevice == null || surface == null || captureSession == null) {
            CameraAutoOptimizer.log("Camera2Session camera #" + cameraId
                    + " capture request skipped: device/surface/session unavailable");
            return false;
        }
        try {
            int template;
            if (recordingVideo) {
                template = CameraDevice.TEMPLATE_RECORD;
            } else if (scanningBarcode) {
                template = CameraDevice.TEMPLATE_STILL_CAPTURE;
            } else {
                template = CameraDevice.TEMPLATE_PREVIEW;
            }
            captureRequestBuilder = cameraDevice.createCaptureRequest(template);
            final String signature = "template=" + template + " recording=" + recordingVideo
                    + " barcode=" + scanningBarcode + " night=" + nightMode + " flash=" + flashing;
            if (!signature.equals(lastRequestSignature)) {
                lastRequestSignature = signature;
                CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " capture request " + signature
                        + " zoom=" + currentZoom + " preview=" + previewSize
                        + " (1=preview 3=record 2=still)");
            }

            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            } else if (nightMode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, isFront ? CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT : CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
            }

            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, flashing ? (recordingVideo ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_SINGLE) : CaptureRequest.FLASH_MODE_OFF);

            if (recordingVideo) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
            }

            Rect crop = cropForZoom(sensorSize, currentZoom);
            if (crop != null) captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, crop);

            captureRequestBuilder.addTarget(surface);
            final boolean neutralScene = !scanningBarcode && !nightMode && !flashing && Math.abs(currentZoom - 1f) < 0.01f;
            autoOptimizer.allowPhotoBenchmarks(neutralScene && !recordingVideo);
            if (!neutralScene) autoOptimizer.invalidateRecording("special scene/flash/zoom excluded from calibration");
            autoOptimizer.repeating(captureSession, captureRequestBuilder, cameraCharacteristics,
                    previewSize, cameraId, recordingVideo, scanningBarcode || nightMode, handler);
            return true;
        } catch (Exception e) {
            FileLog.e("Camera2Sessions setRepeatingRequest error in updateCaptureRequest", e);
            return false;
        }
    }

    public boolean takePicture(final File file, Utilities.Callback<Integer> whenDone) {
        if (isClosed || cameraDevice == null || captureSession == null) {
            CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " takePicture skipped: closed=" + isClosed
                    + " device=" + (cameraDevice != null) + " session=" + (captureSession != null));
            return false;
        }
        autoOptimizer.freezePhoto();
        final CameraHardwareBenchmark.Shot benchmarkShot = new CameraHardwareBenchmark.Shot(cameraId, autoOptimizer.photoCandidate());
        try {
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            final int orientation = getStillJpegOrientation();
            final boolean mirrorPhoto = isFront && flipFront;
            final float photoZoom = currentZoom;
            final Rect photoCrop = cropForZoom(sensorSize, photoZoom);
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            if (photoCrop != null) captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, photoCrop);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    final byte[] bytes;
                    try {
                        image = reader.acquireLatestImage();
                        if (image == null) {
                            CameraAutoOptimizer.log("benchmark photo: null JPEG image; not a successful capture");
                            finishPhoto(benchmarkShot, new byte[0], orientation, mirrorPhoto, false, false, whenDone);
                            return;
                        }
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                    } catch (RuntimeException | OutOfMemoryError e) {
                        CameraAutoOptimizer.error("photo JPEG buffer copy failed", e);
                        finishPhoto(benchmarkShot, new byte[0], orientation, mirrorPhoto, false, false, whenDone);
                        return;
                    } finally {
                        if (image != null) image.close(); // Release the only ImageReader slot BEFORE CPU/JPEG work.
                    }
                    Utilities.globalQueue.postRunnable(() -> savePhoto(file, bytes, orientation,
                            mirrorPhoto, benchmarkShot, whenDone));
                }
            }, handler);
            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            }
            captureRequestBuilder.addTarget(imageReader.getSurface());
            CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " still capture orientation="
                    + orientation + " front=" + isFront + " recording=" + recordingVideo
                    + " barcode=" + scanningBarcode + " mirrorForPreview=" + mirrorPhoto
                    + " zoom=" + photoZoom + " crop=" + photoCrop + " jpeg=" + previewSize);
            autoOptimizer.capture(captureSession, captureRequestBuilder, cameraCharacteristics,
                    previewSize, cameraId, recordingVideo, scanningBarcode || nightMode, handler);
            return true;
        } catch (Exception e) {
            autoOptimizer.resumePhoto();
            benchmarkShot.finish(0, -1, false);
            FileLog.e("Camera2Sessions takePicture error", e);
            return false;
        }
    }


    private void savePhoto(File file, byte[] sourceBytes, int orientation, boolean mirrorPhoto,
                           CameraHardwareBenchmark.Shot benchmarkShot, Utilities.Callback<Integer> whenDone) {
        byte[] bytes = sourceBytes;
        try {
            bytes = CameraPhotoProcessor.forPreview(sourceBytes, mirrorPhoto);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // Preserve the actual shot on allocation/codec failure; never silently claim a mirror.
            CameraAutoOptimizer.error("photo mirror failed; preserving original JPEG", e);
        }
        boolean saved = false;
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.flush();
        } catch (IOException e) {
            CameraAutoOptimizer.error("photo JPEG write failed", e);
            try {
                if (file.isFile()) CameraAutoOptimizer.log("failed photo partial JPEG deleted=" + file.delete());
            } catch (SecurityException cleanupError) {
                CameraAutoOptimizer.error("failed photo partial JPEG cleanup denied", cleanupError);
            }
            finishPhoto(benchmarkShot, bytes, orientation, mirrorPhoto, bytes != sourceBytes, false, whenDone);
            return;
        }
        saved = true; // Includes successful close, not just write acceptance.
        finishPhoto(benchmarkShot, bytes, orientation, mirrorPhoto, bytes != sourceBytes, saved, whenDone);
    }

    private void finishPhoto(CameraHardwareBenchmark.Shot benchmarkShot, byte[] bytes, int orientation,
                             boolean mirrorPhoto, boolean normalized, boolean saved, Utilities.Callback<Integer> whenDone) {
        // JPEG_ORIENTATION is a request, not the residual rotation of the delivered file.
        final Pair<Integer, Integer> jpegTransform = saved
                ? AndroidUtilities.getImageOrientation(new ByteArrayInputStream(bytes)) : new Pair<>(0, 0);
        benchmarkShot.finish(bytes.length, saved ? jpegTransform.first : -1, saved);
        handler.post(() -> {
            autoOptimizer.resumePhoto();
            if (!isClosed && !recordingVideo) updateCaptureRequest();
        });
        CameraAutoOptimizer.log("Camera2Session camera #" + cameraId
                + " jpeg result: requested=" + orientation + " exifRotation=" + jpegTransform.first
                + " exifInvert=" + jpegTransform.second + " bytes=" + bytes.length + " front=" + isFront
                + " mirrorForPreview=" + mirrorPhoto + " normalized=" + normalized + " saved=" + saved);
        AndroidUtilities.runOnUIThread(() -> {
            if (whenDone != null) {
                if (saved) {
                    whenDone.run(jpegTransform.first);
                } else {
                    whenDone.run(-1); // Camera2 failure; callers must not open a partial/missing JPEG.
                }
            }
        });
    }

    static Rect cropForZoom(Rect sensor, float zoom) {
        if (sensor == null || sensor.width() < 2 || sensor.height() < 2
                || Float.isNaN(zoom) || Float.isInfinite(zoom) || zoom < 1.01f) return null;
        int dx = Math.max(1, (int) (sensor.width() / (2f * zoom)));
        int dy = Math.max(1, (int) (sensor.height() / (2f * zoom)));
        int cx = sensor.left + sensor.width() / 2, cy = sensor.top + sensor.height() / 2;
        return new Rect(cx - dx, cy - dy, cx + dx, cy + dy);
    }

    static Size[] commonOutputSizes(Size[] preview, Size[] jpeg) {
        List<Size> common = new ArrayList<>();
        if (preview != null && jpeg != null) {
            for (Size p : preview) {
                if (p == null || p.getWidth() <= 0 || p.getHeight() <= 0) continue;
                for (Size j : jpeg) {
                    if (j != null && p.getWidth() == j.getWidth() && p.getHeight() == j.getHeight()) {
                        common.add(p);
                        break;
                    }
                }
            }
        }
        return common.toArray(new Size[0]);
    }

    public static Size chooseOptimalSize(Size[] choices, int width, int height, boolean notBigger) {
        if (choices == null || choices.length == 0 || width <= 0 || height <= 0) return null;
        // Compare long/short axes, not portrait view axes against landscape camera buffers.
        final int w = Math.max(width, height), h = Math.min(width, height);
        List<Size> exact = new ArrayList<>(), sufficient = new ArrayList<>(), allowed = new ArrayList<>();
        for (Size option : choices) {
            if (option == null || option.getWidth() <= 0 || option.getHeight() <= 0) continue;
            int ow = Math.max(option.getWidth(), option.getHeight());
            int oh = Math.min(option.getWidth(), option.getHeight());
            if (notBigger && (ow > w || oh > h)) continue;
            allowed.add(option);
            if (ow >= w && oh >= h) {
                if ((long) ow * h == (long) oh * w) exact.add(option);
                else if ((long) ow * oh <= (long) w * h * 4) sufficient.add(option);
            }
        }
        if (!exact.isEmpty()) return Collections.min(exact, new CompareSizesByArea());
        List<Size> candidates = sufficient.isEmpty() ? allowed : sufficient;
        Size best = null;
        double bestError = Double.POSITIVE_INFINITY;
        long bestDistance = Long.MAX_VALUE;
        for (Size candidate : candidates) {
            double aspect = (double) Math.max(candidate.getWidth(), candidate.getHeight())
                    / Math.min(candidate.getWidth(), candidate.getHeight());
            double error = Math.abs(aspect - (double) w / h);
            long distance = Math.abs((long) candidate.getWidth() * candidate.getHeight() - (long) w * h);
            if (error < bestError - 1e-9 || (Math.abs(error - bestError) <= 1e-9 && distance < bestDistance)) {
                best = candidate;
                bestError = error;
                bestDistance = distance;
            }
        }
        return best; // Never bypass notBigger or select an arbitrary maximum-area fallback.
    }
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

}