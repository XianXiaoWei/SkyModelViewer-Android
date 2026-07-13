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
        PrintWriter pw = new PrintWriter(os);

        pw.println("# Combined level export by SkyModelViewer");
        pw.println("# Contains: terrain + " + levelMeshes.size() + " mesh instances");
        pw.println("o " + sanitizeName(name));

        int vertexOffset = 0;

        // --- Part 1: Terrain meshes ---
        if (terrains != null) {
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

                // Faces
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
        for (int m = 0; m < levelMeshes.size(); m++) {
            MeshData mesh = levelMeshes.get(m);
            float[] transform = (levelTransforms != null && m < levelTransforms.size())
                                ? levelTransforms.get(m) : null;

            int vCount = mesh.vertices.size();
            pw.println("g mesh_" + m + "_" + sanitizeName(mesh.name));

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
            for (int i = 0; i < mesh.uv0.size(); i++) {
                float[] uv = mesh.uv0.get(i);
                pw.printf(Locale.US, "vt %.6f %.6f%n", uv[0], uv[1]);
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

            // Faces
            for (int[] tri : mesh.indices) {
                int a = tri[0] + vertexOffset + 1;
                int b = tri[1] + vertexOffset + 1;
                int c = tri[2] + vertexOffset + 1;
                pw.printf(Locale.US, "f %d/%d/%d %d/%d/%d %d/%d/%d%n",
                          a, a, a, b, b, b, c, c, c);
            }

            vertexOffset += vCount;
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
