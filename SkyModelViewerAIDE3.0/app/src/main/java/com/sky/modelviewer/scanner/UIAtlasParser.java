package com.sky.modelviewer.scanner;

import android.util.Log;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses UIPackedAtlas.lua to extract image region definitions.
 * Each region maps an icon name (lowercased) to (atlasTextureName, u0, v0, u1, v1).
 *
 * Supports both:
 * - Global client: plain text .lua files (regex matching)
 * - China client: .lua.luac bytecode files (decompile + regex, with bytecode extraction fallback)
 */
public class UIAtlasParser {

    private static final String TAG = "UIAtlasParser";

    public static class ImageRegion {
        public String iconName;
        public String atlasImage; // e.g. "UIPackedAtlas18" (lowercased)
        public float u0, v0, u1, v1; // UV coordinates [left, top, right, bottom]
    }

    private static final HashMap<String, HashMap<String, ImageRegion>> cache = new HashMap<>();

    // Match original DSL format (global client .lua):
    //   resource "ImageRegion" "IconName" { image = "UIPackedAtlas7", uv = { 0.0625, 0.125, 0.1875, 0.25 } }
    private static final Pattern REGION_PATTERN_DSL = Pattern.compile(
        "resource\\s+\"ImageRegion\"\\s+\"([^\"]+)\"\\s*\\{[^}]*?image\\s*=\\s*\"([^\"]+)\"[^}]*?uv\\s*=\\s*\\{([^}]+)\\}",
        Pattern.DOTALL
    );

    // Match decompiled CURRIED format (China client .lua.luac):
    //   resource("ImageRegion")("IconName")({ image = "UIPackedAtlas7", uv = { 0.0625, 0.125, 0.1875, 0.25 } })
    // This is because Lua DSL "resource A B {...}" compiles to resource(A)(B)({...})
    private static final Pattern REGION_PATTERN_CURRIED = Pattern.compile(
        "resource\\s*\\(\\s*\"ImageRegion\"\\s*\\)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)\\s*\\(\\s*\\{[^}]*?image\\s*=\\s*\"([^\"]+)\"[^}]*?uv\\s*=\\s*\\{([^}]+)\\}",
        Pattern.DOTALL
    );

    // Also match plain function-call format (if decompiler changes):
    //   resource("ImageRegion", "IconName", { image = "...", uv = {...} })
    private static final Pattern REGION_PATTERN_FUNC = Pattern.compile(
        "resource\\s*\\(\\s*\"ImageRegion\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\\{[^}]*?image\\s*=\\s*\"([^\"]+)\"[^}]*?uv\\s*=\\s*\\{([^}]+)\\}",
        Pattern.DOTALL
    );

    /**
     * Parse a UV component value — may be a decimal (0.0625) or fraction (1/2048).
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
     * Parse UIPackedAtlas.lua from APK and build icon->region map.
     * Handles both plain text (.lua) and bytecode (.lua.luac) formats.
     * Cached per apkPath.
     */
    public static HashMap<String, ImageRegion> parseAtlas(String apkPath) {
        if (cache.containsKey(apkPath)) return cache.get(apkPath);

        HashMap<String, ImageRegion> regions = new HashMap<>();
        try {
            // findResourceEntry handles .lua -> .lua.luac fallback
            String entryPath = SkyAssetScanner.findResourceEntry(apkPath, "UIPackedAtlas.lua");
            if (entryPath == null) {
                Log.w(TAG, "UIPackedAtlas.lua not found in APK");
                cache.put(apkPath, regions);
                return regions;
            }

            Log.i(TAG, "Found UIPackedAtlas at: " + entryPath);

            byte[] bytes = SkyAssetScanner.readApkEntry(apkPath, entryPath);
            if (bytes == null) {
                Log.w(TAG, "Failed to read UIPackedAtlas bytes");
                cache.put(apkPath, regions);
                return regions;
            }

            Log.i(TAG, "Read " + bytes.length + " bytes, first 4: 0x" +
                String.format("%02x%02x%02x%02x", bytes[0], bytes[1], bytes[2], bytes[3]));

            if (LuaLoader.isLuacBytecode(bytes)) {
                // China client: bytecode - decompile then use regex
                Log.i(TAG, "Detected luac bytecode, decompiling...");
                extractFromBytecode(bytes, regions);
            } else {
                // Global client: plain text - use regex
                Log.i(TAG, "Plain text lua, using regex");
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                extractFromText(text, regions);
            }

            Log.i(TAG, "Total regions extracted: " + regions.size());

        } catch (Exception e) {
            Log.e(TAG, "parseAtlas error: " + e.getMessage(), e);
        }
        cache.put(apkPath, regions);
        return regions;
    }

    /**
     * Extract regions from plain text using regex (global client).
     */
    private static void extractFromText(String text, HashMap<String, ImageRegion> regions) {
        int before = regions.size();
        extractWithPattern(text, REGION_PATTERN_DSL, regions);
        int afterDsl = regions.size();
        extractWithPattern(text, REGION_PATTERN_CURRIED, regions);
        int afterCurried = regions.size();
        extractWithPattern(text, REGION_PATTERN_FUNC, regions);
        int afterFunc = regions.size();
        Log.i(TAG, "Text extraction: DSL=" + (afterDsl - before) +
            " CURRIED=" + (afterCurried - afterDsl) +
            " FUNC=" + (afterFunc - afterCurried));
    }

    private static void extractWithPattern(String text, Pattern pattern, HashMap<String, ImageRegion> regions) {
        Matcher m = pattern.matcher(text);
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
            regions.put(icon.toLowerCase(), r);
        }
    }

    /**
     * Extract regions from luac bytecode (China client).
     * Primary: decompile to text + regex (reuses tested decompiler logic)
     * Fallback: direct bytecode extraction via LuacAtlasExtractor
     */
    private static void extractFromBytecode(byte[] bytes, HashMap<String, ImageRegion> regions) {
        try {
            // Step 1: Parse the bytecode
            com.sky.modelviewer.parsing.LuacParser parser = new com.sky.modelviewer.parsing.LuacParser();
            com.sky.modelviewer.parsing.LuacFunction func = parser.parse(bytes);
            Log.i(TAG, "Parsed bytecode: " + func.instructions.length + " instructions, " +
                func.constants.size() + " constants, " + func.protos.size() + " sub-protos");

            // Step 2: Decompile to text
            com.sky.modelviewer.parsing.LuacDecompiler decompiler =
                new com.sky.modelviewer.parsing.LuacDecompiler(func, 0);
            java.util.List<String> lines = decompiler.decompile();

            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append("\n");
            }
            String decompiledText = sb.toString();
            Log.i(TAG, "Decompiled to " + lines.size() + " lines, " + decompiledText.length() + " chars");

            // Debug: log first few lines to see the format
            int previewLines = Math.min(10, lines.size());
            for (int i = 0; i < previewLines; i++) {
                Log.d(TAG, "Decompiled[" + i + "]: " + lines.get(i));
            }

            // Step 3: Extract regions using regex on decompiled text
            int beforeRegex = regions.size();
            extractWithPattern(decompiledText, REGION_PATTERN_DSL, regions);
            int afterDsl = regions.size();
            extractWithPattern(decompiledText, REGION_PATTERN_CURRIED, regions);
            int afterCurried = regions.size();
            extractWithPattern(decompiledText, REGION_PATTERN_FUNC, regions);
            int afterFunc = regions.size();

            Log.i(TAG, "Decompiled text regex: DSL=" + (afterDsl - beforeRegex) +
                " CURRIED=" + (afterCurried - afterDsl) +
                " FUNC=" + (afterFunc - afterCurried));

            // Step 4: If regex found nothing, try direct bytecode extraction as fallback
            if (regions.isEmpty()) {
                Log.w(TAG, "Regex found 0 regions, trying direct bytecode extraction...");
                com.sky.modelviewer.parsing.LuacAtlasExtractor extractor =
                    new com.sky.modelviewer.parsing.LuacAtlasExtractor(func);
                java.util.List<com.sky.modelviewer.parsing.LuacAtlasExtractor.AtlasEntry> entries =
                    extractor.extract();

                Log.i(TAG, "Direct bytecode extraction found " + entries.size() + " entries");

                for (com.sky.modelviewer.parsing.LuacAtlasExtractor.AtlasEntry entry : entries) {
                    ImageRegion r = new ImageRegion();
                    r.iconName = entry.iconName;
                    r.atlasImage = entry.atlasImage;
                    r.u0 = entry.u0;
                    r.v0 = entry.v0;
                    r.u1 = entry.u1;
                    r.v1 = entry.v1;
                    regions.put(entry.iconName.toLowerCase(), r);
                }

                // Debug: log first few entries
                int previewEntries = Math.min(5, entries.size());
                for (int i = 0; i < previewEntries; i++) {
                    com.sky.modelviewer.parsing.LuacAtlasExtractor.AtlasEntry e = entries.get(i);
                    Log.d(TAG, "Entry[" + i + "]: " + e.iconName + " -> " + e.atlasImage +
                        " uv=[" + e.u0 + "," + e.v0 + "," + e.u1 + "," + e.v1 + "]");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Bytecode extraction failed: " + e.getMessage(), e);
        }
    }

    /**
     * Look up an icon's region by icon name (case-insensitive).
     */
    public static ImageRegion getRegion(String apkPath, String iconName) {
        HashMap<String, ImageRegion> regions = parseAtlas(apkPath);
        if (iconName == null || iconName.isEmpty()) return null;
        ImageRegion region = regions.get(iconName.toLowerCase());
        if (region == null) {
            Log.d(TAG, "Region not found for icon: " + iconName +
                " (total regions: " + regions.size() + ")");
        }
        return region;
    }

    /**
     * Clear cache (called when APK changes).
     */
    public static void clearCache() {
        cache.clear();
    }
}
