package com.sky.modelviewer.render;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

public class MeshSurfaceView extends GLSurfaceView {

    private final MeshRenderer meshRenderer;
    private final ScaleGestureDetector scaleDetector;

    private float lastX = 0f;
    private float lastY = 0f;
    private int pointerCount = 1;

    public MeshSurfaceView(Context context) {
        super(context);
        scaleDetector = null;
        meshRenderer = new MeshRenderer();
        init();
    }

    public MeshSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = 1f / detector.getScaleFactor();
                meshRenderer.zoom(factor);
                return true;
            }
        });
        meshRenderer = new MeshRenderer();
        init();
    }

    private void init() {
        setEGLContextClientVersion(3);
        // Enable MSAA anti-aliasing (4x multisampling) to prevent jagged edges
        // Ported from HTML project's WebGLRenderer({antialias: true})
        // Uses custom config chooser that requests EGL_SAMPLE_BUFFERS=1 + EGL_SAMPLES=4
        setEGLConfigChooser(new EGLConfigChooser() {
            @Override
            public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
                // Try MSAA 4x first
                int[] configAttrs4x = {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_SAMPLE_BUFFERS, 1,
                    EGL10.EGL_SAMPLES, 4,
                    EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                    EGL10.EGL_NONE
                };
                EGLConfig[] configs = new EGLConfig[1];
                int[] numConfigs = new int[1];
                if (egl.eglChooseConfig(display, configAttrs4x, configs, 1, numConfigs) && numConfigs[0] > 0) {
                    return configs[0];
                }
                // Fallback: MSAA 2x
                int[] configAttrs2x = {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_SAMPLE_BUFFERS, 1,
                    EGL10.EGL_SAMPLES, 2,
                    EGL10.EGL_RENDERABLE_TYPE, 4,
                    EGL10.EGL_NONE
                };
                if (egl.eglChooseConfig(display, configAttrs2x, configs, 1, numConfigs) && numConfigs[0] > 0) {
                    return configs[0];
                }
                // Final fallback: no MSAA
                int[] configAttrsNoMSAA = {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_RENDERABLE_TYPE, 4,
                    EGL10.EGL_NONE
                };
                egl.eglChooseConfig(display, configAttrsNoMSAA, configs, 1, numConfigs);
                return numConfigs[0] > 0 ? configs[0] : null;
            }
        });
        setRenderer(meshRenderer);
        // Use continuous render mode so animation playback works
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void setContinuousRender(boolean continuous) {
        setRenderMode(continuous ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
    }

    public MeshRenderer getMeshRenderer() {
        return meshRenderer;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (scaleDetector != null) {
            scaleDetector.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                pointerCount = 1;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerCount = 2;
                lastX = event.getX();
                lastY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (pointerCount >= 2 && (scaleDetector == null || !scaleDetector.isInProgress())) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    meshRenderer.pan(dx, dy);
                } else if (pointerCount < 2 && (scaleDetector == null || !scaleDetector.isInProgress())) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    meshRenderer.orbit(dx, dy);
                }
                lastX = event.getX();
                lastY = event.getY();
                requestRender();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                pointerCount = Math.max(pointerCount - 1, 1);
                lastX = event.getX();
                lastY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
                pointerCount = 1;
                break;
        }

        return true;
    }
}
