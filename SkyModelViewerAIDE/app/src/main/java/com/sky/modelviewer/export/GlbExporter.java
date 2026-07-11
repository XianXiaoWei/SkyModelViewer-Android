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
