package com.sky.modelviewer.parsing;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses TGCL-format .bin files (Objects.level.bin).
 * Ported from bintojson.py by 十二 and Miau.
 *
 * Key design:
 * - "resourceName" string property → mesh name
 * - "shaderParams" array → child class instances, each with "uniformName" + "texValue"
 * - Maps u_diffuse1Tex / u_diffuse2Tex texValues to the mesh entry
 * - "transform" 64-byte property → 4x4 matrix
 */
public class LevelFileParser {

    public static class LevelMeshEntry {
        public final String nodeName;
        public final String meshName;
        public final float[] transformMatrix;
        public final float[] position;
        public final String className;
        public final String diffuse1Tex;  // from u_diffuse1Tex
        public final String diffuse2Tex;  // from u_diffuse2Tex
        public final String normalTex;    // from u_normTex
        public final boolean forceWhiteMesh;

        public LevelMeshEntry(String nodeName, String meshName, float[] transformMatrix,
                              float[] position, String className,
                              String diffuse1Tex, String diffuse2Tex, String normalTex) {
            this(nodeName, meshName, transformMatrix, position, className,
                 diffuse1Tex, diffuse2Tex, normalTex, false);
        }

        public LevelMeshEntry(String nodeName, String meshName, float[] transformMatrix,
                              float[] position, String className,
                              String diffuse1Tex, String diffuse2Tex, String normalTex,
                              boolean forceWhiteMesh) {
            this.nodeName = nodeName;
            this.meshName = meshName;
            this.transformMatrix = transformMatrix;
            this.position = position;
            this.className = className;
            this.diffuse1Tex = diffuse1Tex;
            this.diffuse2Tex = diffuse2Tex;
            this.normalTex = normalTex;
            this.forceWhiteMesh = forceWhiteMesh;
        }
    }

    public static class LevelParseResult {
        public final List<LevelMeshEntry> meshEntries;
        public final List<String> allNodeNames;
        public final List<String> allClassNames;
        public final int version;
        public final String error;

        public LevelParseResult(List<LevelMeshEntry> meshEntries, List<String> allNodeNames,
                                List<String> allClassNames, int version, String error) {
            this.meshEntries = meshEntries;
            this.allNodeNames = allNodeNames;
            this.allClassNames = allClassNames;
            this.version = version;
            this.error = error;
        }
    }

    private static class ClassDef {
        int classPropertyNameOffset;
        int classPropertyStartingIndex;
        int classPropertyCount;
        String className = "";
    }

    private static class PropertyDef {
        int propertyType;
        int propertyNameOffset;
        int objectByteSize;
        int arrayIndex;
        String propertyName = "";
    }

    private byte[] data;
    private int offset;

    private List<ClassDef> classes;
    private List<List<PropertyDef>> allProps;
    private List<LevelMeshEntry> meshEntries;
    private List<String> allNodeNames;
    private List<String> allClassNames;

    private LevelFileParser() {}

    public static LevelParseResult parse(byte[] inputData) {
        if (inputData == null || inputData.length < 44) {
            return new LevelParseResult(new ArrayList<LevelMeshEntry>(),
                new ArrayList<String>(), new ArrayList<String>(), 0, "Data too short");
        }

        if (inputData[0] != 'T' || inputData[1] != 'G' || inputData[2] != 'C' || inputData[3] != 'L') {
            return scanRawBinary(inputData);
        }

        try {
            LevelFileParser parser = new LevelFileParser();
            return parser.parseTGCL(inputData);
        } catch (Exception e) {
            return scanRawBinary(inputData);
        }
    }

    private LevelParseResult parseTGCL(byte[] inputData) {
        this.data = inputData;

        int version = readU32(data, 4);
        int classLength = readU32(data, 8);
        int propertyCount = readU32(data, 12);
        int bstNodeCount = readU32(data, 16);
        int objectPtrCount = readU32(data, 20);
        int classOffset = readU32(data, 24);
        int propertyOffset = readU32(data, 28);
        int propertyNameOffset = readU32(data, 32);
        int bstNodeOffset = readU32(data, 36);
        int fileSize = readU32(data, 40);

        if (classOffset <= 0 || classOffset >= data.length ||
            propertyOffset <= 0 || propertyOffset >= data.length ||
            propertyNameOffset <= 0 || propertyNameOffset >= data.length ||
            bstNodeOffset <= 0 || bstNodeOffset >= data.length) {
            return scanRawBinary(data);
        }
        if (classLength > 10000 || bstNodeCount > 100000) {
            return scanRawBinary(data);
        }

        // Read classes
        classes = new ArrayList<ClassDef>();
        for (int i = 0; i < classLength; i++) {
            int basePos = classOffset + i * 12;
            if (basePos + 12 > data.length) break;
            ClassDef cd = new ClassDef();
            cd.classPropertyNameOffset = readU32(data, basePos);
            cd.classPropertyStartingIndex = readU32(data, basePos + 4);
            cd.classPropertyCount = readU32(data, basePos + 8);
            cd.className = readCString(data, propertyNameOffset + cd.classPropertyNameOffset);
            classes.add(cd);
        }

        // Read all properties per class
        allProps = new ArrayList<List<PropertyDef>>();
        for (int ci = 0; ci < classes.size(); ci++) {
            ClassDef cls = classes.get(ci);
            List<PropertyDef> props = new ArrayList<PropertyDef>();
            if (cls.classPropertyCount > 0 && cls.classPropertyCount < 100000) {
                for (int j = 0; j < cls.classPropertyCount; j++) {
                    int pIdx = cls.classPropertyStartingIndex + j;
                    int pPos = propertyOffset + pIdx * 16;
                    if (pPos + 16 > data.length) break;
                    PropertyDef pd = new PropertyDef();
                    pd.propertyType = readU32(data, pPos);
                    pd.propertyNameOffset = readU32(data, pPos + 4);
                    pd.objectByteSize = readU32(data, pPos + 8);
                    pd.arrayIndex = readU32(data, pPos + 12);
                    pd.propertyName = readCString(data, propertyNameOffset + pd.propertyNameOffset);
                    props.add(pd);
                }
            }
            allProps.add(props);
        }

        meshEntries = new ArrayList<LevelMeshEntry>();
        allNodeNames = new ArrayList<String>();
        allClassNames = new ArrayList<String>();

        // Read BSTNodes sequentially
        offset = bstNodeOffset;
        for (int i = 0; i < bstNodeCount; i++) {
            if (offset + 4 > data.length) break;

            int classIndex = readU32At();
            offset += 4;

            String nodeName = readCStringAt();
            allNodeNames.add(nodeName);

            if (classIndex >= 0 && classIndex < classes.size()) {
                String className = classes.get(classIndex).className;
                allClassNames.add(className);

                // Each BSTNode gets its own texture collection
                List<String[]> shaderTexPairs = new ArrayList<String[]>();
                readClassData(classIndex, nodeName, className, shaderTexPairs);
            } else {
                allClassNames.add("");
            }
        }

        return new LevelParseResult(meshEntries, allNodeNames, allClassNames, version, null);
    }

    /**
     * Skip-read a class instance: advances offset past all properties without
     * creating mesh entries or reading string values. Used for Npc
     * to skip sub-classes that we don't need.
     */
    private void readClassDataSkip(int classIndex) {
        if (classIndex < 0 || classIndex >= allProps.size()) return;
        List<PropertyDef> props = allProps.get(classIndex);

        for (PropertyDef prop : props) {
            if (offset >= data.length) break;
            try {
                int ptype = prop.propertyType;
                int psize = prop.objectByteSize;

                if (ptype == 0) {
                    offset += Math.max(psize, 1);
                } else if (ptype == 1) {
                    readCStringAt(); // advance past string
                } else if (ptype == 2) {
                    offset += 4;
                } else if (ptype == 3) {
                    if (offset + 4 > data.length) break;
                    int count = readU32At();
                    count = Math.min(count, 100000);
                    offset += 4;
                    if (prop.arrayIndex != 0xFFFFFFFF && prop.arrayIndex < allProps.size()) {
                        for (int j = 0; j < count; j++) {
                            if (offset >= data.length) break;
                            readClassDataSkip(prop.arrayIndex);
                        }
                    } else {
                        offset += count * 4;
                    }
                } else {
                    offset += Math.max(psize, 4);
                }
            } catch (Exception e) {
                break;
            }
        }
    }

    /**
     * Reads property data for a class sequentially.
     *
     * - "resourceName" string → mesh name
     * - "transform" 64-byte → 4x4 matrix
     * - "shaderParams" array → recursive child class instances
     *   Each child has "uniformName" + "texValue" strings
     *   Pairs are collected in outShaderTexPairs
     *
     * After reading all properties, if resourceName was found,
     * creates a mesh entry with associated textures.
     */
    private void readClassData(int classIndex, String nodeName, String className,
                               List<String[]> outShaderTexPairs) {
        if (classIndex < 0 || classIndex >= allProps.size()) return;

        List<PropertyDef> props = allProps.get(classIndex);

        String resourceName = null;
        float[] transformMatrix = null;
        float[] position = null;
        int meshTypeValue = -1;

        // For NPC: collect body part mesh names
        String npcBody = null, npcHat = null, npcHair = null, npcMask = null;
        String npcHorn = null, npcWing = null, npcNeck = null, npcFeet = null, npcProp = null;

        // For shaderParams entries: track uniformName + texValue
        String currentUniformName = null;
        String currentTexValue = null;

        for (PropertyDef prop : props) {
            if (offset >= data.length) break;

            try {
                int ptype = prop.propertyType;
                int psize = prop.objectByteSize;
                String pname = prop.propertyName;

                if (ptype == 0) {
                    // General value type
                    if (psize == 1) {
                        if (pname.equals("meshType") && offset < data.length) {
                            meshTypeValue = data[offset] & 0xFF;
                        }
                        offset += 1;
                    } else if (psize == 2) {
                        offset += 2;
                    } else if (psize == 4) {
                        offset += 4;
                    } else if (psize == 8) {
                        offset += 8;
                    } else if (psize == 10) {
                        offset += 10;
                    } else if (psize == 12) {
                        // Vec3
                        if (offset + 12 <= data.length) {
                            float[] floats = readFloats(data, offset, 3);
                            String plower = pname.toLowerCase();
                            if (plower.contains("pos") || plower.contains("loc")) {
                                position = floats;
                            }
                        }
                        offset += 12;
                    } else if (psize == 16) {
                        // Vec4
                        if (offset + 16 <= data.length) {
                            float[] floats = readFloats(data, offset, 4);
                            String plower = pname.toLowerCase();
                            if (plower.contains("pos") || plower.contains("loc")) {
                                position = new float[]{floats[0], floats[1], floats[2]};
                            }
                        }
                        offset += 16;
                    } else if (psize == 64) {
                        // 4x4 transform matrix — already in OpenGL column-major format
                        // Translation is at flat indices [12,13,14] (last column, first 3 rows)
                        if (offset + 64 <= data.length) {
                            float[] matrix = readFloats(data, offset, 16);
                            // Validate: last element of each row group should be [0,0,0,1]
                            if (Math.abs(matrix[3]) < 0.01f &&
                                Math.abs(matrix[7]) < 0.01f &&
                                Math.abs(matrix[11]) < 0.01f &&
                                Math.abs(matrix[15] - 1f) < 0.01f) {
                                transformMatrix = matrix;
                            }
                        }
                        offset += 64;
                    } else {
                        offset += Math.max(psize, 4);
                    }
                } else if (ptype == 1) {
                    // String type
                    String strValue = readCStringAt();
                    String trimmed = strValue.trim();

                    if (pname.equals("resourceName")) {
                        // This is the mesh name (standard mesh nodes)
                        resourceName = trimmed;
                    } else if (pname.equals("meshName")) {
                        // BstNode entries use "meshName" instead of "resourceName"
                        if (resourceName == null || resourceName.isEmpty()) {
                            resourceName = trimmed;
                        }
                    } else if (pname.equals("uniformName")) {
                        // ShaderParams: uniform name (e.g., "u_diffuse1Tex")
                        currentUniformName = trimmed;
                    } else if (pname.equals("texValue")) {
                        // ShaderParams: texture name
                        currentTexValue = trimmed;
                    } else if (!trimmed.isEmpty()) {
                        // NPC body parts: capture mesh name values
                        if (pname.equals("body")) npcBody = trimmed;
                        else if (pname.equals("hat")) npcHat = trimmed;
                        else if (pname.equals("hair")) npcHair = trimmed;
                        else if (pname.equals("mask")) npcMask = trimmed;
                        else if (pname.equals("horn")) npcHorn = trimmed;
                        else if (pname.equals("wing")) npcWing = trimmed;
                        else if (pname.equals("neck")) npcNeck = trimmed;
                        else if (pname.equals("feet")) npcFeet = trimmed;
                        else if (pname.equals("prop")) npcProp = trimmed;
                    }
                    // Other strings are ignored (not mesh names)
                } else if (ptype == 2) {
                    // Clump reference (4 bytes)
                    offset += 4;
                } else if (ptype == 3) {
                    // Array type
                    if (offset + 4 > data.length) break;
                    int count = readU32At();
                    count = Math.min(count, 100000);
                    offset += 4;

                    if (prop.arrayIndex != 0xFFFFFFFF && prop.arrayIndex < allProps.size()) {
                        // Array of class instances — recursively read each
                        String childClassName = classes.get(prop.arrayIndex).className;
                        // Skip sub-class reading for Npc — only need transform + costume strings
                        if ("Npc".equals(className)) {
                            // Skip: just advance offset past the array data
                            // We need to estimate the size — read each child to advance offset
                            // But we can't know size without reading, so skip by reading with a dummy
                            for (int j = 0; j < count; j++) {
                                if (offset >= data.length) break;
                                readClassDataSkip(prop.arrayIndex);
                            }
                        } else {
                            for (int j = 0; j < count; j++) {
                                if (offset >= data.length) break;
                                readClassData(prop.arrayIndex, nodeName, childClassName, outShaderTexPairs);
                            }
                        }
                    } else {
                        // Array of simple values (4 bytes each)
                        offset += count * 4;
                    }
                } else {
                    offset += Math.max(psize, 4);
                }
            } catch (Exception e) {
                break;
            }
        }

        // If this was a shaderParams entry (has uniformName + texValue), record the pair
        if (currentUniformName != null && currentTexValue != null && !currentTexValue.isEmpty()) {
            outShaderTexPairs.add(new String[]{currentUniformName, currentTexValue});
        }

        // CandleObject: map meshType to mesh name
        if ("CandleObject".equals(className) && transformMatrix != null) {
            String candleMesh;
            if (meshTypeValue == 8) {
                candleMesh = "CandleTreasure_01";
            } else if (meshTypeValue == 9) {
                candleMesh = "CandleTreasure_02";
            } else {
                candleMesh = "Candles_02";
            }
            if (position == null) {
                position = new float[]{transformMatrix[12], transformMatrix[13], transformMatrix[14]};
            }
            meshEntries.add(new LevelMeshEntry(nodeName, candleMesh,
                transformMatrix, position, className,
                null, null, null, true));
        }

        // WingBuff: use WingBuffChild_05
        if ("WingBuff".equals(className) && transformMatrix != null) {
            if (position == null) {
                position = new float[]{transformMatrix[12], transformMatrix[13], transformMatrix[14]};
            }
            meshEntries.add(new LevelMeshEntry(nodeName, "WingBuffChild_05",
                transformMatrix, position, className,
                null, null, null, true));
        }

        // Npc: create mesh entries for each non-empty body part
        // NPC auto-scale to 30% (0.6 base * 0.5 additional) — apply scale to the rotation part of the transform
        if ("Npc".equals(className) && transformMatrix != null) {
            if (position == null) {
                position = new float[]{transformMatrix[12], transformMatrix[13], transformMatrix[14]};
            }
            // Scale the rotation part (first 12 elements) by 0.3, keep translation
            float[] npcTransform = new float[16];
            System.arraycopy(transformMatrix, 0, npcTransform, 0, 16);
            float npcScale = 0.3f;
            for (int t = 0; t < 12; t++) {
                npcTransform[t] *= npcScale;
            }
            npcTransform[3] = 0; npcTransform[7] = 0; npcTransform[11] = 0; npcTransform[15] = 1;

            // Order: body, hat, hair, mask, horn, wing, neck, feet, prop
            String[] parts = {npcBody, npcHat, npcHair, npcMask, npcHorn, npcWing, npcNeck, npcFeet, npcProp};
            String[] partNames = {"body", "hat", "hair", "mask", "horn", "wing", "neck", "feet", "prop"};
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] != null && !parts[i].isEmpty()) {
                    meshEntries.add(new LevelMeshEntry(
                        nodeName + "_npc_" + partNames[i], parts[i],
                        npcTransform, position, "Npc",
                        null, null, null, true));
                }
            }
        }

        // If this was a mesh node (has resourceName), create mesh entry
        if (resourceName != null && !resourceName.isEmpty()) {
            if (position == null && transformMatrix != null) {
                position = new float[]{transformMatrix[12], transformMatrix[13], transformMatrix[14]};
            }

            // Extract textures from shaderParams pairs
            String diffuse1Tex = null;
            String diffuse2Tex = null;
            String normalTex = null;
            for (String[] pair : outShaderTexPairs) {
                if (pair[0].equals("u_diffuse1Tex") && diffuse1Tex == null) {
                    diffuse1Tex = pair[1];
                } else if (pair[0].equals("u_diffuse2Tex") && diffuse2Tex == null) {
                    diffuse2Tex = pair[1];
                } else if (pair[0].equals("u_normTex") && normalTex == null) {
                    normalTex = pair[1];
                }
            }

            String meshName = cleanMeshName(resourceName);
            meshEntries.add(new LevelMeshEntry(nodeName, meshName,
                transformMatrix, position, className,
                diffuse1Tex, diffuse2Tex, normalTex));
        }
    }

    private static String cleanMeshName(String name) {
        String meshName = name;
        if (meshName.toLowerCase().endsWith(".mesh")) {
            meshName = meshName.substring(0, meshName.length() - 5);
        }
        if (meshName.toLowerCase().endsWith(".meshes")) {
            meshName = meshName.substring(0, meshName.length() - 7);
        }
        int lastSlash = meshName.lastIndexOf('/');
        if (lastSlash >= 0) {
            meshName = meshName.substring(lastSlash + 1);
        }
        int lastBackslash = meshName.lastIndexOf('\\');
        if (lastBackslash >= 0) {
            meshName = meshName.substring(lastBackslash + 1);
        }
        return meshName;
    }

    private static LevelParseResult scanRawBinary(byte[] data) {
        List<LevelMeshEntry> meshEntries = new ArrayList<LevelMeshEntry>();
        List<String> allNodeNames = new ArrayList<String>();
        List<String> allClassNames = new ArrayList<String>();

        List<int[]> stringRanges = new ArrayList<int[]>();
        int i = 0;
        while (i < data.length) {
            if (data[i] >= 32 && data[i] <= 126) {
                int start = i;
                while (i < data.length && data[i] >= 32 && data[i] <= 126) i++;
                if (i - start >= 4) {
                    stringRanges.add(new int[]{start, i});
                }
            }
            i++;
        }

        for (int[] range : stringRanges) {
            String str = new String(data, range[0], range[1] - range[0]);
            String lower = str.toLowerCase();

            boolean isMesh = false;
            String meshName = str;

            if (lower.endsWith(".mesh")) {
                isMesh = true;
                meshName = str.substring(0, str.length() - 5);
                int lastSlash = meshName.lastIndexOf('/');
                if (lastSlash >= 0) meshName = meshName.substring(lastSlash + 1);
            } else if (lower.contains("mesh") && str.length() < 100 &&
                       !lower.contains("shader") && !lower.contains("level") &&
                       !lower.contains("meshes/bin")) {
                isMesh = true;
            }

            if (!isMesh) continue;

            allNodeNames.add(meshName);

            float[] transform = findNearbyMatrix(data, range[0], 1024);
            float[] position = null;
            if (transform != null) {
                position = new float[]{transform[12], transform[13], transform[14]};
            }

            meshEntries.add(new LevelMeshEntry(meshName, meshName, transform, position,
                "ScannedMesh", null, null, null));
        }

        return new LevelParseResult(meshEntries, allNodeNames, allClassNames, 0, "raw_scan");
    }

    private static float[] findNearbyMatrix(byte[] data, int centerOffset, int searchRange) {
        int start = Math.max(0, centerOffset - searchRange);
        int end = Math.min(data.length - 64, centerOffset + searchRange);

        for (int offset = start; offset <= end; offset += 4) {
            if (offset + 64 > data.length) break;

            float[] matrix = new float[16];
            boolean valid = true;
            for (int j = 0; j < 16; j++) {
                int p = offset + j * 4;
                if (p + 4 > data.length) { valid = false; break; }
                int bits = (data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8) |
                           ((data[p + 2] & 0xFF) << 16) | ((data[p + 3] & 0xFF) << 24);
                matrix[j] = Float.intBitsToFloat(bits);
                if (Float.isNaN(matrix[j]) || Float.isInfinite(matrix[j])) {
                    valid = false;
                    break;
                }
            }

            if (!valid) continue;

            if (Math.abs(matrix[3]) > 0.01f || Math.abs(matrix[7]) > 0.01f ||
                Math.abs(matrix[11]) > 0.01f || Math.abs(matrix[15] - 1f) > 0.01f) {
                continue;
            }

            float col0Len = (float) Math.sqrt(matrix[0]*matrix[0] + matrix[1]*matrix[1] + matrix[2]*matrix[2]);
            float col1Len = (float) Math.sqrt(matrix[4]*matrix[4] + matrix[5]*matrix[5] + matrix[6]*matrix[6]);
            float col2Len = (float) Math.sqrt(matrix[8]*matrix[8] + matrix[9]*matrix[9] + matrix[10]*matrix[10]);

            if (Math.abs(col0Len - 1.0f) < 0.3f && Math.abs(col1Len - 1.0f) < 0.3f &&
                Math.abs(col2Len - 1.0f) < 0.3f) {
                if (Math.abs(matrix[12]) < 100000 && Math.abs(matrix[13]) < 100000 &&
                    Math.abs(matrix[14]) < 100000) {
                    // Matrix is already in OpenGL column-major format, use as-is
                    return matrix;
                }
            }
        }

        return null;
    }

    // === Utility methods ===

    private int readU32At() {
        if (offset + 4 > data.length) return 0;
        int val = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) |
                  ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
        return val;
    }

    private String readCStringAt() {
        int start = offset;
        while (offset < data.length && data[offset] != 0) offset++;
        String result = "";
        if (offset > start) {
            result = new String(data, start, offset - start);
        }
        if (offset < data.length) offset++;
        return result;
    }

    private static int readU32(byte[] data, int offset) {
        if (offset + 4 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) |
               ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static float[] readFloats(byte[] data, int offset, int count) {
        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            int p = offset + i * 4;
            if (p + 4 > data.length) break;
            int bits = (data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8) |
                       ((data[p + 2] & 0xFF) << 16) | ((data[p + 3] & 0xFF) << 24);
            result[i] = Float.intBitsToFloat(bits);
        }
        return result;
    }

    private static String readCString(byte[] data, int offset) {
        if (offset < 0 || offset >= data.length) return "";
        int end = offset;
        while (end < data.length && data[end] != 0) end++;
        if (end > offset) {
            return new String(data, offset, end - offset);
        }
        return "";
    }
}
