package com.droid2developers.liveslider.live_wallpaper;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.droid2developers.liveslider.R;
import com.droid2developers.liveslider.models.BiasChangeEvent;
import com.droid2developers.liveslider.models.FaceRotationEvent;
import com.droid2developers.liveslider.utils.Constant;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.greenrobot.eventbus.EventBus;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import static com.droid2developers.liveslider.utils.Constant.DEFAULT_LOCAL_PATH;
import static com.droid2developers.liveslider.utils.Constant.TYPE_SINGLE;
import androidx.lifecycle.MutableLiveData;

public class LiveWallpaperRenderer implements GLSurfaceView.Renderer {
    private final static int REFRESH_RATE = 60;
    private final static float MAX_BIAS_RANGE = 0.006f;
    private final static String TAG = LiveWallpaperRenderer.class.getSimpleName();

    // Parallax layers
    private ParallaxLayer backgroundLayer;  // Di chuyển nhanh (factor = 1.4)
    private ParallaxLayer foregroundLayer;  // Di chuyển chậm (factor = 1.0)
    private static final float BACKGROUND_PARALLAX_FACTOR = 1.6f;
    private static final float FOREGROUND_PARALLAX_FACTOR = 1.0f;
    private static final float BACKGROUND_ALPHA = 1.0f;
    private static final float FOREGROUND_ALPHA = 0.5f;

    private MutableLiveData<Float> mutableAlfa = null;
    private final float[] mMVPMatrix = new float[16];
    private final float[] mMVPMatrixBackground = new float[16];
    private final float[] mMVPMatrixForeground = new float[16];
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final Context mContext;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private float scrollStep = 1f;
    private final Queue<Float> scrollOffsetXQueue = new CircularFifoQueue<>(10);
    private float scrollOffsetX = 0.5f;
    private float scrollOffsetXBackup = 0.5f;
    private float currentOrientationOffsetX, currentOrientationOffsetY;
    private float orientationOffsetX, orientationOffsetY;
    private final Callbacks mCallbacks;
    private float screenAspectRatio;
    private int screenH;
    private float wallpaperAspectRatio;
    private final Runnable transition = new Runnable() {
        @Override
        public void run() {
            transitionCal();
        }
    };
    private ScheduledFuture<?> transitionHandle;
    private float preA;
    private float preB;

    // Important mutable parameters
    private String localWallpaperPath = null;
    private int delay = 1;
    private float biasRange;
    private float scrollRange;
    private boolean scrollMode = true;
    private boolean needsRefreshWallpaper;
    private boolean isDefaultWallpaper;
    private int wallpaperType;

    private final Handler animationHandler = new Handler(Looper.getMainLooper());

    LiveWallpaperRenderer(Context context, Callbacks callbacks) {
        mContext = context;
        mCallbacks = callbacks;
        mutableAlfa = new MutableLiveData<>(1.0f);
    }

    void release() {
        if (backgroundLayer != null) {
            backgroundLayer.destroy();
            backgroundLayer = null;
        }
        if (foregroundLayer != null) {
            foregroundLayer.destroy();
            foregroundLayer = null;
        }
        stopTransition();
        scheduler.shutdown();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        Wallpaper.initGl();
    }

    void startTransition() {
        stopTransition();
        transitionHandle = scheduler.scheduleWithFixedDelay(transition,
                0, 1000 / REFRESH_RATE, TimeUnit.MILLISECONDS);
    }

    void stopTransition() {
        if (transitionHandle != null) transitionHandle.cancel(true);
    }

    private boolean hasLoggedNullWallpaper = false;
    @Override
    public void onDrawFrame(GL10 gl) {
        if (needsRefreshWallpaper) {
            loadTextures();
            needsRefreshWallpaper = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (Float.isNaN(preA) || Float.isNaN(preB) || Float.isInfinite(preA) || Float.isInfinite(preB)) {
            if (!hasLoggedNullWallpaper) {
                Log.w(TAG, "onDrawFrame: Invalid matrix parameters, skipping draw");
                hasLoggedNullWallpaper = true;
            }
            return;
        }

        float globalAlpha = (mutableAlfa.getValue() != null) ? mutableAlfa.getValue() : 1.0f;

        // Draw BACKGROUND layer first (di chuyển nhanh hơn)
        if (backgroundLayer != null && backgroundLayer.isEnabled()) {
            float bgScrollOffset = calculateParallaxScrollOffset(scrollOffsetX, BACKGROUND_PARALLAX_FACTOR);
            float bgOrientationX = currentOrientationOffsetX * BACKGROUND_PARALLAX_FACTOR;
            float bgOrientationY = currentOrientationOffsetY * BACKGROUND_PARALLAX_FACTOR;

            float bgX = preA * (-2 * bgScrollOffset + 1) + bgOrientationX;
            float bgY = bgOrientationY;

            Matrix.setLookAtM(mViewMatrix, 0, bgX, bgY, preB, bgX, bgY, 0f, 0f, 1.0f, 0.0f);
            Matrix.multiplyMM(mMVPMatrixBackground, 0, mProjectionMatrix, 0, mViewMatrix, 0);
            backgroundLayer.draw(mMVPMatrixBackground, globalAlpha);
        }

        // Draw FOREGROUND layer on top (di chuyển chậm hơn)
        if (foregroundLayer != null && foregroundLayer.isEnabled()) {
            float fgScrollOffset = calculateParallaxScrollOffset(scrollOffsetX, FOREGROUND_PARALLAX_FACTOR);
            float fgOrientationX = currentOrientationOffsetX * FOREGROUND_PARALLAX_FACTOR;
            float fgOrientationY = currentOrientationOffsetY * FOREGROUND_PARALLAX_FACTOR;

            float fgX = preA * (-2 * fgScrollOffset + 1) + fgOrientationX;
            float fgY = fgOrientationY;

            Matrix.setLookAtM(mViewMatrix, 0, fgX, fgY, preB, fgX, fgY, 0f, 0f, 1.0f, 0.0f);
            Matrix.multiplyMM(mMVPMatrixForeground, 0, mProjectionMatrix, 0, mViewMatrix, 0);
            foregroundLayer.draw(mMVPMatrixForeground, globalAlpha);
        }

        hasLoggedNullWallpaper = false;
    }

    /**
     * Calculate scroll offset adjusted for parallax factor
     * Factor > 1.0 = moves faster, Factor < 1.0 = moves slower
     */
    private float calculateParallaxScrollOffset(float baseOffset, float parallaxFactor) {
        // Center the offset around 0.5, apply factor, then restore
        float centered = baseOffset - 0.5f;
        float adjusted = centered * parallaxFactor;
        return adjusted + 0.5f;
    }

    private void preCalculate() {
        if (screenAspectRatio == 0 || Float.isNaN(screenAspectRatio) || Float.isInfinite(screenAspectRatio)) {
            preA = Float.NaN;
            preB = Float.NaN;
            return;
        }
        if (wallpaperAspectRatio == 0 || Float.isNaN(wallpaperAspectRatio) || Float.isInfinite(wallpaperAspectRatio)) {
            preA = Float.NaN;
            preB = Float.NaN;
            return;
        }
        if (scrollStep > 0) {
            if (wallpaperAspectRatio > (1 + 1 / (3 * scrollStep)) * screenAspectRatio) {
                scrollRange = 1 + 1 / (3 * scrollStep);
            } else if (wallpaperAspectRatio >= screenAspectRatio) {
                scrollRange = wallpaperAspectRatio / screenAspectRatio;
            } else {
                scrollRange = 1;
            }
        } else {
            scrollRange = 1;
        }

        // Use foreground factor as baseline so foreground keeps original movement
        float baseFactor = FOREGROUND_PARALLAX_FACTOR;
        preA = screenAspectRatio * (scrollRange - 1) * baseFactor;

        if (screenAspectRatio < 1)
            preB = -1.0f + (biasRange / screenAspectRatio) * baseFactor;
        else
            preB = -1.0f + (biasRange * screenAspectRatio) * baseFactor;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (height == 0) {
            height = 1;
        }

        screenAspectRatio = (float) width / (float) height;
        screenH = height;

        GLES20.glViewport(0, 0, width, height);
        Matrix.frustumM(mProjectionMatrix, 0, -0.1f * screenAspectRatio,
                0.1f * screenAspectRatio, -0.1f, 0.1f, 0.1f, 2);

        needsRefreshWallpaper = true;
        mCallbacks.requestRender();
    }

    void setOffset(float offsetX, float offsetY) {
        if (scrollMode) {
            scrollOffsetXBackup = offsetX;
            scrollOffsetXQueue.offer(offsetX);
        } else {
            scrollOffsetXBackup = offsetX;
        }
    }

    void setOffsetStep(float offsetStepX, float offsetStepY) {
        if (scrollStep != offsetStepX) {
            scrollStep = offsetStepX;
            preCalculate();
        }
    }

    void setOrientationAngle(float roll, float pitch) {
        orientationOffsetX = (float) (biasRange * Math.sin(roll));
        orientationOffsetY = (float) (biasRange * Math.sin(pitch));
    }

    void setNewFaceRotation(int face) {
        EventBus.getDefault().post(new FaceRotationEvent(face));
    }

    void setBiasRange(int multiples) {
        if (multiples == 0) {
            stopTransition();
            biasRange = 0f;
            currentOrientationOffsetX = 0f;
            currentOrientationOffsetY = 0f;
            orientationOffsetX = 0f;
            orientationOffsetY = 0f;
        } else {
            biasRange = multiples * MAX_BIAS_RANGE + 0.03f;
            startTransition();
        }
        preCalculate();
        mCallbacks.requestRender();
    }

    void setDelay(int delay) {
        this.delay = delay;
    }

    void setScrollMode(boolean scrollMode) {
        this.scrollMode = scrollMode;
        if (scrollMode)
            scrollOffsetXQueue.offer(scrollOffsetXBackup);
        else {
            scrollOffsetXQueue.clear();
            scrollOffsetXQueue.offer(0.5f);
        }
    }

    void setLocalWallpaperPath(String name) {
        localWallpaperPath = name;
    }

    void setIsDefaultWallpaper(boolean isDefault) {
        isDefaultWallpaper = isDefault;
    }

    void setWallpaperType(int wallpaperType) {
        this.wallpaperType = wallpaperType;
    }

    void refreshWallpaper(String wallpaperPath, boolean isDefault) {
        Runnable fadeOutThenInRunnable = new Runnable() {
            private float alpha = 1.0f;
            private boolean fadeOutComplete = false;
            private boolean wallpaperChanged = false;

            @Override
            public void run() {
                if (!fadeOutComplete) {
                    alpha -= 0.05f;

                    if (alpha <= 0.0f) {
                        alpha = 0.0f;
                        fadeOutComplete = true;

                        if (!wallpaperChanged) {
                            setLocalWallpaperPath(wallpaperPath);
                            setIsDefaultWallpaper(isDefault);
                            needsRefreshWallpaper = true;
                            wallpaperChanged = true;

                            animationHandler.postDelayed(this, 50);
                            mutableAlfa.setValue(alpha);
                            mCallbacks.requestRender();
                            return;
                        }
                    }
                } else {
                    alpha += 0.04f;

                    if (alpha >= 1.0f) {
                        alpha = 1.0f;
                        mutableAlfa.setValue(alpha);
                        mCallbacks.requestRender();
                        return;
                    }
                }

                mutableAlfa.setValue(alpha);
                mCallbacks.requestRender();
                animationHandler.postDelayed(this, 16);
            }
        };

        animationHandler.post(fadeOutThenInRunnable);
    }

    private void transitionCal() {
        boolean needRefresh = false;

        if (Math.abs(currentOrientationOffsetX - orientationOffsetX) > .0001
                || Math.abs(currentOrientationOffsetY - orientationOffsetY) > .0001) {

            if (delay == 1) {
                currentOrientationOffsetX += (orientationOffsetX - currentOrientationOffsetX) * 0.8f;
                currentOrientationOffsetY += (orientationOffsetY - currentOrientationOffsetY) * 0.8f;
            } else {
                float transitionStep = REFRESH_RATE / LiveWallpaperService.SENSOR_RATE;
                float tinyOffsetX = (orientationOffsetX - currentOrientationOffsetX)
                        / (transitionStep * delay);
                float tinyOffsetY = (orientationOffsetY - currentOrientationOffsetY)
                        / (transitionStep * delay);
                currentOrientationOffsetX += tinyOffsetX;
                currentOrientationOffsetY += tinyOffsetY;
            }
            EventBus.getDefault().post(new BiasChangeEvent(currentOrientationOffsetX / biasRange,
                    currentOrientationOffsetY / biasRange));
            needRefresh = true;
        }
        if (!scrollOffsetXQueue.isEmpty()) {
            scrollOffsetX = scrollOffsetXQueue.poll();
            needRefresh = true;
        }
        if (needRefresh) mCallbacks.requestRender();
    }

    /**
     * Load both background and foreground textures
     */
    private void loadTextures() {
        System.gc();

        // Load BACKGROUND layer (default from drawable)
        Bitmap backgroundBitmap = loadBackgroundBitmap();
        if (backgroundBitmap != null) {
            if (backgroundLayer != null) {
                backgroundLayer.destroy();
            }
            Bitmap croppedBg = cropBitmap(backgroundBitmap);
            backgroundLayer = new ParallaxLayer(croppedBg, BACKGROUND_PARALLAX_FACTOR,
                    BACKGROUND_ALPHA, "Background");
        }

        // Load FOREGROUND layer (user selected image)
        FileInputStream is = null;
        if (wallpaperType == TYPE_SINGLE) {
            if (!isDefaultWallpaper) {
                try {
                    is = new FileInputStream(localWallpaperPath);
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "FileNotFoundException for foreground: " + localWallpaperPath, e);
                    refreshWallpaper(DEFAULT_LOCAL_PATH, true);
                }
            } else {
                try {
                    AssetFileDescriptor fileDescriptor = mContext.getAssets().openFd(Constant.DEFAULT_WALLPAPER_NAME);
                    is = fileDescriptor.createInputStream();
                } catch (IOException e) {
                    Log.e(TAG, "IOException loading default foreground", e);
                }
            }
        } else {
            try {
                is = new FileInputStream(localWallpaperPath);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "FileNotFoundException for foreground: " + localWallpaperPath, e);
                refreshWallpaper(DEFAULT_LOCAL_PATH, true);
            }
        }

        if (is != null) {
            if (foregroundLayer != null) {
                foregroundLayer.destroy();
            }
            Bitmap foregroundBmp = cropBitmap(is);
            foregroundLayer = new ParallaxLayer(foregroundBmp, FOREGROUND_PARALLAX_FACTOR,
                    FOREGROUND_ALPHA, "Foreground");
            try {
                is.close();
            } catch (IOException e) {
                Log.e(TAG, "IOException closing foreground stream", e);
            }
        }

        preCalculate();
        System.gc();
    }

    /**
     * Load default background image from drawable
     */
    private Bitmap loadBackgroundBitmap() {
        try {
            // Tên file: parallax_background.png trong drawable
            Bitmap bitmap = BitmapFactory.decodeResource(mContext.getResources(), R.drawable.parallax_background);
            Log.d(TAG, "Loaded parallax_background successfully");
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error loading parallax_background from drawable", e);
            return null;
        }
    }

    private Bitmap cropBitmap(InputStream is) {
        Bitmap src = BitmapFactory.decodeStream(is);
        if (src == null) {
            Log.e(TAG, "cropBitmap: BitmapFactory returned null");
            return null;
        }
        return cropBitmap(src);
    }

    private Bitmap cropBitmap(Bitmap src) {
        if (src == null) return null;

        final float width = src.getWidth();
        final float height = src.getHeight();
        if (height == 0) {
            Log.e(TAG, "cropBitmap: height is zero");
            src.recycle();
            return null;
        }
        wallpaperAspectRatio = width / height;

        if (wallpaperAspectRatio < screenAspectRatio) {
            scrollRange = 1;
            Bitmap tmp = Bitmap.createBitmap(src, 0,
                    (int) (height - width / screenAspectRatio) / 2,
                    (int) width, (int) (width / screenAspectRatio));
            src.recycle();
            if (tmp.getHeight() > 1.1 * screenH) {
                Bitmap result = Bitmap.createScaledBitmap(tmp,
                        (int) (1.1 * screenH * screenAspectRatio),
                        (int) (1.1 * screenH), true);
                tmp.recycle();
                return result;
            } else
                return tmp;
        } else {
            if (src.getHeight() > 1.1 * screenH) {
                Bitmap result = Bitmap.createScaledBitmap(src,
                        (int) (1.1 * screenH * wallpaperAspectRatio),
                        (int) (1.1 * screenH), true);
                src.recycle();
                return result;
            } else
                return src;
        }
    }

    interface Callbacks {
        void requestRender();
    }
}