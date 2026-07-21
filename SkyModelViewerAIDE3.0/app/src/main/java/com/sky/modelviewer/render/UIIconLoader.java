package com.sky.modelviewer.render;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

/**
 * Loads UI icon bitmaps from KTX atlas textures by cropping UV regions using GPU FBO.
 * Must be called on GL thread.
 */
public class UIIconLoader {

    // Cache: atlasTextureName → GL texture id
    private static final HashMap<String, Integer> atlasTextureCache = new HashMap<>();
    // Cache: iconName → Bitmap
    private static final HashMap<String, Bitmap> iconBitmapCache = new HashMap<>();

    // Simple shader for rendering a textured quad
    private static int iconProgram = 0;
    private static int iconVBO = 0;

    /**
     * Load or get cached icon bitmap.
     * @param iconName Icon name from OutfitDefs (e.g. "UiOutfitBodyClassicDress")
     * @param atlasTextureName Atlas texture name (e.g. "UIPackedAtlas18")
     * @param u0, v0, u1, v1 UV region
     * @param atlasBytes KTX file bytes for the atlas texture
     * @return Bitmap of the icon region, or null on failure
     */
    public static Bitmap loadIcon(String iconName, String atlasTextureName,
                                  float u0, float v0, float u1, float v1,
                                  byte[] atlasBytes) {
        if (iconName == null || iconName.isEmpty()) return null;
        if (iconBitmapCache.containsKey(iconName)) return iconBitmapCache.get(iconName);

        try {
            // Load atlas texture
            Integer texId = atlasTextureCache.get(atlasTextureName);
            if (texId == null) {
                if (atlasBytes == null) return null;
                texId = KtxTextureLoader.loadTexture(atlasBytes);
                if (texId == 0) {
                    texId = KtxTextureLoader.loadStandardImage(atlasBytes);
                }
                if (texId == 0) return null;
                // Ensure proper sampling parameters
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
                atlasTextureCache.put(atlasTextureName, texId);
            }

            // Read texture dimensions from KTX header (bytes 36-43: width, height)
            int tw = 0, th = 0;
            if (atlasBytes != null && atlasBytes.length >= 44) {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(atlasBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                tw = bb.getInt(36);
                th = bb.getInt(40);
            }
            // Fallback: if not from KTX, use power-of-2 guess from standard image
            if (tw <= 0 || th <= 0) {
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(atlasBytes, 0, atlasBytes.length, opts);
                tw = opts.outWidth;
                th = opts.outHeight;
            }
            if (tw <= 0 || th <= 0) return null;

            // Calculate pixel region from UV
            int pw = (int)((u1 - u0) * tw);
            int ph = (int)((v1 - v0) * th);
            if (pw <= 0 || ph <= 0) return null;
            // Clamp to reasonable size
            if (pw > 256) pw = 256;
            if (ph > 256) ph = 256;

            // Create FBO
            int[] fbo = new int[1];
            GLES30.glGenFramebuffers(1, fbo, 0);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0]);

            // Create render texture
            int[] renderTex = new int[1];
            GLES30.glGenTextures(1, renderTex, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, renderTex[0]);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, pw, ph, 0,
                               GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                                         GLES30.GL_TEXTURE_2D, renderTex[0], 0);

            // Set viewport to icon size
            GLES30.glViewport(0, 0, pw, ph);
            GLES30.glClearColor(0, 0, 0, 0);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

            // Render textured quad with UV region
            ensureProgram();
            GLES30.glUseProgram(iconProgram);

            // Bind atlas texture
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);
            int texLoc = GLES30.glGetUniformLocation(iconProgram, "uTexture");
            GLES30.glUniform1i(texLoc, 0);

            // Set UV offset and scale — flip V because KTX textures have top-left origin
            // but OpenGL textures have bottom-left origin
            int uvOffsetLoc = GLES30.glGetUniformLocation(iconProgram, "uUVOffset");
            int uvScaleLoc = GLES30.glGetUniformLocation(iconProgram, "uUVScale");
            // Flip: use (1-v1) as start, (v1-v0) as scale
            GLES30.glUniform2f(uvOffsetLoc, u0, 1.0f - v1);
            GLES30.glUniform2f(uvScaleLoc, u1 - u0, v1 - v0);

            // Render quad
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, iconVBO);
            int posLoc = GLES30.glGetAttribLocation(iconProgram, "aPosition");
            int uvLoc = GLES30.glGetAttribLocation(iconProgram, "aTexCoord");
            GLES30.glEnableVertexAttribArray(posLoc);
            GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, 0);
            GLES30.glEnableVertexAttribArray(uvLoc);
            GLES30.glVertexAttribPointer(uvLoc, 2, GLES30.GL_FLOAT, false, 16, 8);
            GLES30.glDisable(GLES30.GL_CULL_FACE);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

            // Read pixels
            ByteBuffer pixelBuf = ByteBuffer.allocateDirect(pw * ph * 4).order(ByteOrder.nativeOrder());
            GLES30.glReadPixels(0, 0, pw, ph, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuf);

            // Create bitmap (flip Y since OpenGL origin is bottom-left)
            int[] pixels = new int[pw * ph];
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    int srcIdx = ((ph - 1 - y) * pw + x) * 4;
                    int r = pixelBuf.get(srcIdx) & 0xFF;
                    int g = pixelBuf.get(srcIdx + 1) & 0xFF;
                    int b = pixelBuf.get(srcIdx + 2) & 0xFF;
                    int a = pixelBuf.get(srcIdx + 3) & 0xFF;
                    pixels[y * pw + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(pixels, pw, ph, Bitmap.Config.ARGB_8888);

            // Cleanup
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glDeleteFramebuffers(1, fbo, 0);
            GLES30.glDeleteTextures(1, renderTex, 0);

            iconBitmapCache.put(iconName, bitmap);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static void ensureProgram() {
        if (iconProgram != 0) return;

        String vs = "#version 300 es\n" +
            "layout(location=0) in vec2 aPosition;\n" +
            "layout(location=1) in vec2 aTexCoord;\n" +
            "out vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  vTexCoord = aTexCoord;\n" +
            "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            "}\n";

        String fs = "#version 300 es\n" +
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform vec2 uUVOffset;\n" +
            "uniform vec2 uUVScale;\n" +
            "in vec2 vTexCoord;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "  vec2 uv = uUVOffset + vTexCoord * uUVScale;\n" +
            "  fragColor = texture(uTexture, uv);\n" +
            "}\n";

        int vsh = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER);
        GLES30.glShaderSource(vsh, vs);
        GLES30.glCompileShader(vsh);
        int fsh = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER);
        GLES30.glShaderSource(fsh, fs);
        GLES30.glCompileShader(fsh);
        iconProgram = GLES30.glCreateProgram();
        GLES30.glAttachShader(iconProgram, vsh);
        GLES30.glAttachShader(iconProgram, fsh);
        GLES30.glLinkProgram(iconProgram);
        GLES30.glDeleteShader(vsh);
        GLES30.glDeleteShader(fsh);

        // Full-screen quad: position(x,y) + texCoord(u,v)
        // UV (0,0) at bottom-left, (1,1) at top-right
        float[] quad = {
            -1, -1,  0, 0,
             1, -1,  1, 0,
            -1,  1,  0, 1,
             1,  1,  1, 1,
        };
        ByteBuffer bb = ByteBuffer.allocateDirect(quad.length * 4).order(ByteOrder.nativeOrder());
        bb.asFloatBuffer().put(quad);
        int[] vbos = new int[1];
        GLES30.glGenBuffers(1, vbos, 0);
        iconVBO = vbos[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, iconVBO);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.length * 4, bb, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Clear all caches.
     */
    public static void clearCache() {
        for (Integer texId : atlasTextureCache.values()) {
            if (texId != null && texId != 0) {
                int[] ids = {texId};
                GLES30.glDeleteTextures(1, ids, 0);
            }
        }
        atlasTextureCache.clear();
        iconBitmapCache.clear();
    }
}
