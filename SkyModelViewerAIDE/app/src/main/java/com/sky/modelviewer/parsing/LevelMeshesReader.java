package com.sky.modelviewer.parsing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import android.util.Log;

/**
 * Parser for .meshes files (LVL0 format).
 * Ported from meshes2obj_json.py + bstbake_unpack.py.
 *
 * Supports ALL versions 0x34-0x3D:
 *   - Version >= 0x39 (v57): GEO0 is a separate TOC entry, meshopt-compressed vertices
 *   - Version <  0x39 (v52-v56): Terrain in LOD0 (LZ4 compressed), raw 36-byte vertices
 *
 * 36-byte vertex layout:
 *   offset 0:  position (3xf32)
 *   offset 12: normal (3xsnorm + 1 padding byte)
 *   offset 16: material_ids (4xu8)
 *   offset 20: material_weights (4xu8)
 *   offset 24: v_color (4xu8)
 *   offset 28: gi_light (4xu8)
 *   offset 32: meta_id (u32)
 *
 * GEO0 structure:
 *   5xu32 counts (index_count, vertex_count, chunk_count, cloud_chunk_count, subchunk_count)
 *   u32 compressed_size + meshopt compressed vertices
 *   index_count bytes of u8 local indices
 *   (chunk_count + cloud_chunk_count) * 56 bytes chunks
 *   subchunk_count * 8 bytes subchunks
 *
 * Chunk (56 bytes):
 *   idx_start(u32) + vtx_start(u32) + subchunk_start(u32) +
 *   idx_count(u16) + vtx_count(u8) + subchunk_count(u8) +
 *   min(3xf32) + max(3xf32) + pad(4xu32)
 *
 * Global index = vtx_start + local_indices[idx_start + j]
 */
public class LevelMeshesReader {

    private static final String TAG = "LevelMeshesReader";

    // File header
    private static final int LVL0_MAGIC = 0x304C564C; // "LVL0" LE
    private static final int MIN_FILE_SIZE = 12;
    private static final int TOC_ENTRY_START = 12; // 0x0C
    private static final int TOC_ENTRY_SIZE = 12;  // name(4) + offset(4) + length(4)

    // Geometry
    private static final int VERTEX_SIZE = 36;
    private static final int CHUNK_SIZE = 56;
    private static final int SUBCHUNK_SIZE = 8;

    // Version threshold
    private static final int VERSION_LEGACY_THRESHOLD = 0x39; // v57

    // Supported versions
    private static final int[] SUPPORTED_VERSIONS = {
        0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D
    };

    public static class MeshesData {
        public float[] positions;
        public float[] normals;
        public float[] colors;   // RGBA per vertex
        public int[] indices;
        public int[] cloudIndices; // Cloud chunk indices (separate from terrain)
        public int vertexCount;
        public int indexCount;
        public int chunkCount;
        public int cloudCount;
        public float[] boundsMin;   // All vertices range (may include unused vertices)
        public float[] boundsMax;
        // Render bounding box: only vertices referenced by terrain indices (tight framing)
        // Ported from HTML parseGeo0 line 35210-35211
        public float[] renderMin;
        public float[] renderMax;
        public String info;
        public int fileVersion;
        public int meshoptVersion; // 0, 1, or -1 (legacy raw)
    }

    // kMaterial enum → RGB color
    private static final float[][] MATERIAL_COLORS = new float[256][3];
    static {
        for (int i = 0; i < 256; i++) {
            MATERIAL_COLORS[i] = new float[]{0.7f, 0.7f, 0.7f};
        }
        MATERIAL_COLORS[0] = new float[]{0.5f, 0.5f, 0.5f};     // None
        MATERIAL_COLORS[2] = new float[]{0.6f, 0.7f, 0.8f};     // Transparent
        MATERIAL_COLORS[3] = new float[]{0.2f, 0.2f, 0.2f};     // Void
        MATERIAL_COLORS[4] = new float[]{0.9f, 0.8f, 0.6f};     // Particle
        MATERIAL_COLORS[5] = new float[]{0.6f, 0.45f, 0.3f};    // WoodSlippery
        MATERIAL_COLORS[6] = new float[]{0.3f, 0.3f, 0.3f};     // VoidMinor
        MATERIAL_COLORS[7] = new float[]{0.65f, 0.5f, 0.35f};   // WoodPlank
        MATERIAL_COLORS[16] = new float[]{0.55f, 0.5f, 0.45f};  // Cliff
        MATERIAL_COLORS[17] = new float[]{0.6f, 0.5f, 0.35f};   // Soil
        MATERIAL_COLORS[18] = new float[]{0.65f, 0.6f, 0.5f};   // CliffLight
        MATERIAL_COLORS[19] = new float[]{0.4f, 0.35f, 0.3f};   // WallDamaged
        MATERIAL_COLORS[20] = new float[]{0.5f, 0.5f, 0.55f};   // Wall
        MATERIAL_COLORS[21] = new float[]{0.9f, 0.8f, 0.3f};    // Gold
        MATERIAL_COLORS[22] = new float[]{0.7f, 0.85f, 0.95f};  // Glacier
        MATERIAL_COLORS[23] = new float[]{0.8f, 0.8f, 0.85f};   // TileCeiling
        MATERIAL_COLORS[24] = new float[]{0.75f, 0.75f, 0.8f};  // TileFloor
        MATERIAL_COLORS[25] = new float[]{0.7f, 0.7f, 0.75f};   // TileWall
        MATERIAL_COLORS[26] = new float[]{0.6f, 0.45f, 0.35f};  // WallBrick
        MATERIAL_COLORS[27] = new float[]{0.4f, 0.35f, 0.25f};  // SoilWet
        MATERIAL_COLORS[28] = new float[]{0.35f, 0.35f, 0.35f}; // CliffWet
        MATERIAL_COLORS[29] = new float[]{0.85f, 0.85f, 0.8f};  // Bone
        MATERIAL_COLORS[30] = new float[]{0.6f, 0.45f, 0.3f};   // Wood
        MATERIAL_COLORS[31] = new float[]{0.8f, 0.7f, 0.6f};    // Ceramics
        MATERIAL_COLORS[32] = new float[]{0.85f, 0.78f, 0.55f}; // Sand
        MATERIAL_COLORS[33] = new float[]{0.7f, 0.65f, 0.45f};  // SandWet
        MATERIAL_COLORS[34] = new float[]{0.9f, 0.85f, 0.65f};  // SandLight
        MATERIAL_COLORS[35] = new float[]{0.95f, 0.95f, 0.98f}; // Snow
        MATERIAL_COLORS[36] = new float[]{0.75f, 0.68f, 0.45f}; // SandDeep
        MATERIAL_COLORS[37] = new float[]{0.45f, 0.4f, 0.3f};   // Mud
        MATERIAL_COLORS[48] = new float[]{0.4f, 0.6f, 0.3f};    // Grass
        MATERIAL_COLORS[49] = new float[]{0.3f, 0.5f, 0.25f};   // GrassWet
        MATERIAL_COLORS[50] = new float[]{0.5f, 0.7f, 0.35f};   // GrassLight
        MATERIAL_COLORS[51] = new float[]{0.35f, 0.55f, 0.3f};  // GrassMoss
        MATERIAL_COLORS[52] = new float[]{0.7f, 0.5f, 0.5f};    // Cloth
        MATERIAL_COLORS[80] = new float[]{0.9f, 0.9f, 1.0f};    // Cloud
    }

    // ==================== Public API ====================

    public static MeshesData parse(byte[] data) {
        if (data == null || data.length < MIN_FILE_SIZE) {
            Log.e(TAG, "Data too short: " + (data == null ? "null" : data.length));
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // --- File header ---
        int magic = buf.getInt(0);
        if (magic != LVL0_MAGIC) {
            Log.e(TAG, "Bad magic: 0x" + Integer.toHexString(magic));
            return null;
        }

        int version = buf.getInt(4);
        Log.i(TAG, "=== File version: 0x" + Integer.toHexString(version) +
              " (" + version + ") ===");

        // Check supported versions
        boolean supported = false;
        for (int v : SUPPORTED_VERSIONS) {
            if (version == v) { supported = true; break; }
        }
        if (!supported) {
            Log.w(TAG, "Version 0x" + Integer.toHexString(version) +
                  " not in supported list, attempting anyway");
        }

        // --- TOC ---
        int tocCount = data[8] & 0xFF;
        Log.i(TAG, "TOC count: " + tocCount);

        int geo0Offset = -1, geo0Length = 0;
        int lod0Offset = -1, lod0Length = 0;

        for (int i = 0; i < tocCount; i++) {
            int eo = TOC_ENTRY_START + i * TOC_ENTRY_SIZE;
            if (eo + TOC_ENTRY_SIZE > data.length) break;

            String type = readAscii(data, eo, 4);
            int segOff = buf.getInt(eo + 4);
            int segLen = buf.getInt(eo + 8);

            Log.i(TAG, "  TOC[" + i + "]: " + type + " off=" + segOff + " len=" + segLen);

            if (type.equals("GEO0")) { geo0Offset = segOff; geo0Length = segLen; }
            else if (type.equals("LOD0")) { lod0Offset = segOff; lod0Length = segLen; }
        }

        // --- Version-based dispatch ---
        MeshesData result = null;

        if (version >= VERSION_LEGACY_THRESHOLD) {
            // v57+ (0x39-0x3D): GEO0 is a separate TOC entry
            if (geo0Offset >= 0 && geo0Offset < data.length) {
                Log.i(TAG, ">>> Path: GEO0 (v57+ meshopt)");
                result = parseGeo0(data, geo0Offset, geo0Length, version);
            } else {
                Log.e(TAG, "v57+ but no GEO0 segment found!");
            }
        } else {
            // v52-v56 (0x34-0x38): Terrain in LOD0 (LZ4 compressed)
            if (lod0Offset >= 0 && lod0Offset < data.length) {
                Log.i(TAG, ">>> Path: LOD0+LZ4+Terrain (legacy)");
                result = parseLegacyTerrain(data, lod0Offset, lod0Length, version);
            } else {
                Log.e(TAG, "Legacy version but no LOD0 segment found!");
            }
        }

        // Fallback: try the other path if first failed
        if (result == null) {
            if (geo0Offset >= 0 && geo0Offset < data.length) {
                Log.w(TAG, "Fallback: trying GEO0");
                result = parseGeo0(data, geo0Offset, geo0Length, version);
            }
            if (result == null && lod0Offset >= 0 && lod0Offset < data.length) {
                Log.w(TAG, "Fallback: trying LOD0+Terrain");
                result = parseLegacyTerrain(data, lod0Offset, lod0Length, version);
            }
        }

        if (result != null) {
            result.fileVersion = version;
            Log.i(TAG, "=== Parse OK: " + result.vertexCount + " verts, " +
                  result.indices.length + " indices ===");
        } else {
            Log.e(TAG, "=== All parse paths FAILED ===");
        }
        return result;
    }

    public static boolean isMeshesFile(byte[] data) {
        if (data == null || data.length < 4) return false;
        return data[0] == 0x4C && data[1] == 0x56 && data[2] == 0x4C && data[3] == 0x30;
    }

    // ==================== GEO0 path (v57+, 0x39-0x3D) ====================

    /**
     * Parse GEO0 segment for version >= 0x39.
     *
     * Structure:
     *   5x u32: index_count, vertex_count, chunk_count, cloud_chunk_count, subchunk_count
     *   u32 compressed_size + meshopt compressed vertex data
     *   index_count bytes of u8 local indices
     *   (chunk_count + cloud_chunk_count) * 56 bytes chunks
     *   subchunk_count * 8 bytes subchunks
     */
    private static MeshesData parseGeo0(byte[] data, int offset, int length, int version) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int pos = offset;

        // --- 5 u32 counts ---
        int indexCount   = buf.getInt(pos); pos += 4;
        int vertexCount  = buf.getInt(pos); pos += 4;
        int chunkCount   = buf.getInt(pos); pos += 4;
        int cloudCount   = buf.getInt(pos); pos += 4;
        int subchunkCount= buf.getInt(pos); pos += 4;

        Log.i(TAG, "GEO0: idx=" + indexCount + " vtx=" + vertexCount +
              " chunks=" + chunkCount + " cloud=" + cloudCount + " sub=" + subchunkCount);

        if (vertexCount <= 0 || vertexCount > 500000) {
            Log.e(TAG, "Invalid vertex count: " + vertexCount);
            return null;
        }

        // --- Compressed vertex data ---
        int compressedSize = buf.getInt(pos); pos += 4;
        Log.i(TAG, "  compressed_size=" + compressedSize);

        if (compressedSize <= 0 || pos + compressedSize > data.length) {
            Log.e(TAG, "Invalid compressed size: " + compressedSize +
                  " at pos=" + pos + " dataLen=" + data.length);
            return null;
        }

        byte[] compressedVertices = new byte[compressedSize];
        System.arraycopy(data, pos, compressedVertices, 0, compressedSize);
        pos += compressedSize;

        // Check meshopt version
        int moHeader = compressedVertices[0] & 0xFF;
        int moVersion = moHeader & 0x0F;
        Log.i(TAG, "  meshopt header=0x" + Integer.toHexString(moHeader) +
              " version=" + moVersion);

        // Decompress vertices
        byte[] rawVerts;
        try {
            rawVerts = MeshoptDecoder.decodeVertexBuffer(vertexCount, VERTEX_SIZE, compressedVertices);
        } catch (Exception e) {
            Log.e(TAG, "Meshopt decode failed: " + e.getMessage());
            return null;
        }
        if (rawVerts == null || rawVerts.length < vertexCount * VERTEX_SIZE) {
            Log.e(TAG, "Meshopt decode insufficient: " +
                  (rawVerts == null ? "null" : rawVerts.length) +
                  " need=" + (vertexCount * VERTEX_SIZE));
            return null;
        }

        // --- Extract vertices ---
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] colors = new float[vertexCount * 4];

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (int i = 0; i < vertexCount; i++) {
            int b = i * VERTEX_SIZE;

            // Position: 3xf32 @ 0
            float px = Float.intBitsToFloat(leInt(rawVerts, b));
            float py = Float.intBitsToFloat(leInt(rawVerts, b + 4));
            float pz = Float.intBitsToFloat(leInt(rawVerts, b + 8));
            positions[i*3] = px;
            positions[i*3+1] = py;
            positions[i*3+2] = pz;

            if (px < minX) minX = px;
            if (py < minY) minY = py;
            if (pz < minZ) minZ = pz;
            if (px > maxX) maxX = px;
            if (py > maxY) maxY = py;
            if (pz > maxZ) maxZ = pz;

            // Normal: 3x snorm @ 12
            normals[i*3]   = snorm(rawVerts[b + 12]);
            normals[i*3+1] = snorm(rawVerts[b + 13]);
            normals[i*3+2] = snorm(rawVerts[b + 14]);

            // Material blend: 4x material_id @ 16 + 4x weight @ 20
            int m0 = rawVerts[b + 16] & 0xFF;
            int m1 = rawVerts[b + 17] & 0xFF;
            int m2 = rawVerts[b + 18] & 0xFF;
            int m3 = rawVerts[b + 19] & 0xFF;
            float w0 = (rawVerts[b + 20] & 0xFF) / 255.0f;
            float w1 = (rawVerts[b + 21] & 0xFF) / 255.0f;
            float w2 = (rawVerts[b + 22] & 0xFF) / 255.0f;
            float w3 = (rawVerts[b + 23] & 0xFF) / 255.0f;

            float tw = w0 + w1 + w2 + w3;
            if (tw < 0.001f) {
                float[] c = MATERIAL_COLORS[m0];
                colors[i*4] = c[0]; colors[i*4+1] = c[1]; colors[i*4+2] = c[2];
            } else {
                float[] c0 = MATERIAL_COLORS[m0];
                float[] c1 = MATERIAL_COLORS[m1];
                float[] c2 = MATERIAL_COLORS[m2];
                float[] c3 = MATERIAL_COLORS[m3];
                colors[i*4]   = (c0[0]*w0 + c1[0]*w1 + c2[0]*w2 + c3[0]*w3) / tw;
                colors[i*4+1] = (c0[1]*w0 + c1[1]*w1 + c2[1]*w2 + c3[1]*w3) / tw;
                colors[i*4+2] = (c0[2]*w0 + c1[2]*w1 + c2[2]*w2 + c3[2]*w3) / tw;
            }
            colors[i*4+3] = 1.0f;
        }

        // --- Read u8 local indices ---
        int[] localIndices = null;
        if (indexCount > 0 && pos + indexCount <= data.length) {
            localIndices = new int[indexCount];
            for (int i = 0; i < indexCount; i++) {
                localIndices[i] = data[pos + i] & 0xFF;
            }
            pos += indexCount;
            Log.i(TAG, "  Read " + indexCount + " u8 indices");
        } else {
            Log.w(TAG, "  No indices (indexCount=" + indexCount + ")");
        }

        // --- Read chunks (56 bytes each) ---
        int totalChunks = chunkCount + cloudCount;
        int[][] chunks = new int[totalChunks][];
        for (int i = 0; i < totalChunks; i++) {
            if (pos + CHUNK_SIZE > data.length) {
                Log.w(TAG, "  Chunk " + i + " truncated");
                chunks[i] = new int[]{0, 0, 0, 0};
                break;
            }
            // idx_start(u32) + vtx_start(u32) + subchunk_start(u32)
            // + idx_count(u16) + vtx_count(u8) + subchunk_count(u8)
            // + min(3f32) + max(3f32) + pad(4u32) = 56 bytes
            int idxStart  = buf.getInt(pos);
            int vtxStart  = buf.getInt(pos + 4);
            int subStart  = buf.getInt(pos + 8);
            int idxCnt    = buf.getShort(pos + 12) & 0xFFFF;
            int vCount    = data[pos + 14] & 0xFF;
            int subCnt    = data[pos + 15] & 0xFF;
            pos += CHUNK_SIZE;

            chunks[i] = new int[]{idxStart, vtxStart, idxCnt, 0};
        }
        Log.i(TAG, "  Read " + totalChunks + " chunks");

        // --- Skip subchunks (8 bytes each) ---
        pos += subchunkCount * SUBCHUNK_SIZE;

        // --- Build global indices from terrain chunks only + cloud indices separately ---
        // Ported from HTML parseGeo0 line 35171-35200
        List<Integer> globalIndices = new ArrayList<Integer>();
        List<Integer> cloudIdxList = new ArrayList<Integer>();

        // Render bounding box: only vertices referenced by terrain indices
        float rMinX = Float.MAX_VALUE, rMinY = Float.MAX_VALUE, rMinZ = Float.MAX_VALUE;
        float rMaxX = -Float.MAX_VALUE, rMaxY = -Float.MAX_VALUE, rMaxZ = -Float.MAX_VALUE;

        if (localIndices != null) {
            // Terrain chunks (first chunkCount)
            for (int ci = 0; ci < chunkCount && ci < chunks.length; ci++) {
                int[] chunk = chunks[ci];
                int idxStart = chunk[0];
                int vtxStart = chunk[1];
                int idxCnt   = chunk[2];

                for (int j = 0; j < idxCnt; j++) {
                    int li = idxStart + j;
                    if (li >= indexCount) break;
                    int gi = localIndices[li] + vtxStart;
                    if (gi >= 0 && gi < vertexCount) {
                        globalIndices.add(gi);
                        // Update render bounding box (tight framing)
                        float px = positions[gi*3];
                        float py = positions[gi*3+1];
                        float pz = positions[gi*3+2];
                        if (px < rMinX) rMinX = px;
                        if (py < rMinY) rMinY = py;
                        if (pz < rMinZ) rMinZ = pz;
                        if (px > rMaxX) rMaxX = px;
                        if (py > rMaxY) rMaxY = py;
                        if (pz > rMaxZ) rMaxZ = pz;
                    }
                }
            }
            // Cloud chunks (after chunkCount) — separate indices, NOT included in render bounds
            // Ported from HTML line 35190: "cloud chunks not included in renderMin/Max"
            for (int ci = chunkCount; ci < totalChunks && ci < chunks.length; ci++) {
                int[] chunk = chunks[ci];
                int idxStart = chunk[0];
                int vtxStart = chunk[1];
                int idxCnt   = chunk[2];

                for (int j = 0; j < idxCnt; j++) {
                    int li = idxStart + j;
                    if (li >= indexCount) break;
                    int gi = localIndices[li] + vtxStart;
                    if (gi >= 0 && gi < vertexCount) {
                        cloudIdxList.add(gi);
                    }
                }
            }
        }
        Log.i(TAG, "  Built " + globalIndices.size() + " terrain indices, " +
              cloudIdxList.size() + " cloud indices from " + chunkCount + " terrain chunks");

        // --- Assemble result ---
        MeshesData result = new MeshesData();
        result.positions = positions;
        result.normals = normals;
        result.colors = colors;
        result.indices = new int[globalIndices.size()];
        for (int i = 0; i < globalIndices.size(); i++) {
            result.indices[i] = globalIndices.get(i);
        }
        result.vertexCount = vertexCount;
        result.indexCount = result.indices.length;
        result.chunkCount = chunkCount;
        result.cloudCount = cloudCount;
        result.boundsMin = new float[]{minX, minY, minZ};
        result.boundsMax = new float[]{maxX, maxY, maxZ};

        // Render bounding box: tight framing from referenced vertices only
        // Ported from HTML parseGeo0 line 35210-35211
        boolean hasRendered = rMinX != Float.MAX_VALUE;
        result.renderMin = hasRendered ? new float[]{rMinX, rMinY, rMinZ} : result.boundsMin;
        result.renderMax = hasRendered ? new float[]{rMaxX, rMaxY, rMaxZ} : result.boundsMax;

        // Cloud indices (separate from terrain)
        if (!cloudIdxList.isEmpty()) {
            result.cloudIndices = new int[cloudIdxList.size()];
            for (int i = 0; i < cloudIdxList.size(); i++) {
                result.cloudIndices[i] = cloudIdxList.get(i);
            }
        }

        result.meshoptVersion = moVersion;

        float maxSize = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ);
        result.info = "v0x" + Integer.toHexString(version) +
                      " mo:v" + moVersion +
                      " V:" + vertexCount +
                      " I:" + result.indices.length +
                      " C:" + chunkCount + "+" + cloudCount +
                      " S:" + String.format(java.util.Locale.US, "%.1f", maxSize);

        return result;
    }

    // ==================== Legacy path (v52-v56, 0x34-0x38) ====================

    /**
     * Parse legacy terrain from LZ4-compressed LOD0 segment.
     *
     * LOD0 decompressed layout:
     *   1. Mesh.bin (skip)
     *   2. Terrain.bin: u32 blob_count, then per blob:
     *      linked_id(u32) + flags(u8) + aabb_min(3f32) + aabb_max(3f32)
     *      + vertex_count(u32) + index_count(u32)
     *      + vertices(vertex_count * 36 raw bytes)
     *      + octree(u32 size + data)
     *      + grid(aabb + cellSize + 3 counts + patchCount + patches)
     *      + tessellation(3 u32 + data)
     *      + indices(index_count * 2 bytes, u16)
     */
    private static MeshesData parseLegacyTerrain(byte[] data, int lod0Off, int lod0Len, int version) {
        Log.i(TAG, "Legacy: LOD0 at " + lod0Off + " len=" + lod0Len);

        if (lod0Off + lod0Len > data.length) {
            Log.e(TAG, "LOD0 out of bounds");
            return null;
        }

        // --- LZ4 decompress ---
        byte[] lod0Compressed = new byte[lod0Len];
        System.arraycopy(data, lod0Off, lod0Compressed, 0, lod0Len);

        byte[] lod0;
        try {
            lod0 = LZ4BlockDecoder.decompress(lod0Compressed);
        } catch (Exception e) {
            Log.e(TAG, "LZ4 failed: " + e.getMessage());
            return null;
        }
        if (lod0 == null || lod0.length < 4) {
            Log.e(TAG, "LZ4 result too short: " + (lod0 == null ? "null" : lod0.length));
            return null;
        }
        Log.i(TAG, "  LZ4: " + lod0Len + " -> " + lod0.length + " bytes");

        ByteBuffer buf = ByteBuffer.wrap(lod0).order(ByteOrder.LITTLE_ENDIAN);

        // --- Skip Mesh.bin ---
        int pos = 0;
        try {
            pos = skipMeshBin(lod0, 0);
            Log.i(TAG, "  After Mesh.bin: pos=" + pos);
        } catch (Exception e) {
            Log.e(TAG, "  Mesh.bin skip failed: " + e.getMessage());
            pos = 0;
        }

        // --- Parse Terrain.bin ---
        if (pos + 4 > lod0.length) {
            Log.e(TAG, "No terrain data after mesh section");
            return null;
        }

        int blobCount = buf.getInt(pos); pos += 4;
        Log.i(TAG, "  Terrain blobs: " + blobCount);

        if (blobCount <= 0 || blobCount > 1000) {
            Log.e(TAG, "Invalid blob count: " + blobCount);
            return null;
        }

        List<Float> posList = new ArrayList<Float>();
        List<Float> norList = new ArrayList<Float>();
        List<Float> colList = new ArrayList<Float>();
        List<Integer> idxList = new ArrayList<Integer>();

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        int vertexOffset = 0;

        for (int b = 0; b < blobCount; b++) {
            if (pos + 37 > lod0.length) {
                Log.w(TAG, "  Blob " + b + " truncated at pos=" + pos);
                break;
            }

            // Blob header: linked_id(u32) + flags(u8) + aabb(24) + vtx_count(u32) + idx_count(u32) = 37 bytes
            int linkedId = buf.getInt(pos); pos += 4;
            int flags = lod0[pos] & 0xFF; pos += 1;
            pos += 12; // aabb_min
            pos += 12; // aabb_max
            int vtxCount = buf.getInt(pos); pos += 4;
            int idxCount = buf.getInt(pos); pos += 4;

            Log.i(TAG, "  Blob " + b + ": vtx=" + vtxCount + " idx=" + idxCount);

            if (vtxCount <= 0 || vtxCount > 500000) {
                Log.w(TAG, "  Bad vtx count: " + vtxCount);
                break;
            }

            // Read raw 36-byte vertices
            if (pos + vtxCount * VERTEX_SIZE > lod0.length) {
                Log.w(TAG, "  Vertices OOB: pos=" + pos + " need=" + (vtxCount * VERTEX_SIZE));
                break;
            }

            for (int v = 0; v < vtxCount; v++) {
                int b2 = pos + v * VERTEX_SIZE;

                float px = Float.intBitsToFloat(leInt(lod0, b2));
                float py = Float.intBitsToFloat(leInt(lod0, b2 + 4));
                float pz = Float.intBitsToFloat(leInt(lod0, b2 + 8));
                posList.add(px); posList.add(py); posList.add(pz);

                if (px < minX) minX = px;
                if (py < minY) minY = py;
                if (pz < minZ) minZ = pz;
                if (px > maxX) maxX = px;
                if (py > maxY) maxY = py;
                if (pz > maxZ) maxZ = pz;

                norList.add(snorm(lod0[b2 + 12]));
                norList.add(snorm(lod0[b2 + 13]));
                norList.add(snorm(lod0[b2 + 14]));

                // Material blend
                int m0 = lod0[b2 + 16] & 0xFF;
                int m1 = lod0[b2 + 17] & 0xFF;
                int m2 = lod0[b2 + 18] & 0xFF;
                int m3 = lod0[b2 + 19] & 0xFF;
                float w0 = (lod0[b2 + 20] & 0xFF) / 255.0f;
                float w1 = (lod0[b2 + 21] & 0xFF) / 255.0f;
                float w2 = (lod0[b2 + 22] & 0xFF) / 255.0f;
                float w3 = (lod0[b2 + 23] & 0xFF) / 255.0f;

                float tw = w0 + w1 + w2 + w3;
                if (tw < 0.001f) {
                    float[] c = MATERIAL_COLORS[m0];
                    colList.add(c[0]); colList.add(c[1]); colList.add(c[2]);
                } else {
                    float[] c0 = MATERIAL_COLORS[m0];
                    float[] c1 = MATERIAL_COLORS[m1];
                    float[] c2 = MATERIAL_COLORS[m2];
                    float[] c3 = MATERIAL_COLORS[m3];
                    colList.add((c0[0]*w0 + c1[0]*w1 + c2[0]*w2 + c3[0]*w3) / tw);
                    colList.add((c0[1]*w0 + c1[1]*w1 + c2[1]*w2 + c3[1]*w3) / tw);
                    colList.add((c0[2]*w0 + c1[2]*w1 + c2[2]*w2 + c3[2]*w3) / tw);
                }
                colList.add(1.0f);
            }

            pos += vtxCount * VERTEX_SIZE;

            // Skip octree
            if (pos + 4 > lod0.length) break;
            int octreeSize = buf.getInt(pos); pos += 4;
            if (octreeSize > 0 && pos + octreeSize <= lod0.length) pos += octreeSize;

            // Skip grid: aabb_min(12) + aabb_max(12) + cellSize(4) + 3 counts(12) + patchCount(4)
            if (pos + 44 > lod0.length) break;
            pos += 12 + 12 + 4 + 12; // aabb + cellSize + 3 counts
            int patchCount = buf.getInt(pos); pos += 4;
            pos += patchCount * 8; // each patch: u32 + 4 bytes = 8

            // Skip tessellation: 3 u32 counts + data
            if (pos + 12 > lod0.length) break;
            int tessTriEdge = buf.getInt(pos); pos += 4;
            int tessEdge    = buf.getInt(pos); pos += 4;
            int tessIdx     = buf.getInt(pos); pos += 4;
            pos += 4 * tessEdge;   // edge_list (u32 array)
            pos += 2 * tessIdx;    // indices (u16 array)
            pos += 4 * tessTriEdge; // tri_edges (u32 array)

            // Read u16 indices
            if (idxCount > 0 && pos + idxCount * 2 <= lod0.length) {
                for (int i = 0; i < idxCount; i++) {
                    int gi = (lod0[pos + i*2] & 0xFF) | ((lod0[pos + i*2 + 1] & 0xFF) << 8);
                    gi += vertexOffset;
                    idxList.add(gi);
                }
                pos += idxCount * 2;
                Log.i(TAG, "    Read " + idxCount + " u16 indices");
            }

            vertexOffset += vtxCount;
        }

        if (posList.isEmpty()) {
            Log.e(TAG, "No vertices extracted");
            return null;
        }

        // --- Assemble result ---
        int totalVerts = posList.size() / 3;
        float[] positions = new float[posList.size()];
        float[] normals = new float[norList.size()];
        float[] colors = new float[colList.size()];
        int[] indices = new int[idxList.size()];

        for (int i = 0; i < posList.size(); i++) positions[i] = posList.get(i);
        for (int i = 0; i < norList.size(); i++) normals[i] = norList.get(i);
        for (int i = 0; i < colList.size(); i++) colors[i] = colList.get(i);
        for (int i = 0; i < idxList.size(); i++) indices[i] = idxList.get(i);

        float maxSize = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ);

        MeshesData result = new MeshesData();
        result.positions = positions;
        result.normals = normals;
        result.colors = colors;
        result.indices = indices;
        result.vertexCount = totalVerts;
        result.indexCount = indices.length;
        result.boundsMin = new float[]{minX, minY, minZ};
        result.boundsMax = new float[]{maxX, maxY, maxZ};
        result.meshoptVersion = -1; // legacy raw vertices
        result.info = "v0x" + Integer.toHexString(version) +
                      " legacy V:" + totalVerts +
                      " I:" + indices.length +
                      " B:" + blobCount +
                      " S:" + String.format(java.util.Locale.US, "%.1f", maxSize);

        Log.i(TAG, "  Legacy OK: " + totalVerts + " verts, " + indices.length + " indices");
        return result;
    }

    // ==================== Mesh.bin skip ====================

    /**
     * Skip Mesh.bin section in LOD0 payload.
     *
     * Structure:
     *   u32 mesh_count
     *   per mesh: u32 string_len + string_data + u32 guid + u32 sub_count + u8 compressed
     *   per submesh: u32 uv_count + u32 face_byte_count + color_bytes + face_bytes
     */
    private static int skipMeshBin(byte[] data, int pos) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (pos + 4 > data.length) return pos;

        int meshCount = buf.getInt(pos); pos += 4;
        Log.i(TAG, "  Mesh.bin: " + meshCount + " meshes");

        for (int m = 0; m < meshCount; m++) {
            // Length-prefixed string
            if (pos + 4 > data.length) return pos;
            int slen = buf.getInt(pos); pos += 4;
            if (slen < 0 || slen > 4096) {
                Log.w(TAG, "  Bad string len: " + slen + " at mesh " + m);
                return pos;
            }
            pos += slen;

            if (pos + 9 > data.length) return pos;
            pos += 4; // guid
            int subCount = buf.getInt(pos); pos += 4;
            int compressed = data[pos] & 0xFF; pos += 1;

            for (int s = 0; s < subCount; s++) {
                if (pos + 8 > data.length) return pos;
                int uvCount = buf.getInt(pos); pos += 4;
                int faceByteCount = buf.getInt(pos); pos += 4;

                int colorSize = compressed != 0 ? 12 : Math.min(12 * uvCount, Integer.MAX_VALUE);
                if (colorSize < 0) colorSize = 0;
                pos += colorSize;
                pos += faceByteCount;
            }
        }

        Log.i(TAG, "  Mesh.bin skipped, pos=" + pos);
        return pos;
    }

    // ==================== Helpers ====================

    private static float snorm(byte b) {
        int u = b & 0xFF;
        int s = u >= 128 ? u - 256 : u;
        float v = s / 127.0f;
        if (v < -1.0f) v = -1.0f;
        if (v > 1.0f) v = 1.0f;
        return v;
    }

    private static int leInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) |
               ((data[offset + 1] & 0xFF) << 8) |
               ((data[offset + 2] & 0xFF) << 16) |
               ((data[offset + 3] & 0xFF) << 24);
    }

    private static String readAscii(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && offset + i < data.length; i++) {
            char c = (char) (data[offset + i] & 0xFF);
            if (c >= 32 && c < 127) sb.append(c);
        }
        return sb.toString();
    }
}
