package com.sky.modelviewer.render;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

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
