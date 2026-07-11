package com.sky.modelviewer.parsing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for .meshes files (LVL0 format).
 * Ported from that-sky-level-meshes (meshes2obj_json.py).
 *
 * Supports material-based vertex coloring using kMaterial enum + blend weights.
 */
public class LevelMeshesReader {

    private LevelMeshesReader() {}

    public static class MeshesData {
        public float[] positions;
        public float[] normals;
        public float[] colors;   // RGBA per vertex (material-based)
        public int[] indices;
        public int vertexCount;
        public int indexCount;
        public int chunkCount;
        public float[] boundsMin;
        public float[] boundsMax;
        public String info;
    }

    private static final int LVL0_MAGIC = 0x304C564C; // "LVL0" little-endian
    private static final int HEADER_LENGTH = 136;
    private static final int VERTEX_SIZE = 36;
    private static final int TOC_OFFSET = 8;
    private static final int TOC_TOTAL_SIZE = 100;
    private static final int TOC_ENTRY_SIZE = 12;

    // kMaterial enum → RGB color
    // Based on the game's surface material types
    private static final float[][] MATERIAL_COLORS = new float[256][3];
    static {
        // Default: warm gray
        for (int i = 0; i < 256; i++) {
            MATERIAL_COLORS[i] = new float[]{0.7f, 0.7f, 0.7f};
        }
        // None
        MATERIAL_COLORS[0] = new float[]{0.5f, 0.5f, 0.5f};
        MATERIAL_COLORS[2] = new float[]{0.6f, 0.7f, 0.8f};   // Transparent
        MATERIAL_COLORS[3] = new float[]{0.2f, 0.2f, 0.2f};   // Void
        MATERIAL_COLORS[4] = new float[]{0.9f, 0.8f, 0.6f};   // Particle
        // Wood
        MATERIAL_COLORS[5] = new float[]{0.6f, 0.45f, 0.3f};  // WoodSlippery
        MATERIAL_COLORS[6] = new float[]{0.3f, 0.3f, 0.3f};   // VoidMinor
        MATERIAL_COLORS[7] = new float[]{0.65f, 0.5f, 0.35f}; // WoodPlank
        // Stone/Cliff
        MATERIAL_COLORS[16] = new float[]{0.55f, 0.5f, 0.45f}; // Cliff
        MATERIAL_COLORS[17] = new float[]{0.6f, 0.5f, 0.35f};  // Soil
        MATERIAL_COLORS[18] = new float[]{0.65f, 0.6f, 0.5f};  // CliffLight
        MATERIAL_COLORS[19] = new float[]{0.4f, 0.35f, 0.3f};  // WallDamaged
        MATERIAL_COLORS[20] = new float[]{0.5f, 0.5f, 0.55f};  // Wall
        MATERIAL_COLORS[21] = new float[]{0.9f, 0.8f, 0.3f};   // Gold
        MATERIAL_COLORS[22] = new float[]{0.7f, 0.85f, 0.95f}; // Glacier
        MATERIAL_COLORS[23] = new float[]{0.8f, 0.8f, 0.85f};  // TileCeiling
        MATERIAL_COLORS[24] = new float[]{0.75f, 0.75f, 0.8f}; // TileFloor
        MATERIAL_COLORS[25] = new float[]{0.7f, 0.7f, 0.75f};  // TileWall
        MATERIAL_COLORS[26] = new float[]{0.6f, 0.45f, 0.35f}; // WallBrick
        MATERIAL_COLORS[27] = new float[]{0.4f, 0.35f, 0.25f}; // SoilWet
        MATERIAL_COLORS[28] = new float[]{0.35f, 0.35f, 0.35f}; // CliffWet
        MATERIAL_COLORS[29] = new float[]{0.85f, 0.85f, 0.8f}; // Bone
        MATERIAL_COLORS[30] = new float[]{0.6f, 0.45f, 0.3f};  // Wood
        MATERIAL_COLORS[31] = new float[]{0.8f, 0.7f, 0.6f};   // Ceramics
        // Sand
        MATERIAL_COLORS[32] = new float[]{0.85f, 0.78f, 0.55f}; // Sand
        MATERIAL_COLORS[33] = new float[]{0.7f, 0.65f, 0.45f};  // SandWet
        MATERIAL_COLORS[34] = new float[]{0.9f, 0.85f, 0.65f};  // SandLight
        MATERIAL_COLORS[35] = new float[]{0.95f, 0.95f, 0.98f}; // Snow
        MATERIAL_COLORS[36] = new float[]{0.75f, 0.68f, 0.45f}; // SandDeep
        MATERIAL_COLORS[37] = new float[]{0.45f, 0.4f, 0.3f};   // Mud
        // Grass
        MATERIAL_COLORS[48] = new float[]{0.4f, 0.6f, 0.3f};   // Grass
        MATERIAL_COLORS[49] = new float[]{0.3f, 0.5f, 0.25f};  // GrassWet
        MATERIAL_COLORS[50] = new float[]{0.5f, 0.7f, 0.35f};  // GrassLight
        MATERIAL_COLORS[51] = new float[]{0.35f, 0.55f, 0.3f}; // GrassMoss
        MATERIAL_COLORS[52] = new float[]{0.7f, 0.5f, 0.5f};   // Cloth
        MATERIAL_COLORS[80] = new float[]{0.9f, 0.9f, 1.0f};   // Cloud
    }

    public static MeshesData parse(byte[] data) {
        if (data == null || data.length < HEADER_LENGTH) {
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int magic = buf.getInt(0);
        if (magic != LVL0_MAGIC) {
            return null;
        }

        int version = buf.getInt(4);

        int tocCountOffset = TOC_OFFSET;
        int tocEntryStart = TOC_OFFSET + 4;
        int tocCount = buf.getInt(tocCountOffset);
        if (tocCount < 0 || tocCount > 8) {
            return null;
        }

        int geo0Offset = -1;
        int geo0Length = 0;

        for (int i = 0; i < tocCount; i++) {
            int entryOffset = tocEntryStart + i * TOC_ENTRY_SIZE;
            if (entryOffset + TOC_ENTRY_SIZE > TOC_OFFSET + TOC_TOTAL_SIZE) break;

            String type = readString(data, entryOffset, 4);
            int segOffset = buf.getInt(entryOffset + 4);
            int segLength = buf.getInt(entryOffset + 8);

            if (type.equals("GEO0")) {
                geo0Offset = segOffset;
                geo0Length = segLength;
                break;
            }
        }

        if (geo0Offset < 0 || geo0Offset >= data.length) {
            return null;
        }

        return parseGeo0(data, geo0Offset, geo0Length);
    }

    private static MeshesData parseGeo0(byte[] data, int offset, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int pos = offset;

        // 5 u32 counts
        int indexCount = buf.getInt(pos); pos += 4;
        int vertexCount = buf.getInt(pos); pos += 4;
        int chunkCount = buf.getInt(pos); pos += 4;
        int cloudChunkCount = buf.getInt(pos); pos += 4;
        int subchunkCount = buf.getInt(pos); pos += 4;

        if (vertexCount <= 0 || vertexCount > 500000) {
            return null;
        }

        MeshesData result = new MeshesData();
        result.vertexCount = vertexCount;
        result.indexCount = indexCount;
        result.chunkCount = chunkCount;

        // Read compressed vertex data
        int compressedSize = buf.getInt(pos); pos += 4;
        if (compressedSize <= 0 || pos + compressedSize > data.length) {
            return null;
        }

        byte[] compressedVertices = new byte[compressedSize];
        System.arraycopy(data, pos, compressedVertices, 0, compressedSize);
        pos += compressedSize;

        // Decompress vertices
        byte[] rawVertices;
        try {
            rawVertices = MeshoptDecoder.decodeVertexBuffer(vertexCount, VERTEX_SIZE, compressedVertices);
        } catch (Exception e) {
            return null;
        }

        if (rawVertices == null || rawVertices.length < vertexCount * VERTEX_SIZE) {
            return null;
        }

        // Extract positions, normals, and material-based colors
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] colors = new float[vertexCount * 4]; // RGBA

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (int i = 0; i < vertexCount; i++) {
            int vBase = i * VERTEX_SIZE;

            // Position: 3 floats (12 bytes) — little-endian
            float px = Float.intBitsToFloat(
                    (rawVertices[vBase] & 0xFF) |
                    ((rawVertices[vBase + 1] & 0xFF) << 8) |
                    ((rawVertices[vBase + 2] & 0xFF) << 16) |
                    ((rawVertices[vBase + 3] & 0xFF) << 24));
            float py = Float.intBitsToFloat(
                    (rawVertices[vBase + 4] & 0xFF) |
                    ((rawVertices[vBase + 5] & 0xFF) << 8) |
                    ((rawVertices[vBase + 6] & 0xFF) << 16) |
                    ((rawVertices[vBase + 7] & 0xFF) << 24));
            float pz = Float.intBitsToFloat(
                    (rawVertices[vBase + 8] & 0xFF) |
                    ((rawVertices[vBase + 9] & 0xFF) << 8) |
                    ((rawVertices[vBase + 10] & 0xFF) << 16) |
                    ((rawVertices[vBase + 11] & 0xFF) << 24));

            positions[i * 3] = px;
            positions[i * 3 + 1] = py;
            positions[i * 3 + 2] = pz;

            if (px < minX) minX = px;
            if (py < minY) minY = py;
            if (pz < minZ) minZ = pz;
            if (px > maxX) maxX = px;
            if (py > maxY) maxY = py;
            if (pz > maxZ) maxZ = pz;

            // Normal: R8G8B8A8_SNORM (4 bytes at offset 12)
            float nx = snorm(rawVertices[vBase + 12]);
            float ny = snorm(rawVertices[vBase + 13]);
            float nz = snorm(rawVertices[vBase + 14]);
            normals[i * 3] = nx;
            normals[i * 3 + 1] = ny;
            normals[i * 3 + 2] = nz;

            // Material: 4 material indices (bytes 16-19) + 4 weights (bytes 20-23)
            int m0 = rawVertices[vBase + 16] & 0xFF;
            int m1 = rawVertices[vBase + 17] & 0xFF;
            int m2 = rawVertices[vBase + 18] & 0xFF;
            int m3 = rawVertices[vBase + 19] & 0xFF;
            float w0 = (rawVertices[vBase + 20] & 0xFF) / 255.0f;
            float w1 = (rawVertices[vBase + 21] & 0xFF) / 255.0f;
            float w2 = (rawVertices[vBase + 22] & 0xFF) / 255.0f;
            float w3 = (rawVertices[vBase + 23] & 0xFF) / 255.0f;

            // Blend 4 material colors by weight
            float totalW = w0 + w1 + w2 + w3;
            if (totalW < 0.001f) {
                // No weights — use first material
                float[] c = MATERIAL_COLORS[m0 & 0xFF];
                colors[i * 4] = c[0];
                colors[i * 4 + 1] = c[1];
                colors[i * 4 + 2] = c[2];
            } else {
                float[] c0 = MATERIAL_COLORS[m0 & 0xFF];
                float[] c1 = MATERIAL_COLORS[m1 & 0xFF];
                float[] c2 = MATERIAL_COLORS[m2 & 0xFF];
                float[] c3 = MATERIAL_COLORS[m3 & 0xFF];
                colors[i * 4]     = (c0[0] * w0 + c1[0] * w1 + c2[0] * w2 + c3[0] * w3) / totalW;
                colors[i * 4 + 1] = (c0[1] * w0 + c1[1] * w1 + c2[1] * w2 + c3[1] * w3) / totalW;
                colors[i * 4 + 2] = (c0[2] * w0 + c1[2] * w1 + c2[2] * w2 + c3[2] * w3) / totalW;
            }
            colors[i * 4 + 3] = 1.0f; // Alpha
        }

        float sizeX = maxX - minX;
        float sizeY = maxY - minY;
        float sizeZ = maxZ - minZ;
        float maxSize = Math.max(Math.max(sizeX, sizeY), sizeZ);

        result.positions = positions;
        result.normals = normals;
        result.colors = colors;
        result.boundsMin = new float[]{minX, minY, minZ};
        result.boundsMax = new float[]{maxX, maxY, maxZ};

        // Read indices (u8 array) — flat global array
        if (indexCount > 0 && pos + indexCount <= data.length) {
            int[] localIndices = new int[indexCount];
            for (int i = 0; i < indexCount; i++) {
                localIndices[i] = data[pos + i] & 0xFF;
            }
            pos += indexCount;

            // Read ALL chunks (terrain + cloud) from binary
            int totalChunks = chunkCount + cloudChunkCount;
            int[][] allChunks = new int[totalChunks][];
            for (int i = 0; i < totalChunks; i++) {
                if (pos + 56 > data.length) {
                    allChunks[i] = new int[]{0, 0, 0, 0};
                    break;
                }

                int idxStart = buf.getInt(pos); pos += 4;
                int vtxStart = buf.getInt(pos); pos += 4;
                int subchunkStart = buf.getInt(pos); pos += 4;
                int idxCount = buf.getShort(pos) & 0xFFFF; pos += 2;
                int vtxCount = data[pos] & 0xFF; pos += 1;
                int subchunkCount2 = data[pos] & 0xFF; pos += 1;
                // min Vec3 (12) + max Vec3 (12) + padding (16) = 40
                pos += 40;

                allChunks[i] = new int[]{idxStart, vtxStart, idxCount, vtxCount};
            }

            // Read subchunks (8 bytes each)
            // Subchunk: materialId(1) + triangleCount(1) + vtxCount(1) +
            //           triangleStart(1) + triangleEnd(1) + vtxStart(1) + vtxEnd(1) + padding(1)
            int[][] allSubchunks = new int[subchunkCount][];
            for (int i = 0; i < subchunkCount; i++) {
                if (pos + 8 > data.length) {
                    allSubchunks[i] = new int[]{0, 0, 0, 0, 0, 0, 0};
                    break;
                }
                int materialId = data[pos] & 0xFF; pos += 1;
                int triCount = data[pos] & 0xFF; pos += 1;
                int vc = data[pos] & 0xFF; pos += 1;
                int triStart = data[pos] & 0xFF; pos += 1;
                int triEnd = data[pos] & 0xFF; pos += 1;
                int vs = data[pos] & 0xFF; pos += 1;
                int ve = data[pos] & 0xFF; pos += 1;
                pos += 1; // padding

                allSubchunks[i] = new int[]{materialId, triCount, vc, triStart, triEnd, vs, ve};
            }

            // Build global indices — ONLY from terrain chunks
            List<Integer> globalIndices = new ArrayList<Integer>();
            for (int ci = 0; ci < chunkCount && ci < allChunks.length; ci++) {
                int[] chunk = allChunks[ci];
                int idxStart = chunk[0];
                int vtxStart = chunk[1];
                int idxCnt = chunk[2];

                for (int j = 0; j < idxCnt; j++) {
                    int localIdx = idxStart + j;
                    if (localIdx >= indexCount) break;
                    int globalIdx = localIndices[localIdx] + vtxStart;
                    if (globalIdx < 0 || globalIdx >= vertexCount) continue;
                    globalIndices.add(globalIdx);
                }
            }

            result.indices = new int[globalIndices.size()];
            for (int i = 0; i < globalIndices.size(); i++) {
                result.indices[i] = globalIndices.get(i);
            }
        } else {
            result.indices = new int[0];
        }

        result.info = "Verts: " + vertexCount + ", Idx: " + result.indices.length +
                      ", Chunks: " + chunkCount + "T+" + cloudChunkCount + "C" +
                      ", Size: " + String.format(java.util.Locale.US, "%.1f", maxSize);
        return result;
    }

    private static float snorm(byte b) {
        int unsigned = b & 0xFF;
        int signed = unsigned >= 128 ? unsigned - 256 : unsigned;
        float v = signed / 127.0f;
        if (v < -1.0f) v = -1.0f;
        return v;
    }

    private static String readString(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length && offset + i < data.length; i++) {
            sb.append((char) (data[offset + i] & 0xFF));
        }
        return sb.toString();
    }

    public static boolean isMeshesFile(byte[] data) {
        if (data == null || data.length < 4) return false;
        return (data[0] & 0xFF) == 0x4C && // L
               (data[1] & 0xFF) == 0x56 && // V
               (data[2] & 0xFF) == 0x4C && // L
               (data[3] & 0xFF) == 0x30;   // 0
    }
}
