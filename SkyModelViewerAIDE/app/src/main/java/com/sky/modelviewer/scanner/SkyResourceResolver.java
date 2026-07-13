package com.sky.modelviewer.scanner;

import com.sky.modelviewer.model.MaterialInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

public class SkyResourceResolver {

    private static final HashMap<String, JSONArray> outfitDefsCache = new HashMap<String, JSONArray>();
    private static final HashMap<String, JSONArray> placeableDefsCache = new HashMap<String, JSONArray>();
    private static final HashMap<String, JSONObject> imageDefsCache = new HashMap<String, JSONObject>();
    private static final HashMap<String, String> textureFileCache = new HashMap<String, String>();
    private static final HashMap<String, HashSet<String>> shaderNamesCache = new HashMap<String, HashSet<String>>();
    private static final HashMap<String, MaterialInfo> objectMaterialCache = new HashMap<String, MaterialInfo>();
    private static final HashMap<String, List<String>> levelBinCache = new HashMap<String, List<String>>();

    private SkyResourceResolver() {}

    public static MaterialInfo resolveMaterial(String apkPath, String meshEntryPath) {
        String meshName = nameWithoutExtension(new File(meshEntryPath).getName());

        // 1. Try OutfitDefs.json
        JSONArray outfitDefs = findAndLoadOutfitDefs(apkPath);
        if (outfitDefs != null) {
            JSONObject outfit = lookupOutfitForMesh(outfitDefs, meshName);
            if (outfit != null) {
                return new MaterialInfo(
                    getString(outfit, "name"),
                    getString(outfit, "shader"),
                    getString(outfit, "diffuseTex"),
                    "",
                    getString(outfit, "normTex"),
                    getString(outfit, "maskTex"),
                    getString(outfit, "attribTex"),
                    0f, 1f, "OutfitDefs"
                );
            }
        }

        // 2. Try PlaceableDefs.json
        JSONArray placeableDefs = findAndLoadPlaceableDefs(apkPath);
        if (placeableDefs != null) {
            JSONObject placeable = lookupPlaceableMaterial(placeableDefs, meshName);
            if (placeable != null) {
                return new MaterialInfo(
                    getString(placeable, "name"),
                    getString(placeable, "shader"),
                    getString(placeable, "diffuse1Tex"),
                    getString(placeable, "diffuse2Tex"),
                    getString(placeable, "normTex"),
                    "", getString(placeable, "attribTex"),
                    getFloat(placeable, "emission_scale", 0f),
                    getFloat(placeable, "normal_scale", 1f),
                    "PlaceableDefs"
                );
            }
        }

        // 3. Try Objects.level.bin (scan binary data for mesh name + texture refs)
        MaterialInfo objMaterial = lookupObjectBinMaterial(apkPath, meshName);
        if (objMaterial != null) {
            return objMaterial;
        }

        // 4. Try ImageDefs.lua
        JSONObject imageDefs = findAndLoadImageDefs(apkPath);
        if (imageDefs != null) {
            String[] candidates = {meshName, stripMeshVariantSuffix(meshName)};
            for (String candidate : candidates) {
                if (imageDefs.has(candidate)) {
                    return new MaterialInfo(
                        candidate, "Mesh", candidate,
                        "", "", "", "", 0f, 1f, "ImageDefs"
                    );
                }
            }
        }

        // 5. Fallback: use stripped mesh name as texture name
        String strippedName = stripMeshVariantSuffix(meshName);
        if (strippedName != null && !strippedName.trim().isEmpty()) {
            return new MaterialInfo(
                strippedName, "Mesh", strippedName,
                "", "", "", "", 0f, 1f, "MeshName"
            );
        }

        return null;
    }

    public static Float resolveScale(String apkPath, String meshEntryPath) {
        JSONArray placeableDefs = findAndLoadPlaceableDefs(apkPath);
        if (placeableDefs == null) return null;
        String meshName = nameWithoutExtension(new File(meshEntryPath).getName());
        return lookupPlaceableScale(placeableDefs, meshName);
    }

    public static String findTextureFile(String apkPath, String[] textureNames) {
        for (String name : textureNames) {
            if (name == null || name.trim().isEmpty()) continue;
            String cacheKey = apkPath + "|" + name;
            if (textureFileCache.containsKey(cacheKey)) {
                String cached = textureFileCache.get(cacheKey);
                if (cached != null && !cached.isEmpty()) return cached;
                continue;
            }
            String result = SkyAssetScanner.findTextureEntry(apkPath, name);
            textureFileCache.put(cacheKey, result != null ? result : "");
            if (result != null) return result;
        }
        return null;
    }

    public static String stripMeshVariantSuffix(String meshName) {
        String name = meshName.replaceAll("_(StripAnim|CompOcc|ZipPos|ZipUvs|StripNorm|StripUv13|NoOcc|NoCollision).*$", "");
        name = name.replaceAll("_\\d+$", "");
        return name;
    }

    // ===== Objects.level.bin material extraction =====

    private static MaterialInfo lookupObjectBinMaterial(String apkPath, String meshName) {
        String cacheKey = apkPath + "|" + meshName;
        if (objectMaterialCache.containsKey(cacheKey)) {
            return objectMaterialCache.get(cacheKey);
        }

        HashSet<String> shaderNames = loadShaderNames(apkPath);
        byte[] needle = meshName.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        List<String> levelPaths = findAllLevelBins(apkPath);

        MaterialInfo best = null;
        int bestScore = -1;

        for (String levelPath : levelPaths) {
            byte[] raw = SkyAssetScanner.readApkEntry(apkPath, levelPath);
            if (raw == null) continue;

            int baseOffset = 0;
            int searchLen = raw.length - needle.length;
            
            while (baseOffset <= searchLen) {
                int rel = indexOf(raw, needle, baseOffset);
                if (rel < 0) break;

                MaterialInfo material = extractObjectMaterial(raw, rel, meshName, shaderNames);
                if (material != null) {
                    int score = scoreObjectMaterial(material, meshName);
                    if (score > bestScore) {
                        best = material;
                        bestScore = score;
                    }
                }

                baseOffset = rel + 1;
            }

            if (bestScore >= 13) break;
        }

        objectMaterialCache.put(cacheKey, best);
        return best;
    }

    private static MaterialInfo extractObjectMaterial(byte[] raw, int meshOffset, String meshName, HashSet<String> shaderNames) {
        int start = Math.max(0, meshOffset - 0x500);
        int end = Math.min(raw.length, meshOffset + 0x100);

        List<BinaryString> prior = extractAsciiStrings(raw, start, end, 3);

        // Filter to only strings before meshOffset
        List<BinaryString> beforeMesh = new ArrayList<BinaryString>();
        for (BinaryString bs : prior) {
            if (bs.offset < meshOffset) {
                beforeMesh.add(bs);
            }
        }
        if (beforeMesh.isEmpty()) return null;

        // Find parameter pairs: u_xxx -> value
        HashMap<String, String> parameters = new HashMap<String, String>();
        HashSet<Integer> valueOffsets = new HashSet<Integer>();

        for (int i = 0; i + 1 < beforeMesh.size(); i++) {
            BinaryString current = beforeMesh.get(i);
            if (!current.text.startsWith("u_")) continue;

            BinaryString next = beforeMesh.get(i + 1);
            if (next.text.startsWith("u_") || next.text.startsWith("BstNode_")) continue;

            parameters.put(current.text, next.text);
            valueOffsets.add(next.offset);
        }

        String diffuse = getFirstParameter(parameters, new String[]{"u_diffuse1Tex", "u_diffuseTex", "u_tex", "u_mainTex"});
        String normal = getFirstParameter(parameters, new String[]{"u_normTex", "u_normalTex"});
        String attrib = getFirstParameter(parameters, new String[]{"u_attribTex"});

        // Find shader name
        String shader = "";
        for (int i = beforeMesh.size() - 1; i >= 0; i--) {
            BinaryString candidate = beforeMesh.get(i);
            if (valueOffsets.contains(candidate.offset)) continue;
            if (candidate.text.startsWith("u_") || candidate.text.startsWith("BstNode_")) continue;

            if (shaderNames.contains(candidate.text)) {
                shader = candidate.text;
                break;
            }
        }

        if (isEmpty(diffuse) && isEmpty(normal) && isEmpty(attrib) && isEmpty(shader)) {
            return null;
        }

        return new MaterialInfo(
            meshName, shader,
            diffuse != null ? diffuse : "",
            "", normal != null ? normal : "",
            "", attrib != null ? attrib : "",
            0f, 1f, "Objects.level.bin"
        );
    }

    private static List<BinaryString> extractAsciiStrings(byte[] raw, int start, int end, int minLength) {
        List<BinaryString> result = new ArrayList<BinaryString>();
        int textStart = -1;
        StringBuilder chars = new StringBuilder();

        for (int i = start; i < end; i++) {
            byte b = raw[i];
            if (b >= 32 && b <= 126) {
                if (textStart < 0) textStart = i;
                chars.append((char) b);
            } else {
                if (textStart >= 0 && chars.length() >= minLength) {
                    result.add(new BinaryString(textStart, chars.toString()));
                }
                textStart = -1;
                chars.setLength(0);
            }
        }

        if (textStart >= 0 && chars.length() >= minLength) {
            result.add(new BinaryString(textStart, chars.toString()));
        }

        return result;
    }

    private static int scoreObjectMaterial(MaterialInfo material, String meshName) {
        String baseName = stripMeshVariantSuffix(meshName);
        int score = 0;

        if (!isEmpty(material.diffuseTex)) {
            score += 4;
            if (material.diffuseTex.equalsIgnoreCase(baseName)) {
                score += 4;
            } else if (material.diffuseTex.toLowerCase().startsWith(baseName.toLowerCase()) ||
                       baseName.toLowerCase().startsWith(material.diffuseTex.toLowerCase())) {
                score += 2;
            }
        }

        if (!isEmpty(material.normTex)) {
            score += 2;
            if (!material.normTex.equalsIgnoreCase("UpNormal") &&
                !material.normTex.equalsIgnoreCase("FlatNormal") &&
                !material.normTex.equalsIgnoreCase("DefaultNormal")) {
                score += 2;
            }
        }

        if (!isEmpty(material.attribTex)) {
            score += 2;
        }

        if (!isEmpty(material.shader)) {
            score += 3;
        }

        return score;
    }

    private static String getFirstParameter(HashMap<String, String> parameters, String[] names) {
        for (int i = 0; i < names.length; i++) {
            String value = parameters.get(names[i]);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        int max = haystack.length - needle.length;
        for (int i = from; i <= max; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private static List<String> findAllLevelBins(String apkPath) {
        if (levelBinCache.containsKey(apkPath)) {
            return levelBinCache.get(apkPath);
        }
        List<String> result = new ArrayList<String>();
        try {
            ZipFile zipFile = new ZipFile(apkPath);
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();
                if (name.endsWith("objects.level.bin")) {
                    result.add(entry.getName());
                }
            }
            zipFile.close();
        } catch (Exception e) {
            // ignore
        }
        levelBinCache.put(apkPath, result);
        return result;
    }

    private static HashSet<String> loadShaderNames(String apkPath) {
        if (shaderNamesCache.containsKey(apkPath)) {
            return shaderNamesCache.get(apkPath);
        }
        HashSet<String> result = new HashSet<String>();
        String entryPath = SkyAssetScanner.findResourceEntry(apkPath, "ShaderDefs.lua");
        if (entryPath != null) {
            byte[] bytes = SkyAssetScanner.readApkEntry(apkPath, entryPath);
            if (bytes != null) {
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                Pattern pattern = Pattern.compile("resource\\s+\"Shader\"\\s+\"([^\"]+)\"");
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    result.add(matcher.group(1));
                }
            }
        }
        shaderNamesCache.put(apkPath, result);
        return result;
    }

    // ===== Helper classes and methods =====

    private static class BinaryString {
        final int offset;
        final String text;

        BinaryString(int offset, String text) {
            this.offset = offset;
            this.text = text;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ===== JSON resource loading =====

    private static JSONArray findAndLoadOutfitDefs(String apkPath) {
        if (outfitDefsCache.containsKey(apkPath)) return outfitDefsCache.get(apkPath);
        String entryPath = SkyAssetScanner.findResourceEntry(apkPath, "OutfitDefs.json");
        if (entryPath == null) {
            outfitDefsCache.put(apkPath, null);
            return null;
        }
        byte[] bytes = SkyAssetScanner.readApkEntry(apkPath, entryPath);
        if (bytes == null) {
            outfitDefsCache.put(apkPath, null);
            return null;
        }
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        try {
            JSONArray arr = new JSONArray(json);
            outfitDefsCache.put(apkPath, arr);
            return arr;
        } catch (org.json.JSONException e) {
            outfitDefsCache.put(apkPath, null);
            return null;
        }
    }

    private static JSONArray findAndLoadPlaceableDefs(String apkPath) {
        if (placeableDefsCache.containsKey(apkPath)) return placeableDefsCache.get(apkPath);
        String entryPath = SkyAssetScanner.findResourceEntry(apkPath, "PlaceableDefs.json");
        if (entryPath == null) {
            placeableDefsCache.put(apkPath, null);
            return null;
        }
        byte[] bytes = SkyAssetScanner.readApkEntry(apkPath, entryPath);
        if (bytes == null) {
            placeableDefsCache.put(apkPath, null);
            return null;
        }
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        try {
            JSONArray arr = new JSONArray(json);
            placeableDefsCache.put(apkPath, arr);
            return arr;
        } catch (org.json.JSONException e) {
            placeableDefsCache.put(apkPath, null);
            return null;
        }
    }

    private static JSONObject findAndLoadImageDefs(String apkPath) {
        if (imageDefsCache.containsKey(apkPath)) return imageDefsCache.get(apkPath);
        String entryPath = SkyAssetScanner.findResourceEntry(apkPath, "ImageDefs.lua");
        if (entryPath == null) {
            imageDefsCache.put(apkPath, null);
            return null;
        }
        byte[] bytes = SkyAssetScanner.readApkEntry(apkPath, entryPath);
        if (bytes == null) {
            imageDefsCache.put(apkPath, null);
            return null;
        }
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        JSONObject result = new JSONObject();

        Pattern pattern = Pattern.compile(
            "resourcedef\\s+\"Image\"\\s+\"([^\"]+)\"\\s*\\{[^}]*source\\s*=\\s*\"([^\"]+)\"",
            Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                result.put(matcher.group(1), matcher.group(2));
            } catch (org.json.JSONException e) {
                // ignore
            }
        }

        imageDefsCache.put(apkPath, result);
        return result;
    }

    private static JSONObject lookupOutfitForMesh(JSONArray outfits, String meshName) {
        for (int i = 0; i < outfits.length(); i++) {
            JSONObject entry = outfits.optJSONObject(i);
            if (entry == null) continue;
            if (meshName.equalsIgnoreCase(getString(entry, "mesh"))) {
                return entry;
            }
        }

        String meshLower = meshName.toLowerCase();
        JSONObject best = null;
        int bestLen = 0;
        for (int i = 0; i < outfits.length(); i++) {
            JSONObject entry = outfits.optJSONObject(i);
            if (entry == null) continue;
            String entryMesh = getString(entry, "mesh");
            if (entryMesh.isEmpty()) continue;
            String em = entryMesh.toLowerCase();
            if (meshLower.equals(em) || meshLower.startsWith(em + "_")) {
                if (em.length() > bestLen) {
                    best = entry;
                    bestLen = em.length();
                }
            }
        }
        return best;
    }

    private static JSONObject lookupPlaceableMaterial(JSONArray placeableDefs, String meshName) {
        String meshLower = meshName.toLowerCase();
        JSONObject best = null;
        int bestLen = 0;
        for (int i = 0; i < placeableDefs.length(); i++) {
            JSONObject entry = placeableDefs.optJSONObject(i);
            if (entry == null) continue;
            String em = getString(entry, "mesh");
            if (em.isEmpty()) continue;
            String emLower = em.toLowerCase();
            if (meshLower.equals(emLower) ||
                meshLower.startsWith(emLower + "_") ||
                meshLower.endsWith("_" + emLower)) {
                if (emLower.length() > bestLen) {
                    best = entry;
                    bestLen = emLower.length();
                }
            }
        }
        return best;
    }

    private static Float lookupPlaceableScale(JSONArray placeableDefs, String meshName) {
        String meshLower = meshName.toLowerCase();
        Float best = null;
        int bestLen = 0;
        for (int i = 0; i < placeableDefs.length(); i++) {
            JSONObject entry = placeableDefs.optJSONObject(i);
            if (entry == null) continue;
            String em = getString(entry, "mesh");
            if (em.isEmpty()) continue;
            String emLower = em.toLowerCase();
            if (meshLower.equals(emLower) ||
                meshLower.startsWith(emLower + "_") ||
                meshLower.endsWith("_" + emLower)) {
                if (emLower.length() <= bestLen) continue;
                if (entry.has("scale")) {
                    JSONArray scale = entry.optJSONArray("scale");
                    if (scale != null && scale.length() > 0) {
                        best = (float) scale.optDouble(0);
                        bestLen = emLower.length();
                    }
                }
            }
        }
        return best;
    }

    private static String getString(JSONObject obj, String key) {
        if (obj.has(key) && obj.opt(key) instanceof String) return obj.optString(key);
        return "";
    }

    private static float getFloat(JSONObject obj, String key, float defaultValue) {
        if (obj.has(key) && obj.opt(key) instanceof Number) return (float) obj.optDouble(key);
        return defaultValue;
    }

    private static String nameWithoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) return fileName.substring(0, dot);
        return fileName;
    }
}
