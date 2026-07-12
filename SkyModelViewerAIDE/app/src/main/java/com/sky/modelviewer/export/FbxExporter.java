package com.sky.modelviewer.export;

import com.sky.modelviewer.model.MeshData;
import com.sky.modelviewer.parsing.LevelMeshesReader;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/**
 * ASCII FBX 7.4 format exporter.
 * Produces files compatible with Blender, Maya, 3ds Max.
 */
public class FbxExporter {

    private static int sGeomId = 0;
    private static int sModelId = 0;

    /**
     * Export a single mesh model to ASCII FBX.
     */
    public static void exportMesh(OutputStream os, MeshData mesh, float scale) {
        PrintWriter pw = new PrintWriter(os);
        String name = sanitizeName(mesh.name);

        writeHeader(pw);

        int vCount = mesh.vertices.size();
        float[] flatVerts = new float[vCount * 3];
        for (int i = 0; i < vCount; i++) {
            float[] v = mesh.vertices.get(i);
            flatVerts[i * 3] = v[0] * scale;
            flatVerts[i * 3 + 1] = v[1] * scale;
            flatVerts[i * 3 + 2] = v[2] * scale;
        }

        int triCount = mesh.indices.size();
        int[] flatIdx = new int[triCount * 3];
        for (int i = 0; i < triCount; i++) {
            int[] tri = mesh.indices.get(i);
            flatIdx[i * 3] = tri[0];
            flatIdx[i * 3 + 1] = tri[1];
            flatIdx[i * 3 + 2] = tri[2];
        }

        float[] flatNorms = new float[vCount * 3];
        for (int i = 0; i < vCount; i++) {
            byte[] attr = mesh.packedVertexAttrs.get(i);
            flatNorms[i * 3] = snorm(attr[0]);
            flatNorms[i * 3 + 1] = snorm(attr[1]);
            flatNorms[i * 3 + 2] = snorm(attr[2]);
        }

        float[] flatUVs = new float[vCount * 2];
        for (int i = 0; i < mesh.uv0.size() && i < vCount; i++) {
            float[] uv = mesh.uv0.get(i);
            flatUVs[i * 2] = uv[0];
            flatUVs[i * 2 + 1] = uv[1];
        }

        writeObjects(pw, name, flatVerts, flatIdx, flatNorms,
                     flatUVs.length > 0 ? flatUVs : null);
        writeConnections(pw);
        writeFooter(pw);

        pw.flush();
    }

    /**
     * Export terrain data to ASCII FBX.
     */
    public static void exportTerrain(OutputStream os, LevelMeshesReader.MeshesData terrain,
                                     float scale, String name) {
        PrintWriter pw = new PrintWriter(os);
        String n = sanitizeName(name);

        writeHeader(pw);

        int vCount = terrain.vertexCount;
        float[] flatVerts = new float[vCount * 3];
        for (int i = 0; i < vCount; i++) {
            flatVerts[i * 3] = terrain.positions[i * 3] * scale;
            flatVerts[i * 3 + 1] = terrain.positions[i * 3 + 1] * scale;
            flatVerts[i * 3 + 2] = terrain.positions[i * 3 + 2] * scale;
        }

        int triCount = terrain.indices.length / 3;
        int[] flatIdx = new int[triCount * 3];
        for (int i = 0; i < triCount; i++) {
            flatIdx[i * 3] = terrain.indices[i * 3];
            flatIdx[i * 3 + 1] = terrain.indices[i * 3 + 1];
            flatIdx[i * 3 + 2] = terrain.indices[i * 3 + 2];
        }

        writeObjects(pw, n, flatVerts, flatIdx, terrain.normals, null);
        writeConnections(pw);
        writeFooter(pw);

        pw.flush();
    }

    /**
     * Export a complete level: terrain + all mesh instances with transforms applied.
     */
    public static void exportCombinedLevel(OutputStream os,
                                           LevelMeshesReader.MeshesData[] terrains,
                                           List<MeshData> levelMeshes,
                                           List<float[]> levelTransforms,
                                           float scale, String name) {
        PrintWriter pw = new PrintWriter(os);
        String n = sanitizeName(name);

        writeHeader(pw);

        int totalVerts = 0, totalTris = 0;
        if (terrains != null) {
            for (LevelMeshesReader.MeshesData t : terrains) {
                totalVerts += t.vertexCount;
                totalTris += t.indices.length / 3;
            }
        }
        for (MeshData m : levelMeshes) {
            totalVerts += m.vertices.size();
            totalTris += m.indices.size();
        }

        float[] flatVerts = new float[totalVerts * 3];
        float[] flatNorms = new float[totalVerts * 3];
        int[] flatIdx = new int[totalTris * 3];

        int vOff = 0, iOff = 0, vAccum = 0;

        if (terrains != null) {
            for (LevelMeshesReader.MeshesData t : terrains) {
                for (int i = 0; i < t.vertexCount; i++) {
                    flatVerts[(vOff + i) * 3] = t.positions[i * 3] * scale;
                    flatVerts[(vOff + i) * 3 + 1] = t.positions[i * 3 + 1] * scale;
                    flatVerts[(vOff + i) * 3 + 2] = t.positions[i * 3 + 2] * scale;
                    flatNorms[(vOff + i) * 3] = t.normals[i * 3];
                    flatNorms[(vOff + i) * 3 + 1] = t.normals[i * 3 + 1];
                    flatNorms[(vOff + i) * 3 + 2] = t.normals[i * 3 + 2];
                }
                int tris = t.indices.length / 3;
                for (int i = 0; i < tris; i++) {
                    flatIdx[iOff + i * 3] = t.indices[i * 3] + vAccum;
                    flatIdx[iOff + i * 3 + 1] = t.indices[i * 3 + 1] + vAccum;
                    flatIdx[iOff + i * 3 + 2] = t.indices[i * 3 + 2] + vAccum;
                }
                vOff += t.vertexCount;
                iOff += tris * 3;
                vAccum += t.vertexCount;
            }
        }

        for (int m = 0; m < levelMeshes.size(); m++) {
            MeshData mesh = levelMeshes.get(m);
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
                flatVerts[(vOff + i) * 3] = x;
                flatVerts[(vOff + i) * 3 + 1] = y;
                flatVerts[(vOff + i) * 3 + 2] = z;

                byte[] attr = mesh.packedVertexAttrs.get(i);
                float nx = snorm(attr[0]), ny = snorm(attr[1]), nz = snorm(attr[2]);
                if (transform != null) {
                    float tx = transform[0]*nx + transform[4]*ny + transform[8]*nz;
                    float ty = transform[1]*nx + transform[5]*ny + transform[9]*nz;
                    float tz = transform[2]*nx + transform[6]*ny + transform[10]*nz;
                    nx = tx; ny = ty; nz = tz;
                    float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
                    if (len > 0.0001f) { nx /= len; ny /= len; nz /= len; }
                }
                flatNorms[(vOff + i) * 3] = nx;
                flatNorms[(vOff + i) * 3 + 1] = ny;
                flatNorms[(vOff + i) * 3 + 2] = nz;
            }

            for (int i = 0; i < mesh.indices.size(); i++) {
                int[] tri = mesh.indices.get(i);
                flatIdx[iOff + i * 3] = tri[0] + vAccum;
                flatIdx[iOff + i * 3 + 1] = tri[1] + vAccum;
                flatIdx[iOff + i * 3 + 2] = tri[2] + vAccum;
            }

            vOff += vc;
            iOff += mesh.indices.size() * 3;
            vAccum += vc;
        }

        writeObjects(pw, n, flatVerts, flatIdx, flatNorms, null);
        writeConnections(pw);
        writeFooter(pw);

        pw.flush();
    }

    /**
     * Export multiple terrain meshes combined into one FBX (legacy).
     */
    public static void exportCombined(OutputStream os,
                                      LevelMeshesReader.MeshesData[] terrains,
                                      float scale, String name) {
        PrintWriter pw = new PrintWriter(os);
        String n = sanitizeName(name);

        writeHeader(pw);

        int totalVerts = 0, totalTris = 0;
        for (LevelMeshesReader.MeshesData t : terrains) {
            totalVerts += t.vertexCount;
            totalTris += t.indices.length / 3;
        }

        float[] flatVerts = new float[totalVerts * 3];
        float[] flatNorms = new float[totalVerts * 3];
        int[] flatIdx = new int[totalTris * 3];

        int vOff = 0, iOff = 0, vAccum = 0;
        for (LevelMeshesReader.MeshesData t : terrains) {
            for (int i = 0; i < t.vertexCount; i++) {
                flatVerts[(vOff + i) * 3] = t.positions[i * 3] * scale;
                flatVerts[(vOff + i) * 3 + 1] = t.positions[i * 3 + 1] * scale;
                flatVerts[(vOff + i) * 3 + 2] = t.positions[i * 3 + 2] * scale;
                flatNorms[(vOff + i) * 3] = t.normals[i * 3];
                flatNorms[(vOff + i) * 3 + 1] = t.normals[i * 3 + 1];
                flatNorms[(vOff + i) * 3 + 2] = t.normals[i * 3 + 2];
            }
            int tris = t.indices.length / 3;
            for (int i = 0; i < tris; i++) {
                flatIdx[iOff + i * 3] = t.indices[i * 3] + vAccum;
                flatIdx[iOff + i * 3 + 1] = t.indices[i * 3 + 1] + vAccum;
                flatIdx[iOff + i * 3 + 2] = t.indices[i * 3 + 2] + vAccum;
            }
            vOff += t.vertexCount;
            iOff += tris * 3;
            vAccum += t.vertexCount;
        }

        writeObjects(pw, n, flatVerts, flatIdx, flatNorms, null);
        writeConnections(pw);
        writeFooter(pw);

        pw.flush();
    }

    // ==================== FBX structure ====================

    private static void writeHeader(PrintWriter pw) {
        sGeomId = 2000000;
        sModelId = 3000000;

        pw.println("; FBX 7.4.0 project file");
        pw.println("; Generated by SkyModelViewer");
        pw.println("");
        pw.println("FBXHeaderExtension:  {");
        pw.println("\tFBXHeaderVersion: 1003");
        pw.println("\tFBXVersion: 7400");
        pw.println("\tCreationTimeStamp:  {");
        pw.println("\t\tVersion: 1000");
        pw.println("\t\tYear: 2026");
        pw.println("\t\tMonth: 7");
        pw.println("\t\tDay: 12");
        pw.println("\t\tHour: 0");
        pw.println("\t\tMinute: 0");
        pw.println("\t\tSecond: 0");
        pw.println("\t\tMillisecond: 0");
        pw.println("\t}");
        pw.println("\tCreator: \"SkyModelViewer\"");
        pw.println("}");
        pw.println("");
        pw.println("GlobalSettings:  {");
        pw.println("\tVersion: 1000");
        pw.println("\tProperties70:  {");
        pw.println("\t\tP: \"UpAxis\", \"int\", \"Integer\", \"\",1");
        pw.println("\t\tP: \"UpAxisSign\", \"int\", \"Integer\", \"\",1");
        pw.println("\t\tP: \"FrontAxis\", \"int\", \"Integer\", \"\",2");
        pw.println("\t\tP: \"FrontAxisSign\", \"int\", \"Integer\", \"\",1");
        pw.println("\t\tP: \"CoordAxis\", \"int\", \"Integer\", \"\",0");
        pw.println("\t\tP: \"CoordAxisSign\", \"int\", \"Integer\", \"\",1");
        pw.println("\t\tP: \"UnitScaleFactor\", \"double\", \"Number\", \"\",1");
        pw.println("\t}");
        pw.println("}");
        pw.println("");
        pw.println("Documents:  {");
        pw.println("\tCount: 1");
        pw.println("\tDocument: 100000000000001, \"Scene\", \"Scene\" {");
        pw.println("\t\tProperties70:  {");
        pw.println("\t\t\tP: \"SourceObject\", \"object\", \"\", \"\"");
        pw.println("\t\t}");
        pw.println("\t\tRootNode: 0");
        pw.println("\t}");
        pw.println("}");
        pw.println("");
        pw.println("Definitions:  {");
        pw.println("\tVersion: 100");
        pw.println("\tCount: 3");
        pw.println("\tObjectType: \"GlobalSettings\" {");
        pw.println("\t\tCount: 1");
        pw.println("\t}");
        pw.println("\tObjectType: \"Model\" {");
        pw.println("\t\tCount: 1");
        pw.println("\t}");
        pw.println("\tObjectType: \"Geometry\" {");
        pw.println("\t\tCount: 1");
        pw.println("\t}");
        pw.println("}");
        pw.println("");
    }

    private static void writeObjects(PrintWriter pw, String name,
                                     float[] verts, int[] indices,
                                     float[] norms, float[] uvs) {
        int geomId = sGeomId++;
        int modelId = sModelId++;

        pw.println("Objects:  {");

        // === Geometry ===
        pw.println("\tGeometry: " + geomId + ", \"Geometry::" + name + "\", \"Mesh\" {");

        // Vertices
        pw.println("\t\tVertices: *" + verts.length + " {");
        pw.print("\t\t\ta: ");
        writeFloatArray(pw, verts, 3);
        pw.println("\t\t}");

        // PolygonVertexIndex — every 3rd index (triangle end) is negated: -(idx+1)
        pw.println("\t\tPolygonVertexIndex: *" + indices.length + " {");
        pw.print("\t\t\ta: ");
        writeIntArray(pw, indices, 3, true);
        pw.println("\t\t}");

        pw.println("\t\tGeometryVersion: 124");

        // Normals — ByControlPoint (one normal per vertex)
        if (norms != null && norms.length > 0) {
            pw.println("\t\tLayerElementNormal: 0 {");
            pw.println("\t\t\tVersion: 101");
            pw.println("\t\t\tName: \"\"");
            pw.println("\t\t\tMappingInformationType: \"ByControlPoint\"");
            pw.println("\t\t\tReferenceInformationType: \"Direct\"");
            pw.println("\t\t\tNormals: *" + norms.length + " {");
            pw.print("\t\t\t\ta: ");
            writeFloatArray(pw, norms, 3);
            pw.println("\t\t\t}");
            pw.println("\t\t}");
        }

        // UVs — ByControlPoint
        if (uvs != null && uvs.length > 0) {
            pw.println("\t\tLayerElementUV: 0 {");
            pw.println("\t\t\tVersion: 101");
            pw.println("\t\t\tName: \"\"");
            pw.println("\t\t\tMappingInformationType: \"ByControlPoint\"");
            pw.println("\t\t\tReferenceInformationType: \"Direct\"");
            pw.println("\t\t\tUV: *" + uvs.length + " {");
            pw.print("\t\t\t\ta: ");
            writeFloatArray(pw, uvs, 2);
            pw.println("\t\t\t}");
            pw.println("\t\t}");
        }

        // Layer
        pw.println("\t\tLayer: 0 {");
        pw.println("\t\t\tVersion: 100");
        pw.println("\t\t\tLayerElement:  {");
        pw.println("\t\t\t\tType: \"LayerElementNormal\"");
        pw.println("\t\t\t\tTypedIndex: 0");
        pw.println("\t\t\t}");
        if (uvs != null && uvs.length > 0) {
            pw.println("\t\t\tLayerElement:  {");
            pw.println("\t\t\t\tType: \"LayerElementUV\"");
            pw.println("\t\t\t\tTypedIndex: 0");
            pw.println("\t\t\t}");
        }
        pw.println("\t\t}");

        pw.println("\t}");

        // === Model ===
        pw.println("\tModel: " + modelId + ", \"Model::" + name + "\", \"Mesh\" {");
        pw.println("\t\tVersion: 232");
        pw.println("\t\tProperties70:  {");
        pw.println("\t\t}");
        pw.println("\t\tMultiLayer: 0");
        pw.println("\t\tMultiTake: 0");
        pw.println("\t\tShading: YES");
        pw.println("\t\tCulling: \"CullingOff\"");
        pw.println("\t}");

        pw.println("}");
        pw.println("");

        // Store IDs for connections
        sLastGeomId = geomId;
        sLastModelId = modelId;
    }

    private static int sLastGeomId = 0;
    private static int sLastModelId = 0;

    private static void writeConnections(PrintWriter pw) {
        pw.println("Connections:  {");
        pw.println("\tC: \"OO\"," + sLastGeomId + "," + sLastModelId);
        pw.println("\tC: \"OO\"," + sLastModelId + ",0");
        pw.println("}");
        pw.println("");
    }

    private static void writeFooter(PrintWriter pw) {
        pw.println("Takes:  {");
        pw.println("\tCurrent: \"\"");
        pw.println("}");
    }

    // ==================== Array writers ====================

    /**
     * Write a float array as a single line (no mid-array newlines).
     */
    private static void writeFloatArray(PrintWriter pw, float[] arr, int perLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US, "%.6f", arr[i]));
        }
        // Split into lines of reasonable length to avoid memory issues
        String s = sb.toString();
        int maxLineLen = 5000;
        if (s.length() <= maxLineLen) {
            pw.println(s);
            return;
        }
        // Write in chunks, splitting at comma boundaries
        int pos = 0;
        boolean first = true;
        while (pos < s.length()) {
            int end = Math.min(pos + maxLineLen, s.length());
            if (end < s.length()) {
                // Find next comma to split at
                int comma = s.indexOf(',', end);
                if (comma > 0) end = comma;
            }
            if (!first) pw.print("\n\t\t\t\t");
            pw.print(s.substring(pos, end));
            first = false;
            pos = end;
        }
        pw.println();
    }

    /**
     * Write an int array as a single line (no mid-array newlines).
     * If negateEvery3rd is true, every 3rd element is stored as -(val+1).
     */
    private static void writeIntArray(PrintWriter pw, int[] arr, int perLine, boolean negateEvery3rd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            int val = arr[i];
            if (negateEvery3rd && (i % 3 == 2)) {
                val = -(val + 1);
            }
            sb.append(val);
        }
        String s = sb.toString();
        int maxLineLen = 5000;
        if (s.length() <= maxLineLen) {
            pw.println(s);
            return;
        }
        int pos = 0;
        boolean first = true;
        while (pos < s.length()) {
            int end = Math.min(pos + maxLineLen, s.length());
            if (end < s.length()) {
                int comma = s.indexOf(',', end);
                if (comma > 0) end = comma;
            }
            if (!first) pw.print("\n\t\t\t\t");
            pw.print(s.substring(pos, end));
            first = false;
            pos = end;
        }
        pw.println();
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

    private static String sanitizeName(String name) {
        if (name == null) return "mesh";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
