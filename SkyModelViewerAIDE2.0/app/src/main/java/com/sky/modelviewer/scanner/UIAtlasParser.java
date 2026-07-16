package com.sky.modelviewer.scanner;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses UIPackedAtlas.lua to extract image region definitions.
 * Each region maps an icon name to (atlasTextureName, u0, v0, u1, v1).
 */
public class UIAtlasParser {

    public static class ImageRegion {
        public String iconName;
        public String atlasImage; // e.g. "UIPackedAtlas18"
        public float u0, v0, u1, v1; // UV coordinates
    }

    private static final HashMap<String, HashMap<String, ImageRegion>> cache = new HashMap<>();

    // Pattern: resource "ImageRegion" "IconName" { image = "AtlasName", uv = { u0, v0, u1, v1 } }
    private static final Pattern REGION_PATTERN = Pattern.compile(
        "resource\\s+\"ImageRegion\"\\s+\"([^\"]+)\"\\s*\\{\\s*" +
        "image\\s*=\\s*\"([^\"]+)\"\\s*,\\s*" +
        "uv\\s*=\\s*\\{\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*\\}" +
        "[^}]*\\}"
    );

    /**
     * Parse UIPackedAtlas.lua from APK and build icon→region map.
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
                ImageRegion r = new ImageRegion();
                r.iconName = m.group(1);
                r.atlasImage = m.group(2);
                r.u0 = Float.parseFloat(m.group(3));
                r.v0 = Float.parseFloat(m.group(4));
                r.u1 = Float.parseFloat(m.group(5));
                r.v1 = Float.parseFloat(m.group(6));
                regions.put(r.iconName, r);
            }
        } catch (Exception e) {
            // ignore
        }
        cache.put(apkPath, regions);
        return regions;
    }

    /**
     * Look up an icon's region by icon name.
     */
    public static ImageRegion getRegion(String apkPath, String iconName) {
        HashMap<String, ImageRegion> regions = parseAtlas(apkPath);
        if (iconName == null || iconName.isEmpty()) return null;
        return regions.get(iconName);
    }

    /**
     * Clear cache (called when APK changes).
     */
    public static void clearCache() {
        cache.clear();
    }
}
