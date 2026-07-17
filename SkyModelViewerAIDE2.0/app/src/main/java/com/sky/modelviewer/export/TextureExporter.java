package com.sky.modelviewer.export;

import android.graphics.Bitmap;

import java.io.OutputStream;
import java.io.ByteArrayOutputStream;

/**
 * Export textures as PNG or JPEG.
 *
 * Works with Bitmap objects decoded from KTX or standard image formats.
 */
public class TextureExporter {

    /**
     * Export a bitmap as PNG.
     */
    public static void exportPng(OutputStream os, Bitmap bitmap) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
    }

    /**
     * Export a bitmap as JPEG with specified quality.
     */
    public static void exportJpeg(OutputStream os, Bitmap bitmap, int quality) {
        // JPEG doesn't support alpha — convert to RGB if needed
        Bitmap rgb = bitmap;
        if (bitmap.hasAlpha()) {
            rgb = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(rgb);
            canvas.drawColor(android.graphics.Color.BLACK);
            canvas.drawBitmap(bitmap, 0, 0, null);
        }
        rgb.compress(Bitmap.CompressFormat.JPEG, quality, os);
    }

    /**
     * Export raw KTX decoded pixels as PNG.
     *
     * @param pixels ARGB pixel array
     * @param width  Image width
     * @param height Image height
     */
    public static void exportPngFromPixels(OutputStream os, int[] pixels, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        exportPng(os, bitmap);
        bitmap.recycle();
    }

    /**
     * Export raw KTX decoded pixels as JPEG.
     */
    public static void exportJpegFromPixels(OutputStream os, int[] pixels, int width, int height, int quality) {
        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        exportJpeg(os, bitmap, quality);
        bitmap.recycle();
    }

    /**
     * Convert raw RGBA bytes to a Bitmap.
     */
    public static Bitmap rgbaToBitmap(byte[] rgba, int width, int height) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < width * height; i++) {
            int r = rgba[i * 4] & 0xFF;
            int g = rgba[i * 4 + 1] & 0xFF;
            int b = rgba[i * 4 + 2] & 0xFF;
            int a = rgba[i * 4 + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
