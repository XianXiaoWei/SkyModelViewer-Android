package com.sky.modelviewer.render;

import android.graphics.Bitmap;
import android.util.Log;
import com.sky.modelviewer.scanner.SkyAssetScanner;
import com.sky.modelviewer.scanner.UIAtlasParser;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads wardrobe icons by:
 *   1. Looking up the icon name in UIPackedAtlas.lua regions
 *   2. Decoding the atlas KTX texture (R11_EAC software decode, matching HTML decodeR11Ktx)
 *   3. Cropping the UV region from the atlas
 *   4. Applying HSV tint (matching HTML dressSelectTint + buildIconDataUrl)
 *
 * Ported from HTML buildIconDataUrl (line 37584-37630).
 *
 * The atlas uses "粉白透明" encoding: pixel intensity stored in RGB (R=G=B=intensity),
 * alpha is derived from max(R,G,B) during icon rendering.
 *
 * This loader runs on a background thread — no GL calls.
 */
public class WardrobeIconLoader implements com.sky.modelviewer.ui.WardrobeGridAdapter.IconLoader {

    private static final String TAG = "WardrobeIconLoader";

    /** Info needed to render an outfit's icon. */
    public static class OutfitInfo {
        public String iconName;        // e.g. "UiOutfitBodyClassicDress"
        public float[] baseHsv;        // [h, s, v] or null
        public float[] primaryDyeHsv;  // or null
        public float[] secondaryDyeHsv;// or null
        public float[] iconHsv;        // or null
    }

    private final String apkPath;
    private final Map<String, String> texIndex;

    // entryName → OutfitInfo (populated by MainActivity)
    private final Map<String, OutfitInfo> outfitInfoMap = new HashMap<>();

    // Cache: atlasName(lowercase) → decoded RGBA data {width, height, data}
    private final Map<String, Etc2Decoder.DecodedImage> atlasCache = new HashMap<>();

    // Cache: entryName → Bitmap (final tinted icon)
    private final Map<String, Bitmap> bitmapCache = new HashMap<>();

    public WardrobeIconLoader(String apkPath, Map<String, String> texIndex) {
        this.apkPath = apkPath;
        this.texIndex = texIndex != null ? texIndex : new HashMap<String, String>();
    }

    /**
     * Register outfit info for icon rendering.
     * Called by MainActivity when loading OutfitDefs.
     */
    public void registerOutfitInfo(String entryName, OutfitInfo info) {
        if (entryName != null && info != null) {
            outfitInfoMap.put(entryName, info);
        }
    }

    /**
     * Load an icon bitmap by outfit entry name.
     * @param entryName the outfit's unique name from OutfitDefs
     * @return Bitmap of the icon, or null on failure
     */
    @Override
    public Bitmap loadIcon(String entryName) {
        if (entryName == null || entryName.isEmpty()) return null;
        if (bitmapCache.containsKey(entryName)) return bitmapCache.get(entryName);

        OutfitInfo info = outfitInfoMap.get(entryName);
        if (info == null || info.iconName == null || info.iconName.isEmpty()) {
            Log.d(TAG, "No outfit info for: " + entryName);
            return null;
        }

        Log.d(TAG, "Loading icon for " + entryName + " -> iconName=" + info.iconName);

        // 1. Look up atlas region
        UIAtlasParser.ImageRegion region = UIAtlasParser.getRegion(apkPath, info.iconName);

        if (region == null) {
            Log.d(TAG, "Region not found for " + info.iconName + ", trying standalone KTX");
            // Fallback: try loading as standalone KTX (e.g. UiOutfitPendantAP02.ktx)
            Bitmap bmp = loadStandaloneKtxIcon(entryName, info);
            bitmapCache.put(entryName, bmp);
            return bmp;
        }

        Log.d(TAG, "Region found: " + info.iconName + " -> atlas=" + region.atlasImage +
            " uv=[" + region.u0 + "," + region.v0 + "," + region.u1 + "," + region.v1 + "]");

        // 2. Decode atlas texture (cached)
        Etc2Decoder.DecodedImage atlas = ensureAtlasDecoded(region.atlasImage);
        if (atlas == null) {
            Log.w(TAG, "Atlas decode failed for: " + region.atlasImage + ", trying standalone KTX");
            Bitmap bmp = loadStandaloneKtxIcon(entryName, info);
            bitmapCache.put(entryName, bmp);
            return bmp;
        }

        // 3. Crop region from atlas
        int W = atlas.width, H = atlas.height;
        int left = Math.round(region.u0 * W);
        int top = Math.round(region.v0 * H);
        int right = Math.round(region.u1 * W);
        int bottom = Math.round(region.v1 * H);
        int cw = right - left;
        int ch = bottom - top;
        if (cw <= 0 || ch <= 0 || left < 0 || top < 0 || right > W || bottom > H) {
            Log.w(TAG, "Invalid crop: atlas=" + W + "x" + H + " crop=[" + left + "," + top + "," + right + "," + bottom + "]");
            bitmapCache.put(entryName, null);
            return null;
        }

        Log.d(TAG, "Cropping: atlas=" + W + "x" + H + " -> " + cw + "x" + ch + " at [" + left + "," + top + "]");

        // 4. Apply HSV tint + create bitmap
        float[] tint = dressSelectTint(info);
        int[] tintRgb = null;
        float valScale = 1.0f;
        if (tint != null) {
            tintRgb = hsvToRgb(tint[0], tint[1], 100.0f); // V=100 for pure color
            valScale = Math.max(0, Math.min(1, tint[2] / 100.0f));
        }

        int[] pixels = new int[cw * ch];
        byte[] src = atlas.data;
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int si = (((top + y) * W) + (left + x)) * 4;
                int r = src[si] & 0xFF;
                int g = src[si + 1] & 0xFF;
                int b = src[si + 2] & 0xFF;
                // Atlas intensity = max(R,G,B) — serves as both brightness and alpha
                int inten = Math.max(r, Math.max(g, b));
                if (inten == 0) {
                    // Fully transparent
                    pixels[y * cw + x] = 0;
                    continue;
                }
                int pr, pg, pb, pa;
                if (tintRgb != null) {
                    // Tinted: lum = intensity * valScale, color = tintRgb * lum / 255
                    int lum = clamp255(Math.round(inten * valScale));
                    pr = (tintRgb[0] * lum / 255) & 0xFF;
                    pg = (tintRgb[1] * lum / 255) & 0xFF;
                    pb = (tintRgb[2] * lum / 255) & 0xFF;
                    pa = inten;
                } else {
                    // No tint: white icon using intensity as brightness
                    pr = inten; pg = inten; pb = inten;
                    pa = inten;
                }
                // Android Bitmap uses ARGB_8888: 0xAARRGGBB
                pixels[y * cw + x] = (pa << 24) | (pr << 16) | (pg << 8) | pb;
            }
        }

        Bitmap bmp = Bitmap.createBitmap(pixels, cw, ch, Bitmap.Config.ARGB_8888);
        bitmapCache.put(entryName, bmp);
        return bmp;
    }

    /**
     * Load a standalone KTX icon (not in UIPackedAtlas).
     * Used for icons like UiOutfitPendantAP02.ktx (seasonal pendants etc.)
     * that exist as independent KTX files in the APK.
     *
     * The KTX is decoded to RGBA via Etc2Decoder.decodeKtx, then:
     *   - If HSV tint is needed: apply tint using pixel intensity as brightness
     *   - Otherwise: use the texture as-is (it's already a full-color icon)
     *
     * @param entryName outfit entry name (for caching)
     * @param info outfit info with iconName + HSV data
     * @return Bitmap of the icon, or null on failure
     */
    private Bitmap loadStandaloneKtxIcon(String entryName, OutfitInfo info) {
        String key = info.iconName.toLowerCase();
        String entryPath = texIndex.get(key);
        if (entryPath == null) return null;

        byte[] raw = SkyAssetScanner.readApkEntry(apkPath, entryPath);
        if (raw == null) return null;

        Etc2Decoder.DecodedImage img = Etc2Decoder.decodeKtx(raw);
        if (img == null) return null;

        int W = img.width, H = img.height;
        if (W <= 0 || H <= 0) return null;

        float[] tint = dressSelectTint(info);
        int[] tintRgb = null;
        float valScale = 1.0f;
        if (tint != null) {
            tintRgb = hsvToRgb(tint[0], tint[1], 100.0f);
            valScale = Math.max(0, Math.min(1, tint[2] / 100.0f));
        }

        int[] pixels = new int[W * H];
        byte[] src = img.data;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int si = (y * W + x) * 4;
                int r = src[si] & 0xFF;
                int g = src[si + 1] & 0xFF;
                int b = src[si + 2] & 0xFF;
                int a = src[si + 3] & 0xFF;
                int pr, pg, pb, pa;
                if (tintRgb != null) {
                    // Tinted: use intensity as brightness, tint provides color
                    int inten = Math.max(r, Math.max(g, b));
                    if (inten == 0) {
                        pixels[y * W + x] = 0;
                        continue;
                    }
                    int lum = clamp255(Math.round(inten * valScale));
                    pr = (tintRgb[0] * lum / 255) & 0xFF;
                    pg = (tintRgb[1] * lum / 255) & 0xFF;
                    pb = (tintRgb[2] * lum / 255) & 0xFF;
                    pa = a;
                } else {
                    // No tint: use original colors (standalone KTX icons are full-color)
                    pr = r; pg = g; pb = b;
                    pa = a;
                }
                pixels[y * W + x] = (pa << 24) | (pr << 16) | (pg << 8) | pb;
            }
        }

        return Bitmap.createBitmap(pixels, W, H, Bitmap.Config.ARGB_8888);
    }

    /**
     * Decode an atlas KTX texture (cached by atlas name).
     * UI atlases are R11_EAC — software decoded via Etc2Decoder.decodeKtx.
     * Falls back to standard image decode if KTX decode fails.
     * Ported from HTML ensureAtlasImage (line 37549-37561).
     */
    private Etc2Decoder.DecodedImage ensureAtlasDecoded(String atlasName) {
        if (atlasName == null) return null;
        String key = atlasName.toLowerCase();
        if (atlasCache.containsKey(key)) return atlasCache.get(key);

        Etc2Decoder.DecodedImage img = null;
        try {
            String entryPath = texIndex.get(key);
            if (entryPath == null) {
                Log.w(TAG, "Atlas texture not in texIndex: " + key);
                atlasCache.put(key, null);
                return null;
            }
            Log.d(TAG, "Loading atlas KTX: " + key + " -> " + entryPath);
            byte[] raw = SkyAssetScanner.readApkEntry(apkPath, entryPath);
            if (raw == null) {
                Log.w(TAG, "Failed to read atlas KTX bytes: " + entryPath);
                atlasCache.put(key, null);
                return null;
            }
            Log.d(TAG, "Decoding KTX: " + raw.length + " bytes");
            img = Etc2Decoder.decodeKtx(raw);
            if (img != null) {
                Log.d(TAG, "KTX decoded: " + img.width + "x" + img.height);
            } else {
                Log.w(TAG, "KTX decode returned null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Atlas decode error: " + e.getMessage(), e);
            img = null;
        }
        atlasCache.put(key, img);
        return img;
    }

    /**
     * Select tint HSV — priority: secondary_dye → primary_dye → icon → base.
     * First with S>0 and V>0; else first non-default (0,0,100).
     * Returns null if no tint needed.
     * Ported from HTML dressSelectTint (line 37574-37579).
     */
    private static float[] dressSelectTint(OutfitInfo info) {
        float[][] cand = new float[4][];
        cand[0] = info.secondaryDyeHsv;
        cand[1] = info.primaryDyeHsv;
        cand[2] = info.iconHsv;
        cand[3] = info.baseHsv;
        // First pass: any with S>0 and V>0
        for (float[] hsv : cand) {
            if (hsv != null && hsv.length >= 3 && hsv[1] > 0 && hsv[2] > 0) return hsv;
        }
        // Second pass: any non-default (not [0,0,100])
        for (float[] hsv : cand) {
            if (hsv != null && hsv.length >= 3 &&
                !(Math.abs(hsv[0]) < 1e-6 && Math.abs(hsv[1]) < 1e-6 && Math.abs(hsv[2] - 100) < 1e-6)) {
                return hsv;
            }
        }
        return null;
    }

    /**
     * HSV (h: 0-360, s: 0-100, v: 0-100) → RGB [r,g,b] 0-255.
     * Ported from HTML dressHsvToRgb (line 37565-37571).
     */
    private static int[] hsvToRgb(float h, float s, float v) {
        h = ((h % 360) + 360) % 360;
        s = Math.max(0, Math.min(1, s / 100.0f));
        v = Math.max(0, Math.min(1, v / 100.0f));
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60) % 2 - 1));
        float mm = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return new int[] {
            Math.round((r + mm) * 255),
            Math.round((g + mm) * 255),
            Math.round((b + mm) * 255)
        };
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /**
     * Clear all caches (called when APK changes).
     */
    public void clearCache() {
        atlasCache.clear();
        bitmapCache.clear();
        outfitInfoMap.clear();
    }
}
