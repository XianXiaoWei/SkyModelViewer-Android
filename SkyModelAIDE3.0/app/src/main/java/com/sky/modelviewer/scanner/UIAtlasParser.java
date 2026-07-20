package com.sky.modelviewer.scanner;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses UIPackedAtlas.lua to extract image region definitions.
 * Each region maps an icon name (lowercased) to (atlasTextureName, u0, v0, u1, v1).
 *
 * Ported from HTML ensureAtlasRegions (line 37477-37495):
 *   lua line format:
 *     resource "ImageRegion" "IconName" { image = "UIPackedAtlas7", uv = { 0.0625, 0.125, 0.1875, 0.25 } }
 *   uv values may be decimals (0.0625) or fractions (1/2048).
 *   HTML regex allows any chars (except }) between image and uv fields.
 */
public class UIAtlasParser {

    public static class ImageRegion {
        public String iconName;
        public String atlasImage; // e.g. "UIPackedAtlas18" (lowercased)
        public float u0, v0, u1, v1; // UV coordinates [left, top, right, bottom]
    }

    private static final HashMap<String, HashMap<String, ImageRegion>> cache = new HashMap<>();

    // Match HTML regex exactly:
    //   resource\s+"ImageRegion"\s+"([^"]+)"\s*\{[^}]*?image\s*=\s*"([^"]+)"[^}]*?uv\s*=\s*\{([^}]+)\}
    // Allows any chars (except }) between fields. uv may contain fractions like "1/2048".
    private static final Pattern REGION_PATTERN = Pattern.compile(
        "resource\\s+\"ImageRegion\"\\s+\"([^\"]+)\"\\s*\\{[^}]*?image\\s*=\\s*\"([^\"]+)\"[^}]*?uv\\s*=\\s*\\{([^}]+)\\}",
        Pattern.DOTALL
    );

    /**
     * Parse a UV component value — may be a decimal (0.0625) or fraction (1/2048).
     * Ported from HTML parseNum.
     */
    private static float parseNum(String s) {
        s = s.trim();
        if (s.contains("/")) {
            String[] parts = s.split("/");
            if (parts.length == 2) {
                try {
                    return Float.parseFloat(parts[0].trim()) / Float.parseFloat(parts[1].trim());
                } catch (NumberFormatException e) {
                    return Float.NaN;
                }
            }
        }
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /**
     * Parse UIPackedAtlas.lua from APK and build icon→region map.
     * Keys are lowercased icon names (matching HTML).
     * Cached per apkPath.
     */
    public static HashMap<String, ImageRegion> parseAtlas(String apkPath) {
        if (cache.containsKey(apkPath)) return cache.get(apkPath);

        HashMap<String, ImageRegion> regions = new HashMap<>();
        try {
            String entryPath = SkyAssetScanner.findResourceEntry(apkPath, "UIPackedAtlas.lua");
            if (entryPath == null) {
                cache.put(apkPath, regions);
                return regions;
            }
            byte[] bytes = SkyAssetScanner.readApkEntry(apkPath, entryPath);
            if (bytes == null) {
                cache.put(apkPath, regions);
                return regions;
            }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            Matcher m = REGION_PATTERN.matcher(text);
            while (m.find()) {
                String icon = m.group(1);
                String image = m.group(2).toLowerCase();
                String[] uvParts = m.group(3).split(",");
                if (uvParts.length != 4) continue;
                float u0 = parseNum(uvParts[0]);
                float v0 = parseNum(uvParts[1]);
                float u1 = parseNum(uvParts[2]);
                float v1 = parseNum(uvParts[3]);
                if (Float.isNaN(u0) || Float.isNaN(v0) || Float.isNaN(u1) || Float.isNaN(v1)) continue;
                ImageRegion r = new ImageRegion();
                r.iconName = icon;
                r.atlasImage = image;
                r.u0 = u0; r.v0 = v0; r.u1 = u1; r.v1 = v1;
                // HTML: map.set(icon.toLowerCase(), ...)
                regions.put(icon.toLowerCase(), r);
            }
        } catch (Exception e) {
            // ignore
        }
        cache.put(apkPath, regions);
        return regions;
    }

    /**
     * Look up an icon's region by icon name (case-insensitive).
     */
    public static ImageRegion getRegion(String apkPath, String iconName) {
        HashMap<String, ImageRegion> regions = parseAtlas(apkPath);
        if (iconName == null || iconName.isEmpty()) return null;
        return regions.get(iconName.toLowerCase());
    }

    /**
     * Clear cache (called when APK changes).
     */
    public static void clearCache() {
        cache.clear();
    }
}
