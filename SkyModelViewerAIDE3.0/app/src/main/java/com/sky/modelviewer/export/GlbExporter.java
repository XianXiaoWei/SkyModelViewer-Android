package com.sky.modelviewer.export;

import com.sky.modelviewer.model.BoneWeight;
import com.sky.modelviewer.model.MeshData;
import com.sky.modelviewer.model.SkeletonBone;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Exports mesh data (with optional texture and skeleton) to GLB (binary GLTF 2.0) format.
 * Ported from SkyModel.Core.Export.GlbExporter.
 */
public class GlbExporter {

    private static final int GLB_MAGIC = 0x46546C67;       // "glTF"
    private static final int GLB_VERSION = 2;
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;   // "JSON"
    private static final int BIN_CHUNK_TYPE = 0x004E4942;     // "BIN\0"

    private static final int COMP_FLOAT = 5126;
    private static final int COMP_USHORT = 5123;
    private static final int COMP_UINT = 5125;

    private static final int TARGET_ARRAY_BUFFER = 34962;
    private static final int TARGET_ELEMENT_ARRAY_BUFFER = 34963;

    // Sign pattern for R * M * R where R = diag(-1, 1, -1, 1) (180-degree Y rotation)
    private static final int[] FLIP_SIGNS = {1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, -1, -1, 1, -1, 1};

    private GlbExporter() {
        // Singleton - no instances
    }

    /**
     * Export terrain data (from .meshes) to GLB format.
     */
    public static void exportTerrain(
            OutputStream outputStream,
            com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData terrain,
            float scale,
            boolean flipCoordinates
    ) throws java.io.IOException, org.json.JSONException {
        if (terrain.positions == null || terrain.positions.length == 0) {
            throw new IllegalArgumentException("Terrain has no vertices.");
        }

        int vCount = terrain.vertexCount;

        // Build binary buffer: positions + normals + indices
        ByteArrayOutputStream binBuf = new ByteArrayOutputStream();

        // Positions
        int posOffset = binBuf.size();
        ByteBuffer posBuf = ByteBuffer.allocate(vCount * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < vCount; i++) {
            float x = terrain.positions[i * 3] * scale;
            float y = terrain.positions[i * 3 + 1] * scale;
            float z = terrain.positions[i * 3 + 2] * scale;
            if (flipCoordinates) { y = -y; z = -z; }
            posBuf.putFloat(x).putFloat(y).putFloat(z);
        }
        binBuf.write(posBuf.array());

        // Normals
        int normOffset = binBuf.size();
        ByteBuffer normBuf = ByteBuffer.allocate(vCount * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < vCount; i++) {
            float nx = terrain.normals[i * 3];
            float ny = terrain.normals[i * 3 + 1];
            float nz = terrain.normals[i * 3 + 2];
            if (flipCoordinates) { ny = -ny; nz = -nz; }
            normBuf.putFloat(nx).putFloat(ny).putFloat(nz);
        }
        binBuf.write(normBuf.array());

        // Indices (u16 or u32 depending on vertex count)
        int idxCount = terrain.indices.length;
        int idxOffset = binBuf.size();
        boolean useU16 = vCount <= 65536;
        int idxBytes = useU16 ? idxCount * 2 : idxCount * 4;

        JSONObject gltf = new JSONObject();
        gltf.put("asset", new JSONObject().put("version", "2.0").put("generator", "SkyModelViewer"));

        JSONArray buffers = new JSONArray();
        int totalBinLen = posOffset + (normOffset - posOffset) + idxBytes;
        // Pad to 4-byte alignment
        while (totalBinLen % 4 != 0) totalBinLen++;
        buffers.put(new JSONObject().put("byteLength", totalBinLen));
        gltf.put("buffers", buffers);

        JSONArray bufferViews = new JSONArray();
        bufferViews.put(new JSONObject()
                .put("buffer", 0).put("byteOffset", posOffset)
                .put("byteLength", vCount * 12)
                .put("target", TARGET_ARRAY_BUFFER));
        bufferViews.put(new JSONObject()
                .put("buffer", 0).put("byteOffset", normOffset)
                .put("byteLength", vCount * 12)
                .put("target", TARGET_ARRAY_BUFFER));
        bufferViews.put(new JSONObject()
                .put("buffer", 0).put("byteOffset", idxOffset)
                .put("byteLength", idxBytes)
                .put("target", TARGET_ELEMENT_ARRAY_BUFFER));
        gltf.put("bufferViews", bufferViews);

        JSONArray accessors = new JSONArray();
        accessors.put(new JSONObject()
                .put("bufferView", 0).put("componentType", COMP_FLOAT).put("count", vCount)
                .put("type", "VEC3"));
        accessors.put(new JSONObject()
                .put("bufferView", 1).put("componentType", COMP_FLOAT).put("count", vCount)
                .put("type", "VEC3"));
        accessors.put(new JSONObject()
                .put("bufferView", 2).put("componentType", useU16 ? COMP_USHORT : COMP_UINT)
                .put("count", idxCount).put("type", "SCALAR"));
        gltf.put("accessors", accessors);

        JSONArray meshes = new JSONArray();
        JSONObject mesh = new JSONObject();
        JSONArray primitives = new JSONArray();
        JSONObject prim = new JSONObject();
        prim.put("attributes", new JSONObject()
                .put("POSITION", 0).put("NORMAL", 1));
        prim.put("indices", 2);
        prim.put("mode", 4); // TRIANGLES
        primitives.put(prim);
        mesh.put("primitives", primitives);
        meshes.put(mesh);
        gltf.put("meshes", meshes);

        JSONArray nodes = new JSONArray();
        nodes.put(new JSONObject().put("mesh", 0));
        gltf.put("nodes", nodes);

        gltf.put("scene", 0);
        gltf.put("scenes", new JSONArray().put(new JSONObject().put("nodes", new JSONArray().put(0))));

        // Write indices
        if (useU16) {
            ByteBuffer idxBuf = ByteBuffer.allocate(idxCount * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < idxCount; i++) {
                idxBuf.putShort((short) terrain.indices[i]);
            }
            binBuf.write(idxBuf.array());
            // Pad
            int pad = (4 - (idxCount * 2 % 4)) % 4;
            for (int p = 0; p < pad; p++) binBuf.write(0);
        } else {
            ByteBuffer idxBuf = ByteBuffer.allocate(idxCount * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < idxCount; i++) {
                idxBuf.putInt(terrain.indices[i]);
            }
            binBuf.write(idxBuf.array());
        }

        // Build GLB
        byte[] jsonBytes = gltf.toString().getBytes("UTF-8");
        // Pad JSON to 4-byte alignment with spaces
        while (jsonBytes.length % 4 != 0) {
            jsonBytes = padArray(jsonBytes, (byte) 0x20);
        }

        byte[] binBytes = binBuf.toByteArray();
        // Pad bin to 4-byte alignment
        while (binBytes.length % 4 != 0) {
            binBytes = padArray(binBytes, (byte) 0x00);
        }

        int totalLength = 12 + 8 + jsonBytes.length + 8 + binBytes.length;
        ByteBuffer glb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(GLB_MAGIC);
        glb.putInt(GLB_VERSION);
        glb.putInt(totalLength);

        glb.putInt(jsonBytes.length);
        glb.putInt(JSON_CHUNK_TYPE);
        glb.put(jsonBytes);

        glb.putInt(binBytes.length);
        glb.putInt(BIN_CHUNK_TYPE);
        glb.put(binBytes);

        outputStream.write(glb.array());
    }

    /**
     * Export multiple terrain meshes combined into one GLB.
     */
    public static void exportCombinedTerrain(
            OutputStream outputStream,
            com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData[] terrains,
            float scale,
            boolean flipCoordinates
    ) throws java.io.IOException, org.json.JSONException {
        // Merge all into one
        int totalVerts = 0, totalIdx = 0;
        for (com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData t : terrains) {
            totalVerts += t.vertexCount;
            totalIdx += t.indices.length;
        }

        com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData merged =
            new com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData();
        merged.vertexCount = totalVerts;
        merged.positions = new float[totalVerts * 3];
        merged.normals = new float[totalVerts * 3];
        merged.indices = new int[totalIdx];

        int vOff = 0, iOff = 0, vAccum = 0;
        for (com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData t : terrains) {
            System.arraycopy(t.positions, 0, merged.positions, vOff * 3, t.vertexCount * 3);
            System.arraycopy(t.normals, 0, merged.normals, vOff * 3, t.vertexCount * 3);
            for (int i = 0; i < t.indices.length; i++) {
                merged.indices[iOff + i] = t.indices[i] + vAccum;
            }
            vOff += t.vertexCount;
            iOff += t.indices.length;
            vAccum += t.vertexCount;
        }

        exportTerrain(outputStream, merged, scale, flipCoordinates);
    }

    /**
     * Export a complete level: terrain + all mesh instances with transforms applied.
     */
    public static void exportCombinedLevel(
            OutputStream outputStream,
            com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData[] terrains,
            List<com.sky.modelviewer.model.MeshData> levelMeshes,
            List<float[]> levelTransforms,
            float scale,
            boolean flipCoordinates
    ) throws java.io.IOException, org.json.JSONException {
        // Merge terrain + mesh instances into one MeshesData
        int totalVerts = 0, totalIdx = 0;
        if (terrains != null) {
            for (com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData t : terrains) {
                totalVerts += t.vertexCount;
                totalIdx += t.indices.length;
            }
        }
        for (com.sky.modelviewer.model.MeshData m : levelMeshes) {
            totalVerts += m.vertices.size();
            totalIdx += m.indices.size() * 3;
        }

        com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData merged =
            new com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData();
        merged.vertexCount = totalVerts;
        merged.positions = new float[totalVerts * 3];
        merged.normals = new float[totalVerts * 3];
        merged.indices = new int[totalIdx];

        int vOff = 0, iOff = 0, vAccum = 0;

        // Terrain
        if (terrains != null) {
            for (com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData t : terrains) {
                System.arraycopy(t.positions, 0, merged.positions, vOff * 3, t.vertexCount * 3);
                System.arraycopy(t.normals, 0, merged.normals, vOff * 3, t.vertexCount * 3);
                for (int i = 0; i < t.indices.length; i++) {
                    merged.indices[iOff + i] = t.indices[i] + vAccum;
                }
                vOff += t.vertexCount;
                iOff += t.indices.length;
                vAccum += t.vertexCount;
            }
        }

        // Mesh instances with transforms
        for (int m = 0; m < levelMeshes.size(); m++) {
            com.sky.modelviewer.model.MeshData mesh = levelMeshes.get(m);
            float[] transform = (levelTransforms != null && m < levelTransforms.size())
                                ? levelTransforms.get(m) : null;
            int vc = mesh.vertices.size();

            for (int i = 0; i < vc; i++) {
                float[] v = mesh.vertices.get(i);
                float x = v[0] * scale, y = v[1] * scale, z = v[2] * scale;

                if (transform != null) {
                    float nx = transform[0]*x + transform[4]*y + transform[8]*z + transform[12];
                    float ny = transform[1]*x + transform[5]*y + transform[9]*z + transform[13];
                    float nz = transform[2]*x + transform[6]*y + transform[10]*z + transform[14];
                    x = nx; y = ny; z = nz;
                }
                merged.positions[(vOff + i) * 3] = x;
                merged.positions[(vOff + i) * 3 + 1] = y;
                merged.positions[(vOff + i) * 3 + 2] = z;

                byte[] attr = mesh.packedVertexAttrs.get(i);
                float nx = snormByte(attr[0]), ny = snormByte(attr[1]), nz = snormByte(attr[2]);
                if (transform != null) {
                    float tx = transform[0]*nx + transform[4]*ny + transform[8]*nz;
                    float ty = transform[1]*nx + transform[5]*ny + transform[9]*nz;
                    float tz = transform[2]*nx + transform[6]*ny + transform[10]*nz;
                    nx = tx; ny = ty; nz = tz;
                    float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
                    if (len > 0.0001f) { nx /= len; ny /= len; nz /= len; }
                }
                merged.normals[(vOff + i) * 3] = nx;
                merged.normals[(vOff + i) * 3 + 1] = ny;
                merged.normals[(vOff + i) * 3 + 2] = nz;
            }

            for (int i = 0; i < mesh.indices.size(); i++) {
                int[] tri = mesh.indices.get(i);
                merged.indices[iOff + i * 3] = tri[0] + vAccum;
                merged.indices[iOff + i * 3 + 1] = tri[1] + vAccum;
                merged.indices[iOff + i * 3 + 2] = tri[2] + vAccum;
            }

            vOff += vc;
            iOff += mesh.indices.size() * 3;
            vAccum += vc;
        }

        exportTerrain(outputStream, merged, 1.0f, flipCoordinates);
    }

    /**
     * Export a complete level with textures: terrain (vertex colors via COLOR_0) +
     * mesh instances (each with its own embedded texture via baseColorTexture).
     *
     * GLB buffer layout:
     *   - terrain: positions + normals + colors(COLOR_0 VEC3 float) + indices
     *   - per mesh: positions + normals + uv0 + indices
     *   - per textured mesh: texture image data (4-byte aligned)
     *
     * JSON structure: one mesh with multiple primitives (terrain + each mesh instance),
     * one material for terrain (white baseColorFactor, vertex color), one material per
     * textured mesh (baseColorTexture pointing to its own image).
     *
     * @param outputStream    Destination output stream.
     * @param terrains        Terrain data array (positions/normals/colors/indices), or null.
     * @param levelMeshes     Mesh instances (positions/normals/uv0/indices/packedVertexAttrs).
     * @param levelTransforms 4x4 column-major transforms per mesh, or null.
     * @param textureDataList Texture byte data per mesh (may contain nulls).
     * @param textureMimeList Texture MIME type per mesh (may contain nulls).
     * @param scale           Uniform scale factor applied to positions.
     * @param flipCoordinates If true, applies y=-y, z=-z to positions and normals.
     */
    public static void exportCombinedLevelWithTextures(
            OutputStream outputStream,
            com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData[] terrains,
            List<com.sky.modelviewer.model.MeshData> levelMeshes,
            List<float[]> levelTransforms,
            List<byte[]> textureDataList,
            List<String> textureMimeList,
            float scale,
            boolean flipCoordinates
    ) throws java.io.IOException, org.json.JSONException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        List<BufferViewInfo> bufferViews = new ArrayList<BufferViewInfo>();
        List<AccessorInfo> accessors = new ArrayList<AccessorInfo>();
        List<int[]> primitives = new ArrayList<int[]>();
        // Each primitive int[] = {posAcc, normAcc, texAcc, idxAcc, materialIdx, type}
        //   type 0 = terrain (texAcc is COLOR_0 accessor)
        //   type 1 = mesh    (texAcc is TEXCOORD_0 accessor, or -1 if no UVs)

        List<JSONObject> materials = new ArrayList<JSONObject>();
        // Per-mesh node info: name and optional 4x4 column-major matrix (null = no matrix).
        // Each primitive in `primitives` has a matching entry here (parallel lists).
        List<String> meshNames = new ArrayList<String>();
        List<float[]> meshMatrices = new ArrayList<float[]>();
        // Texture info: parallel lists tracking each embedded texture
        List<Integer> texBufferViewIndices = new ArrayList<Integer>();
        List<String> texMimes = new ArrayList<String>();

        int nextMaterialIdx = 0;
        int nextTextureIdx = 0;

        // 只翻转X和Y轴，Z轴不翻转
        float fx = flipCoordinates ? -1f : 1f;
        float fy = flipCoordinates ? -1f : 1f;
        float fz = 1f;

        // === Material 0: Terrain (white baseColorFactor, vertex color via COLOR_0) ===
        JSONObject terrainMat = new JSONObject();
        JSONObject terrainPbr = new JSONObject();
        terrainPbr.put("metallicFactor", 0.0);
        terrainPbr.put("roughnessFactor", 1.0);
        JSONArray terrainColorFactor = new JSONArray();
        terrainColorFactor.put(1.0).put(1.0).put(1.0).put(1.0);
        terrainPbr.put("baseColorFactor", terrainColorFactor);
        terrainMat.put("pbrMetallicRoughness", terrainPbr);
        terrainMat.put("name", "terrain");
        materials.add(terrainMat);
        int terrainMatIdx = nextMaterialIdx++;

        // === Terrain primitives ===
        if (terrains != null && terrains.length > 0) {
            int totalTV = 0, totalTI = 0;
            for (int t = 0; t < terrains.length; t++) {
                totalTV += terrains[t].vertexCount;
                totalTI += terrains[t].indices.length;
            }

            // Positions
            align(buffer, 4);
            int tPosOff = buffer.size();
            float[] tPosMin = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] tPosMax = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            ByteBuffer tPosBb = ByteBuffer.allocate(totalTV * 12).order(ByteOrder.LITTLE_ENDIAN);
            for (int t = 0; t < terrains.length; t++) {
                com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData terrain = terrains[t];
                for (int i = 0; i < terrain.vertexCount; i++) {
                    float x = terrain.positions[i * 3] * scale * fx;
                    float y = terrain.positions[i * 3 + 1] * scale * fy;
                    float z = terrain.positions[i * 3 + 2] * scale * fz;
                    tPosBb.putFloat(x).putFloat(y).putFloat(z);
                    if (x < tPosMin[0]) tPosMin[0] = x;
                    if (y < tPosMin[1]) tPosMin[1] = y;
                    if (z < tPosMin[2]) tPosMin[2] = z;
                    if (x > tPosMax[0]) tPosMax[0] = x;
                    if (y > tPosMax[1]) tPosMax[1] = y;
                    if (z > tPosMax[2]) tPosMax[2] = z;
                }
            }
            byte[] tPosArr = tPosBb.array();
            buffer.write(tPosArr, 0, tPosArr.length);
            bufferViews.add(new BufferViewInfo(tPosOff, buffer.size() - tPosOff, TARGET_ARRAY_BUFFER));
            int tPosBvIdx = bufferViews.size() - 1;
            int tPosAcc = accessors.size();
            accessors.add(new AccessorInfo(tPosBvIdx, COMP_FLOAT, totalTV, "VEC3", tPosMin, tPosMax));

            // Normals
            align(buffer, 4);
            int tNormOff = buffer.size();
            ByteBuffer tNormBb = ByteBuffer.allocate(totalTV * 12).order(ByteOrder.LITTLE_ENDIAN);
            for (int t = 0; t < terrains.length; t++) {
                com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData terrain = terrains[t];
                for (int i = 0; i < terrain.vertexCount; i++) {
                    float nx = terrain.normals[i * 3] * fx;
                    float ny = terrain.normals[i * 3 + 1] * fy;
                    float nz = terrain.normals[i * 3 + 2] * fz;
                    tNormBb.putFloat(nx).putFloat(ny).putFloat(nz);
                }
            }
            byte[] tNormArr = tNormBb.array();
            buffer.write(tNormArr, 0, tNormArr.length);
            bufferViews.add(new BufferViewInfo(tNormOff, buffer.size() - tNormOff, TARGET_ARRAY_BUFFER));
            int tNormBvIdx = bufferViews.size() - 1;
            int tNormAcc = accessors.size();
            accessors.add(new AccessorInfo(tNormBvIdx, COMP_FLOAT, totalTV, "VEC3", null, null));

            // Colors (COLOR_0, VEC3 float; default gray if terrain has no colors)
            align(buffer, 4);
            int tColorOff = buffer.size();
            ByteBuffer tColorBb = ByteBuffer.allocate(totalTV * 12).order(ByteOrder.LITTLE_ENDIAN);
            for (int t = 0; t < terrains.length; t++) {
                com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData terrain = terrains[t];
                for (int i = 0; i < terrain.vertexCount; i++) {
                    float r, g, b;
                    if (terrain.colors != null && (i + 1) * 4 <= terrain.colors.length) {
                        r = terrain.colors[i * 4];
                        g = terrain.colors[i * 4 + 1];
                        b = terrain.colors[i * 4 + 2];
                    } else {
                        r = 0.5f; g = 0.5f; b = 0.5f;
                    }
                    tColorBb.putFloat(r).putFloat(g).putFloat(b);
                }
            }
            byte[] tColorArr = tColorBb.array();
            buffer.write(tColorArr, 0, tColorArr.length);
            bufferViews.add(new BufferViewInfo(tColorOff, buffer.size() - tColorOff, TARGET_ARRAY_BUFFER));
            int tColorBvIdx = bufferViews.size() - 1;
            int tColorAcc = accessors.size();
            accessors.add(new AccessorInfo(tColorBvIdx, COMP_FLOAT, totalTV, "VEC3", null, null));

            // Indices (uint16 or uint32 depending on total vertex count)
            int tIdxCount = totalTI;
            align(buffer, 4);
            boolean tUseU32 = totalTV > 65535;
            int tIdxOff = buffer.size();
            int tIdxCompType = tUseU32 ? COMP_UINT : COMP_USHORT;
            ByteBuffer tIdxBb;
            if (tUseU32) {
                tIdxBb = ByteBuffer.allocate(tIdxCount * 4).order(ByteOrder.LITTLE_ENDIAN);
            } else {
                tIdxBb = ByteBuffer.allocate(tIdxCount * 2).order(ByteOrder.LITTLE_ENDIAN);
            }
            int tVertAccum = 0;
            for (int t = 0; t < terrains.length; t++) {
                com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData terrain = terrains[t];
                for (int i = 0; i < terrain.indices.length; i++) {
                    int idx = terrain.indices[i] + tVertAccum;
                    if (tUseU32) {
                        tIdxBb.putInt(idx);
                    } else {
                        tIdxBb.putShort((short) idx);
                    }
                }
                tVertAccum += terrain.vertexCount;
            }
            byte[] tIdxArr = tIdxBb.array();
            buffer.write(tIdxArr, 0, tIdxArr.length);
            bufferViews.add(new BufferViewInfo(tIdxOff, buffer.size() - tIdxOff, TARGET_ELEMENT_ARRAY_BUFFER));
            int tIdxBvIdx = bufferViews.size() - 1;
            int tIdxAcc = accessors.size();
            accessors.add(new AccessorInfo(tIdxBvIdx, tIdxCompType, tIdxCount, "SCALAR", null, null));

            // Terrain primitive: type=0 (COLOR_0)
            primitives.add(new int[]{tPosAcc, tNormAcc, tColorAcc, tIdxAcc, terrainMatIdx, 0});
            meshNames.add("terrain");
            meshMatrices.add(null);
        }

        // === Mesh instance primitives ===
        if (levelMeshes != null) {
            for (int m = 0; m < levelMeshes.size(); m++) {
                com.sky.modelviewer.model.MeshData mesh = levelMeshes.get(m);
                float[] transform = (levelTransforms != null && m < levelTransforms.size())
                                    ? levelTransforms.get(m) : null;
                // When a transform exists it is applied via the node matrix (not baked
                // into vertices). When absent, flip is baked into vertices instead.
                boolean hasMatrix = transform != null;
                int vc = mesh.vertices.size();
                if (vc == 0) continue;

                byte[] texData = (textureDataList != null && m < textureDataList.size())
                                 ? textureDataList.get(m) : null;
                String texMime = (textureMimeList != null && m < textureMimeList.size())
                                 ? textureMimeList.get(m) : null;
                boolean hasTexture = texData != null && texData.length > 0
                                     && texMime != null && !texMime.isEmpty();
                boolean hasUvs = mesh.uv0 != null && !mesh.uv0.isEmpty();

                // Positions (scale always; flip only when no node matrix; transform via node matrix)
                align(buffer, 4);
                int mPosOff = buffer.size();
                float[] mPosMin = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
                float[] mPosMax = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                ByteBuffer mPosBb = ByteBuffer.allocate(vc * 12).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < vc; i++) {
                    float[] v = mesh.vertices.get(i);
                    float x = v[0] * scale;
                    float y = v[1] * scale;
                    float z = v[2] * scale;
                    if (!hasMatrix) {
                        x = x * fx;
                        y = y * fy;
                        z = z * fz;
                    }
                    mPosBb.putFloat(x).putFloat(y).putFloat(z);
                    if (x < mPosMin[0]) mPosMin[0] = x;
                    if (y < mPosMin[1]) mPosMin[1] = y;
                    if (z < mPosMin[2]) mPosMin[2] = z;
                    if (x > mPosMax[0]) mPosMax[0] = x;
                    if (y > mPosMax[1]) mPosMax[1] = y;
                    if (z > mPosMax[2]) mPosMax[2] = z;
                }
                byte[] mPosArr = mPosBb.array();
                buffer.write(mPosArr, 0, mPosArr.length);
                bufferViews.add(new BufferViewInfo(mPosOff, buffer.size() - mPosOff, TARGET_ARRAY_BUFFER));
                int mPosBvIdx = bufferViews.size() - 1;
                int mPosAcc = accessors.size();
                accessors.add(new AccessorInfo(mPosBvIdx, COMP_FLOAT, vc, "VEC3", mPosMin, mPosMax));

                // Normals (flip only when no node matrix; rotation handled by node matrix)
                align(buffer, 4);
                int mNormOff = buffer.size();
                ByteBuffer mNormBb = ByteBuffer.allocate(vc * 12).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < vc; i++) {
                    byte[] attr = mesh.packedVertexAttrs.get(i);
                    float nx = snormByte(attr[0]);
                    float ny = snormByte(attr[1]);
                    float nz = snormByte(attr[2]);
                    if (!hasMatrix) {
                        nx = nx * fx;
                        ny = ny * fy;
                        nz = nz * fz;
                    }
                    mNormBb.putFloat(nx).putFloat(ny).putFloat(nz);
                }
                byte[] mNormArr = mNormBb.array();
                buffer.write(mNormArr, 0, mNormArr.length);
                bufferViews.add(new BufferViewInfo(mNormOff, buffer.size() - mNormOff, TARGET_ARRAY_BUFFER));
                int mNormBvIdx = bufferViews.size() - 1;
                int mNormAcc = accessors.size();
                accessors.add(new AccessorInfo(mNormBvIdx, COMP_FLOAT, vc, "VEC3", null, null));

                // UVs (flip V: v = 1.0 - v)
                int mUvAcc = -1;
                if (hasUvs) {
                    align(buffer, 4);
                    int mUvOff = buffer.size();
                    ByteBuffer mUvBb = ByteBuffer.allocate(vc * 8).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < vc; i++) {
                        float[] uv = mesh.uv0.get(i);
                        float u = uv[0];
                        float v = 1.0f - uv[1];
                        mUvBb.putFloat(u).putFloat(v);
                    }
                    byte[] mUvArr = mUvBb.array();
                    buffer.write(mUvArr, 0, mUvArr.length);
                    bufferViews.add(new BufferViewInfo(mUvOff, buffer.size() - mUvOff, TARGET_ARRAY_BUFFER));
                    int mUvBvIdx = bufferViews.size() - 1;
                    mUvAcc = accessors.size();
                    accessors.add(new AccessorInfo(mUvBvIdx, COMP_FLOAT, vc, "VEC2", null, null));
                }

                // Indices (uint16 or uint32)
                int mIdxCount = mesh.indices.size() * 3;
                align(buffer, 4);
                boolean mUseU32 = vc > 65535;
                int mIdxOff = buffer.size();
                int mIdxCompType = mUseU32 ? COMP_UINT : COMP_USHORT;
                ByteBuffer mIdxBb;
                if (mUseU32) {
                    mIdxBb = ByteBuffer.allocate(mIdxCount * 4).order(ByteOrder.LITTLE_ENDIAN);
                } else {
                    mIdxBb = ByteBuffer.allocate(mIdxCount * 2).order(ByteOrder.LITTLE_ENDIAN);
                }
                for (int i = 0; i < mesh.indices.size(); i++) {
                    int[] tri = mesh.indices.get(i);
                    if (mUseU32) {
                        mIdxBb.putInt(tri[0]).putInt(tri[1]).putInt(tri[2]);
                    } else {
                        mIdxBb.putShort((short) tri[0]).putShort((short) tri[1]).putShort((short) tri[2]);
                    }
                }
                byte[] mIdxArr = mIdxBb.array();
                buffer.write(mIdxArr, 0, mIdxArr.length);
                bufferViews.add(new BufferViewInfo(mIdxOff, buffer.size() - mIdxOff, TARGET_ELEMENT_ARRAY_BUFFER));
                int mIdxBvIdx = bufferViews.size() - 1;
                int mIdxAcc = accessors.size();
                accessors.add(new AccessorInfo(mIdxBvIdx, mIdxCompType, mIdxCount, "SCALAR", null, null));

                // Material: reuse terrain material by default; create textured material if applicable
                int meshMatIdx = terrainMatIdx;
                if (hasTexture && hasUvs) {
                    // Write texture image data (4-byte aligned)
                    align(buffer, 4);
                    int texOff = buffer.size();
                    buffer.write(texData, 0, texData.length);
                    int texViewIdx = bufferViews.size();
                    bufferViews.add(new BufferViewInfo(texOff, texData.length, null));

                    // Create material with baseColorTexture
                    int texIdx = nextTextureIdx++;
                    JSONObject meshMat = new JSONObject();
                    JSONObject meshPbr = new JSONObject();
                    meshPbr.put("metallicFactor", 0.0);
                    meshPbr.put("roughnessFactor", 1.0);
                    JSONObject baseColorTex = new JSONObject();
                    baseColorTex.put("index", texIdx);
                    meshPbr.put("baseColorTexture", baseColorTex);
                    meshMat.put("pbrMetallicRoughness", meshPbr);
                    meshMat.put("name", mesh.name != null ? mesh.name : "mesh_" + m);
                    meshMatIdx = nextMaterialIdx++;
                    materials.add(meshMat);

                    // Track texture/image info
                    texBufferViewIndices.add(Integer.valueOf(texViewIdx));
                    texMimes.add(texMime);
                }

                // Mesh primitive: type=1 (TEXCOORD_0)
                primitives.add(new int[]{mPosAcc, mNormAcc, mUvAcc, mIdxAcc, meshMatIdx, 1});
                meshNames.add(mesh.name != null ? mesh.name : "mesh_" + m);
                if (hasMatrix) {
                    // 只翻转X和Y轴，Z轴不翻转
                    float[] nodeMatrix = new float[16];
                    for (int mi = 0; mi < 16; mi++) nodeMatrix[mi] = transform[mi];
                    // x行（索引0,4,8,12）
                    nodeMatrix[0] = -nodeMatrix[0];
                    nodeMatrix[4] = -nodeMatrix[4];
                    nodeMatrix[8] = -nodeMatrix[8];
                    nodeMatrix[12] = -nodeMatrix[12];
                    // y行（索引1,5,9,13）
                    nodeMatrix[1] = -nodeMatrix[1];
                    nodeMatrix[5] = -nodeMatrix[5];
                    nodeMatrix[9] = -nodeMatrix[9];
                    nodeMatrix[13] = -nodeMatrix[13];
                    meshMatrices.add(nodeMatrix);
                } else {
                    meshMatrices.add(null);
                }
            }
        }

        if (primitives.isEmpty()) {
            throw new IllegalArgumentException("No terrain or mesh data to export.");
        }

        byte[] binData = buffer.toByteArray();

        // === Build GLTF JSON ===
        JSONObject gltf = new JSONObject();
        JSONObject asset = new JSONObject();
        asset.put("version", "2.0");
        asset.put("generator", "SkyModelViewer");
        gltf.put("asset", asset);
        gltf.put("scene", 0);

        JSONArray scenes = new JSONArray();
        JSONObject scene = new JSONObject();
        JSONArray sceneNodes = new JSONArray();
        for (int i = 0; i < primitives.size(); i++) {
            sceneNodes.put(i);
        }
        scene.put("nodes", sceneNodes);
        scenes.put(scene);
        gltf.put("scenes", scenes);

        // Nodes: one node per mesh, each referencing its own mesh with an optional
        // transform matrix. This makes every mesh instance a separate, editable object
        // when imported into tools such as Blender.
        JSONArray nodes = new JSONArray();
        for (int i = 0; i < primitives.size(); i++) {
            JSONObject meshNode = new JSONObject();
            meshNode.put("mesh", i);
            meshNode.put("name", meshNames.get(i));
            float[] nodeMatrix = meshMatrices.get(i);
            if (nodeMatrix != null) {
                JSONArray matrixArr = new JSONArray();
                for (int j = 0; j < 16; j++) {
                    matrixArr.put((double) nodeMatrix[j]);
                }
                meshNode.put("matrix", matrixArr);
            }
            nodes.put(meshNode);
        }
        gltf.put("nodes", nodes);

        // Meshes: one mesh per primitive (single primitive each) so that each mesh
        // instance is an independent mesh definition referenced by its own node.
        JSONArray meshesJson = new JSONArray();
        for (int i = 0; i < primitives.size(); i++) {
            int[] prim = primitives.get(i);
            JSONObject attrs = new JSONObject();
            attrs.put("POSITION", prim[0]);
            attrs.put("NORMAL", prim[1]);
            if (prim[2] >= 0) {
                if (prim[5] == 0) {
                    // Terrain: COLOR_0
                    attrs.put("COLOR_0", prim[2]);
                } else {
                    // Mesh: TEXCOORD_0
                    attrs.put("TEXCOORD_0", prim[2]);
                }
            }
            JSONObject primObj = new JSONObject();
            primObj.put("attributes", attrs);
            primObj.put("indices", prim[3]);
            primObj.put("material", prim[4]);
            primObj.put("mode", 4);
            JSONArray primsJson = new JSONArray();
            primsJson.put(primObj);
            JSONObject meshObj = new JSONObject();
            meshObj.put("name", meshNames.get(i));
            meshObj.put("primitives", primsJson);
            meshesJson.put(meshObj);
        }
        gltf.put("meshes", meshesJson);

        // Materials
        JSONArray materialsJson = new JSONArray();
        for (int i = 0; i < materials.size(); i++) {
            materialsJson.put(materials.get(i));
        }
        gltf.put("materials", materialsJson);

        // Accessors
        JSONArray accessorsJson = new JSONArray();
        for (int i = 0; i < accessors.size(); i++) {
            AccessorInfo acc = accessors.get(i);
            JSONObject accObj = new JSONObject();
            accObj.put("bufferView", acc.bufferView);
            accObj.put("componentType", acc.componentType);
            accObj.put("count", acc.count);
            accObj.put("type", acc.type);
            if (acc.min != null) {
                JSONArray minArr = new JSONArray();
                for (int j = 0; j < acc.min.length; j++) minArr.put((double) acc.min[j]);
                accObj.put("min", minArr);
            }
            if (acc.max != null) {
                JSONArray maxArr = new JSONArray();
                for (int j = 0; j < acc.max.length; j++) maxArr.put((double) acc.max[j]);
                accObj.put("max", maxArr);
            }
            accessorsJson.put(accObj);
        }
        gltf.put("accessors", accessorsJson);

        // BufferViews
        JSONArray bufferViewsJson = new JSONArray();
        for (int i = 0; i < bufferViews.size(); i++) {
            BufferViewInfo bv = bufferViews.get(i);
            JSONObject bvObj = new JSONObject();
            bvObj.put("buffer", 0);
            bvObj.put("byteOffset", bv.offset);
            bvObj.put("byteLength", bv.length);
            if (bv.target != null) bvObj.put("target", bv.target);
            bufferViewsJson.put(bvObj);
        }
        gltf.put("bufferViews", bufferViewsJson);

        // Buffers
        JSONArray buffers = new JSONArray();
        JSONObject bufferObj = new JSONObject();
        bufferObj.put("byteLength", binData.length);
        buffers.put(bufferObj);
        gltf.put("buffers", buffers);

        // Textures, Images, Samplers (only if there are textured meshes)
        if (!texBufferViewIndices.isEmpty()) {
            JSONArray images = new JSONArray();
            JSONArray textures = new JSONArray();
            for (int i = 0; i < texBufferViewIndices.size(); i++) {
                JSONObject img = new JSONObject();
                img.put("bufferView", texBufferViewIndices.get(i).intValue());
                img.put("mimeType", texMimes.get(i));
                images.put(img);

                JSONObject tex = new JSONObject();
                tex.put("source", i);
                tex.put("sampler", 0);
                textures.put(tex);
            }
            gltf.put("images", images);
            gltf.put("textures", textures);

            JSONArray samplers = new JSONArray();
            JSONObject sampler = new JSONObject();
            sampler.put("wrapS", 33071); // CLAMP_TO_EDGE
            sampler.put("wrapT", 33071);
            samplers.put(sampler);
            gltf.put("samplers", samplers);
        }

        // === Write GLB ===
        writeGlb(outputStream, gltf, binData);
    }

    private static float snormByte(byte b) {
        int u = b & 0xFF;
        int s = u >= 128 ? u - 256 : u;
        float v = s / 127.0f;
        if (v < -1.0f) v = -1.0f;
        if (v > 1.0f) v = 1.0f;
        return v;
    }

    private static byte[] padArray(byte[] arr, byte pad) {
        byte[] result = new byte[arr.length + 1];
        System.arraycopy(arr, 0, result, 0, arr.length);
        result[arr.length] = pad;
        return result;
    }


    private static class BufferViewInfo {
        final int offset;
        final int length;
        final Integer target;

        BufferViewInfo(int offset, int length, Integer target) {
            this.offset = offset;
            this.length = length;
            this.target = target;
        }
    }

    private static class AccessorInfo {
        final int bufferView;
        final int componentType;
        final int count;
        final String type;
        final float[] min;
        final float[] max;

        AccessorInfo(int bufferView, int componentType, int count, String type, float[] min, float[] max) {
            this.bufferView = bufferView;
            this.componentType = componentType;
            this.count = count;
            this.type = type;
            this.min = min;
            this.max = max;
        }
    }

    /**
     * Exports a mesh to a GLB stream with optional embedded texture and skeleton.
     *
     * @param outputStream    Destination output stream.
     * @param meshData        Parsed mesh data.
     * @param textureData     Optional PNG/JPEG byte data to embed as the diffuse texture.
     * @param textureMimeType MIME type of textureData, or null.
     * @param scale           Uniform scale factor applied to positions.
     * @param flipCoordinates If true, applies a 180-degree Y-axis rotation (-x, y, -z).
     */
    public static void export(
            OutputStream outputStream,
            MeshData meshData,
            byte[] textureData,
            String textureMimeType,
            float scale,
            boolean flipCoordinates
    ) throws java.io.IOException, org.json.JSONException {
        if (meshData.vertices.isEmpty()) {
            throw new IllegalArgumentException("Mesh has no vertices.");
        }
        if (meshData.indices.isEmpty()) {
            throw new IllegalArgumentException("Mesh has no indices.");
        }

        List<float[]> vertices = meshData.vertices;
        List<float[]> uvs = meshData.uv0;
        List<int[]> indices = meshData.indices;
        List<List<BoneWeight>> boneWeights = meshData.boneWeights;
        List<SkeletonBone> skeleton = meshData.embeddedSkeleton;
        boolean isSkinned = skeleton != null && !skeleton.isEmpty() && !boneWeights.isEmpty();
        // GLTF requires JOINTS_0/WEIGHTS_0 to have same count as POSITION.
        if (isSkinned && boneWeights.size() != vertices.size()) isSkinned = false;
        boolean hasTexture = textureData != null && textureData.length > 0 && textureMimeType != null && !textureMimeType.isEmpty();
        boolean hasUvs = !uvs.isEmpty();

        float fx, fy, fz;
        if (flipCoordinates) {
            fx = -1f;
            fy = 1f;
            fz = -1f;
        } else {
            fx = 1f;
            fy = 1f;
            fz = 1f;
        }

        // === Build binary buffer ===
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        List<BufferViewInfo> bufferViews = new ArrayList<>();
        List<AccessorInfo> accessors = new ArrayList<>();

        // 1. Positions
        align(buffer, 4);
        int posOffset = buffer.size();
        float[] posMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] posMax = {Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE};
        ByteBuffer posBb = ByteBuffer.allocate(vertices.size() * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] v : vertices) {
            float x = v[0];
            float y = v[1];
            float z = v[2];
            float px = x * fx * scale;
            float py = y * fy * scale;
            float pz = z * fz * scale;
            posBb.putFloat(px);
            posBb.putFloat(py);
            posBb.putFloat(pz);
            if (px < posMin[0]) posMin[0] = px;
            if (py < posMin[1]) posMin[1] = py;
            if (pz < posMin[2]) posMin[2] = pz;
            if (px > posMax[0]) posMax[0] = px;
            if (py > posMax[1]) posMax[1] = py;
            if (pz > posMax[2]) posMax[2] = pz;
        }
        byte[] posArr = posBb.array();
        buffer.write(posArr, 0, posArr.length);
        bufferViews.add(new BufferViewInfo(posOffset, buffer.size() - posOffset, TARGET_ARRAY_BUFFER));
        int posAccIdx = accessors.size();
        accessors.add(new AccessorInfo(posAccIdx, COMP_FLOAT, vertices.size(), "VEC3", posMin, posMax));

        // 2. Normals
        List<float[]> normals = computeSmoothNormals(vertices, indices);
        align(buffer, 4);
        int normOffset = buffer.size();
        ByteBuffer normBb = ByteBuffer.allocate(normals.size() * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] n : normals) {
            float nx = n[0];
            float ny = n[1];
            float nz = n[2];
            normBb.putFloat(nx * fx);
            normBb.putFloat(ny * fy);
            normBb.putFloat(nz * fz);
        }
        byte[] normArr = normBb.array();
        buffer.write(normArr, 0, normArr.length);
        bufferViews.add(new BufferViewInfo(normOffset, buffer.size() - normOffset, TARGET_ARRAY_BUFFER));
        int normAccIdx = accessors.size();
        accessors.add(new AccessorInfo(normAccIdx, COMP_FLOAT, normals.size(), "VEC3", null, null));

        // 3. UVs
        int uvAccIdx = -1;
        if (hasUvs) {
            align(buffer, 4);
            int uvOffset = buffer.size();
            ByteBuffer uvBb = ByteBuffer.allocate(uvs.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] uv : uvs) {
                float u = uv[0];
                float v = uv[1];
                uvBb.putFloat(u);
                uvBb.putFloat(v);
            }
            byte[] uvArr = uvBb.array();
            buffer.write(uvArr, 0, uvArr.length);
            bufferViews.add(new BufferViewInfo(uvOffset, buffer.size() - uvOffset, TARGET_ARRAY_BUFFER));
            uvAccIdx = accessors.size();
            accessors.add(new AccessorInfo(uvAccIdx, COMP_FLOAT, uvs.size(), "VEC2", null, null));
        }

        // 4. Joints and Weights
        int jointsAccIdx = -1;
        int weightsAccIdx = -1;
        if (isSkinned) {
            align(buffer, 4);
            int jointsOffset = buffer.size();
            ByteBuffer jointsBb = ByteBuffer.allocate(boneWeights.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < boneWeights.size(); i++) {
                List<BoneWeight> top4 = getTop4Weights(boneWeights.get(i));
                for (int j = 0; j < 4; j++) jointsBb.putShort((short) top4.get(j).boneIndex);
            }
            byte[] jointsArr = jointsBb.array();
            buffer.write(jointsArr, 0, jointsArr.length);
            bufferViews.add(new BufferViewInfo(jointsOffset, buffer.size() - jointsOffset, TARGET_ARRAY_BUFFER));
            jointsAccIdx = accessors.size();
            accessors.add(new AccessorInfo(jointsAccIdx, COMP_USHORT, boneWeights.size(), "VEC4", null, null));

            align(buffer, 4);
            int weightsOffset = buffer.size();
            ByteBuffer weightsBb = ByteBuffer.allocate(boneWeights.size() * 16).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < boneWeights.size(); i++) {
                List<BoneWeight> top4 = getTop4Weights(boneWeights.get(i));
                float total = 0f;
                for (BoneWeight bw : top4) total += bw.weight;
                for (int j = 0; j < 4; j++) weightsBb.putFloat(total > 0 ? top4.get(j).weight / total : 0f);
            }
            byte[] weightsArr = weightsBb.array();
            buffer.write(weightsArr, 0, weightsArr.length);
            bufferViews.add(new BufferViewInfo(weightsOffset, buffer.size() - weightsOffset, TARGET_ARRAY_BUFFER));
            weightsAccIdx = accessors.size();
            accessors.add(new AccessorInfo(weightsAccIdx, COMP_FLOAT, boneWeights.size(), "VEC4", null, null));
        }

        // 5. Indices
        align(buffer, 4);
        boolean useUint32 = vertices.size() > 65535;
        int idxOffset = buffer.size();
        int idxCompType = useUint32 ? COMP_UINT : COMP_USHORT;
        ByteBuffer idxBb;
        if (useUint32) {
            idxBb = ByteBuffer.allocate(indices.size() * 12).order(ByteOrder.LITTLE_ENDIAN);
        } else {
            idxBb = ByteBuffer.allocate(indices.size() * 6).order(ByteOrder.LITTLE_ENDIAN);
        }
        for (int[] tri : indices) {
            int a = tri[0];
            int b = tri[1];
            int c = tri[2];
            if (useUint32) {
                idxBb.putInt(a);
                idxBb.putInt(b);
                idxBb.putInt(c);
            } else {
                idxBb.putShort((short) a);
                idxBb.putShort((short) b);
                idxBb.putShort((short) c);
            }
        }
        byte[] idxArr = idxBb.array();
        buffer.write(idxArr, 0, idxArr.length);
        bufferViews.add(new BufferViewInfo(idxOffset, buffer.size() - idxOffset, TARGET_ELEMENT_ARRAY_BUFFER));
        int idxAccIdx = accessors.size();
        accessors.add(new AccessorInfo(idxAccIdx, idxCompType, indices.size() * 3, "SCALAR", null, null));

        // 6. Inverse Bind Matrices
        int ibmAccIdx = -1;
        if (isSkinned && skeleton != null) {
            align(buffer, 4);
            int ibmOffset = buffer.size();
            ByteBuffer ibmBb = ByteBuffer.allocate(skeleton.size() * 64).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < skeleton.size(); i++) {
                float[] mat = ensureMatrix16(skeleton.get(i).inverseBindMatrix);
                if (flipCoordinates) mat = flipMatrix(mat);
                // Transpose row-major to column-major for GLTF
                for (int col = 0; col < 4; col++)
                    for (int row = 0; row < 4; row++)
                        ibmBb.putFloat(mat[row * 4 + col]);
            }
            byte[] ibmArr = ibmBb.array();
            buffer.write(ibmArr, 0, ibmArr.length);
            bufferViews.add(new BufferViewInfo(ibmOffset, buffer.size() - ibmOffset, null));
            ibmAccIdx = accessors.size();
            accessors.add(new AccessorInfo(ibmAccIdx, COMP_FLOAT, skeleton.size(), "MAT4", null, null));
        }

        // 7. Texture image data
        int texViewIdx = -1;
        if (hasTexture && textureData != null) {
            align(buffer, 4);
            int texOffset = buffer.size();
            buffer.write(textureData, 0, textureData.length);
            texViewIdx = bufferViews.size();
            bufferViews.add(new BufferViewInfo(texOffset, textureData.length, null));
        }

        byte[] binData = buffer.toByteArray();

        // === Build GLTF JSON ===
        JSONObject gltf = buildGltfJson(
                meshData.name, isSkinned, skeleton,
                posAccIdx, normAccIdx, uvAccIdx,
                jointsAccIdx, weightsAccIdx, idxAccIdx, ibmAccIdx,
                texViewIdx, textureMimeType,
                hasTexture, hasUvs,
                bufferViews, accessors, binData.length,
                flipCoordinates
        );

        // === Write GLB ===
        writeGlb(outputStream, gltf, binData);
    }

    // === Normal computation ===

    private static List<float[]> computeSmoothNormals(
            List<float[]> vertices,
            List<int[]> indices
    ) {
        float[][] normals = new float[vertices.size()][3];

        for (int[] tri : indices) {
            int a = tri[0];
            int b = tri[1];
            int c = tri[2];
            if (a < 0 || a >= vertices.size() ||
                b < 0 || b >= vertices.size() ||
                c < 0 || c >= vertices.size()) continue;

            float[] va = vertices.get(a);
            float[] vb = vertices.get(b);
            float[] vc = vertices.get(c);

            float ux = vb[0] - va[0];
            float uy = vb[1] - va[1];
            float uz = vb[2] - va[2];
            float vx = vc[0] - va[0];
            float vy = vc[1] - va[1];
            float vz = vc[2] - va[2];

            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;

            normals[a][0] += nx; normals[a][1] += ny; normals[a][2] += nz;
            normals[b][0] += nx; normals[b][1] += ny; normals[b][2] += nz;
            normals[c][0] += nx; normals[c][1] += ny; normals[c][2] += nz;
        }

        List<float[]> result = new ArrayList<>(normals.length);
        for (int i = 0; i < normals.length; i++) {
            float[] n = normals[i];
            float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (len > 1e-10f) {
                normals[i] = new float[]{n[0] / len, n[1] / len, n[2] / len};
            } else {
                normals[i] = new float[]{0f, 1f, 0f};
            }
            result.add(normals[i]);
        }

        return result;
    }

    // === Matrix helpers ===

    private static float[] flipMatrix(float[] m) {
        float[] result = new float[16];
        for (int i = 0; i < 16; i++) result[i] = m[i] * FLIP_SIGNS[i];
        return result;
    }

    private static float[] matrixToColumnMajor(float[] m) {
        return new float[]{
                m[0], m[1], m[2], m[3],
                m[4], m[5], m[6], m[7],
                m[8], m[9], m[10], m[11],
                m[12], m[13], m[14], m[15]
        };
    }

    // === Weight helpers ===

    private static List<BoneWeight> getTop4Weights(List<BoneWeight> weights) {
        java.util.Comparator<BoneWeight> cmp = new java.util.Comparator<BoneWeight>() {
            @Override
            public int compare(BoneWeight a, BoneWeight b) {
                return Float.compare(b.weight, a.weight);
            }
        };
        if (weights.size() >= 4) {
            List<BoneWeight> sorted = new ArrayList<BoneWeight>(weights);
            Collections.sort(sorted, cmp);
            List<BoneWeight> top4 = new ArrayList<BoneWeight>(4);
            for (int i = 0; i < 4; i++) top4.add(sorted.get(i));
            return top4;
        }
        List<BoneWeight> result = new ArrayList<BoneWeight>(4);
        result.add(new BoneWeight(0, 0f));
        result.add(new BoneWeight(0, 0f));
        result.add(new BoneWeight(0, 0f));
        result.add(new BoneWeight(0, 0f));
        List<BoneWeight> sorted = new ArrayList<BoneWeight>(weights);
        Collections.sort(sorted, cmp);
        for (int idx = 0; idx < sorted.size(); idx++) result.set(idx, sorted.get(idx));
        return result;
    }

    private static float[] ensureMatrix16(float[] m) {
        if (m.length >= 16) return m;
        float[] result = new float[16];
        result[0] = 1f; result[5] = 1f; result[10] = 1f; result[15] = 1f;
        for (int i = 0; i < m.length; i++) if (i < 16) result[i] = m[i];
        return result;
    }

    // === Buffer alignment ===

    private static void align(ByteArrayOutputStream stream, int alignment) {
        int remainder = stream.size() % alignment;
        if (remainder <= 0) return;
        int padding = alignment - remainder;
        stream.write(new byte[padding], 0, padding);
    }

    // === GLTF JSON construction ===

    private static JSONObject buildGltfJson(
            String meshName,
            boolean isSkinned,
            List<SkeletonBone> skeleton,
            int posAccIdx, int normAccIdx, int uvAccIdx,
            int jointsAccIdx, int weightsAccIdx, int idxAccIdx, int ibmAccIdx,
            int texViewIdx, String textureMimeType,
            boolean hasTexture, boolean hasUvs,
            List<BufferViewInfo> bufferViews,
            List<AccessorInfo> accessors,
            int binLength,
            boolean flipCoordinates
    ) throws org.json.JSONException {
        // --- Compute skeleton local transforms ---
        List<Integer> rootJoints = null;
        List<List<Integer>> childrenOf = null;
        float[][] localTransforms = null;

        if (isSkinned && skeleton != null) {
            // World bind transforms = inverse(IBM)
            float[][] worldTransforms = new float[skeleton.size()][16];
            for (int i = 0; i < skeleton.size(); i++) {
                float[] ibm = ensureMatrix16(skeleton.get(i).inverseBindMatrix);
                if (flipCoordinates) ibm = flipMatrix(ibm);
                worldTransforms[i] = invertMatrix4x4(ibm);
            }

            // Local transforms = inverse(parent_world) * world
            localTransforms = new float[skeleton.size()][16];
            for (int i = 0; i < skeleton.size(); i++) {
                int parent = skeleton.get(i).parentIndex;
                if (parent >= 0 && parent < skeleton.size()) {
                    float[] parentInv = invertMatrix4x4(worldTransforms[parent]);
                    localTransforms[i] = multiplyMatrix4x4(parentInv, worldTransforms[i]);
                } else {
                    localTransforms[i] = worldTransforms[i];
                }
            }

            // Build children lists
            childrenOf = new ArrayList<>(skeleton.size());
            for (int i = 0; i < skeleton.size(); i++) childrenOf.add(new ArrayList<>());
            rootJoints = new ArrayList<>();
            for (int i = 0; i < skeleton.size(); i++) {
                int parent = skeleton.get(i).parentIndex;
                if (parent >= 0 && parent < skeleton.size()) {
                    childrenOf.get(parent).add(i);
                } else {
                    rootJoints.add(i);
                }
            }
        }

        // --- Build nodes array ---
        JSONArray nodes = new JSONArray();

        // Node 0: mesh node
        JSONObject meshNode = new JSONObject();
        meshNode.put("mesh", 0);
        meshNode.put("name", meshName);
        if (isSkinned && skeleton != null) {
            meshNode.put("skin", 0);
        }
        nodes.put(meshNode);

        // Joint nodes (index = boneIndex + 1)
        if (isSkinned && skeleton != null && localTransforms != null) {
            for (int i = 0; i < skeleton.size(); i++) {
                JSONObject jointNode = new JSONObject();
                jointNode.put("name", skeleton.get(i).name);

                float[] matArray = matrixToColumnMajor(localTransforms[i]);
                JSONArray matrixJson = new JSONArray();
                for (float f : matArray) matrixJson.put((double) f);
                jointNode.put("matrix", matrixJson);

                if (childrenOf != null && !childrenOf.get(i).isEmpty()) {
                    JSONArray childArray = new JSONArray();
                    for (int ci : childrenOf.get(i)) childArray.put(ci + 1);
                    jointNode.put("children", childArray);
                }

                nodes.put(jointNode);
            }
        }

        // --- Build scene nodes ---
        JSONArray sceneNodes = new JSONArray();
        sceneNodes.put(0); // mesh node
        if (isSkinned && rootJoints != null) {
            for (int ri : rootJoints) sceneNodes.put(ri + 1);
        }

        // --- Build mesh primitives ---
        JSONObject attributes = new JSONObject();
        attributes.put("POSITION", posAccIdx);
        attributes.put("NORMAL", normAccIdx);
        if (hasUvs && uvAccIdx >= 0) attributes.put("TEXCOORD_0", uvAccIdx);
        if (isSkinned && jointsAccIdx >= 0 && weightsAccIdx >= 0) {
            attributes.put("JOINTS_0", jointsAccIdx);
            attributes.put("WEIGHTS_0", weightsAccIdx);
        }

        JSONObject primitive = new JSONObject();
        primitive.put("attributes", attributes);
        primitive.put("indices", idxAccIdx);
        primitive.put("material", 0);

        // --- Build materials ---
        JSONObject pbr = new JSONObject();
        pbr.put("metallicFactor", 0.0);
        pbr.put("roughnessFactor", 1.0);

        if (hasTexture && hasUvs) {
            pbr.put("baseColorTexture", new JSONObject().put("index", 0));
        } else {
            JSONArray baseColorFactor = new JSONArray();
            baseColorFactor.put(0.84).put(0.87).put(0.91).put(1.0);
            pbr.put("baseColorFactor", baseColorFactor);
        }

        JSONObject material = new JSONObject();
        material.put("pbrMetallicRoughness", pbr);
        material.put("name", "default");

        // --- Build textures, images, samplers ---
        JSONArray textures = null;
        JSONArray images = null;
        JSONArray samplers = null;

        if (hasTexture && hasUvs && texViewIdx >= 0) {
            textures = new JSONArray();
            JSONObject tex = new JSONObject();
            tex.put("source", 0);
            tex.put("sampler", 0);
            textures.put(tex);

            images = new JSONArray();
            JSONObject img = new JSONObject();
            img.put("bufferView", texViewIdx);
            img.put("mimeType", textureMimeType != null ? textureMimeType : "image/png");
            images.put(img);

            samplers = new JSONArray();
            JSONObject sampler = new JSONObject();
            sampler.put("wrapS", 33071); // CLAMP_TO_EDGE
            sampler.put("wrapT", 33071);
            samplers.put(sampler);
        }

        // --- Build skins ---
        JSONArray skins = null;
        if (isSkinned && skeleton != null) {
            JSONArray jointsArray = new JSONArray();
            for (int i = 0; i < skeleton.size(); i++) jointsArray.put(i + 1);

            skins = new JSONArray();
            JSONObject skin = new JSONObject();
            skin.put("joints", jointsArray);
            skin.put("inverseBindMatrices", ibmAccIdx);
            skins.put(skin);
        }

        // --- Build accessors array ---
        JSONArray accessorsJson = new JSONArray();
        for (AccessorInfo acc : accessors) {
            JSONObject accObj = new JSONObject();
            accObj.put("bufferView", acc.bufferView);
            accObj.put("componentType", acc.componentType);
            accObj.put("count", acc.count);
            accObj.put("type", acc.type);
            if (acc.min != null) {
                JSONArray minArr = new JSONArray();
                for (float f : acc.min) minArr.put((double) f);
                accObj.put("min", minArr);
            }
            if (acc.max != null) {
                JSONArray maxArr = new JSONArray();
                for (float f : acc.max) maxArr.put((double) f);
                accObj.put("max", maxArr);
            }
            accessorsJson.put(accObj);
        }

        // --- Build bufferViews array ---
        JSONArray bufferViewsJson = new JSONArray();
        for (BufferViewInfo bv : bufferViews) {
            JSONObject bvObj = new JSONObject();
            bvObj.put("buffer", 0);
            bvObj.put("byteOffset", bv.offset);
            bvObj.put("byteLength", bv.length);
            if (bv.target != null) bvObj.put("target", bv.target);
            bufferViewsJson.put(bvObj);
        }

        // --- Assemble GLTF root ---
        JSONObject gltf = new JSONObject();
        JSONObject asset = new JSONObject();
        asset.put("version", "2.0");
        asset.put("generator", "SkyModelViewer");
        gltf.put("asset", asset);
        gltf.put("scene", 0);

        JSONArray scenes = new JSONArray();
        JSONObject scene = new JSONObject();
        scene.put("nodes", sceneNodes);
        scenes.put(scene);
        gltf.put("scenes", scenes);

        gltf.put("nodes", nodes);

        JSONArray meshes = new JSONArray();
        JSONObject meshObj = new JSONObject();
        meshObj.put("name", meshName);
        JSONArray primitives = new JSONArray();
        primitives.put(primitive);
        meshObj.put("primitives", primitives);
        meshes.put(meshObj);
        gltf.put("meshes", meshes);

        JSONArray materials = new JSONArray();
        materials.put(material);
        gltf.put("materials", materials);

        gltf.put("accessors", accessorsJson);
        gltf.put("bufferViews", bufferViewsJson);

        JSONArray buffers = new JSONArray();
        JSONObject bufferObj = new JSONObject();
        bufferObj.put("byteLength", binLength);
        buffers.put(bufferObj);
        gltf.put("buffers", buffers);

        if (skins != null) gltf.put("skins", skins);
        if (textures != null) gltf.put("textures", textures);
        if (images != null) gltf.put("images", images);
        if (samplers != null) gltf.put("samplers", samplers);

        return gltf;
    }

    // === 4x4 matrix operations (row-major) ===

    private static float[] invertMatrix4x4(float[] m) {
        float[] inv = new float[16];
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
                m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
                m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
                m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
                m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
                m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
                m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
                m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
                m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
                m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
                m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
                m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
                m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
                m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
                m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
                m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
                m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];

        float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (det == 0f) return identityMatrix4x4();
        det = 1f / det;
        for (int i = 0; i < 16; i++) inv[i] *= det;
        return inv;
    }

    private static float[] multiplyMatrix4x4(float[] a, float[] b) {
        float[] result = new float[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[i * 4 + k] * b[k * 4 + j];
                }
                result[i * 4 + j] = sum;
            }
        }
        return result;
    }

    private static float[] identityMatrix4x4() {
        return new float[]{
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
        };
    }

    // === GLB file writing ===

    private static void writeGlb(OutputStream outputStream, JSONObject gltf, byte[] binData) throws java.io.IOException, org.json.JSONException {
        String jsonStr = gltf.toString();
        byte[] jsonBytes = jsonStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Pad JSON chunk to 4-byte alignment with spaces (0x20)
        int jsonPadding = (4 - jsonBytes.length % 4) % 4;
        if (jsonPadding > 0) {
            byte[] padded = new byte[jsonBytes.length + jsonPadding];
            System.arraycopy(jsonBytes, 0, padded, 0, jsonBytes.length);
            for (int i = jsonBytes.length; i < padded.length; i++) padded[i] = 0x20;
            jsonBytes = padded;
        }

        // Pad BIN chunk to 4-byte alignment with zeros (0x00)
        byte[] binDataPadded = binData;
        int binPadding = (4 - binData.length % 4) % 4;
        if (binPadding > 0) {
            byte[] padded = new byte[binData.length + binPadding];
            System.arraycopy(binData, 0, padded, 0, binData.length);
            binDataPadded = padded;
        }

        // GLB structure: 12-byte header + JSON chunk + BIN chunk
        int totalLength = 12 + (8 + jsonBytes.length) + (8 + binDataPadded.length);

        ByteBuffer headerBb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);

        // GLB header
        headerBb.putInt(GLB_MAGIC);
        headerBb.putInt(GLB_VERSION);
        headerBb.putInt(totalLength);

        // JSON chunk
        headerBb.putInt(jsonBytes.length);
        headerBb.putInt(JSON_CHUNK_TYPE);
        headerBb.put(jsonBytes);

        // BIN chunk
        headerBb.putInt(binDataPadded.length);
        headerBb.putInt(BIN_CHUNK_TYPE);
        headerBb.put(binDataPadded);

        outputStream.write(headerBb.array());
    }
}
