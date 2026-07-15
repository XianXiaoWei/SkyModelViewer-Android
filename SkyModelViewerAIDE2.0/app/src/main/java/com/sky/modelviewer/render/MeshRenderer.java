package com.sky.modelviewer.render;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MeshRenderer implements GLSurfaceView.Renderer {

    public enum ViewMode { TEXTURE, SOLID, WIRE }

    private static final String TAG = "MeshRenderer";

    // Multi-mesh support
    public static class MeshInstance {
        public int positionBuffer;
        public int normalBuffer;
        public int colorBuffer;
        public int uvBuffer;
        public int indexBuffer;
        public int indexCount;
        public int vertexCount;
        public int textureId;
        public boolean hasTexture;
        public boolean hasVertexColors;
        public boolean useIntIndices;
        public boolean doubleSided;
        public boolean disableTexture; // Wardrobe mode: render without texture (no alpha discard)
        public boolean noAlphaDiscard; // Wardrobe mode: use texture color but don't discard transparent pixels
        public int lineIndexCount;
        public int[] storedIndices;
        public float[] transform;
        public String name;
        // Bounding sphere (in world space after transform)
        public float boundCenterX, boundCenterY, boundCenterZ;
        public float boundRadius;
        public boolean hidden = false;

        // Bone skinning data
        public int boneIndexBuffer;     // vec4 of bone indices (location=4)
        public int boneWeightBuffer;    // vec4 of bone weights (location=5)
        public boolean hasBoneWeights = false;
        public float[] originalPositions;  // CPU copy of original vertex positions (for skinning)
        public float[] originalNormals;    // CPU copy of original normals

        // Per-instance bone mapping (for wardrobe mode: each part has its own skeleton)
        public int[] instMeshBoneToAnimBone = null;
        public int instMeshBoneCount = 0;
        public float[] instMeshIBM = null;  // per-instance IBM (16*boneCount, column-major)
        public List<String> instMeshBoneNames = null; // per-instance mesh bone names
        public String wardrobeCategory = null; // "body", "hat", etc.

        // Color override (from OutfitDefs)
        public float[] baseHsv = null;  // [H, S, V] — null = no color override
        public boolean colorOverride = false;
        // Prop (背饰): use identity IBM (don't apply bone's inverse bind matrix)
        public boolean useIdentityIBM = false;

        public MeshInstance() {
            int[] buffers = new int[4];
        }
    }

    private final List<MeshInstance> meshInstances = new ArrayList<MeshInstance>();

    // Legacy single-mesh fields (kept for compatibility)

    private int program = 0;
    private int wireProgram = 0;

    private int posLoc = 0, normLoc = 0, uvLoc = 0, colorLoc = 0;
    private int mvpLoc = 0, mvLoc = 0, normalMatrixLoc = 0;
    private int lightDirLoc = 0, ambientColorLoc = 0, keyColorLoc = 0, fillColorLoc = 0;
    private int viewModeLoc = 0, textureLoc = 0, hasTextureLoc = 0, hasVertexColorsLoc = 0;
    private int noAlphaDiscardLoc = 0;
    private int wireMvpLoc = 0, wirePosLoc = 0;

    private int positionBuffer = 0, normalBuffer = 0, uvBuffer = 0, indexBuffer = 0;
    private int indexCount = 0, vertexCount = 0;

    private int textureId = 0;
    private boolean hasTexture = false;
    private android.graphics.Bitmap lastTextureBitmap = null;
    private int lastTextureWidth = 0;
    private int lastTextureHeight = 0;

    private float[] cameraTarget = new float[]{0f, 0f, 0f};
    private float cameraYaw = -2.3561944902f;
    private float cameraPitch = -0.35f;
    private double cameraDistance = 5.0;
    private float fov = 55f;
    private float near = 0.001f;
    private float far = 5000f;

    private float lightYaw = -2.0344439358f;
    private float lightPitch = -0.52f;

    private ViewMode viewMode = ViewMode.TEXTURE;
    private float scale = 1f;
    private boolean quadMode = false; // When true, disable face culling (for KTX quad)

    private float[] meshMin = new float[]{0f, 0f, 0f};
    private float[] meshMax = new float[]{0f, 0f, 0f};
    private float meshRadius = 0.5f;

    private int viewportWidth = 1;
    private int viewportHeight = 1;

    private float[] pendingPositions = null;
    private float[] pendingNormals = null;
    private float[] pendingUvs = null;
    private short[] pendingIndicesShort = null;
    private int[] pendingIndicesInt = null;
    private boolean needsBufferUpload = false;

    // ===== Animation playback state =====
    private com.sky.modelviewer.parsing.AnimPackParser.AnimPack currentAnim = null;
    private int[] animBoneParents = null;
    private int animRootIdx = 0;
    private float animTime = 0f;
    private boolean animPlaying = false;
    private float animSpeed = 1.0f;
    private float[] boneMatrices = null;  // 16 * boneCount, column-major
    private float[] invBindMatrices = null; // 16 * boneCount, column-major (from reference SQT)
    private float[] meshInverseBindMatrices = null; // 16 * meshBoneCount, column-major (from mesh embedded skeleton)
    private float[] meshIBMForAnimBone = null; // 16 * animBoneCount, column-major (mesh IBM mapped to animpack bone order)
    private float[] meshIBMInvForAnimBone = null; // inverse of meshIBMForAnimBone (W_bind matrices)
    private int[] meshBoneToAnimBone = null; // maps mesh bone index → animpack bone index
    private List<String> meshBoneNames = null; // bone names from mesh's embedded skeleton
    private int meshBoneCount = 0;
    private com.sky.modelviewer.parsing.AnimPackParser.DecodedAnimation decodedAnim = null;
    private int animFrameCount = 0;
    private int skinProgram = 0;
    private int skinMvpLoc, skinMvLoc, skinNormalMatrixLoc;
    private int skinLightDirLoc, skinAmbientLoc, skinKeyColorLoc, skinFillColorLoc;
    private int skinViewModeLoc, skinTextureLoc, skinHasTextureLoc, skinHasVertexColorsLoc;
    private int skinNoAlphaDiscardLoc;
    private int skinHasColorOverrideLoc;
    private int skinBaseHsvLoc;
    private int skinInstanceTransformLoc;
    private int skinHasInstanceTransformLoc;
    private int skinBoneMatricesLoc;
    private int skinPosLoc, skinNormLoc, skinUvLoc, skinColorLoc, skinBoneIdxLoc, skinBoneWtLoc;
    private long lastFrameTime = 0;

    public void setMesh(com.sky.modelviewer.model.MeshData meshData, float meshScale) {
        scale = meshScale;
        int vc = meshData.vertices.size();
        int ic = meshData.indices.size() * 3;

        float[] positions = new float[vc * 3];
        List<float[]> normals = computeSmoothNormals(meshData.vertices, meshData.indices);
        int pi = 0;
        for (float[] v : meshData.vertices) {
            positions[pi++] = -v[0] * meshScale;
            positions[pi++] = v[1] * meshScale;
            positions[pi++] = -v[2] * meshScale;
        }

        float[] normalArr = new float[vc * 3];
        int ni = 0;
        for (float[] n : normals) {
            normalArr[ni++] = -n[0];
            normalArr[ni++] = n[1];
            normalArr[ni++] = -n[2];
        }

        float[] uvs = new float[vc * 2];
        int ui = 0;
        for (float[] uv : meshData.uv0) {
            uvs[ui++] = uv[0];
            uvs[ui++] = uv[1];
        }
        while (ui < vc * 2) {
            uvs[ui++] = 0f;
        }

        boolean useShort = vc <= 65535;
        short[] indexArr = useShort ? new short[ic] : null;
        int[] indexArrInt = !useShort ? new int[ic] : null;
        int ii = 0;
        for (int[] tri : meshData.indices) {
            if (useShort) {
                indexArr[ii++] = (short) tri[0];
                indexArr[ii++] = (short) tri[1];
                indexArr[ii++] = (short) tri[2];
            } else {
                indexArrInt[ii++] = tri[0];
                indexArrInt[ii++] = tri[1];
                indexArrInt[ii++] = tri[2];
            }
        }

        // Use unified multi-mesh path: clear old instances, add this one
        clearMeshInstances();
        quadMode = false;
        addMeshInstance(positions, normalArr, uvs, indexArr, indexArrInt,
                        textureId, null, meshData.name);

        // Store original positions/normals for bone skinning
        if (!meshInstances.isEmpty()) {
            MeshInstance inst = meshInstances.get(meshInstances.size() - 1);
            inst.originalPositions = positions.clone();
            inst.originalNormals = normalArr.clone();

            // Upload bone weights if available
            if (meshData.boneWeights != null && !meshData.boneWeights.isEmpty()) {
                uploadBoneWeights(inst, meshData.boneWeights, meshData.embeddedSkeleton);

                // Store mesh bone names for animpack mapping
                meshBoneNames = new ArrayList<String>();
                if (meshData.embeddedSkeleton != null) {
                    for (com.sky.modelviewer.model.SkeletonBone sb : meshData.embeddedSkeleton) {
                        meshBoneNames.add(sb.name);
                    }
                }
                meshBoneCount = meshBoneNames.size();

                // Store mesh inverse bind matrices (copy as-is, same format as composeMat4)
                if (meshData.embeddedSkeleton != null && !meshData.embeddedSkeleton.isEmpty()) {
                    meshInverseBindMatrices = new float[meshBoneCount * 16];
                    for (int mi = 0; mi < meshBoneCount; mi++) {
                        float[] rm = meshData.embeddedSkeleton.get(mi).inverseBindMatrix;
                        if (rm == null || rm.length < 16) continue;
                        // Copy as-is: translation at [12,13,14] matches column-major column-vector
                        System.arraycopy(rm, 0, meshInverseBindMatrices, mi * 16, 16);
                    }
                }
            }
        }

        // Clear any previous animation
        currentAnim = null;
        animPlaying = false;
        boneMatrices = null;

        frameCamera();
    }

    /**
     * Add a wardrobe mesh part without clearing existing instances.
     * Used in wardrobe mode to layer multiple character parts.
     * Must be called on GL thread.
     */
    public void addWardrobeMesh(com.sky.modelviewer.model.MeshData meshData, float meshScale,
                                int textureId, String category) {
        addWardrobeMesh(meshData, meshScale, textureId, category, null, null, false, null);
    }

    /**
     * Add a wardrobe mesh part with optional transform (for offsets).
     */
    public void addWardrobeMesh(com.sky.modelviewer.model.MeshData meshData, float meshScale,
                                int textureId, String category, float[] transform) {
        addWardrobeMesh(meshData, meshScale, textureId, category, transform, null, false, null);
    }

    /**
     * Add a wardrobe mesh part with full options: transform, color override, prop bone name.
     */
    public void addWardrobeMesh(com.sky.modelviewer.model.MeshData meshData, float meshScale,
                                int textureId, String category, float[] transform,
                                float[] baseHsv, boolean colorOverride, String propBoneName) {
        int vc = meshData.vertices.size();
        int ic = meshData.indices.size() * 3;

        float[] positions = new float[vc * 3];
        List<float[]> normals = computeSmoothNormals(meshData.vertices, meshData.indices);
        int pi = 0;
        for (float[] v : meshData.vertices) {
            positions[pi++] = -v[0] * meshScale;
            positions[pi++] = v[1] * meshScale;
            positions[pi++] = -v[2] * meshScale;
        }

        float[] normalArr = new float[vc * 3];
        int ni = 0;
        for (float[] n : normals) {
            normalArr[ni++] = -n[0];
            normalArr[ni++] = n[1];
            normalArr[ni++] = -n[2];
        }

        float[] uvs = new float[vc * 2];
        int ui = 0;
        for (float[] uv : meshData.uv0) {
            uvs[ui++] = uv[0];
            uvs[ui++] = uv[1];
        }
        while (ui < vc * 2) {
            uvs[ui++] = 0f;
        }

        boolean useShort = vc <= 65535;
        short[] indexArr = useShort ? new short[ic] : null;
        int[] indexArrInt = !useShort ? new int[ic] : null;
        int ii = 0;
        for (int[] tri : meshData.indices) {
            if (useShort) {
                indexArr[ii++] = (short) tri[0];
                indexArr[ii++] = (short) tri[1];
                indexArr[ii++] = (short) tri[2];
            } else {
                indexArrInt[ii++] = tri[0];
                indexArrInt[ii++] = tri[1];
                indexArrInt[ii++] = tri[2];
            }
        }

        // Remove existing instance with same category
        removeWardrobeMesh(category);

        // doubleSided = true: don't cull back faces, show complete mesh
        addMeshInstance(positions, normalArr, uvs, indexArr, indexArrInt,
                        textureId, transform, meshData.name, true);

        // Set up bone weights and per-instance bone data
        if (!meshInstances.isEmpty()) {
            MeshInstance inst = meshInstances.get(meshInstances.size() - 1);
            inst.originalPositions = positions.clone();
            inst.originalNormals = normalArr.clone();
            inst.wardrobeCategory = category;
            inst.disableTexture = false;
            inst.noAlphaDiscard = true; // Use texture color but don't discard transparent pixels
            inst.baseHsv = baseHsv;    // Color override from OutfitDefs
            inst.colorOverride = colorOverride;

            if ("prop".equals(category)) {
                // Prop (背饰): load at origin with no bone weights.
                // Bone binding to L_shoulderAUX happens in setAnimation() when animation is loaded.
                inst.hasBoneWeights = false;
                // If animation is already loaded, bind immediately
                if (currentAnim != null) {
                    bindPropToAnimBone(inst, currentAnim, "L_shoulderAUX");
                }
            } else if (meshData.boneWeights != null && !meshData.boneWeights.isEmpty()) {
                uploadBoneWeights(inst, meshData.boneWeights, meshData.embeddedSkeleton);

                // Store per-instance bone names
                inst.instMeshBoneNames = new ArrayList<String>();
                if (meshData.embeddedSkeleton != null) {
                    for (com.sky.modelviewer.model.SkeletonBone sb : meshData.embeddedSkeleton) {
                        inst.instMeshBoneNames.add(sb.name);
                    }
                }

                // Store per-instance mesh IBM (from mesh file)
                if (meshData.embeddedSkeleton != null && !meshData.embeddedSkeleton.isEmpty()) {
                    int meshBC = meshData.embeddedSkeleton.size();
                    inst.instMeshIBM = new float[meshBC * 16];
                    for (int mi = 0; mi < meshBC; mi++) {
                        float[] rm = meshData.embeddedSkeleton.get(mi).inverseBindMatrix;
                        if (rm != null && rm.length >= 16) {
                            System.arraycopy(rm, 0, inst.instMeshIBM, mi * 16, 16);
                        }
                    }
                    inst.instMeshBoneCount = meshBC;
                }

                // Build bone mapping if animation is already loaded
                if (currentAnim != null) {
                    buildInstanceBoneMapping(inst, currentAnim);
                }
            }
        }

        if (skinProgram == 0 && !meshInstances.isEmpty()) {
            createSkinProgram();
        }

        // Frame camera to fit all wardrobe meshes
        frameCamera();
    }

    /**
     * Remove a wardrobe mesh by category.
     */
    public void removeWardrobeMesh(String category) {
        for (int i = meshInstances.size() - 1; i >= 0; i--) {
            MeshInstance inst = meshInstances.get(i);
            if (category != null && category.equals(inst.wardrobeCategory)) {
                if (inst.colorBuffer != 0) {
                    GLES30.glDeleteBuffers(1, new int[]{inst.colorBuffer}, 0);
                }
                int[] bufs = {inst.positionBuffer, inst.normalBuffer, inst.uvBuffer, inst.indexBuffer};
                GLES30.glDeleteBuffers(4, bufs, 0);
                meshInstances.remove(i);
            }
        }
    }

    /**
     * Clear all wardrobe meshes.
     */
    public void clearWardrobeMeshes() {
        for (int i = meshInstances.size() - 1; i >= 0; i--) {
            MeshInstance inst = meshInstances.get(i);
            if (inst.wardrobeCategory != null) {
                if (inst.colorBuffer != 0) {
                    GLES30.glDeleteBuffers(1, new int[]{inst.colorBuffer}, 0);
                }
                int[] bufs = {inst.positionBuffer, inst.normalBuffer, inst.uvBuffer, inst.indexBuffer};
                GLES30.glDeleteBuffers(4, bufs, 0);
                meshInstances.remove(i);
            }
        }
    }

    /**
     * Upload bone weight data to GPU buffers.
     */
    private void uploadBoneWeights(MeshInstance inst,
                                   List<List<com.sky.modelviewer.model.BoneWeight>> boneWeights,
                                   List<com.sky.modelviewer.model.SkeletonBone> skeleton) {
        if (boneWeights == null || boneWeights.isEmpty()) return;
        int vc = boneWeights.size();
        if (vc == 0) return;

        float[] boneIndices = new float[vc * 4];
        float[] boneWeightsArr = new float[vc * 4];

        for (int i = 0; i < vc; i++) {
            List<com.sky.modelviewer.model.BoneWeight> weights = boneWeights.get(i);
            if (weights == null || weights.isEmpty()) {
                // No bone weights: assign to bone 0 with weight 1.0 to avoid collapse
                boneIndices[i * 4] = 0;
                boneWeightsArr[i * 4] = 1.0f;
                continue;
            }
            for (int j = 0; j < Math.min(4, weights.size()); j++) {
                boneIndices[i * 4 + j] = weights.get(j).boneIndex;
                boneWeightsArr[i * 4 + j] = weights.get(j).weight;
            }
        }

        int[] bufs = new int[2];
        GLES30.glGenBuffers(2, bufs, 0);
        inst.boneIndexBuffer = bufs[0];
        inst.boneWeightBuffer = bufs[1];

        ByteBuffer idxBuf = ByteBuffer.allocateDirect(boneIndices.length * 4).order(ByteOrder.nativeOrder());
        idxBuf.asFloatBuffer().put(boneIndices).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.boneIndexBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, boneIndices.length * 4, idxBuf, GLES30.GL_STATIC_DRAW);

        ByteBuffer wtBuf = ByteBuffer.allocateDirect(boneWeightsArr.length * 4).order(ByteOrder.nativeOrder());
        wtBuf.asFloatBuffer().put(boneWeightsArr).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.boneWeightBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, boneWeightsArr.length * 4, wtBuf, GLES30.GL_STATIC_DRAW);

        inst.hasBoneWeights = true;
    }

    // ===== Animation playback =====

    /**
     * Load an animpack for playback on the current mesh.
     * Must be called on GL thread.
     */
    public void setAnimation(com.sky.modelviewer.parsing.AnimPackParser.AnimPack anim) {
        currentAnim = anim;
        if (anim == null || anim.bones == null || anim.bones.isEmpty()) {
            animPlaying = false;
            // Clear prop bone binding when animation is removed
            for (MeshInstance inst : meshInstances) {
                if (inst.wardrobeCategory != null && "prop".equals(inst.wardrobeCategory)) {
                    inst.hasBoneWeights = false;
                    inst.useIdentityIBM = false;
                    inst.instMeshBoneToAnimBone = null;
                    inst.instMeshBoneCount = 0;
                }
            }
            return;
        }
        animBoneParents = com.sky.modelviewer.parsing.AnimPackParser.getParentIndices(anim.bones);
        animRootIdx = com.sky.modelviewer.parsing.AnimPackParser.findRootIndex(anim.bones);

        // Build mesh bone → animpack bone mapping by name
        int animBoneCount = anim.bones.size();
        if (meshBoneNames == null || meshBoneNames.isEmpty()) {
            // No mesh skeleton — can't do bone mapping
            meshBoneToAnimBone = new int[0];
        } else {
            meshBoneToAnimBone = new int[meshBoneCount];
            for (int mi = 0; mi < meshBoneCount; mi++) {
                String meshName = meshBoneNames.get(mi);
                int foundIdx = -1;
                if (meshName != null) {
                    for (int ai = 0; ai < animBoneCount; ai++) {
                        if (com.sky.modelviewer.parsing.AnimPackParser.boneNamesMatch(
                                meshName, anim.bones.get(ai).name)) {
                            foundIdx = ai;
                            break;
                        }
                    }
                }
                meshBoneToAnimBone[mi] = foundIdx;
            }
        }

        // Compute inverse bind matrices from REFERENCE SQT world transforms.
        // bone.matrix in the file is the WORLD BIND MATRIX (not inverse),
        // so we must compute IBM = inverse(world_from_refSQT).
        // Reference SQT is always available (it's the bind/T-pose).
        List<com.sky.modelviewer.parsing.AnimPackParser.AnimSQT> refSqtList = null;
        if (anim.segments != null && !anim.segments.isEmpty()) {
            com.sky.modelviewer.parsing.AnimPackParser.AnimSegment seg = anim.segments.get(0);
            if (seg.sqtList != null && !seg.sqtList.isEmpty()) {
                refSqtList = seg.sqtList;
            }
        }
        // Fallback: if no reference SQT, use clip SQT
        if (refSqtList == null || refSqtList.isEmpty()) {
            refSqtList = com.sky.modelviewer.parsing.AnimPackParser.getBoneSqtList(anim);
        }

        // Build world transforms from reference SQT using parent hierarchy
        float[] worldMats = new float[animBoneCount * 16];
        for (int i = 0; i < animBoneCount; i++) {
            com.sky.modelviewer.parsing.AnimPackParser.Bone b = anim.bones.get(i);
            com.sky.modelviewer.parsing.AnimPackParser.AnimSQT sqt =
                (i < refSqtList.size()) ? refSqtList.get(i) : null;
            float[] t, r, s;
            if (sqt != null) {
                t = sqt.translation; r = sqt.rotation; s = sqt.scale;
            } else {
                t = new float[] {0,0,0}; r = new float[] {0,0,0,1}; s = new float[] {1,1,1};
            }
            float[] local = com.sky.modelviewer.parsing.AnimPackParser.composeMat4(t, r, s);
            if (b.parentIndex >= 0 && b.parentIndex < i) {
                float[] parentWorld = new float[16];
                System.arraycopy(worldMats, b.parentIndex * 16, parentWorld, 0, 16);
                float[] world = com.sky.modelviewer.parsing.AnimPackParser.multiplyMat4(parentWorld, local);
                System.arraycopy(world, 0, worldMats, i * 16, 16);
            } else {
                System.arraycopy(local, 0, worldMats, i * 16, 16);
            }
        }

        // IBM = inverse(world_from_refSQT) — column-major
        invBindMatrices = new float[animBoneCount * 16];
        for (int i = 0; i < animBoneCount; i++) {
            float[] world = new float[16];
            System.arraycopy(worldMats, i * 16, world, 0, 16);
            float[] inv = com.sky.modelviewer.parsing.AnimPackParser.invertMat4(world);
            System.arraycopy(inv, 0, invBindMatrices, i * 16, 16);
        }

        // Use animpack bone.matrix directly as the IBM for skinning.
        //
        // KEY INSIGHT: bone.matrix IS an InverseBindMatrix (IBM = inv(W_bind_mesh)),
        // confirmed by both the Python reference ("绑定姿态 IBM") and C# code.
        // It has translation at flat indices [12,13,14], same as composeMat4's
        // column-major column-vector output. Whether the file stores it as
        // row-major row-vector or column-major column-vector, copying the flat
        // array as-is gives the correct column-major column-vector matrix.
        // DO NOT TRANSPOSE — transposing puts translation at [3,7,11] (wrong).
        // DO NOT INVERT — bone.matrix is already an IBM.
        //
        // B = W_anim * bone.matrix = W_anim * inv(W_bind_mesh)
        // At frame 0: B = W_bind_anim * inv(W_bind_mesh)
        //   → mesh deforms from T-pose to animpack's first frame ✓
        // During animation: B = W_anim * inv(W_bind_mesh)
        //   → mesh follows bones ✓
        meshIBMForAnimBone = new float[animBoneCount * 16];
        for (int i = 0; i < animBoneCount; i++) {
            float[] rm = anim.bones.get(i).matrix; // IBM, flat 16 floats
            if (rm != null && rm.length >= 16) {
                System.arraycopy(rm, 0, meshIBMForAnimBone, i * 16, 16);
            } else {
                for (int j = 0; j < 16; j++) {
                    meshIBMForAnimBone[i * 16 + j] = (j % 5 == 0) ? 1f : 0f;
                }
            }
        }
        // Pre-compute inverse IBMs (W_bind = inverse(IBM)) for prop bone binding
        // IBM is affine: [R|t; 0|1], inverse = [R^T | -R^T*t; 0|1]
        meshIBMInvForAnimBone = new float[animBoneCount * 16];
        for (int i = 0; i < animBoneCount; i++) {
            float r00 = meshIBMForAnimBone[i*16+0], r10 = meshIBMForAnimBone[i*16+1], r20 = meshIBMForAnimBone[i*16+2];
            float r01 = meshIBMForAnimBone[i*16+4], r11 = meshIBMForAnimBone[i*16+5], r21 = meshIBMForAnimBone[i*16+6];
            float r02 = meshIBMForAnimBone[i*16+8], r12 = meshIBMForAnimBone[i*16+9], r22 = meshIBMForAnimBone[i*16+10];
            float tx = meshIBMForAnimBone[i*16+12], ty = meshIBMForAnimBone[i*16+13], tz = meshIBMForAnimBone[i*16+14];
            // R^T (transpose)
            meshIBMInvForAnimBone[i*16+0] = r00;  meshIBMInvForAnimBone[i*16+1] = r01;  meshIBMInvForAnimBone[i*16+2] = r02;
            meshIBMInvForAnimBone[i*16+4] = r10;  meshIBMInvForAnimBone[i*16+5] = r11;  meshIBMInvForAnimBone[i*16+6] = r12;
            meshIBMInvForAnimBone[i*16+8] = r20;  meshIBMInvForAnimBone[i*16+9] = r21;  meshIBMInvForAnimBone[i*16+10] = r22;
            // -R^T * t
            meshIBMInvForAnimBone[i*16+12] = -(r00*tx + r10*ty + r20*tz);
            meshIBMInvForAnimBone[i*16+13] = -(r01*tx + r11*ty + r21*tz);
            meshIBMInvForAnimBone[i*16+14] = -(r02*tx + r12*ty + r22*tz);
            // affine w row
            meshIBMInvForAnimBone[i*16+3] = 0; meshIBMInvForAnimBone[i*16+7] = 0;
            meshIBMInvForAnimBone[i*16+11] = 0; meshIBMInvForAnimBone[i*16+15] = 1;
        }

        // Decode animation data (all keyframe sets)
        decodedAnim = null;
        animFrameCount = 0;
        if (anim.segments != null && !anim.segments.isEmpty()) {
            com.sky.modelviewer.parsing.AnimPackParser.AnimSegment seg = anim.segments.get(0);
            if (seg.clipData != null) {
                decodedAnim = com.sky.modelviewer.parsing.AnimPackParser.decodeAnimation(anim);
                if (decodedAnim != null && decodedAnim.hasAnimation) {
                    animFrameCount = decodedAnim.frameCount;
                } else if (decodedAnim != null && decodedAnim.baseSqt != null) {
                    // Static pose (no animation, just use base SQT)
                    animFrameCount = 1;
                }
            }
        }

        boneMatrices = new float[animBoneCount * 16];
        animTime = 0f;
        animPlaying = true;

        // Create skinning program if not yet
        if (skinProgram == 0) {
            createSkinProgram();
        }

        // Initialize bone matrices
        updateBoneMatrices(0f);

        // Build per-instance bone mapping for wardrobe instances
        for (MeshInstance inst : meshInstances) {
            if (inst.wardrobeCategory == null) continue;
            
            if ("prop".equals(inst.wardrobeCategory)) {
                // Prop (背饰): bind to L_shoulderAUX bone in the animation
                // This happens when animation is loaded, not at mesh load time
                bindPropToAnimBone(inst, anim, "L_shoulderAUX");
            } else if (inst.hasBoneWeights) {
                buildInstanceBoneMapping(inst, anim);
            }
        }
    }

    /**
     * Bind a prop mesh instance to a specific bone in the animation.
     * Creates dummy bone weights (all vertices → target bone, weight 1.0)
     * and sets up identity IBM so the prop is positioned at the bone's world location.
     */
    private void bindPropToAnimBone(MeshInstance inst,
                                     com.sky.modelviewer.parsing.AnimPackParser.AnimPack anim,
                                     String boneName) {
        int animBoneCount = anim.bones.size();
        
        // Fuzzy search for boneName in animation bone list
        int targetAnimBoneIdx = -1;
        String boneLower = boneName.toLowerCase();
        // Exact match first
        for (int ai = 0; ai < animBoneCount; ai++) {
            if (boneName.equals(anim.bones.get(ai).name)) {
                targetAnimBoneIdx = ai;
                break;
            }
        }
        // Fuzzy match
        if (targetAnimBoneIdx < 0) {
            for (int ai = 0; ai < animBoneCount; ai++) {
                String bn = anim.bones.get(ai).name.toLowerCase();
                if (bn.contains(boneLower) || boneLower.contains(bn)) {
                    targetAnimBoneIdx = ai;
                    break;
                }
            }
        }
        if (targetAnimBoneIdx < 0) {
            android.util.Log.w("MeshRenderer", "Prop bone not found in animation: " + boneName);
            return;
        }
        android.util.Log.d("MeshRenderer", "Prop bound to anim bone [" + targetAnimBoneIdx + "] " + anim.bones.get(targetAnimBoneIdx).name);

        // Create bone weights: single bone, all vertices → bone 0 with weight 1.0
        int vc = inst.vertexCount;
        float[] dummyWeights = new float[vc * 4];
        for (int vi = 0; vi < vc; vi++) {
            dummyWeights[vi * 4] = 1f;
        }
        int[] dummyIndices = new int[vc * 4];
        // All vertices → mesh bone 0 (we only have 1 bone)

        // Upload bone data
        inst.hasBoneWeights = true;
        if (inst.boneIndexBuffer == 0 || inst.boneWeightBuffer == 0) {
            int[] bbufs = new int[2];
            GLES30.glGenBuffers(2, bbufs, 0);
            inst.boneIndexBuffer = bbufs[0];
            inst.boneWeightBuffer = bbufs[1];
        }

        ByteBuffer idxBB = ByteBuffer.allocateDirect(dummyIndices.length * 4).order(ByteOrder.nativeOrder());
        idxBB.asIntBuffer().put(dummyIndices).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.boneIndexBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, dummyIndices.length * 4, idxBB, GLES30.GL_STATIC_DRAW);

        ByteBuffer wtBB = ByteBuffer.allocateDirect(dummyWeights.length * 4).order(ByteOrder.nativeOrder());
        wtBB.asFloatBuffer().put(dummyWeights).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.boneWeightBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, dummyWeights.length * 4, wtBB, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

        // Set up bone mapping: mesh bone 0 → anim bone targetAnimBoneIdx
        inst.instMeshBoneNames = new ArrayList<String>();
        inst.instMeshBoneNames.add(boneName);
        inst.instMeshBoneToAnimBone = new int[]{targetAnimBoneIdx};
        inst.instMeshBoneCount = 1;
        // Use identity IBM: skinnedPos = W_anim * identity * vertexPos = W_anim * vertexPos
        // This positions the prop's local origin at the bone's animated world position
        inst.useIdentityIBM = true;
    }

    /**
     * Build per-instance bone mapping and IBM for a wardrobe mesh instance.
     */
    private void buildInstanceBoneMapping(MeshInstance inst,
                                          com.sky.modelviewer.parsing.AnimPackParser.AnimPack anim) {
        if (inst.instMeshBoneNames == null || inst.instMeshBoneNames.isEmpty()) return;
        int animBoneCount = anim.bones.size();
        int meshBC = inst.instMeshBoneNames.size();
        int[] mapping = new int[meshBC];
        for (int mi = 0; mi < meshBC; mi++) {
            String meshName = inst.instMeshBoneNames.get(mi);
            int foundIdx = -1;
            if (meshName != null) {
                for (int ai = 0; ai < animBoneCount; ai++) {
                    if (com.sky.modelviewer.parsing.AnimPackParser.boneNamesMatch(
                            meshName, anim.bones.get(ai).name)) {
                        foundIdx = ai;
                        break;
                    }
                }
            }
            mapping[mi] = foundIdx;
        }
        inst.instMeshBoneToAnimBone = mapping;
        inst.instMeshBoneCount = meshBC;

        // Use animpack bone.matrix as IBM (same approach as global meshIBMForAnimBone)
        // The IBM for each animpack bone is already computed in meshIBMForAnimBone
        // Per-instance IBM is the same (it's the animpack's IBM, not the mesh's)
        // The per-instance meshIBM (from mesh file) is stored in inst.instMeshIBM
        // We use inst.instMeshIBM as the invBind in updateBoneMatrices via per-instance path
    }

    public void setAnimPlaying(boolean playing) { animPlaying = playing; }

    /**
     * Clear all animation state without touching mesh instances.
     * Used when switching between visualization and wardrobe modes.
     */
    public void clearAnimation() {
        currentAnim = null;
        animPlaying = false;
        boneMatrices = null;
        meshIBMForAnimBone = null;
        meshIBMInvForAnimBone = null;
        invBindMatrices = null;
        decodedAnim = null;
        animFrameCount = 0;
        animTime = 0f;
        meshBoneToAnimBone = null;
        // Clear per-instance bone mapping for all instances
        for (MeshInstance inst : meshInstances) {
            inst.instMeshBoneToAnimBone = null;
            inst.instMeshBoneCount = 0;
            if (inst.wardrobeCategory != null && "prop".equals(inst.wardrobeCategory)) {
                inst.hasBoneWeights = false;
                inst.useIdentityIBM = false;
            }
        }
    }
    public boolean isAnimPlaying() { return animPlaying; }
    public void setAnimSpeed(float speed) { animSpeed = speed; }
    public float getAnimTime() { return animTime; }

    // Debug info for UI
    public String getAnimDebugInfo() {
        if (currentAnim == null) return "No anim";
        StringBuilder sb = new StringBuilder();
        sb.append("v=").append(currentAnim.version);
        sb.append(" bones=").append(currentAnim.boneCount);
        sb.append(" comp=").append(currentAnim.compression);
        sb.append(" segs=").append(currentAnim.segments != null ? currentAnim.segments.size() : 0);
        if (currentAnim.segments != null && !currentAnim.segments.isEmpty()) {
            com.sky.modelviewer.parsing.AnimPackParser.AnimSegment seg = currentAnim.segments.get(0);
            sb.append(" refSqt=").append(seg.sqtList != null ? seg.sqtList.size() : 0);
            sb.append(" compSz=").append(seg.compressedSize);
            sb.append(" decompSz=").append(seg.decompressedSize);
            if (seg.decompressionError != null && seg.decompressionError.length() > 0) {
                sb.append(" ERR:").append(seg.decompressionError);
            }
            if (seg.clipData != null) {
                sb.append(" clipOK");
                sb.append(" rawBytes=").append(seg.clipData.rawBytes != null ? seg.clipData.rawBytes.length : 0);
                sb.append(" clipSqt=").append(seg.clipData.sqtList != null ? seg.clipData.sqtList.size() : 0);
                sb.append(" animBytes=").append(seg.clipData.rawBytes != null ? seg.clipData.rawBytes.length : 0);
                if (seg.clipData.keyframeHeader != null) {
                    sb.append(" kfFrames=").append(seg.clipData.keyframeHeader.frameCount());
                }
            } else {
                sb.append(" clipNULL");
            }
        }
        sb.append(" decodedAnim=").append(decodedAnim != null ? "yes" : "no");
        if (decodedAnim != null) {
            sb.append(" hasAnim=").append(decodedAnim.hasAnimation);
            sb.append(" frames=").append(decodedAnim.frameCount);
            sb.append(" minF=").append(decodedAnim.minFrame);
            sb.append(" maxF=").append(decodedAnim.maxFrame);
            sb.append(" frameData=").append(decodedAnim.frameData != null ? decodedAnim.frameData.length : 0);
        }
        sb.append(" animFrames=").append(animFrameCount);
        sb.append(" meshBones=").append(meshBoneCount);
        // Count matched bones
        int matched = 0;
        if (meshBoneToAnimBone != null) {
            for (int idx : meshBoneToAnimBone) if (idx >= 0) matched++;
        }
        sb.append(" matched=").append(matched);
        sb.append(" playing=").append(animPlaying);
        return sb.toString();
    }

    public float getAnimDuration() {
        if (animFrameCount <= 0) return 1f;
        return animFrameCount / 30f;
    }

    public float getAnimTimeNormalized() {
        float dur = getAnimDuration();
        if (dur < 0.001f) return 0f;
        return animTime / dur;
    }

    public void setAnimTimeNormalized(float normalized) {
        float dur = getAnimDuration();
        animTime = normalized * dur;
        if (animTime < 0) animTime = 0;
        if (animTime > dur) animTime = dur;
        updateBoneMatrices(animTime);
    }

    /**
     * Update animation time and recompute bone matrices.
     * Called each frame from onDrawFrame.
     */
    public void updateAnimation(float deltaTime) {
        if (!animPlaying || currentAnim == null) return;
        float fps = 30f;
        float duration = 1f;

        if (animFrameCount > 0) {
            duration = animFrameCount / fps;
        }
        if (duration < 0.1f) duration = 1f;

        animTime += deltaTime * animSpeed;
        if (animTime > duration) animTime -= duration;
        if (animTime < 0) animTime += duration;

        updateBoneMatrices(animTime);
    }

    /**
     * Compute bone matrices by sampling keyframe data at given time.
     * For each bone:
     *   1. Get SQT at current frame (from keyframes or base SQT)
     *   2. Compute local transform
     *   3. Multiply with parent world transform
     *   4. Final = world * invBindMatrix
     */
    private void updateBoneMatrices(float time) {
        if (currentAnim == null || currentAnim.bones == null) return;
        int boneCount = currentAnim.bones.size();
        if (boneMatrices == null || boneMatrices.length < boneCount * 16) {
            boneMatrices = new float[boneCount * 16];
        }

        // Get base SQT list
        List<com.sky.modelviewer.parsing.AnimPackParser.AnimSQT> baseSqtList =
            com.sky.modelviewer.parsing.AnimPackParser.getBoneSqtList(currentAnim);

        // Determine current frame index
        float fps = 30f;
        int frameIdx = (int)(time * fps);
        if (animFrameCount > 0 && frameIdx >= animFrameCount) frameIdx = animFrameCount - 1;
        if (frameIdx < 0) frameIdx = 0;

        // Build per-bone SQT at current frame
        float[] locals = new float[boneCount * 16];
        for (int i = 0; i < boneCount; i++) {
            float[] t, r, s;

            // Try decoded animation data first
            if (decodedAnim != null && decodedAnim.frameData != null) {
                int fi = Math.min(frameIdx, decodedAnim.frameCount - 1);
                if (fi < 0) fi = 0;

                // For each component (quat, trans, scale), search backward from current frame
                // to find the most recent frame that has data (hold-last-keyframe).
                int base = (i * decodedAnim.frameCount + fi) * 10;

                // Rotation (quat at indices 3-6)
                // Search backward first, then forward if not found
                int quatFrame = fi;
                while (quatFrame > 0) {
                    int b = (i * decodedAnim.frameCount + quatFrame) * 10;
                    if (b + 7 <= decodedAnim.frameData.length && !Float.isNaN(decodedAnim.frameData[b + 3])) break;
                    quatFrame--;
                }
                // If not found backward, search forward
                int qbIdx = (i * decodedAnim.frameCount + quatFrame) * 10;
                if (qbIdx + 7 <= decodedAnim.frameData.length && Float.isNaN(decodedAnim.frameData[qbIdx + 3])) {
                    quatFrame = fi + 1;
                    while (quatFrame < decodedAnim.frameCount) {
                        int b = (i * decodedAnim.frameCount + quatFrame) * 10;
                        if (b + 7 <= decodedAnim.frameData.length && !Float.isNaN(decodedAnim.frameData[b + 3])) break;
                        quatFrame++;
                    }
                }
                int qb = (i * decodedAnim.frameCount + Math.min(quatFrame, decodedAnim.frameCount - 1)) * 10;
                if (qb + 7 <= decodedAnim.frameData.length && quatFrame < decodedAnim.frameCount && !Float.isNaN(decodedAnim.frameData[qb + 3])) {
                    r = new float[] {decodedAnim.frameData[qb+3], decodedAnim.frameData[qb+4],
                                     decodedAnim.frameData[qb+5], decodedAnim.frameData[qb+6]};
                } else {
                    // No keyframe data for this bone at ANY frame.
                    // DON'T use clip/ref SQT (that gives identity = T-pose).
                    // Instead, search the entire frameData array for this bone.
                    r = null;
                    for (int sf = 0; sf < decodedAnim.frameCount; sf++) {
                        int b = (i * decodedAnim.frameCount + sf) * 10;
                        if (b + 7 <= decodedAnim.frameData.length && !Float.isNaN(decodedAnim.frameData[b + 3])) {
                            r = new float[] {decodedAnim.frameData[b+3], decodedAnim.frameData[b+4],
                                             decodedAnim.frameData[b+5], decodedAnim.frameData[b+6]};
                            break;
                        }
                    }
                    if (r == null) {
                        // Truly no data anywhere — use clip SQT as last resort
                        r = (i < baseSqtList.size()) ? baseSqtList.get(i).rotation : new float[] {0,0,0,1};
                    }
                }

                // Translation (at indices 7-9)
                int transFrame = fi;
                while (transFrame > 0) {
                    int b = (i * decodedAnim.frameCount + transFrame) * 10;
                    if (b + 10 <= decodedAnim.frameData.length && !Float.isNaN(decodedAnim.frameData[b + 7])) break;
                    transFrame--;
                }
                int tbIdx = (i * decodedAnim.frameCount + transFrame) * 10;
                if (tbIdx + 10 <= decodedAnim.frameData.length && Float.isNaN(decodedAnim.frameData[tbIdx + 7])) {
                    transFrame = fi + 1;
                    while (transFrame < decodedAnim.frameCount) {
                        int b = (i * decodedAnim.frameCount + transFrame) * 10;
                        if (b + 10 <= decodedAnim.frameData.length && !Float.isNaN(decodedAnim.frameData[b + 7])) break;
                        transFrame++;
                    }
                }
                int tb = (i * decodedAnim.frameCount + Math.min(transFrame, decodedAnim.frameCount - 1)) * 10;
                if (tb + 10 <= decodedAnim.frameData.length && transFrame < decodedAnim.frameCount && !Float.isNaN(decodedAnim.frameData[tb + 7])) {
                    t = new float[] {decodedAnim.frameData[tb+7], decodedAnim.frameData[tb+8],
                                     decodedAnim.frameData[tb+9]};
                } else {
                    t = null;
                    for (int sf = 0; sf < decodedAnim.frameCount; sf++) {
                        int b = (i * decodedAnim.frameCount + sf) * 10;
                        if (b + 10 <= decodedAnim.frameData.length && !Float.isNaN(decodedAnim.frameData[b + 7])) {
                            t = new float[] {decodedAnim.frameData[b+7], decodedAnim.frameData[b+8],
                                             decodedAnim.frameData[b+9]};
                            break;
                        }
                    }
                    if (t == null) {
                        t = (i < baseSqtList.size()) ? baseSqtList.get(i).translation : new float[] {0,0,0};
                    }
                }

                // Scale (at indices 0-2)
                int scaleFrame = fi;
                while (scaleFrame > 0) {
                    int b = (i * decodedAnim.frameCount + scaleFrame) * 10;
                    if (b + 3 <= decodedAnim.frameData.length && !Float.isNaN(decodedAnim.frameData[b])) break;
                    scaleFrame--;
                }
                int sbIdx = (i * decodedAnim.frameCount + scaleFrame) * 10;
                if (sbIdx + 3 <= decodedAnim.frameData.length && Float.isNaN(decodedAnim.frameData[sbIdx])) {
                    scaleFrame = fi + 1;
                    while (scaleFrame < decodedAnim.frameCount) {
                        int b = (i * decodedAnim.frameCount + scaleFrame) * 10;
                        if (b + 3 <= decodedAnim.frameData.length && !Float.isNaN(decodedAnim.frameData[b])) break;
                        scaleFrame++;
                    }
                }
                int sb = (i * decodedAnim.frameCount + Math.min(scaleFrame, decodedAnim.frameCount - 1)) * 10;
                if (sb + 3 <= decodedAnim.frameData.length && scaleFrame < decodedAnim.frameCount && !Float.isNaN(decodedAnim.frameData[sb])) {
                    s = new float[] {decodedAnim.frameData[sb], decodedAnim.frameData[sb+1],
                                     decodedAnim.frameData[sb+2]};
                } else {
                    s = null;
                    for (int sf = 0; sf < decodedAnim.frameCount; sf++) {
                        int b = (i * decodedAnim.frameCount + sf) * 10;
                        if (b + 3 <= decodedAnim.frameData.length && !Float.isNaN(decodedAnim.frameData[b])) {
                            s = new float[] {decodedAnim.frameData[b], decodedAnim.frameData[b+1],
                                             decodedAnim.frameData[b+2]};
                            break;
                        }
                    }
                    if (s == null) {
                        s = (i < baseSqtList.size()) ? baseSqtList.get(i).scale : new float[] {1,1,1};
                    }
                }
            } else if (i < baseSqtList.size()) {
                // Use base SQT (static pose)
                com.sky.modelviewer.parsing.AnimPackParser.AnimSQT sqt = baseSqtList.get(i);
                t = sqt.translation; r = sqt.rotation; s = sqt.scale;
            } else {
                t = new float[] {0,0,0}; r = new float[] {0,0,0,1}; s = new float[] {1,1,1};
            }

            float[] local = com.sky.modelviewer.parsing.AnimPackParser.composeMat4(t, r, s);
            System.arraycopy(local, 0, locals, i * 16, 16);
        }

        // Compute world transforms using parent hierarchy
        float[] worlds = new float[boneCount * 16];
        for (int i = 0; i < boneCount; i++) {
            int parent = animBoneParents[i];
            if (parent >= 0 && parent < i) {
                float[] parentWorld = new float[16];
                System.arraycopy(worlds, parent * 16, parentWorld, 0, 16);
                float[] local = new float[16];
                System.arraycopy(locals, i * 16, local, 0, 16);
                float[] world = com.sky.modelviewer.parsing.AnimPackParser.multiplyMat4(parentWorld, local);
                System.arraycopy(world, 0, worlds, i * 16, 16);
            } else {
                System.arraycopy(locals, i * 16, worlds, i * 16, 16);
            }
        }

        // Final bone matrix = world * invBindMatrix
        for (int i = 0; i < boneCount; i++) {
            float[] world = new float[16];
            System.arraycopy(worlds, i * 16, world, 0, 16);
            float[] invBind = new float[16];
            if (meshIBMForAnimBone != null && i * 16 + 16 <= meshIBMForAnimBone.length) {
                // Use bone.matrix as IBM: inv(W_bind_mesh)
                // B = W_anim * inv(W_bind_mesh), so at frame 0
                // the mesh deforms from T-pose to the animpack's first frame.
                System.arraycopy(meshIBMForAnimBone, i * 16, invBind, 0, 16);
            } else if (invBindMatrices != null && i * 16 + 16 <= invBindMatrices.length) {
                // Fallback: animpack's IBM (inv(W_bind_anim))
                System.arraycopy(invBindMatrices, i * 16, invBind, 0, 16);
            } else {
                // Identity fallback
                for (int j = 0; j < 16; j++) invBind[j] = (j % 5 == 0) ? 1f : 0f;
            }
            // Standard skinning: B = W_anim * invBind
            float[] finalMat = com.sky.modelviewer.parsing.AnimPackParser.multiplyMat4(world, invBind);

            // Transform bone matrix to match mesh vertex coordinate system.
            // Mesh vertices are: (-v.x * scale, v.y * scale, -v.z * scale)
            // This is a flip F=diag(-1,1,-1,1) + scale S=diag(s,s,s,1).
            // Required: B' = F * S * B * S^-1 * F
            // Effect on column-major matrix:
            //   1. Scale translation (indices 12,13,14) by meshScale
            //   2. Flip: negate indices {1,3,4,6,9,11,12,14}
            float s = scale;
            finalMat[12] *= s;
            finalMat[13] *= s;
            finalMat[14] *= s;
            finalMat[1]  = -finalMat[1];
            finalMat[3]  = -finalMat[3];
            finalMat[4]  = -finalMat[4];
            finalMat[6]  = -finalMat[6];
            finalMat[9]  = -finalMat[9];
            finalMat[11] = -finalMat[11];
            finalMat[12] = -finalMat[12];
            finalMat[14] = -finalMat[14];

            System.arraycopy(finalMat, 0, boneMatrices, i * 16, 16);
        }
    }

    /**
     * Create the skinning shader program.
     */
    private void createSkinProgram() {
        String vs =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "layout(location = 0) in vec3 aPosition;\n" +
            "layout(location = 1) in vec3 aNormal;\n" +
            "layout(location = 2) in vec2 aTexcoord;\n" +
            "layout(location = 3) in vec4 aColor;\n" +
            "layout(location = 4) in vec4 aBoneIndices;\n" +
            "layout(location = 5) in vec4 aBoneWeights;\n" +
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uMV;\n" +
            "uniform mat4 uNormalMatrix;\n" +
            "uniform mat4 uBoneMatrices[128];\n" +
            "uniform mat4 uInstanceTransform;\n" +
            "uniform int uHasInstanceTransform;\n" +
            "out vec3 vNormal;\n" +
            "out vec2 vTexcoord;\n" +
            "out vec3 vWorldPos;\n" +
            "out vec4 vColor;\n" +
            "void main() {\n" +
            "    vec4 localPos = vec4(aPosition, 1.0);\n" +
            "    vec4 localNorm = vec4(aNormal, 0.0);\n" +
            "    if (uHasInstanceTransform == 1) {\n" +
            "        localPos = uInstanceTransform * localPos;\n" +
            "        localNorm = uInstanceTransform * localNorm;\n" +
            "    }\n" +
            "    mat4 boneMat = uBoneMatrices[int(aBoneIndices.x)] * aBoneWeights.x +\n" +
            "                    uBoneMatrices[int(aBoneIndices.y)] * aBoneWeights.y +\n" +
            "                    uBoneMatrices[int(aBoneIndices.z)] * aBoneWeights.z +\n" +
            "                    uBoneMatrices[int(aBoneIndices.w)] * aBoneWeights.w;\n" +
            "    vec4 skinnedPos = boneMat * localPos;\n" +
            "    vec4 skinnedNorm = boneMat * localNorm;\n" +
            "    vec4 worldPos = uMV * skinnedPos;\n" +
            "    vWorldPos = worldPos.xyz;\n" +
            "    vNormal = normalize((uNormalMatrix * skinnedNorm).xyz);\n" +
            "    vTexcoord = aTexcoord;\n" +
            "    vColor = aColor;\n" +
            "    gl_Position = uMVP * skinnedPos;\n" +
            "}\n";

        String fs =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec3 vNormal;\n" +
            "in vec2 vTexcoord;\n" +
            "in vec3 vWorldPos;\n" +
            "in vec4 vColor;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform vec3 uAmbientColor;\n" +
            "uniform vec3 uKeyColor;\n" +
            "uniform vec3 uFillColor;\n" +
            "uniform int uViewMode;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform int uHasTexture;\n" +
            "uniform int uHasVertexColors;\n" +
            "uniform int uNoAlphaDiscard;\n" +
            "uniform int uHasColorOverride;\n" +
            "uniform vec3 uBaseHsv;\n" +
            "out vec4 fragColor;\n" +
            "// HSV to RGB conversion\n" +
            "vec3 hsv2rgb(vec3 hsv) {\n" +
            "    float h = hsv.x / 360.0;\n" +
            "    float s = hsv.y / 100.0;\n" +
            "    float v = hsv.z / 100.0;\n" +
            "    vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);\n" +
            "    vec3 p = abs(fract(vec3(h) + vec3(1.0, 2.0/3.0, 1.0/3.0)) * 6.0 - 3.0);\n" +
            "    return v * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), s);\n" +
            "}\n" +
            "void main() {\n" +
            "    vec3 N = normalize(vNormal);\n" +
            "    vec3 L = normalize(-uLightDir);\n" +
            "    float NdotL = max(dot(N, L), 0.0);\n" +
            "    vec3 keyContribution = uKeyColor * NdotL;\n" +
            "    vec3 fillLight = normalize(vec3(-uLightDir.z, uLightDir.y, -uLightDir.x));\n" +
            "    float NdotF = max(dot(N, normalize(-fillLight)), 0.0) * 0.3;\n" +
            "    vec3 fillContribution = uFillColor * NdotF;\n" +
            "    vec3 baseColor = uAmbientColor + keyContribution + fillContribution;\n" +
            "    if (uViewMode == 1) {\n" +
            "        baseColor = vec3(dot(N, L) * 0.8 + 0.2);\n" +
            "    }\n" +
            "    if (uHasTexture == 1) {\n" +
            "        vec4 texColor = texture(uTexture, vTexcoord);\n" +
            "        baseColor *= texColor.rgb;\n" +
            "        if (uNoAlphaDiscard == 0 && texColor.a < 0.01) discard;\n" +
            "    }\n" +
            "    if (uHasVertexColors == 1) {\n" +
            "        baseColor *= vColor.rgb;\n" +
            "    }\n" +
            "    {\n" +
            "        vec3 hsvColor = hsv2rgb(uBaseHsv);\n" +
            "        if (uHasColorOverride == 1) {\n" +
            "            baseColor = hsvColor * (uAmbientColor + keyContribution + fillContribution);\n" +
            "        } else {\n" +
            "            baseColor *= hsvColor;\n" +
            "        }\n" +
            "    }\n" +
            "    fragColor = vec4(baseColor, 1.0);\n" +
            "}\n";

        int vsId = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER);
        GLES30.glShaderSource(vsId, vs);
        GLES30.glCompileShader(vsId);
        int fsId = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER);
        GLES30.glShaderSource(fsId, fs);
        GLES30.glCompileShader(fsId);
        skinProgram = GLES30.glCreateProgram();
        GLES30.glAttachShader(skinProgram, vsId);
        GLES30.glAttachShader(skinProgram, fsId);
        GLES30.glLinkProgram(skinProgram);
        GLES30.glDeleteShader(vsId);
        GLES30.glDeleteShader(fsId);

        skinMvpLoc = GLES30.glGetUniformLocation(skinProgram, "uMVP");
        skinMvLoc = GLES30.glGetUniformLocation(skinProgram, "uMV");
        skinNormalMatrixLoc = GLES30.glGetUniformLocation(skinProgram, "uNormalMatrix");
        skinLightDirLoc = GLES30.glGetUniformLocation(skinProgram, "uLightDir");
        skinAmbientLoc = GLES30.glGetUniformLocation(skinProgram, "uAmbientColor");
        skinKeyColorLoc = GLES30.glGetUniformLocation(skinProgram, "uKeyColor");
        skinFillColorLoc = GLES30.glGetUniformLocation(skinProgram, "uFillColor");
        skinViewModeLoc = GLES30.glGetUniformLocation(skinProgram, "uViewMode");
        skinTextureLoc = GLES30.glGetUniformLocation(skinProgram, "uTexture");
        skinHasTextureLoc = GLES30.glGetUniformLocation(skinProgram, "uHasTexture");
        skinHasVertexColorsLoc = GLES30.glGetUniformLocation(skinProgram, "uHasVertexColors");
        skinNoAlphaDiscardLoc = GLES30.glGetUniformLocation(skinProgram, "uNoAlphaDiscard");
        skinHasColorOverrideLoc = GLES30.glGetUniformLocation(skinProgram, "uHasColorOverride");
        skinBaseHsvLoc = GLES30.glGetUniformLocation(skinProgram, "uBaseHsv");
        skinInstanceTransformLoc = GLES30.glGetUniformLocation(skinProgram, "uInstanceTransform");
        skinHasInstanceTransformLoc = GLES30.glGetUniformLocation(skinProgram, "uHasInstanceTransform");
        skinBoneMatricesLoc = GLES30.glGetUniformLocation(skinProgram, "uBoneMatrices");
        skinPosLoc = 0;
        skinNormLoc = 1;
        skinUvLoc = 2;
        skinColorLoc = 3;
        skinBoneIdxLoc = 4;
        skinBoneWtLoc = 5;
    }

    public void setTexture(int texId) {
        textureId = texId;
        hasTexture = texId != 0;
        // Update the last mesh instance's texture if any
        if (!meshInstances.isEmpty()) {
            meshInstances.get(meshInstances.size() - 1).textureId = texId;
            meshInstances.get(meshInstances.size() - 1).hasTexture = texId != 0;
        }
    }

    public void setViewMode(ViewMode mode) {
        viewMode = mode;
    }

    public android.graphics.Bitmap getLastTextureBitmap() {
        return lastTextureBitmap;
    }

    public void setLastTextureBitmap(android.graphics.Bitmap bmp) {
        lastTextureBitmap = bmp;
        if (bmp != null) {
            lastTextureWidth = bmp.getWidth();
            lastTextureHeight = bmp.getHeight();
        }
    }

    public void setTextureInfo(int texId, int width, int height) {
        textureId = texId;
        lastTextureWidth = width;
        lastTextureHeight = height;
    }

    public int getLastTextureWidth() { return lastTextureWidth; }
    public int getLastTextureHeight() { return lastTextureHeight; }
    public int getLastTextureId() { return textureId; }

    /**
     * Read texture from GPU using FBO + shader. Must be called on GL thread.
     * Renders the texture to an FBO via a fullscreen quad, then reads pixels.
     * Returns a Bitmap with the texture pixels, or null on failure.
     */
    public android.graphics.Bitmap readTextureFromGPU() {
        if (textureId == 0 || lastTextureWidth <= 0 || lastTextureHeight <= 0) {
            return null;
        }

        int w = lastTextureWidth;
        int h = lastTextureHeight;

        // Limit size to avoid OOM
        if (w > 4096 || h > 4096) {
            w = Math.min(w, 4096);
            h = Math.min(h, 4096);
        }

        try {
            // --- Create simple texture-copy shader ---
            String vsSrc =
                "#version 300 es\n" +
                "precision highp float;\n" +
                "layout(location=0) in vec2 aPos;\n" +
                "layout(location=1) in vec2 aUV;\n" +
                "out vec2 vUV;\n" +
                "void main(){\n" +
                "  vUV=aUV;\n" +
                "  gl_Position=vec4(aPos,0.0,1.0);\n" +
                "}\n";
            String fsSrc =
                "#version 300 es\n" +
                "precision highp float;\n" +
                "in vec2 vUV;\n" +
                "uniform sampler2D uTex;\n" +
                "out vec4 frag;\n" +
                "void main(){\n" +
                "  frag=texture(uTex,vUV);\n" +
                "}\n";

            int vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER);
            GLES30.glShaderSource(vs, vsSrc);
            GLES30.glCompileShader(vs);
            int fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER);
            GLES30.glShaderSource(fs, fsSrc);
            GLES30.glCompileShader(fs);
            int copyProg = GLES30.glCreateProgram();
            GLES30.glAttachShader(copyProg, vs);
            GLES30.glAttachShader(copyProg, fs);
            GLES30.glLinkProgram(copyProg);
            int[] linkStatus = new int[1];
            GLES30.glGetProgramiv(copyProg, GLES30.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES30.GL_TRUE) {
                GLES30.glDeleteShader(vs);
                GLES30.glDeleteShader(fs);
                GLES30.glDeleteProgram(copyProg);
                return null;
            }

            // --- Fullscreen quad VAO/VBO ---
            float[] quadVerts = new float[]{
                -1f, -1f, 0f, 1f,   // pos(x,y), uv(u,v)
                 1f, -1f, 1f, 1f,
                 1f,  1f, 1f, 0f,
                -1f,  1f, 0f, 0f
            };
            short[] quadIdx = new short[]{0, 1, 2, 0, 2, 3};

            int[] vao = new int[1];
            GLES30.glGenVertexArrays(1, vao, 0);
            GLES30.glBindVertexArray(vao[0]);

            int[] vbo = new int[1];
            GLES30.glGenBuffers(1, vbo, 0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0]);
            java.nio.ByteBuffer vb = java.nio.ByteBuffer.allocateDirect(quadVerts.length * 4).order(java.nio.ByteOrder.nativeOrder());
            vb.asFloatBuffer().put(quadVerts).position(0);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadVerts.length * 4, vb, GLES30.GL_STATIC_DRAW);

            GLES30.glEnableVertexAttribArray(0); // aPos
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0);
            GLES30.glEnableVertexAttribArray(1); // aUV
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8);

            int[] ibo = new int[1];
            GLES30.glGenBuffers(1, ibo, 0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0]);
            java.nio.ByteBuffer ib = java.nio.ByteBuffer.allocateDirect(quadIdx.length * 2).order(java.nio.ByteOrder.nativeOrder());
            ib.asShortBuffer().put(quadIdx).position(0);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, quadIdx.length * 2, ib, GLES30.GL_STATIC_DRAW);

            // --- FBO ---
            int[] fbo = new int[1];
            GLES30.glGenFramebuffers(1, fbo, 0);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0]);

            int[] rbo = new int[1];
            GLES30.glGenRenderbuffers(1, rbo, 0);
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo[0]);
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, w, h);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                                             GLES30.GL_RENDERBUFFER, rbo[0]);

            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                GLES30.glDeleteFramebuffers(1, fbo, 0);
                GLES30.glDeleteRenderbuffers(1, rbo, 0);
                GLES30.glDeleteBuffers(1, vbo, 0);
                GLES30.glDeleteBuffers(1, ibo, 0);
                GLES30.glDeleteVertexArrays(1, vao, 0);
                GLES30.glDeleteShader(vs);
                GLES30.glDeleteShader(fs);
                GLES30.glDeleteProgram(copyProg);
                return null;
            }

            // --- Render ---
            GLES30.glViewport(0, 0, w, h);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glDisable(GLES30.GL_CULL_FACE);

            GLES30.glUseProgram(copyProg);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProg, "uTex"), 0);

            GLES30.glBindVertexArray(vao[0]);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, 6, GLES30.GL_UNSIGNED_SHORT, 0);

            GLES30.glFinish();

            // --- Read pixels ---
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder());
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);

            // Convert to bitmap (flip Y)
            int[] pixels = new int[w * h];
            buf.position(0);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int r = buf.get() & 0xFF;
                    int g = buf.get() & 0xFF;
                    int b = buf.get() & 0xFF;
                    int a = buf.get() & 0xFF;
                    pixels[(h - 1 - y) * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }

            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(pixels, w, h,
                    android.graphics.Bitmap.Config.ARGB_8888);

            // --- Cleanup ---
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glDeleteFramebuffers(1, fbo, 0);
            GLES30.glDeleteRenderbuffers(1, rbo, 0);
            GLES30.glDeleteBuffers(1, vbo, 0);
            GLES30.glDeleteBuffers(1, ibo, 0);
            GLES30.glDeleteVertexArrays(1, vao, 0);
            GLES30.glDeleteShader(vs);
            GLES30.glDeleteShader(fs);
            GLES30.glDeleteProgram(copyProg);

            // Restore viewport
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);

            lastTextureBitmap = bitmap;
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void renderTexturedQuadFullscreen(int w, int h) {
        // Unused — rendering handled in readTextureFromGPU
        GLES30.glClearColor(1, 1, 1, 1);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
    }

    // ===== Multi-mesh API =====

    public void clearMeshInstances() {
        quadMode = false;
        // Clean up wireframe line buffers
        for (int[] cached : lineBufferCache.values()) {
            if (cached[0] != 0) {
                GLES30.glDeleteBuffers(1, new int[]{cached[0]}, 0);
            }
        }
        lineBufferCache.clear();
        for (MeshInstance inst : meshInstances) {
            // Delete bone buffers if they exist
            if (inst.boneIndexBuffer != 0 || inst.boneWeightBuffer != 0) {
                int[] boneBufs = {inst.boneIndexBuffer, inst.boneWeightBuffer};
                GLES30.glDeleteBuffers(2, boneBufs, 0);
                inst.boneIndexBuffer = 0;
                inst.boneWeightBuffer = 0;
            }
            if (inst.colorBuffer != 0) {
                GLES30.glDeleteBuffers(1, new int[]{inst.colorBuffer}, 0);
            }
            int[] bufs = {inst.positionBuffer, inst.normalBuffer, inst.uvBuffer, inst.indexBuffer};
            GLES30.glDeleteBuffers(4, bufs, 0);
        }
        meshInstances.clear();
        vertexCount = 0;
        indexCount = 0;
        meshRadius = 0.5f;
        // Reset bounding box so stale values don't affect frameCamera()
        meshMin[0] = 0f; meshMin[1] = 0f; meshMin[2] = 0f;
        meshMax[0] = 0f; meshMax[1] = 0f; meshMax[2] = 0f;
    }

    public void addMeshInstance(float[] positions, float[] normals, float[] uvs,
                                short[] indicesShort, int[] indicesInt,
                                int textureId, float[] transform, String name) {
        addMeshInstance(positions, normals, null, uvs, indicesShort, indicesInt,
                        textureId, transform, name, false);
    }

    public void addMeshInstance(float[] positions, float[] normals, float[] uvs,
                                short[] indicesShort, int[] indicesInt,
                                int textureId, float[] transform, String name,
                                boolean doubleSided) {
        addMeshInstance(positions, normals, null, uvs, indicesShort, indicesInt,
                        textureId, transform, name, doubleSided);
    }

    public void addMeshInstance(float[] positions, float[] normals, float[] colors,
                                float[] uvs, short[] indicesShort, int[] indicesInt,
                                int textureId, float[] transform, String name,
                                boolean doubleSided) {
        MeshInstance inst = new MeshInstance();
        int[] buffers = new int[4];
        GLES30.glGenBuffers(4, buffers, 0);
        inst.positionBuffer = buffers[0];
        inst.normalBuffer = buffers[1];
        inst.uvBuffer = buffers[2];
        inst.indexBuffer = buffers[3];
        inst.vertexCount = positions.length / 3;
        inst.indexCount = indicesShort != null ? indicesShort.length : (indicesInt != null ? indicesInt.length : 0);
        inst.textureId = textureId;
        inst.hasTexture = textureId != 0;
        inst.hasVertexColors = (colors != null && colors.length > 0);
        inst.useIntIndices = (indicesInt != null);
        inst.doubleSided = doubleSided;
        inst.transform = transform;
        inst.name = name;

        if (inst.hasVertexColors) {
            int[] cb = new int[1];
            GLES30.glGenBuffers(1, cb, 0);
            inst.colorBuffer = cb[0];
        }

        // Upload position
        ByteBuffer posBuf = ByteBuffer.allocateDirect(positions.length * 4).order(ByteOrder.nativeOrder());
        posBuf.asFloatBuffer().put(positions).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.positionBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, positions.length * 4, posBuf, GLES30.GL_STATIC_DRAW);

        // Upload normals
        ByteBuffer normBuf = ByteBuffer.allocateDirect(normals.length * 4).order(ByteOrder.nativeOrder());
        normBuf.asFloatBuffer().put(normals).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.normalBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, normals.length * 4, normBuf, GLES30.GL_STATIC_DRAW);

        // Upload vertex colors
        if (inst.hasVertexColors) {
            ByteBuffer colorBuf = ByteBuffer.allocateDirect(colors.length * 4).order(ByteOrder.nativeOrder());
            colorBuf.asFloatBuffer().put(colors).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.colorBuffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, colors.length * 4, colorBuf, GLES30.GL_STATIC_DRAW);
        }

        // Upload UVs
        if (uvs != null && uvs.length > 0) {
            ByteBuffer uvBuf = ByteBuffer.allocateDirect(uvs.length * 4).order(ByteOrder.nativeOrder());
            uvBuf.asFloatBuffer().put(uvs).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.uvBuffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, uvs.length * 4, uvBuf, GLES30.GL_STATIC_DRAW);
        }

        // Upload indices — always store for wireframe mode
        if (indicesShort != null) {
            inst.storedIndices = new int[indicesShort.length];
            for (int i = 0; i < indicesShort.length; i++) inst.storedIndices[i] = indicesShort[i] & 0xFFFF;
            ByteBuffer idxBuf = ByteBuffer.allocateDirect(indicesShort.length * 2).order(ByteOrder.nativeOrder());
            idxBuf.asShortBuffer().put(indicesShort).position(0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, inst.indexBuffer);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indicesShort.length * 2, idxBuf, GLES30.GL_STATIC_DRAW);
        } else if (indicesInt != null) {
            inst.storedIndices = indicesInt.clone();
            ByteBuffer idxBuf = ByteBuffer.allocateDirect(indicesInt.length * 4).order(ByteOrder.nativeOrder());
            idxBuf.asIntBuffer().put(indicesInt).position(0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, inst.indexBuffer);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indicesInt.length * 4, idxBuf, GLES30.GL_STATIC_DRAW);
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);

        meshInstances.add(inst);
        vertexCount += inst.vertexCount;
        indexCount += inst.indexCount;

        // Update bounds and compute per-instance bounding sphere
        boolean boundsInit = false;
        float instMinX = 0, instMinY = 0, instMinZ = 0;
        float instMaxX = 0, instMaxY = 0, instMaxZ = 0;
        for (int i = 0; i < inst.vertexCount; i++) {
            float px = positions[i * 3];
            float py = positions[i * 3 + 1];
            float pz = positions[i * 3 + 2];
            // Skip NaN/Infinity vertices
            if (Float.isNaN(px) || Float.isNaN(py) || Float.isNaN(pz) ||
                Float.isInfinite(px) || Float.isInfinite(py) || Float.isInfinite(pz)) continue;
            // Apply transform if present
            if (transform != null) {
                float tx = transform[0] * px + transform[4] * py + transform[8] * pz + transform[12];
                float ty = transform[1] * px + transform[5] * py + transform[9] * pz + transform[13];
                float tz = transform[2] * px + transform[6] * py + transform[10] * pz + transform[14];
                px = tx; py = ty; pz = tz;
                if (Float.isNaN(px) || Float.isNaN(py) || Float.isNaN(pz)) continue;
            }
            if (!boundsInit) {
                meshMin[0] = px; meshMin[1] = py; meshMin[2] = pz;
                meshMax[0] = px; meshMax[1] = py; meshMax[2] = pz;
                instMinX = px; instMinY = py; instMinZ = pz;
                instMaxX = px; instMaxY = py; instMaxZ = pz;
                boundsInit = true;
            } else {
                meshMin[0] = Math.min(meshMin[0], px);
                meshMin[1] = Math.min(meshMin[1], py);
                meshMin[2] = Math.min(meshMin[2], pz);
                meshMax[0] = Math.max(meshMax[0], px);
                meshMax[1] = Math.max(meshMax[1], py);
                meshMax[2] = Math.max(meshMax[2], pz);
                instMinX = Math.min(instMinX, px);
                instMinY = Math.min(instMinY, py);
                instMinZ = Math.min(instMinZ, pz);
                instMaxX = Math.max(instMaxX, px);
                instMaxY = Math.max(instMaxY, py);
                instMaxZ = Math.max(instMaxZ, pz);
            }
        }
        // Compute per-instance bounding sphere
        inst.boundCenterX = (instMinX + instMaxX) * 0.5f;
        inst.boundCenterY = (instMinY + instMaxY) * 0.5f;
        inst.boundCenterZ = (instMinZ + instMaxZ) * 0.5f;
        float rX = (instMaxX - instMinX) * 0.5f;
        float rY = (instMaxY - instMinY) * 0.5f;
        float rZ = (instMaxZ - instMinZ) * 0.5f;
        inst.boundRadius = Math.max(Math.max(rX, rY), rZ);
        if (inst.boundRadius < 0.001f) inst.boundRadius = 1.0f;

        float sx = meshMax[0] - meshMin[0];
        float sy = meshMax[1] - meshMin[1];
        float sz = meshMax[2] - meshMin[2];
        meshRadius = Math.max(Math.max(sx, sy), sz) * 0.5f;
        if (meshRadius < 0.001f) meshRadius = 0.5f;
    }

    public int getMeshInstanceCount() {
        return meshInstances.size();
    }

    /**
     * Update texture for ALL mesh instances with the given name (for async texture loading).
     * Multiple instances may share the same mesh name — update all of them.
     */
    public void updateMeshTexture(String name, int textureId) {
        for (MeshInstance inst : meshInstances) {
            if (name.equals(inst.name)) {
                // Only set texture if this instance doesn't already have one
                if (!inst.hasTexture) {
                    inst.textureId = textureId;
                    inst.hasTexture = textureId != 0;
                }
            }
        }
    }

    public void setMeshesHidden(String nameContains, boolean hidden) {
        String lower = nameContains.toLowerCase();
        for (MeshInstance inst : meshInstances) {
            if (inst.name != null && inst.name.toLowerCase().contains(lower)) {
                inst.hidden = hidden;
            }
        }
    }

    /**
     * Creates a simple textured quad for KTX texture preview.
     */
    public void showTexturedQuad(int textureId) {
        clearMeshInstances();
        quadMode = true;

        float[] positions = new float[]{
            -1f, -1f, 0f,
             1f, -1f, 0f,
             1f,  1f, 0f,
            -1f,  1f, 0f
        };
        float[] normals = new float[]{
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f
        };
        float[] uvs = new float[]{
            0f, 1f,
            1f, 1f,
            1f, 0f,
            0f, 0f
        };
        short[] indices = new short[]{0, 1, 2, 0, 2, 3};

        addMeshInstance(positions, normals, uvs, indices, null, textureId, null, "TexturePreview");

        // Reset camera for quad viewing - look straight at the quad from +Z
        cameraTarget = new float[]{0f, 0f, 0f};
        cameraDistance = 3.0;
        cameraYaw = -1.5707963f; // -pi/2, looking towards -Z
        cameraPitch = 0f;
        meshRadius = 1.5f;
        far = 100f;
        near = 0.1f;
    }

    public void orbit(float dx, float dy) {
        cameraYaw += dx * 0.01f;
        cameraPitch -= dy * 0.01f;
    }

    public void pan(float dx, float dy) {
        float[] forward = computeForward();
        float[] right = normalize(cross(forward, new float[]{0f, 1f, 0f}));
        float[] up = normalize(cross(right, forward));
        float s = (float) (cameraDistance * 0.002);
        cameraTarget[0] += -right[0] * dx * s + up[0] * dy * s;
        cameraTarget[1] += -right[1] * dx * s + up[1] * dy * s;
        cameraTarget[2] += -right[2] * dx * s + up[2] * dy * s;
    }

    /**
     * Move camera target in the horizontal plane (XZ) based on joystick input.
     * jx > 0 = move right, jx < 0 = move left
     * jy > 0 = move forward, jy < 0 = move backward
     */
    public void moveHorizontal(float jx, float jy, float speed) {
        float[] forward = computeForward();
        // Project forward onto XZ plane
        float fx = forward[0], fz = forward[2];
        float flen = (float) Math.sqrt(fx * fx + fz * fz);
        if (flen > 0.001f) { fx /= flen; fz /= flen; }
        float[] right = normalize(cross(forward, new float[]{0f, 1f, 0f}));

        cameraTarget[0] += (right[0] * jx + fx * jy) * speed;
        cameraTarget[1] += (right[1] * jx) * speed; // right.y should be ~0
        cameraTarget[2] += (right[2] * jx + fz * jy) * speed;
    }

    /**
     * Move camera target vertically (Y axis).
     */
    public void moveVertical(float dy, float speed) {
        cameraTarget[1] += dy * speed;
    }

    public void zoom(float factor) {
        cameraDistance *= factor;
        cameraDistance = Math.max(0.05, Math.min(500000.0, cameraDistance));
    }

    public void frameCamera() {
        // If we have mesh instances, frame based on their bounds
        if (!meshInstances.isEmpty()) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            boolean any = false;
            for (MeshInstance inst : meshInstances) {
                if (inst.vertexCount == 0) continue;
                // Use bounding sphere center ± radius
                minX = Math.min(minX, inst.boundCenterX - inst.boundRadius);
                minY = Math.min(minY, inst.boundCenterY - inst.boundRadius);
                minZ = Math.min(minZ, inst.boundCenterZ - inst.boundRadius);
                maxX = Math.max(maxX, inst.boundCenterX + inst.boundRadius);
                maxY = Math.max(maxY, inst.boundCenterY + inst.boundRadius);
                maxZ = Math.max(maxZ, inst.boundCenterZ + inst.boundRadius);
                any = true;
            }
            if (any) {
                float cx = (minX + maxX) * 0.5f;
                float cy = (minY + maxY) * 0.5f;
                float cz = (minZ + maxZ) * 0.5f;
                float sx = maxX - minX, sy = maxY - minY, sz = maxZ - minZ;
                // Use diagonal length for better framing
                float diag = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
                float radius = diag * 0.5f;
                if (radius < 0.001f) radius = 1.0f;
                cameraTarget = new float[]{cx, cy, cz};
                cameraDistance = radius * 1.8f;
                if (cameraDistance < 0.5f) cameraDistance = 0.5f;
                cameraYaw = -2.3561944902f;
                cameraPitch = -0.35f;
                near = Math.max(0.001f, radius * 0.001f);
                far = Math.max(10000f, radius * 100f);
                return;
            }
        }
        // Fallback: camera at origin
        cameraTarget = new float[]{0f, 0f, 0f};
        cameraDistance = 20.0;
        cameraYaw = -2.3561944902f;
        cameraPitch = -0.35f;
        near = 0.01f;
        far = 1000000f;
    }

    public double getCameraDistance() {
        return cameraDistance;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            GLES30.glClearColor(0.067f, 0.082f, 0.110f, 1f);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glEnable(GLES30.GL_CULL_FACE);
            GLES30.glCullFace(GLES30.GL_BACK);

            createProgram();
            createWireProgram();

            int[] buffers = new int[4];
            GLES30.glGenBuffers(4, buffers, 0);
            positionBuffer = buffers[0];
            normalBuffer = buffers[1];
            uvBuffer = buffers[2];
            indexBuffer = buffers[3];
        } catch (Exception e) {
            Log.e(TAG, "onSurfaceCreated failed", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewportWidth = Math.max(width, 1);
        viewportHeight = Math.max(height, 1);
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        // Update animation
        if (animPlaying && currentAnim != null) {
            long now = System.nanoTime();
            if (lastFrameTime == 0) lastFrameTime = now;
            float dt = (now - lastFrameTime) / 1e9f;
            lastFrameTime = now;
            updateAnimation(dt);
        } else {
            lastFrameTime = 0;
        }

        // Draw a test axis indicator at origin (always visible, helps debug camera)
        drawTestAxes();

        if (meshInstances.isEmpty()) return;

        // Disable face culling entirely — level meshes have mixed winding orders
        GLES30.glDisable(GLES30.GL_CULL_FACE);

        float[] viewMatrix = computeViewMatrix();
        float[] projMatrix = computeProjectionMatrix();
        float[] vp = multiplyMatrix(projMatrix, viewMatrix);

        // Get camera position for distance culling
        float[] fwd = computeForward();
        float camX = (float)(cameraTarget[0] - fwd[0] * cameraDistance);
        float camY = (float)(cameraTarget[1] - fwd[1] * cameraDistance);
        float camZ = (float)(cameraTarget[2] - fwd[2] * cameraDistance);
        // Cull distance: render meshes within this range of camera
        float cullDist = (float)(cameraDistance * 3.0 + 200.0);

        // Bind program and set per-frame uniforms once
        GLES30.glUseProgram(program);
        float[] lightDir = buildLightDirection();
        GLES30.glUniform3f(lightDirLoc, lightDir[0], lightDir[1], lightDir[2]);
        GLES30.glUniform3f(ambientColorLoc, 118f / 255f, 118f / 255f, 118f / 255f);
        GLES30.glUniform3f(keyColorLoc, 244f / 255f, 244f / 255f, 244f / 255f);
        GLES30.glUniform3f(fillColorLoc, 150f / 255f, 160f / 255f, 176f / 255f);
        int modeFlag = viewMode == ViewMode.TEXTURE ? 0 : (viewMode == ViewMode.SOLID ? 1 : 2);
        GLES30.glUniform1i(viewModeLoc, modeFlag);

        for (MeshInstance inst : meshInstances) {
            if (inst.vertexCount == 0 || inst.indexCount == 0) continue;
            if (inst.hidden) continue;

            // Distance culling: skip meshes too far from camera
            float dx = inst.boundCenterX - camX;
            float dy = inst.boundCenterY - camY;
            float dz = inst.boundCenterZ - camZ;
            float distSq = dx * dx + dy * dy + dz * dz;
            float cullRange = cullDist + inst.boundRadius;
            if (distSq > cullRange * cullRange) continue;

            float[] modelMatrix = inst.transform;
            boolean useSkinShader = (inst.hasBoneWeights && skinProgram != 0);
            // Prop: transform applied BEFORE skinning (local space, identity IBM)
            // Non-prop skinned with transform: applied AFTER skinning (world space, baked into MVP)
            // Non-skinned: transform applied as model matrix (baked into MVP/MV)
            boolean usePreSkinTransform = useSkinShader && inst.useIdentityIBM;
            if (modelMatrix != null && !usePreSkinTransform) {
                // Apply transform as model matrix (post-skin for skinned, or only transform for non-skinned)
                multiplyMatrixInto(vp, modelMatrix, tmpMVP);
                multiplyMatrixInto(viewMatrix, modelMatrix, tmpMV);
                computeNormalMatrixInto(tmpMV, tmpNormal);
            } else {
                // Prop skinned: transform goes in shader, not MVP
                // Or no transform at all
                System.arraycopy(vp, 0, tmpMVP, 0, 16);
                System.arraycopy(viewMatrix, 0, tmpMV, 0, 16);
                computeNormalMatrixInto(tmpMV, tmpNormal);
            }

            // Use skinning shader if mesh has bone weights (with or without animation)
            if (inst.hasBoneWeights && skinProgram != 0) {
                renderSkinnedInstance(inst, tmpMVP, tmpMV, tmpNormal);
            } else {
                renderInstance(inst, tmpMVP, tmpMV, tmpNormal);
            }
        }
    }

    private void renderInstance(MeshInstance inst, float[] mvp, float[] mv, float[] normalMatrix) {
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(mvLoc, 1, false, mv, 0);
        GLES30.glUniformMatrix4fv(normalMatrixLoc, 1, false, normalMatrix, 0);

        GLES30.glUniform1i(viewModeLoc, viewMode == ViewMode.TEXTURE ? 0 : (viewMode == ViewMode.SOLID ? 1 : 2));
        GLES30.glUniform1i(hasTextureLoc, (inst.hasTexture && !inst.disableTexture) ? 1 : 0);
        GLES30.glUniform1i(hasVertexColorsLoc, inst.hasVertexColors ? 1 : 0);
        GLES30.glUniform1i(noAlphaDiscardLoc, inst.noAlphaDiscard ? 1 : 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.positionBuffer);
        GLES30.glEnableVertexAttribArray(posLoc);
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.normalBuffer);
        GLES30.glEnableVertexAttribArray(normLoc);
        GLES30.glVertexAttribPointer(normLoc, 3, GLES30.GL_FLOAT, false, 0, 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.uvBuffer);
        GLES30.glEnableVertexAttribArray(uvLoc);
        GLES30.glVertexAttribPointer(uvLoc, 2, GLES30.GL_FLOAT, false, 0, 0);

        // Vertex colors (location = 3)
        if (inst.hasVertexColors && inst.colorBuffer != 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.colorBuffer);
            GLES30.glEnableVertexAttribArray(colorLoc);
            GLES30.glVertexAttribPointer(colorLoc, 4, GLES30.GL_FLOAT, false, 0, 0);
        } else {
            // Provide default color (white) so shader doesn't read garbage
            GLES30.glVertexAttrib4f(colorLoc, 1.0f, 1.0f, 1.0f, 1.0f);
            GLES30.glDisableVertexAttribArray(colorLoc);
        }

        if (inst.hasTexture) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inst.textureId);
            GLES30.glUniform1i(textureLoc, 0);
        }

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, inst.indexBuffer);
        // Per-instance face culling control
        if (inst.doubleSided) {
            GLES30.glDisable(GLES30.GL_CULL_FACE);
        } else {
            GLES30.glEnable(GLES30.GL_CULL_FACE);
            GLES30.glCullFace(GLES30.GL_BACK);
        }
        int indexType = inst.useIntIndices ? GLES30.GL_UNSIGNED_INT : GLES30.GL_UNSIGNED_SHORT;
        // In wireframe mode: skip triangle fill, only draw lines
        if (viewMode == ViewMode.WIRE) {
            int lineIdxBuf = getOrCreateLineIndexBuffer(inst);
            if (lineIdxBuf != 0) {
                GLES30.glUniform1i(viewModeLoc, 3); // bright line color
                GLES30.glDisable(GLES30.GL_CULL_FACE);
                GLES30.glLineWidth(1.5f);

                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, lineIdxBuf);
                GLES30.glDrawElements(GLES30.GL_LINES, inst.lineIndexCount,
                    GLES30.GL_UNSIGNED_INT, 0);

                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, inst.indexBuffer);
            }
        } else {
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, inst.indexCount, indexType, 0);
        }

        GLES30.glDisableVertexAttribArray(posLoc);
        GLES30.glDisableVertexAttribArray(normLoc);
        GLES30.glDisableVertexAttribArray(uvLoc);
        GLES30.glDisableVertexAttribArray(colorLoc);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private void renderSkinnedInstance(MeshInstance inst, float[] mvp, float[] mv, float[] normalMatrix) {
        GLES30.glUseProgram(skinProgram);

        GLES30.glUniformMatrix4fv(skinMvpLoc, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(skinMvLoc, 1, false, mv, 0);
        GLES30.glUniformMatrix4fv(skinNormalMatrixLoc, 1, false, normalMatrix, 0);

        float[] lightDir = buildLightDirection();
        GLES30.glUniform3f(skinLightDirLoc, lightDir[0], lightDir[1], lightDir[2]);
        GLES30.glUniform3f(skinAmbientLoc, 118f/255f, 118f/255f, 118f/255f);
        GLES30.glUniform3f(skinKeyColorLoc, 244f/255f, 244f/255f, 244f/255f);
        GLES30.glUniform3f(skinFillColorLoc, 150f/255f, 160f/255f, 176f/255f);
        int modeFlag = viewMode == ViewMode.TEXTURE ? 0 : (viewMode == ViewMode.SOLID ? 1 : 2);
        GLES30.glUniform1i(skinViewModeLoc, modeFlag);
        GLES30.glUniform1i(skinHasTextureLoc, (inst.hasTexture && !inst.disableTexture) ? 1 : 0);
        GLES30.glUniform1i(skinHasVertexColorsLoc, inst.hasVertexColors ? 1 : 0);
        GLES30.glUniform1i(skinNoAlphaDiscardLoc, inst.noAlphaDiscard ? 1 : 0);
        GLES30.glUniform1i(skinHasColorOverrideLoc, inst.colorOverride ? 1 : 0);
        if (inst.baseHsv != null) {
            GLES30.glUniform3f(skinBaseHsvLoc, inst.baseHsv[0], inst.baseHsv[1], inst.baseHsv[2]);
        } else {
            GLES30.glUniform3f(skinBaseHsvLoc, 0f, 0f, 100f);
        }

        // Upload instance transform — only for prop (applied BEFORE bone skinning in shader)
        // Non-prop skinned items have their transform baked into MVP/MV (post-skin, world space)
        if (inst.useIdentityIBM && inst.transform != null) {
            GLES30.glUniform1i(skinHasInstanceTransformLoc, 1);
            GLES30.glUniformMatrix4fv(skinInstanceTransformLoc, 1, false, inst.transform, 0);
        } else {
            GLES30.glUniform1i(skinHasInstanceTransformLoc, 0);
        }

        // Upload bone matrices — remapped from animpack bone order to mesh bone order
        if (skinBoneMatricesLoc >= 0) {
            if (boneMatrices != null) {
                float[] remapped;
                int[] boneMap = inst.instMeshBoneToAnimBone != null ? inst.instMeshBoneToAnimBone : meshBoneToAnimBone;
                int bCount = inst.instMeshBoneToAnimBone != null ? inst.instMeshBoneCount : meshBoneCount;
                if (boneMap != null && boneMap.length > 0) {
                    remapped = new float[bCount * 16];
                    for (int mi = 0; mi < bCount; mi++) {
                        int ai = boneMap[mi];
                        if (ai >= 0 && ai * 16 + 16 <= boneMatrices.length) {
                            if (inst.useIdentityIBM && meshIBMInvForAnimBone != null &&
                                ai * 16 + 16 <= meshIBMInvForAnimBone.length) {
                                // Prop: undo IBM to get W_anim only
                                // boneMatrices[ai] = W_anim * IBM, so W_anim = boneMatrices * inv(IBM)
                                float[] bm = new float[16];
                                System.arraycopy(boneMatrices, ai * 16, bm, 0, 16);
                                float[] invIbm = new float[16];
                                System.arraycopy(meshIBMInvForAnimBone, ai * 16, invIbm, 0, 16);
                                float[] result = com.sky.modelviewer.parsing.AnimPackParser.multiplyMat4(bm, invIbm);
                                System.arraycopy(result, 0, remapped, mi * 16, 16);
                            } else {
                                System.arraycopy(boneMatrices, ai * 16, remapped, mi * 16, 16);
                            }
                        } else {
                            for (int j = 0; j < 16; j++) remapped[mi * 16 + j] = (j % 5 == 0) ? 1f : 0f;
                        }
                    }
                } else {
                    remapped = boneMatrices;
                }
                int uploadCount = Math.min(remapped.length / 16, 128);
                GLES30.glUniformMatrix4fv(skinBoneMatricesLoc, uploadCount, false, remapped, 0);
            } else {
                // No animation — for normal meshes use identity (vertices stay in place)
                // For prop (useIdentityIBM): use W_bind = inv(IBM) to position at bone
                int bCount = inst.instMeshBoneCount > 0 ? inst.instMeshBoneCount : 1;
                bCount = Math.min(bCount, 128);
                float[] boneMats = new float[bCount * 16];
                int[] boneMap = inst.instMeshBoneToAnimBone;
                for (int mi = 0; mi < bCount; mi++) {
                    if (inst.useIdentityIBM && meshIBMInvForAnimBone != null) {
                        int ai = (boneMap != null && mi < boneMap.length) ? boneMap[mi] : mi;
                        if (ai >= 0 && ai * 16 + 16 <= meshIBMInvForAnimBone.length) {
                            System.arraycopy(meshIBMInvForAnimBone, ai * 16, boneMats, mi * 16, 16);
                            continue;
                        }
                    }
                    // Identity
                    for (int j = 0; j < 16; j++) boneMats[mi * 16 + j] = (j % 5 == 0) ? 1f : 0f;
                }
                GLES30.glUniformMatrix4fv(skinBoneMatricesLoc, bCount, false, boneMats, 0);
            }
        }

        // Position
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.positionBuffer);
        GLES30.glEnableVertexAttribArray(skinPosLoc);
        GLES30.glVertexAttribPointer(skinPosLoc, 3, GLES30.GL_FLOAT, false, 0, 0);

        // Normal
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.normalBuffer);
        GLES30.glEnableVertexAttribArray(skinNormLoc);
        GLES30.glVertexAttribPointer(skinNormLoc, 3, GLES30.GL_FLOAT, false, 0, 0);

        // UV
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.uvBuffer);
        GLES30.glEnableVertexAttribArray(skinUvLoc);
        GLES30.glVertexAttribPointer(skinUvLoc, 2, GLES30.GL_FLOAT, false, 0, 0);

        // Color
        if (inst.hasVertexColors && inst.colorBuffer != 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.colorBuffer);
            GLES30.glEnableVertexAttribArray(skinColorLoc);
            GLES30.glVertexAttribPointer(skinColorLoc, 4, GLES30.GL_FLOAT, false, 0, 0);
        } else {
            GLES30.glVertexAttrib4f(skinColorLoc, 1f, 1f, 1f, 1f);
            GLES30.glDisableVertexAttribArray(skinColorLoc);
        }

        // Bone indices (location=4)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.boneIndexBuffer);
        GLES30.glEnableVertexAttribArray(skinBoneIdxLoc);
        GLES30.glVertexAttribPointer(skinBoneIdxLoc, 4, GLES30.GL_FLOAT, false, 0, 0);

        // Bone weights (location=5)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst.boneWeightBuffer);
        GLES30.glEnableVertexAttribArray(skinBoneWtLoc);
        GLES30.glVertexAttribPointer(skinBoneWtLoc, 4, GLES30.GL_FLOAT, false, 0, 0);

        // Texture
        if (inst.hasTexture) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inst.textureId);
            GLES30.glUniform1i(skinTextureLoc, 0);
        }

        // Draw
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, inst.indexBuffer);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        int indexType = inst.useIntIndices ? GLES30.GL_UNSIGNED_INT : GLES30.GL_UNSIGNED_SHORT;
        if (viewMode != ViewMode.WIRE) {
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, inst.indexCount, indexType, 0);
        }

        // Cleanup
        GLES30.glDisableVertexAttribArray(skinPosLoc);
        GLES30.glDisableVertexAttribArray(skinNormLoc);
        GLES30.glDisableVertexAttribArray(skinUvLoc);
        GLES30.glDisableVertexAttribArray(skinColorLoc);
        GLES30.glDisableVertexAttribArray(skinBoneIdxLoc);
        GLES30.glDisableVertexAttribArray(skinBoneWtLoc);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);

        // Restore main program for next instance
        GLES30.glUseProgram(program);
    }

    // Cache for wireframe line index buffers
    private final java.util.HashMap<Integer, int[]> lineBufferCache = new java.util.HashMap<Integer, int[]>();

    private int getOrCreateLineIndexBuffer(MeshInstance inst) {
        Integer key = inst.positionBuffer;
        if (lineBufferCache.containsKey(key)) {
            int[] cached = lineBufferCache.get(key);
            inst.lineIndexCount = cached[1];
            return cached[0];
        }

        if (inst.storedIndices == null || inst.storedIndices.length < 3) {
            lineBufferCache.put(key, new int[]{0, 0});
            return 0;
        }

        int triCount = inst.storedIndices.length / 3;

        // Generate line indices: for each triangle (i0,i1,i2) → edges (i0,i1),(i1,i2),(i2,i0)
        int[] lineIdxInt = new int[triCount * 6];
        int li = 0;
        for (int t = 0; t < triCount; t++) {
            int i0 = inst.storedIndices[t * 3];
            int i1 = inst.storedIndices[t * 3 + 1];
            int i2 = inst.storedIndices[t * 3 + 2];
            lineIdxInt[li++] = i0;
            lineIdxInt[li++] = i1;
            lineIdxInt[li++] = i1;
            lineIdxInt[li++] = i2;
            lineIdxInt[li++] = i2;
            lineIdxInt[li++] = i0;
        }

        int[] bufIds = new int[1];
        GLES30.glGenBuffers(1, bufIds, 0);
        java.nio.ByteBuffer lineBuf = java.nio.ByteBuffer.allocateDirect(lineIdxInt.length * 4)
            .order(java.nio.ByteOrder.nativeOrder());
        lineBuf.asIntBuffer().put(lineIdxInt).position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, bufIds[0]);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, lineIdxInt.length * 4,
            lineBuf, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);

        inst.lineIndexCount = lineIdxInt.length;
        lineBufferCache.put(key, new int[]{bufIds[0], lineIdxInt.length});
        return bufIds[0];
    }

    /**
     * Draw colored axis lines at origin to verify camera and rendering.
     * Red=X, Green=Y, Blue=Z, each 5 units long.
     */
    private int testAxisVBO = 0;
    private void drawTestAxes() {
        if (wireProgram == 0) return;

        // Create VBO on first call
        if (testAxisVBO == 0) {
            float[] axisVerts = new float[]{
                // X axis (red)
                0,0,0, 1,0,0,
                5,0,0, 1,0,0,
                // Y axis (green)
                0,0,0, 0,1,0,
                0,5,0, 0,1,0,
                // Z axis (blue)
                0,0,0, 0,0,1,
                0,0,5, 0,0,1,
                // Small triangle at origin (white) to verify fill rendering
                -1,0,-1, 1,1,1,
                 1,0,-1, 1,1,1,
                 0,0, 2, 1,1,1,
            };
            int[] bufs = new int[1];
            GLES30.glGenBuffers(1, bufs, 0);
            testAxisVBO = bufs[0];
            ByteBuffer bb = ByteBuffer.allocateDirect(axisVerts.length * 4).order(ByteOrder.nativeOrder());
            bb.asFloatBuffer().put(axisVerts).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, testAxisVBO);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, axisVerts.length * 4, bb, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        float[] viewMatrix = computeViewMatrix();
        float[] projMatrix = computeProjectionMatrix();
        float[] vp = multiplyMatrix(projMatrix, viewMatrix);

        GLES30.glUseProgram(wireProgram);
        GLES30.glUniformMatrix4fv(wireMvpLoc, 1, false, vp, 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, testAxisVBO);
        GLES30.glEnableVertexAttribArray(wirePosLoc);
        GLES30.glVertexAttribPointer(wirePosLoc, 3, GLES30.GL_FLOAT, false, 24, 0);

        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);

        // Draw axis lines (6 vertices = 3 lines)
        GLES30.glLineWidth(3f);
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 6);

        // Draw test triangle (3 vertices starting at index 6)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 6, 3);

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisableVertexAttribArray(wirePosLoc);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
    }

    private void renderSolid(float[] mvp, float[] mv) {
        GLES30.glUseProgram(program);

        float[] normalMatrix = transpose(invertUpper3x3(mv));

        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(mvLoc, 1, false, mv, 0);
        GLES30.glUniformMatrix4fv(normalMatrixLoc, 1, false, normalMatrix, 0);

        float[] lightDir = buildLightDirection();
        GLES30.glUniform3f(lightDirLoc, lightDir[0], lightDir[1], lightDir[2]);
        GLES30.glUniform3f(ambientColorLoc, 118f / 255f, 118f / 255f, 118f / 255f);
        GLES30.glUniform3f(keyColorLoc, 244f / 255f, 244f / 255f, 244f / 255f);
        GLES30.glUniform3f(fillColorLoc, 150f / 255f, 160f / 255f, 176f / 255f);

        int modeFlag = viewMode == ViewMode.TEXTURE ? 0 : (viewMode == ViewMode.SOLID ? 1 : 2);
        GLES30.glUniform1i(viewModeLoc, modeFlag);
        GLES30.glUniform1i(hasTextureLoc, hasTexture ? 1 : 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, positionBuffer);
        GLES30.glEnableVertexAttribArray(posLoc);
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, normalBuffer);
        GLES30.glEnableVertexAttribArray(normLoc);
        GLES30.glVertexAttribPointer(normLoc, 3, GLES30.GL_FLOAT, false, 0, 0);

        if (pendingUvs != null) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvBuffer);
            GLES30.glEnableVertexAttribArray(uvLoc);
            GLES30.glVertexAttribPointer(uvLoc, 2, GLES30.GL_FLOAT, false, 0, 0);
        } else {
            GLES30.glDisableVertexAttribArray(uvLoc);
            GLES30.glVertexAttrib2f(uvLoc, 0f, 0f);
        }

        if (hasTexture) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
            GLES30.glUniform1i(textureLoc, 0);
        }

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        int indexType = vertexCount <= 65535 ? GLES30.GL_UNSIGNED_SHORT : GLES30.GL_UNSIGNED_INT;
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, indexType, 0);

        GLES30.glDisableVertexAttribArray(posLoc);
        GLES30.glDisableVertexAttribArray(normLoc);
        GLES30.glDisableVertexAttribArray(uvLoc);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private void renderWire(float[] mvp) {
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);

        GLES30.glUseProgram(program);
        float[] view = computeViewMatrix();
        float[] mv = view;
        float[] normalMatrix = transpose(invertUpper3x3(mv));
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(mvLoc, 1, false, mv, 0);
        GLES30.glUniformMatrix4fv(normalMatrixLoc, 1, false, normalMatrix, 0);

        float[] lightDir = buildLightDirection();
        GLES30.glUniform3f(lightDirLoc, lightDir[0], lightDir[1], lightDir[2]);
        GLES30.glUniform3f(ambientColorLoc, 118f / 255f, 118f / 255f, 118f / 255f);
        GLES30.glUniform3f(keyColorLoc, 244f / 255f, 244f / 255f, 244f / 255f);
        GLES30.glUniform3f(fillColorLoc, 150f / 255f, 160f / 255f, 176f / 255f);
        GLES30.glUniform1i(viewModeLoc, 2);
        GLES30.glUniform1i(hasTextureLoc, 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, positionBuffer);
        GLES30.glEnableVertexAttribArray(posLoc);
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, normalBuffer);
        GLES30.glEnableVertexAttribArray(normLoc);
        GLES30.glVertexAttribPointer(normLoc, 3, GLES30.GL_FLOAT, false, 0, 0);

        GLES30.glDisableVertexAttribArray(uvLoc);
        GLES30.glVertexAttrib2f(uvLoc, 0f, 0f);

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        int indexType = vertexCount <= 65535 ? GLES30.GL_UNSIGNED_SHORT : GLES30.GL_UNSIGNED_INT;
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, indexType, 0);

        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);

        GLES30.glUseProgram(wireProgram);
        GLES30.glUniformMatrix4fv(wireMvpLoc, 1, false, mvp, 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, positionBuffer);
        GLES30.glEnableVertexAttribArray(wirePosLoc);
        GLES30.glVertexAttribPointer(wirePosLoc, 3, GLES30.GL_FLOAT, false, 0, 0);

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        GLES30.glDrawElements(GLES30.GL_LINES, indexCount, indexType, 0);

        GLES30.glDisableVertexAttribArray(wirePosLoc);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private void uploadBuffers() {
        if (pendingPositions == null || pendingNormals == null) return;

        ByteBuffer posBuf = ByteBuffer.allocateDirect(pendingPositions.length * 4).order(ByteOrder.nativeOrder());
        posBuf.asFloatBuffer().put(pendingPositions).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, positionBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, pendingPositions.length * 4, posBuf, GLES30.GL_STATIC_DRAW);

        ByteBuffer normBuf = ByteBuffer.allocateDirect(pendingNormals.length * 4).order(ByteOrder.nativeOrder());
        normBuf.asFloatBuffer().put(pendingNormals).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, normalBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, pendingNormals.length * 4, normBuf, GLES30.GL_STATIC_DRAW);

        if (pendingUvs != null && pendingUvs.length > 0) {
            ByteBuffer uvBuf = ByteBuffer.allocateDirect(pendingUvs.length * 4).order(ByteOrder.nativeOrder());
            uvBuf.asFloatBuffer().put(pendingUvs).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvBuffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, pendingUvs.length * 4, uvBuf, GLES30.GL_STATIC_DRAW);
        }

        if (pendingIndicesShort != null) {
            ByteBuffer idxBuf = ByteBuffer.allocateDirect(pendingIndicesShort.length * 2).order(ByteOrder.nativeOrder());
            idxBuf.asShortBuffer().put(pendingIndicesShort).position(0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, pendingIndicesShort.length * 2, idxBuf, GLES30.GL_STATIC_DRAW);
        } else if (pendingIndicesInt != null) {
            ByteBuffer idxBuf = ByteBuffer.allocateDirect(pendingIndicesInt.length * 4).order(ByteOrder.nativeOrder());
            idxBuf.asIntBuffer().put(pendingIndicesInt).position(0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, pendingIndicesInt.length * 4, idxBuf, GLES30.GL_STATIC_DRAW);
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
    }

    private float[] computeForward() {
        float cp = (float) Math.cos(cameraPitch);
        return normalize(new float[]{
            (float) (cp * Math.cos(cameraYaw)),
            (float) Math.sin(cameraPitch),
            (float) (cp * Math.sin(cameraYaw))
        });
    }

    private float[] computeViewMatrix() {
        cameraPitch = Math.max(-1.45f, Math.min(1.45f, cameraPitch));
        float[] forward = computeForward();
        float[] pos = new float[]{
            (float) (cameraTarget[0] - forward[0] * cameraDistance),
            (float) (cameraTarget[1] - forward[1] * cameraDistance),
            (float) (cameraTarget[2] - forward[2] * cameraDistance)
        };
        return lookAt(pos, cameraTarget, new float[]{0f, 1f, 0f});
    }

    private float[] computeProjectionMatrix() {
        float aspect = (float) viewportWidth / (float) viewportHeight;
        // Dynamic near: always 0.1% of camera distance, clamped
        float actualNear = Math.max(near, (float) (cameraDistance * 0.001));
        if (actualNear < 0.001f) actualNear = 0.001f;
        // Very large far to prevent any clipping
        float actualFar = Math.max(far, (float) (cameraDistance + meshRadius * 4f + 1000f));
        return perspective(fov, aspect, actualNear, actualFar);
    }

    private float[] buildLightDirection() {
        lightPitch = Math.max(-1.3f, Math.min(1.3f, lightPitch));
        float cp = (float) Math.cos(lightPitch);
        float[] dir = new float[]{
            (float) (cp * Math.cos(lightYaw)),
            (float) Math.sin(lightPitch),
            (float) (cp * Math.sin(lightYaw))
        };
        float len = (float) Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        if (len < 1e-6f) return new float[]{-0.6f, -0.5f, -0.6f};
        dir[0] /= len; dir[1] /= len; dir[2] /= len;
        return dir;
    }

    private float[] lookAt(float[] eye, float[] center, float[] up) {
        float[] f = normalize(new float[]{center[0] - eye[0], center[1] - eye[1], center[2] - eye[2]});
        float[] s = normalize(cross(f, up));
        float[] u = cross(s, f);
        return new float[]{
            s[0], u[0], -f[0], 0f,
            s[1], u[1], -f[1], 0f,
            s[2], u[2], -f[2], 0f,
            -dot(s, eye), -dot(u, eye), dot(f, eye), 1f
        };
    }

    private float[] perspective(float fovy, float aspect, float near, float far) {
        double rad = Math.toRadians(fovy);
        float tanHalfFovy = (float) Math.tan(rad / 2);
        return new float[]{
            1f / (aspect * tanHalfFovy), 0f, 0f, 0f,
            0f, 1f / tanHalfFovy, 0f, 0f,
            0f, 0f, (far + near) / (near - far), -1f,
            0f, 0f, (2f * far * near) / (near - far), 0f
        };
    }

    // Reusable temp matrices to avoid per-frame GC pressure
    private final float[] tmpMVP = new float[16];
    private final float[] tmpMV = new float[16];
    private final float[] tmpNormal = new float[16];
    private final float[] tmpResult = new float[16];

    private float[] multiplyMatrix(float[] a, float[] b) {
        float[] result = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                result[col * 4 + row] = sum;
            }
        }
        return result;
    }

    /** Multiply a*b into pre-allocated result (no allocation) */
    private void multiplyMatrixInto(float[] a, float[] b, float[] result) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                result[col * 4 + row] = sum;
            }
        }
    }

    /** Compute normal matrix (inverse transpose of upper 3x3) into pre-allocated result */
    private void computeNormalMatrixInto(float[] mv, float[] result) {
        float a00 = mv[0], a01 = mv[4], a02 = mv[8];
        float a10 = mv[1], a11 = mv[5], a12 = mv[9];
        float a20 = mv[2], a21 = mv[6], a22 = mv[10];
        float det = a00 * (a11 * a22 - a12 * a21) - a01 * (a10 * a22 - a12 * a20) + a02 * (a10 * a21 - a11 * a20);
        if (Math.abs(det) < 1e-10f) {
            // identity
            result[0]=1; result[1]=0; result[2]=0; result[3]=0;
            result[4]=0; result[5]=1; result[6]=0; result[7]=0;
            result[8]=0; result[9]=0; result[10]=1; result[11]=0;
            result[12]=0; result[13]=0; result[14]=0; result[15]=1;
            return;
        }
        float invDet = 1.0f / det;
        // Inverse 3x3
        float i00 = (a11 * a22 - a12 * a21) * invDet;
        float i01 = (a02 * a21 - a01 * a22) * invDet;
        float i02 = (a01 * a12 - a02 * a11) * invDet;
        float i10 = (a12 * a20 - a10 * a22) * invDet;
        float i11 = (a00 * a22 - a02 * a20) * invDet;
        float i12 = (a02 * a10 - a00 * a12) * invDet;
        float i20 = (a10 * a21 - a11 * a20) * invDet;
        float i21 = (a01 * a20 - a00 * a21) * invDet;
        float i22 = (a00 * a11 - a01 * a10) * invDet;
        // Transpose into result
        result[0]=i00; result[1]=i10; result[2]=i20; result[3]=0;
        result[4]=i01; result[5]=i11; result[6]=i21; result[7]=0;
        result[8]=i02; result[9]=i12; result[10]=i22; result[11]=0;
        result[12]=0; result[13]=0; result[14]=0; result[15]=1;
    }

    private float[] identityMatrix() {
        return new float[]{1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f};
    }

    private float[] invertUpper3x3(float[] m) {
        float a00 = m[0], a01 = m[4], a02 = m[8];
        float a10 = m[1], a11 = m[5], a12 = m[9];
        float a20 = m[2], a21 = m[6], a22 = m[10];
        float det = a00 * (a11 * a22 - a12 * a21) - a01 * (a10 * a22 - a12 * a20) + a02 * (a10 * a21 - a11 * a20);
        if (det == 0f) return identityMatrix();
        float invDet = 1f / det;
        return new float[]{
            (a11 * a22 - a12 * a21) * invDet,
            (a12 * a20 - a10 * a22) * invDet,
            (a10 * a21 - a11 * a20) * invDet,
            0f,
            (a02 * a21 - a01 * a22) * invDet,
            (a00 * a22 - a02 * a20) * invDet,
            (a01 * a20 - a00 * a21) * invDet,
            0f,
            (a01 * a12 - a02 * a11) * invDet,
            (a02 * a10 - a00 * a12) * invDet,
            (a00 * a11 - a01 * a10) * invDet,
            0f,
            0f, 0f, 0f, 1f
        };
    }

    private float[] transpose(float[] m) {
        return new float[]{
            m[0], m[4], m[8], m[12],
            m[1], m[5], m[9], m[13],
            m[2], m[6], m[10], m[14],
            m[3], m[7], m[11], m[15]
        };
    }

    private float[] cross(float[] a, float[] b) {
        return new float[]{
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }

    private float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private float[] normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-6f) return new float[]{0f, 1f, 0f};
        return new float[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private void createProgram() {
        String vertexShader =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "layout(location = 0) in vec3 aPosition;\n" +
            "layout(location = 1) in vec3 aNormal;\n" +
            "layout(location = 2) in vec2 aTexcoord;\n" +
            "layout(location = 3) in vec4 aColor;\n" +
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uMV;\n" +
            "uniform mat4 uNormalMatrix;\n" +
            "out vec3 vNormal;\n" +
            "out vec2 vTexcoord;\n" +
            "out vec3 vWorldPos;\n" +
            "out vec4 vColor;\n" +
            "void main() {\n" +
            "    vec4 worldPos = uMV * vec4(aPosition, 1.0);\n" +
            "    vWorldPos = worldPos.xyz;\n" +
            "    vNormal = normalize((uNormalMatrix * vec4(aNormal, 0.0)).xyz);\n" +
            "    vTexcoord = aTexcoord;\n" +
            "    vColor = aColor;\n" +
            "    gl_Position = uMVP * vec4(aPosition, 1.0);\n" +
            "}\n";

        String fragmentShader =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec3 vNormal;\n" +
            "in vec2 vTexcoord;\n" +
            "in vec3 vWorldPos;\n" +
            "in vec4 vColor;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform vec3 uAmbientColor;\n" +
            "uniform vec3 uKeyColor;\n" +
            "uniform vec3 uFillColor;\n" +
            "uniform int uViewMode;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform int uHasTexture;\n" +
            "uniform int uHasVertexColors;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec3 N = normalize(vNormal);\n" +
            "    vec3 L = normalize(-uLightDir);\n" +
            "    float NdotL = max(dot(N, L), 0.0);\n" +
            "    vec3 keyContribution = uKeyColor * NdotL;\n" +
            "    vec3 fillDir = normalize(vec3(-uLightDir.x * 0.35, -0.5, -uLightDir.z * 0.35));\n" +
            "    float NdotF = max(dot(N, fillDir), 0.0);\n" +
            "    vec3 fillContribution = uFillColor * NdotF * 0.5;\n" +
            "    vec3 ambient = uAmbientColor;\n" +
            "    vec3 baseColor;\n" +
            "    float alpha = 1.0;\n" +
            "    if (uHasTexture == 1) {\n" +
            "        vec4 texColor = texture(uTexture, vTexcoord);\n" +
            "        baseColor = texColor.rgb;\n" +
            "        alpha = texColor.a;\n" +
            "    } else if (uHasVertexColors == 1) {\n" +
            "        baseColor = vColor.rgb;\n" +
            "        alpha = vColor.a;\n" +
            "    } else {\n" +
            "        baseColor = vec3(0.80, 0.84, 0.89);\n" +
            "    }\n" +
            "    if (uViewMode == 0) {\n" +  // Texture mode — full texture + lighting
            "        vec3 lighting = ambient + keyContribution + fillContribution;\n" +
            "        fragColor = vec4(baseColor * lighting, alpha);\n" +
            "    } else if (uViewMode == 1) {\n" +  // Solid mode — NO texture, just flat color + lighting
            "        vec3 solidColor = vec3(0.80, 0.84, 0.89);\n" +
            "        vec3 lighting = ambient + keyContribution + fillContribution;\n" +
            "        fragColor = vec4(solidColor * lighting, 1.0);\n" +
            "    } else if (uViewMode == 2) {\n" +  // Wireframe fill — show dim lit surface behind lines
            "        vec3 lighting = ambient * 0.3 + keyContribution * 0.15 + fillContribution * 0.15;\n" +
            "        fragColor = vec4(baseColor * lighting, 1.0);\n" +
            "    } else {\n" +  // Wireframe edges (mode 3) — bright lines
            "        fragColor = vec4(0.0, 0.9, 1.0, 1.0);\n" +
            "    }\n" +
            "}\n";

        program = createShaderProgram(vertexShader, fragmentShader);

        posLoc = GLES30.glGetAttribLocation(program, "aPosition");
        normLoc = GLES30.glGetAttribLocation(program, "aNormal");
        uvLoc = GLES30.glGetAttribLocation(program, "aTexcoord");
        mvpLoc = GLES30.glGetUniformLocation(program, "uMVP");
        mvLoc = GLES30.glGetUniformLocation(program, "uMV");
        normalMatrixLoc = GLES30.glGetUniformLocation(program, "uNormalMatrix");
        lightDirLoc = GLES30.glGetUniformLocation(program, "uLightDir");
        ambientColorLoc = GLES30.glGetUniformLocation(program, "uAmbientColor");
        keyColorLoc = GLES30.glGetUniformLocation(program, "uKeyColor");
        fillColorLoc = GLES30.glGetUniformLocation(program, "uFillColor");
        viewModeLoc = GLES30.glGetUniformLocation(program, "uViewMode");
        textureLoc = GLES30.glGetUniformLocation(program, "uTexture");
        hasTextureLoc = GLES30.glGetUniformLocation(program, "uHasTexture");
        hasVertexColorsLoc = GLES30.glGetUniformLocation(program, "uHasVertexColors");
        noAlphaDiscardLoc = GLES30.glGetUniformLocation(program, "uNoAlphaDiscard");
        colorLoc = 3; // layout(location = 3)
    }

    private void createWireProgram() {
        String vertexShader =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "layout(location = 0) in vec3 aPosition;\n" +
            "uniform mat4 uMVP;\n" +
            "void main() {\n" +
            "    gl_Position = uMVP * vec4(aPosition, 1.0);\n" +
            "}\n";

        String fragmentShader =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = vec4(0.98, 0.98, 0.98, 1.0);\n" +
            "}\n";

        wireProgram = createShaderProgram(vertexShader, fragmentShader);
        wireMvpLoc = GLES30.glGetUniformLocation(wireProgram, "uMVP");
        wirePosLoc = GLES30.glGetAttribLocation(wireProgram, "aPosition");
    }

    private int createShaderProgram(String vertexSrc, String fragmentSrc) {
        int vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc);
        int fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc);
        int prog = GLES30.glCreateProgram();
        GLES30.glAttachShader(prog, vs);
        GLES30.glAttachShader(prog, fs);
        GLES30.glLinkProgram(prog);
        int[] linkStatus = new int[1];
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES30.GL_TRUE) {
            String log = GLES30.glGetProgramInfoLog(prog);
            GLES30.glDeleteProgram(prog);
            throw new RuntimeException("Failed to link program: " + log);
        }
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        return prog;
    }

    private int compileShader(int type, String src) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, src);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES30.GL_TRUE) {
            String log = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new RuntimeException("Failed to compile shader: " + log);
        }
        return shader;
    }

    private List<float[]> computeSmoothNormals(List<float[]> vertices, List<int[]> indices) {
        float[][] normals = new float[vertices.size()][3];
        for (int[] tri : indices) {
            int a = tri[0], b = tri[1], c = tri[2];
            if (a < 0 || a >= vertices.size() || b < 0 || b >= vertices.size() || c < 0 || c >= vertices.size())
                continue;
            float[] va = vertices.get(a);
            float[] vb = vertices.get(b);
            float[] vc = vertices.get(c);
            float ux = vb[0] - va[0], uy = vb[1] - va[1], uz = vb[2] - va[2];
            float vx = vc[0] - va[0], vy = vc[1] - va[1], vz = vc[2] - va[2];
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            normals[a][0] += nx; normals[a][1] += ny; normals[a][2] += nz;
            normals[b][0] += nx; normals[b][1] += ny; normals[b][2] += nz;
            normals[c][0] += nx; normals[c][1] += ny; normals[c][2] += nz;
        }
        List<float[]> result = new ArrayList<>();
        for (float[] n : normals) {
            float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (len > 1e-10f) {
                result.add(new float[]{n[0] / len, n[1] / len, n[2] / len});
            } else {
                result.add(new float[]{0f, 1f, 0f});
            }
        }
        return result;
    }
}
