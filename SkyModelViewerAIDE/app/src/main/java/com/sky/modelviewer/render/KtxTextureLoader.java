package com.sky.modelviewer.render;

import android.opengl.GLES30;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.opengl.GLUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class KtxTextureLoader {

    private static final byte[] KTX_IDENTIFIER = new byte[]{
        (byte) 0xAB, 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, (byte) 0xBB,
        0x0D, 0x0A, 0x1A, 0x0A
    };

    private static final int KTX_ENDIAN_LITTLE = 0x04030201;

    private static final int GL_RGBA = 0x1908;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_COMPRESSED_RGB8_ETC2 = 0x9274;
    private static final int GL_COMPRESSED_SRGB8_ETC2 = 0x9275;
    private static final int GL_COMPRESSED_RGBA8_ETC2_EAC = 0x9278;
    private static final int GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC = 0x9279;

    private KtxTextureLoader() {}

    public static int loadTexture(byte[] data) {
        if (data.length < 68) return 0;

        for (int i = 0; i < KTX_IDENTIFIER.length; i++) {
            if (data[i] != KTX_IDENTIFIER[i]) return 0;
        }

        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        if (bb.getInt(12) != KTX_ENDIAN_LITTLE) return 0;

        int glType = bb.getInt(16);
        int glFormat = bb.getInt(24);
        int glInternalFormat = bb.getInt(28);
        int glBaseInternalFormat = bb.getInt(32);
        int width = bb.getInt(36);
        int height = bb.getInt(40);
        int pixelDepth = bb.getInt(44);
        int arrayElements = bb.getInt(48);
        int faces = bb.getInt(52);
        int mipmapLevels = bb.getInt(56);
        int keyValueDataSize = bb.getInt(60);

        if (width <= 0 || height <= 0 || pixelDepth != 0 || arrayElements != 0 || faces != 1) {
            return 0;
        }

        int offset = 64 + keyValueDataSize;
        if (offset + 4 > data.length) return 0;

        int[] texIds = new int[1];
        GLES30.glGenTextures(1, texIds, 0);
        if (texIds[0] == 0) return 0;
        int texId = texIds[0];

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);

        boolean isCompressed = isCompressedFormat(glInternalFormat);
        int numLevels = mipmapLevels == 0 ? 1 : mipmapLevels;
        int currentWidth = width;
        int currentHeight = height;

        for (int level = 0; level < numLevels; level++) {
            if (offset + 4 > data.length) break;
            int imageSize = bb.getInt(offset);
            offset += 4;
            if (imageSize <= 0 || offset + imageSize > data.length) break;

            byte[] imageData = new byte[imageSize];
            System.arraycopy(data, offset, imageData, 0, imageSize);
            offset += imageSize;

            int paddedSize = (imageSize + 3) & ~3;
            offset += (paddedSize - imageSize);

            ByteBuffer imgBuf = ByteBuffer.allocateDirect(imageData.length).order(ByteOrder.nativeOrder());
            imgBuf.put(imageData).position(0);

            if (isCompressed) {
                GLES30.glCompressedTexImage2D(
                    GLES30.GL_TEXTURE_2D, level, glInternalFormat,
                    currentWidth, currentHeight, 0, imageSize, imgBuf);
            } else {
                int format = glFormat != 0 ? glFormat : GL_RGBA;
                int type = glType != 0 ? glType : GLES30.GL_UNSIGNED_BYTE;
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, level, glInternalFormat,
                    currentWidth, currentHeight, 0, format, type, imgBuf);
            }

            currentWidth = Math.max(currentWidth / 2, 1);
            currentHeight = Math.max(currentHeight / 2, 1);
        }

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            numLevels > 1 ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        if (mipmapLevels <= 1) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        return texId;
    }

    public static int loadStandardImage(byte[] data) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPremultiplied = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) return 0;

        int[] texIds = new int[1];
        GLES30.glGenTextures(1, texIds, 0);
        if (texIds[0] == 0) {
            bitmap.recycle();
            return 0;
        }
        int texId = texIds[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, bitmap, 0);

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        bitmap.recycle();
        return texId;
    }

    private static boolean isCompressedFormat(int internalFormat) {
        return internalFormat == GL_COMPRESSED_RGB8_ETC2 ||
               internalFormat == GL_COMPRESSED_SRGB8_ETC2 ||
               internalFormat == GL_COMPRESSED_RGBA8_ETC2_EAC ||
               internalFormat == GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC;
    }
}
