package com.sky.modelviewer.scanner;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import com.sky.modelviewer.model.MeshCatalogEntry;
import com.sky.modelviewer.model.ScanResult;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

public class SkyAssetScanner {

    private SkyAssetScanner() {}

    public static class AppEntry {
        public final String packageName;
        public final String label;
        public final String apkPath;

        public AppEntry(String packageName, String label, String apkPath) {
            this.packageName = packageName;
            this.label = label;
            this.apkPath = apkPath;
        }
    }

    public static List<AppEntry> findSkyApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<AppEntry> result = new ArrayList<>();
        for (ApplicationInfo appInfo : packages) {
            if (appInfo.packageName.toLowerCase().contains("sky")) {
                String label = pm.getApplicationLabel(appInfo).toString();
                result.add(new AppEntry(appInfo.packageName, label, appInfo.sourceDir));
            }
        }
        Collections.sort(result, new java.util.Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.toLowerCase().compareTo(b.label.toLowerCase());
            }
        });
        return result;
    }

    public static ScanResult scanApk(String apkPath) throws Exception {
        ZipFile zipFile = new ZipFile(apkPath);
        List<MeshCatalogEntry> meshes = new ArrayList<>();
        String meshPrefix = "assets/Data/Meshes/Bin/";
        String levelPrefix = "assets/Data/Levels/";
        String levelFileName = "Objects.level.bin";

        java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            String nameLower = name.toLowerCase();

            // Scan mesh files
            if (nameLower.startsWith(meshPrefix.toLowerCase()) &&
                nameLower.endsWith(".mesh")) {
                String relativePath = name.substring("assets/".length());
                String meshName = nameWithoutExtension(new File(name).getName());

                meshes.add(new MeshCatalogEntry(
                    meshName, name, relativePath,
                    inferCategory(relativePath, meshName), "mesh"
                ));
            }

            // Scan level files: assets/Data/Levels/<subfolder>/Objects.level.bin
            if (nameLower.startsWith(levelPrefix.toLowerCase()) &&
                nameLower.endsWith(levelFileName.toLowerCase())) {
                // Extract the subfolder name as the level name
                // e.g. "assets/Data/Levels/Prairie/Objects.level.bin" -> "Prairie"
                String afterPrefix = name.substring(levelPrefix.length());
                int slashIdx = afterPrefix.indexOf('/');
                String levelName;
                if (slashIdx > 0) {
                    levelName = afterPrefix.substring(0, slashIdx);
                } else {
                    levelName = nameWithoutExtension(new File(name).getName());
                }

                String relativePath = name.substring("assets/".length());
                meshes.add(new MeshCatalogEntry(
                    "[Level] " + levelName, name, relativePath,
                    "Level", "level"
                ));
            }

            // Scan KTX texture files: assets/Data/Images/Bin/ETC2/
            String ktxPrefix = "assets/Data/Images/Bin/ETC2/";
            if (nameLower.startsWith(ktxPrefix.toLowerCase()) &&
                nameLower.endsWith(".ktx")) {
                String relativePath = name.substring("assets/".length());
                String ktxName = nameWithoutExtension(new File(name).getName());

                meshes.add(new MeshCatalogEntry(
                    ktxName, name, relativePath,
                    "Texture", "ktx"
                ));
            }

            // Scan animpack files: anywhere under assets/Data/Meshes/Bin/
            if (nameLower.endsWith(".animpack")) {
                String relativePath = name.substring("assets/".length());
                String animName = nameWithoutExtension(new File(name).getName());

                meshes.add(new MeshCatalogEntry(
                    animName, name, relativePath,
                    "Animation", "animpack"
                ));
            }
        }

        zipFile.close();

        Collections.sort(meshes, new java.util.Comparator<MeshCatalogEntry>() {
            @Override
            public int compare(MeshCatalogEntry a, MeshCatalogEntry b) {
                return a.relativePath.toLowerCase().compareTo(b.relativePath.toLowerCase());
            }
        });

        return new ScanResult(apkPath, meshes);
    }

    public static boolean isValidSkyApk(String apkPath) {
        try {
            ZipFile zipFile = new ZipFile(apkPath);
            String meshPrefix = "assets/Data/Meshes/Bin/";
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            boolean found = false;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().toLowerCase().startsWith(meshPrefix.toLowerCase())) {
                    found = true;
                    break;
                }
            }
            zipFile.close();
            return found;
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] readApkEntry(String apkPath, String entryName) {
        try {
            ZipFile zipFile = new ZipFile(apkPath);
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                zipFile.close();
                return null;
            }
            java.io.InputStream input = zipFile.getInputStream(entry);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            byte[] bytes = baos.toByteArray();
            input.close();
            zipFile.close();
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Read multiple entries from APK in a single ZipFile open — much faster than
     * calling readApkEntry repeatedly.
     * @param apkPath APK file path
     * @param entryNames list of entry paths to read
     * @return map of entryName -> byte[], only for entries that were found
     */
    public static java.util.Map<String, byte[]> readMultipleApkEntries(
            String apkPath, java.util.Collection<String> entryNames) {
        java.util.Map<String, byte[]> result = new java.util.HashMap<String, byte[]>();
        if (entryNames == null || entryNames.isEmpty()) return result;
        try {
            ZipFile zipFile = new ZipFile(apkPath);
            try {
                for (String entryName : entryNames) {
                    ZipEntry entry = zipFile.getEntry(entryName);
                    if (entry == null) continue;
                    java.io.InputStream input = zipFile.getInputStream(entry);
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = input.read(buf)) > 0) {
                        baos.write(buf, 0, n);
                    }
                    result.put(entryName, baos.toByteArray());
                    input.close();
                }
            } finally {
                zipFile.close();
            }
        } catch (Exception e) {
            // ignore
        }
        return result;
    }

    public static List<String> listTextures(String apkPath) {
        List<String> result = new ArrayList<>();
        try {
            ZipFile zipFile = new ZipFile(apkPath);
            String texPrefix = "assets/Data/Images/Bin/ETC2/";
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().toLowerCase().startsWith(texPrefix.toLowerCase()) &&
                    entry.getName().toLowerCase().endsWith(".ktx")) {
                    result.add(entry.getName());
                }
            }
            zipFile.close();
        } catch (Exception e) {
            // ignore
        }
        return result;
    }

    public static String findTextureEntry(String apkPath, String textureName) {
        if (textureName == null || textureName.trim().isEmpty()) return null;

        try {
            ZipFile zipFile = new ZipFile(apkPath);
            String texPrefix = "assets/Data/Images/Bin/ETC2/";

            List<String> candidates = new ArrayList<>();
            String normalized = textureName.trim().replace('"', ' ').trim().replace('\\', '/');
            while (normalized.startsWith("/")) normalized = normalized.substring(1);

            candidates.add(normalized);
            String withoutExt = nameWithoutExtension(new File(normalized).getName());
            candidates.add(withoutExt);
            candidates.add(new File(withoutExt).getName());

            String[] prefixes = {"data/images/bin/etc2/", "data/images/bin/", "data/images/", "images/bin/etc2/", "images/bin/", "images/", "bin/etc2/"};
            for (String prefix : prefixes) {
                if (normalized.toLowerCase().startsWith(prefix)) {
                    candidates.add(normalized.substring(prefix.length()));
                }
            }

            List<String> distinctCandidates = new ArrayList<>();
            for (String c : candidates) {
                if (!distinctCandidates.contains(c)) distinctCandidates.add(c);
            }

            String[] exts = {".ktx", ".png", ".jpg", ".jpeg"};
            for (String candidate : distinctCandidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                String baseName = nameWithoutExtension(new File(candidate).getName());

                for (String ext : exts) {
                    String fullPath = texPrefix + baseName + ext;
                    if (zipFile.getEntry(fullPath) != null) {
                        zipFile.close();
                        return fullPath;
                    }
                }
            }

            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            String baseName = nameWithoutExtension(new File(textureName).getName());
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().toLowerCase().startsWith(texPrefix.toLowerCase())) {
                    String entryBase = nameWithoutExtension(new File(entry.getName()).getName());
                    if (entryBase.equalsIgnoreCase(baseName)) {
                        zipFile.close();
                        return entry.getName();
                    }
                }
            }

            zipFile.close();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build a global texture index from ALL .ktx files in the APK.
     * Ported from HTML buildTexIndex (line 36814-36822):
     *   Scans every .ktx entry (not just ETC2 folder), key = lowercase base name (no path, no ext).
     * This is critical because game textures may live in various subfolders,
     * and the TGCL shaderParams diffuseTex is just a bare name like "DawnTempleRock01".
     */
    public static java.util.HashMap<String, String> buildTextureIndex(String apkPath) {
        java.util.HashMap<String, String> index = new java.util.HashMap<String, String>();
        try {
            ZipFile zipFile = new ZipFile(apkPath);
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String nl = name.toLowerCase();
                if (nl.endsWith(".ktx")) {
                    // HTML line 37463: base = nl.substring(nl.lastIndexOf('/') + 1).replace(/\.ktx$/, '')
                    int slash = nl.lastIndexOf('/');
                    String base = slash >= 0 ? nl.substring(slash + 1) : nl;
                    // Fix: only strip trailing .ktx, not all occurrences (was: base.replace(".ktx", ""))
                    if (base.endsWith(".ktx")) base = base.substring(0, base.length() - 4);
                    if (!index.containsKey(base)) {
                        index.put(base, name);
                    }
                }
            }
            zipFile.close();
        } catch (Exception e) {
            // ignore
        }
        return index;
    }

    /**
     * Look up a texture by name in the pre-built index.
     * EXACT match to HTML loadTexture (line 37636-37637):
     *   const key = texName.toLowerCase();
     *   const entry = texIndex.get(key);
     * No path/extension stripping — HTML doesn't do it, so we don't either.
     * @param index the texture index from buildTextureIndex
     * @param textureName bare texture name (e.g. "DawnTempleRock01")
     * @return full APK entry path, or null if not found
     */
    public static String findTextureByIndex(java.util.HashMap<String, String> index, String textureName) {
        if (textureName == null || textureName.trim().isEmpty()) return null;
        // Match HTML exactly: just lowercase, direct lookup
        String key = textureName.trim().toLowerCase();
        return index.get(key);
    }

    public static String findResourceEntry(String apkPath, String resourceName) {
        try {
            ZipFile zipFile = new ZipFile(apkPath);
            String resourcePath = "assets/Data/Resources/" + resourceName;
            if (zipFile.getEntry(resourcePath) != null) {
                zipFile.close();
                return resourcePath;
            }

            String altPath = "assets/initial/Data/Resources/" + resourceName;
            if (zipFile.getEntry(altPath) != null) {
                zipFile.close();
                return altPath;
            }

            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().toLowerCase().endsWith("/" + resourceName.toLowerCase())) {
                    zipFile.close();
                    return entry.getName();
                }
            }

            zipFile.close();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> findMeshesFilesInLevel(String apkPath, String levelBinPath) {
        List<String> result = new ArrayList<>();
        try {
            ZipFile zipFile = new ZipFile(apkPath);
            int lastSlash = levelBinPath.lastIndexOf('/');
            String levelDir = lastSlash >= 0 ? levelBinPath.substring(0, lastSlash) : "";

            // Search for ALL .meshes files in the level directory
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                // Match .meshes files in this level directory
                if (name.endsWith(".meshes")) {
                    // Check if it's in the same level directory
                    String lowerName = name.toLowerCase();
                    String lowerDir = levelDir.toLowerCase();
                    if (lowerName.startsWith(lowerDir + "/") || lowerName.startsWith(lowerDir + "\\")) {
                        result.add(name);
                    }
                }
            }

            // Fallback: if nothing found in level dir, search entire APK for BstBaked.meshes
            if (result.isEmpty()) {
                entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName().endsWith("BstBaked.meshes") ||
                        e.getName().endsWith(".meshes")) {
                        result.add(e.getName());
                    }
                }
            }

            zipFile.close();
        } catch (Exception e) {
            // ignore
        }
        return result;
    }

    private static String inferCategory(String relativePath, String meshName) {
        String lowerPath = relativePath.toLowerCase();
        String lowerName = meshName.toLowerCase();

        if (lowerName.startsWith("body_") || lowerName.startsWith("hair_") || lowerName.startsWith("cape_")) {
            return "Character";
        }
        if (lowerName.startsWith("char") || lowerPath.contains("/meshes/data/meshes/bin/")) {
            return "Character";
        }
        if (lowerPath.contains("/levels/")) {
            return "Level";
        }
        if (lowerName.startsWith("prop_") || lowerName.startsWith("furn_") || lowerName.startsWith("bonfire")) {
            return "Prop";
        }
        return "Other";
    }

    private static String nameWithoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) return fileName.substring(0, dot);
        return fileName;
    }
}
