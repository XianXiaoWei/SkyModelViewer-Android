package com.sky.modelviewer.export;

import com.sky.modelviewer.model.MeshData;
import com.sky.modelviewer.parsing.LevelMeshesReader;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/**
 * OBJ format exporter for mesh models and terrain data.
 *
 * Supports:
 *   - Single mesh (MeshData) → .obj with v/vn/vt/f
 *   - Combined level (terrain + mesh instances with transforms) → .obj
 */
public class ObjExporter {

    /**
     * Export a single mesh model to OBJ format.
     */
    public static void exportMesh(OutputStream os, MeshData mesh, float scale) {
        PrintWriter pw = new PrintWriter(os);

        pw.println("# Exported by SkyModelViewer");
        pw.println("o " + sanitizeName(mesh.name));

        // Vertices
        for (float[] v : mesh.vertices) {
            pw.printf(Locale.US, "v %.6f %.6f %.6f%n",
                      v[0] * scale, v[1] * scale, v[2] * scale);
        }

        // UVs
        for (float[] uv : mesh.uv0) {
            pw.printf(Locale.US, "vt %.6f %.6f%n", uv[0], uv[1]);
        }

        // Normals
        for (byte[] attr : mesh.packedVertexAttrs) {
            float nx = snorm(attr[0]);
            float ny = snorm(attr[1]);
            float nz = snorm(attr[2]);
            pw.printf(Locale.US, "vn %.6f %.6f %.6f%n", nx, ny, nz);
        }

        // Faces (1-based indexing)
        for (int[] tri : mesh.indices) {
            int a = tri[0] + 1, b = tri[1] + 1, c = tri[2] + 1;
            pw.printf(Locale.US, "f %d/%d/%d %d/%d/%d %d/%d/%d%n",
                      a, a, a, b, b, b, c, c, c);
        }

        pw.flush();
    }

    /**
     * Export terrain data (from .meshes) to OBJ format.
     */
    public static void exportTerrain(OutputStream os, LevelMeshesReader.MeshesData terrain,
                                     float scale, String name) {
        PrintWriter pw = new PrintWriter(os);

        pw.println("# Exported by SkyModelViewer");
        pw.println("o " + sanitizeName(name));

        int vCount = terrain.vertexCount;

        // Vertices
        for (int i = 0; i < vCount; i++) {
            int off = i * 3;
            pw.printf(Locale.US, "v %.6f %.6f %.6f%n",
                      terrain.positions[off] * scale,
                      terrain.positions[off + 1] * scale,
                      terrain.positions[off + 2] * scale);
        }

        // Normals
        for (int i = 0; i < vCount; i++) {
            int off = i * 3;
            pw.printf(Locale.US, "vn %.6f %.6f %.6f%n",
                      terrain.normals[off], terrain.normals[off + 1], terrain.normals[off + 2]);
        }

        // Faces
        int triCount = terrain.indices.length / 3;
        for (int i = 0; i < triCount; i++) {
            int a = terrain.indices[i * 3] + 1;
            int b = terrain.indices[i * 3 + 1] + 1;
            int c = terrain.indices[i * 3 + 2] + 1;
            pw.printf(Locale.US, "f %d//%d %d//%d %d//%d%n", a, a, b, b, c, c);
        }

        pw.flush();
    }

    /**
     * Export a complete level: terrain meshes + all mesh instances with transforms.
     * (Backward-compatible version without textures - delegates to the textured version.)
     *
     * @param terrains        Terrain data from .meshes
     * @param levelMeshes     Mesh instances placed in the level
     * @param levelTransforms Transform matrices (4x4 column-major) for each mesh, or null
     * @param scale           Global scale factor
     * @param name            Object name
     */
    public static void exportCombinedLevel(OutputStream os,
                                           LevelMeshesReader.MeshesData[] terrains,
                                           List<MeshData> levelMeshes,
                                           List<float[]> levelTransforms,
                                           float scale, String name) {
        exportCombinedLevel(os, terrains, levelMeshes, levelTransforms, scale, name, null, null);
    }

    /**
     * Export a complete level with optional texture references.
     *
     * OBJ format:
     *   - If textureDataList is provided (non-null, non-empty):
     *     - mtllib levelName.mtl
     *     - Terrain: usemtl terrain, vertex colors via v(x y z r g b)
     *     - Each mesh with texture: usemtl mesh_name (texture file referenced as mesh_name.png)
     *   - If textureDataList is null/empty: uses existing format (no material references)
     *
     * Note: OBJ does not support embedded textures. Texture file names are referenced
     * via usemtl; the actual texture data (textureDataList) is not written to the OBJ file.
     *
     * @param os              Destination output stream.
     * @param terrains        Terrain data from .meshes
     * @param levelMeshes     Mesh instances placed in the level
     * @param levelTransforms Transform matrices (4x4 column-major) for each mesh, or null
     * @param scale           Global scale factor
     * @param levelName       Object/material-library name
     * @param textureDataList Texture byte data per mesh (null/empty disables texture references)
     * @param textureNames    Texture file name per mesh (e.g., "mesh_name.png"), or null
     */
    public static void exportCombinedLevel(OutputStream os,
                                           LevelMeshesReader.MeshesData[] terrains,
                                           List<MeshData> levelMeshes,
                                           List<float[]> levelTransforms,
                                           float scale,
                                           String levelName,
                                           List<byte[]> textureDataList,
                                           List<String> textureNames) {
        PrintWriter pw = new PrintWriter(os);

        boolean useTextures = textureDataList != null && !textureDataList.isEmpty();
        int meshCount = levelMeshes != null ? levelMeshes.size() : 0;

        pw.println("# Combined level export by SkyModelViewer");
        pw.println("# Contains: terrain + " + meshCount + " mesh instances");
        pw.println("o " + sanitizeName(levelName));

        if (useTextures) {
            pw.println("mtllib " + sanitizeName(levelName) + ".mtl");
        }

        int vertexOffset = 0;
        int uvOffset = 0;

        // --- Part 1: Terrain meshes ---
        if (terrains != null) {
            for (int t = 0; t < terrains.length; t++) {
                LevelMeshesReader.MeshesData terrain = terrains[t];
                int vCount = terrain.vertexCount;

                pw.println("g terrain_" + t);
                if (useTextures) {
                    pw.println("usemtl terrain");
                }

                // Vertices (with color if useTextures)
                for (int i = 0; i < vCount; i++) {
                    int off = i * 3;
                    float vx = terrain.positions[off] * scale;
                    float vy = terrain.positions[off + 1] * scale;
                    float vz = terrain.positions[off + 2] * scale;
                    if (useTextures) {
                        float r = 0.5f, g = 0.5f, b = 0.5f;
                        if (terrain.colors != null && (i + 1) * 4 <= terrain.colors.length) {
                            r = terrain.colors[i * 4];
                            g = terrain.colors[i * 4 + 1];
                            b = terrain.colors[i * 4 + 2];
                        }
                        pw.printf(Locale.US, "v %.6f %.6f %.6f %.6f %.6f %.6f%n",
                                  vx, vy, vz, r, g, b);
                    } else {
                        pw.printf(Locale.US, "v %.6f %.6f %.6f%n", vx, vy, vz);
                    }
                }

                // Normals
                for (int i = 0; i < vCount; i++) {
                    int off = i * 3;
                    pw.printf(Locale.US, "vn %.6f %.6f %.6f%n",
                              terrain.normals[off], terrain.normals[off + 1], terrain.normals[off + 2]);
                }

                // Faces (terrain has no UVs)
                int triCount = terrain.indices.length / 3;
                for (int i = 0; i < triCount; i++) {
                    int a = terrain.indices[i * 3] + vertexOffset + 1;
                    int b = terrain.indices[i * 3 + 1] + vertexOffset + 1;
                    int c = terrain.indices[i * 3 + 2] + vertexOffset + 1;
                    pw.printf(Locale.US, "f %d//%d %d//%d %d//%d%n", a, a, b, b, c, c);
                }

                vertexOffset += vCount;
            }
        }

        // --- Part 2: Mesh instances with transforms ---
        if (levelMeshes != null) {
            for (int m = 0; m < levelMeshes.size(); m++) {
                MeshData mesh = levelMeshes.get(m);
                float[] transform = (levelTransforms != null && m < levelTransforms.size())
                                    ? levelTransforms.get(m) : null;

                int vCount = mesh.vertices.size();
                String meshName = sanitizeName(mesh.name);
                boolean hasUvs = mesh.uv0 != null && !mesh.uv0.isEmpty();

                pw.println("g mesh_" + m + "_" + meshName);

                if (useTextures) {
                    boolean hasTexture = m < textureDataList.size()
                                         && textureDataList.get(m) != null
                                         && textureDataList.get(m).length > 0;
                    if (hasTexture) {
                        pw.println("usemtl " + meshName);
                        // Write texture file name reference as comment
                        String texName = (textureNames != null && m < textureNames.size()
                                          && textureNames.get(m) != null)
                                         ? textureNames.get(m) : (meshName + ".png");
                        pw.println("# map_Kd " + texName);
                    } else {
                        pw.println("usemtl terrain");
                    }
                }

                // Vertices (with transform applied)
                for (int i = 0; i < vCount; i++) {
                    float[] v = mesh.vertices.get(i);
                    float x = v[0] * scale;
                    float y = v[1] * scale;
                    float z = v[2] * scale;

                    if (transform != null) {
                        // Column-major 4x4: transform[col*4 + row]
                        float nx = transform[0]*x + transform[4]*y + transform[8]*z + transform[12];
                        float ny = transform[1]*x + transform[5]*y + transform[9]*z + transform[13];
                        float nz = transform[2]*x + transform[6]*y + transform[10]*z + transform[14];
                        x = nx; y = ny; z = nz;
                    }

                    pw.printf(Locale.US, "v %.6f %.6f %.6f%n", x, y, z);
                }

                // UVs
                if (hasUvs) {
                    for (int i = 0; i < mesh.uv0.size(); i++) {
                        float[] uv = mesh.uv0.get(i);
                        pw.printf(Locale.US, "vt %.6f %.6f%n", uv[0], uv[1]);
                    }
                }

                // Normals (with transform rotation applied)
                for (int i = 0; i < mesh.packedVertexAttrs.size(); i++) {
                    byte[] attr = mesh.packedVertexAttrs.get(i);
                    float nx = snorm(attr[0]);
                    float ny = snorm(attr[1]);
                    float nz = snorm(attr[2]);

                    if (transform != null) {
                        // Only rotation part (no translation for normals)
                        float tx = transform[0]*nx + transform[4]*ny + transform[8]*nz;
                        float ty = transform[1]*nx + transform[5]*ny + transform[9]*nz;
                        float tz = transform[2]*nx + transform[6]*ny + transform[10]*nz;
                        nx = tx; ny = ty; nz = tz;
                        // Normalize
                        float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
                        if (len > 0.0001f) { nx /= len; ny /= len; nz /= len; }
                    }

                    pw.printf(Locale.US, "vn %.6f %.6f %.6f%n", nx, ny, nz);
                }

                // Faces (with correct UV offset)
                for (int[] tri : mesh.indices) {
                    int vA = tri[0] + vertexOffset + 1;
                    int vB = tri[1] + vertexOffset + 1;
                    int vC = tri[2] + vertexOffset + 1;
                    if (hasUvs) {
                        int uvA = tri[0] + uvOffset + 1;
                        int uvB = tri[1] + uvOffset + 1;
                        int uvC = tri[2] + uvOffset + 1;
                        pw.printf(Locale.US, "f %d/%d/%d %d/%d/%d %d/%d/%d%n",
                                  vA, uvA, vA, vB, uvB, vB, vC, uvC, vC);
                    } else {
                        pw.printf(Locale.US, "f %d//%d %d//%d %d//%d%n",
                                  vA, vA, vB, vB, vC, vC);
                    }
                }

                if (hasUvs) {
                    uvOffset += mesh.uv0.size();
                }
                vertexOffset += vCount;
            }
        }

        pw.flush();
    }

    /**
     * Export multiple terrain meshes combined into one OBJ (legacy, terrain only).
     */
    public static void exportCombined(OutputStream os,
                                      LevelMeshesReader.MeshesData[] terrains,
                                      float scale, String name) {
        PrintWriter pw = new PrintWriter(os);

        pw.println("# Combined export by SkyModelViewer");
        pw.println("o " + sanitizeName(name));

        int vertexOffset = 0;

        for (int t = 0; t < terrains.length; t++) {
            LevelMeshesReader.MeshesData terrain = terrains[t];
            int vCount = terrain.vertexCount;

            pw.println("g terrain_" + t);

            // Vertices
            for (int i = 0; i < vCount; i++) {
                int off = i * 3;
                pw.printf(Locale.US, "v %.6f %.6f %.6f%n",
                          terrain.positions[off] * scale,
                          terrain.positions[off + 1] * scale,
                          terrain.positions[off + 2] * scale);
            }

            // Normals
            for (int i = 0; i < vCount; i++) {
                int off = i * 3;
                pw.printf(Locale.US, "vn %.6f %.6f %.6f%n",
                          terrain.normals[off], terrain.normals[off + 1], terrain.normals[off + 2]);
            }

            // Faces (offset by accumulated vertex count)
            int triCount = terrain.indices.length / 3;
            for (int i = 0; i < triCount; i++) {
                int a = terrain.indices[i * 3] + vertexOffset + 1;
                int b = terrain.indices[i * 3 + 1] + vertexOffset + 1;
                int c = terrain.indices[i * 3 + 2] + vertexOffset + 1;
                pw.printf(Locale.US, "f %d//%d %d//%d %d//%d%n", a, a, b, b, c, c);
            }

            vertexOffset += vCount;
        }

        pw.flush();
    }

    private static float snorm(byte b) {
        int u = b & 0xFF;
        int s = u >= 128 ? u - 256 : u;
        float v = s / 127.0f;
        if (v < -1.0f) v = -1.0f;
        if (v > 1.0f) v = 1.0f;
        return v;
    }

    private static String sanitizeName(String name) {
        if (name == null) return "mesh";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
