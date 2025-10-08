package com.droid2developers.liveslider.live_wallpaper;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * Represents a single parallax layer with its own wallpaper and movement factor
 */
public class ParallaxLayer {
    private static final String TAG = "ParallaxLayer";

    private Wallpaper wallpaper;
    private float parallaxFactor;  // Movement multiplier (1.0 = normal, 1.4 = 40% faster)
    private float alpha;            // Transparency (1.0 = opaque, 0.0 = transparent)
    private boolean enabled;
    private String name;            // For debugging

    /**
     * Create a parallax layer
     * @param bitmap The image for this layer
     * @param parallaxFactor Movement multiplier
     * @param alpha Transparency (1.0 = opaque, 0.5 = semi-transparent)
     * @param name Layer name for debugging
     */
    public ParallaxLayer(Bitmap bitmap, float parallaxFactor, float alpha, String name) {
        this.name = name;
        if (bitmap != null) {
            this.wallpaper = new Wallpaper(bitmap);
            this.enabled = true;
            Log.d(TAG, "Created layer '" + name + "' with factor=" + parallaxFactor + ", alpha=" + alpha);
        } else {
            this.enabled = false;
            Log.w(TAG, "Layer '" + name + "' disabled - bitmap is null");
        }
        this.parallaxFactor = parallaxFactor;
        this.alpha = alpha;
    }

    /**
     * Draw this layer
     * @param mvpMatrix MVP matrix for this layer
     * @param globalAlpha Global alpha for fade effects
     */
    public void draw(float[] mvpMatrix, float globalAlpha) {
        if (wallpaper != null && enabled) {
            wallpaper.draw(mvpMatrix, alpha * globalAlpha);
        }
    }

    public void destroy() {
        if (wallpaper != null) {
            wallpaper.destroy();
            wallpaper = null;
        }
        enabled = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public float getParallaxFactor() {
        return parallaxFactor;
    }

    public void setParallaxFactor(float factor) {
        this.parallaxFactor = factor;
    }

    public float getAlpha() {
        return alpha;
    }

    public String getName() {
        return name;
    }
}