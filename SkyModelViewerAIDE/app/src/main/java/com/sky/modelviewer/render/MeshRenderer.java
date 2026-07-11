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
        public float[] transform;
        public String name;

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
    private int wireMvpLoc = 0, wirePosLoc = 0;

    private int positionBuffer = 0, normalBuffer = 0, uvBuffer = 0, indexBuffer = 0;
    private int indexCount = 0, vertexCount = 0;

    private int textureId = 0;
    private boolean hasTexture = false;

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

        frameCamera();
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

    // ===== Multi-mesh API =====

    public void clearMeshInstances() {
        quadMode = false;
        for (MeshInstance inst : meshInstances) {
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

        // Upload indices
        if (indicesShort != null) {
            ByteBuffer idxBuf = ByteBuffer.allocateDirect(indicesShort.length * 2).order(ByteOrder.nativeOrder());
            idxBuf.asShortBuffer().put(indicesShort).position(0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, inst.indexBuffer);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indicesShort.length * 2, idxBuf, GLES30.GL_STATIC_DRAW);
        } else if (indicesInt != null) {
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

        // Update bounds
        boolean boundsInit = false;
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
                boundsInit = true;
            } else {
                meshMin[0] = Math.min(meshMin[0], px);
                meshMin[1] = Math.min(meshMin[1], py);
                meshMin[2] = Math.min(meshMin[2], pz);
                meshMax[0] = Math.max(meshMax[0], px);
                meshMax[1] = Math.max(meshMax[1], py);
                meshMax[2] = Math.max(meshMax[2], pz);
            }
        }
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
        // For levels: camera at origin
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

        // Draw a test axis indicator at origin (always visible, helps debug camera)
        drawTestAxes();

        if (meshInstances.isEmpty()) return;

        // Disable face culling entirely — level meshes have mixed winding orders
        GLES30.glDisable(GLES30.GL_CULL_FACE);

        float[] viewMatrix = computeViewMatrix();
        float[] projMatrix = computeProjectionMatrix();
        float[] vp = multiplyMatrix(projMatrix, viewMatrix);

        for (MeshInstance inst : meshInstances) {
            if (inst.vertexCount == 0 || inst.indexCount == 0) continue;
            float[] modelMatrix = inst.transform;
            float[] mvp;
            float[] mv;
            if (modelMatrix != null) {
                mvp = multiplyMatrix(vp, modelMatrix);
                mv = multiplyMatrix(viewMatrix, modelMatrix);
            } else {
                mvp = vp;
                mv = viewMatrix;
            }
            renderInstance(inst, mvp, mv);
        }
    }

    private void renderInstance(MeshInstance inst, float[] mvp, float[] mv) {
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
        GLES30.glUniform1i(hasTextureLoc, inst.hasTexture ? 1 : 0);
        GLES30.glUniform1i(hasVertexColorsLoc, inst.hasVertexColors ? 1 : 0);

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
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, inst.indexCount, indexType, 0);

        GLES30.glDisableVertexAttribArray(posLoc);
        GLES30.glDisableVertexAttribArray(normLoc);
        GLES30.glDisableVertexAttribArray(uvLoc);
        GLES30.glDisableVertexAttribArray(colorLoc);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
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
            "    if (uViewMode == 0) {\n" +
            "        vec3 lighting = ambient + keyContribution + fillContribution;\n" +
            "        fragColor = vec4(baseColor * lighting, alpha);\n" +
            "    } else {\n" +
            "        vec3 lighting = ambient + keyContribution + fillContribution;\n" +
            "        fragColor = vec4(baseColor * lighting, 1.0);\n" +
            "    }\n" +
            "    if (uViewMode == 2) {\n" +
            "        fragColor = vec4(0.047, 0.055, 0.067, 0.11);\n" +
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
