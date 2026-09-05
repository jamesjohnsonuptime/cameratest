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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Session {

    private boolean isError;
    private boolean isSuccess;
    private volatile boolean isClosed;
    private final Camera2AutoOptimizer autoOptimizer = new Camera2AutoOptimizer();

    private final CameraManager cameraManager;
    private final boolean isFront;
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
                        Size size = chooseOptimalSize(confMap.getOutputSizes(SurfaceTexture.class), viewWidth, viewHeight, false);
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
                    + "; no camera advertised a usable SurfaceTexture size, preview would stay black");
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
                + " preview=" + bestSize + " view=" + viewWidth + "x" + viewHeight
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
                Camera2Session.this.cameraDevice = camera;
                FileLog.d("Camera2Session camera #" + cameraId + " disconnected");
                CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " device disconnected "
                        + (System.currentTimeMillis() - createdTime) + "ms after create");
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
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
            cameraManager.openCamera(cameraId, cameraStateCallback, handler);
        } catch (Exception e) {
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

    private final Rect cropRegion = new Rect();
    public void setZoom(float value) {
        if (!isInitiated()) return;
        if (captureRequestBuilder == null || cameraDevice == null || sensorSize == null) return;

        currentZoom = Utilities.clamp(value, maxZoom, 1f);
        updateCaptureRequest();
    }

    private volatile boolean flashing;
    public void setFlash(boolean flash) {
        if (flashing != flash) {
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

            if (sensorSize != null && Math.abs(currentZoom - 1f) >= 0.01f) {
                final int centerX = sensorSize.width() / 2;
                final int centerY = sensorSize.height() / 2;
                final int deltaX = (int) ((0.5f * sensorSize.width()) / currentZoom);
                final int deltaY = (int) ((0.5f * sensorSize.height()) / currentZoom);
                cropRegion.set(
                        centerX - deltaX,
                        centerY - deltaY,
                        centerX + deltaX,
                        centerY + deltaY
                );
                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
            }

            captureRequestBuilder.addTarget(surface);
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
        try {
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            final int orientation = getJpegOrientation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                        if (null != output) {
                            try {
                                output.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    AndroidUtilities.runOnUIThread(() -> {
                        if (whenDone != null) {
                            whenDone.run(orientation);
                        }
                    });
                }
            }, null);
            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            }
            captureRequestBuilder.addTarget(imageReader.getSurface());
            CameraAutoOptimizer.log("Camera2Session camera #" + cameraId + " still capture orientation="
                    + orientation + " recording=" + recordingVideo + " barcode=" + scanningBarcode);
            autoOptimizer.capture(captureSession, captureRequestBuilder, cameraCharacteristics,
                    previewSize, cameraId, recordingVideo, scanningBarcode || nightMode, handler);
            return true;
        } catch (Exception e) {
            FileLog.e("Camera2Sessions takePicture error", e);
            return false;
        }
    }


    public static Size chooseOptimalSize(Size[] choices, int width, int height, boolean notBigger) {
        List<Size> bigEnoughWithAspectRatio = new ArrayList<>(choices.length);
        List<Size> bigEnough = new ArrayList<>(choices.length);
        int w = width;
        int h = height;
        for (int a = 0; a < choices.length; a++) {
            Size option = choices[a];
            if (notBigger && (option.getHeight() > height || option.getWidth() > width)) {
                continue;
            }
            if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnoughWithAspectRatio.add(option);
            } else if (option.getHeight() * option.getWidth() <= width * height * 4 && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnoughWithAspectRatio.size() > 0) {
            return Collections.min(bigEnoughWithAspectRatio, new CompareSizesByArea());
        } else if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
        }
    }
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

}