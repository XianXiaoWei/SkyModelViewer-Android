package com.sky.modelviewer.parsing;

import com.sky.modelviewer.model.BoneWeight;
import com.sky.modelviewer.model.MeshData;
import com.sky.modelviewer.model.SkeletonBone;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.jpountz.lz4.LZ4Factory;

/**
 * Port of SkyModel.Core.Parsing.TgcMeshReader.
 *
 * Reads a TGC .mesh binary blob. Unlike the C# original (which opens a file
 * path directly), this implementation accepts the already-loaded byte[] because
 * on Android the mesh data is typically streamed out of a zipped APK entry and
 * there is no real filesystem path to hand to File.ReadAllBytes.
 *
 * Implemented as a singleton-style utility: a non-instantiable class with a
 * private constructor and only static methods (the Kotlin source used an
 * `object` declaration).
 */
public final class TgcMeshReader {

    private TgcMeshReader() {
    }

    /**
     * Parses a .mesh file from its raw bytes.
     *
     * @param raw      the full, uncompressed-on-disk file contents of the .mesh
     *                 file (the LZ4 payload inside is decompressed internally).
     * @param filePath the logical source path of the file; used for the mesh
     *                 name (name without extension) and stored verbatim in
     *                 MeshData.sourcePath.
     */
    public static MeshData readMesh(byte[] raw, String filePath) throws IOException {
        if (raw.length < 4) {
            throw new IOException("File is too small to be a valid .mesh file.");
        }

        final int version = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(0x00);
        final String filename = new File(filePath).getName();
        final String nameNoExt = nameWithoutExtension(filename);

        // Dispatch based on version
        switch (version) {
            case 0x17:
            case 0x18:
                return parseLegacy17(raw, nameNoExt, filePath, version, filename);
            case 0x19:
            case 0x1A:
            case 0x1B:
                return parseLegacy1A(raw, nameNoExt, filePath, version, filename);
            case 0x1C:
            case 0x1D:
                return parseLegacy1C(raw, nameNoExt, filePath, version, filename);
            case 0x1E:
                return parseLegacy1E(raw, nameNoExt, filePath, version, filename);
            case 0x1F:
            case 0x20:
                return parseModern(raw, filePath, version);
            default:
                throw new IOException("Unsupported mesh version: 0x" + Integer.toHexString(version));
        }
    }

    /**
     * Parse version 0x17/0x18 mesh files.
     * No compression. Vertex data at fixed offsets.
     */
    private static MeshData parseLegacy17(byte[] data, String name, String path,
                                          int version, String filename) throws IOException {
        int vip, iip, vs;
        boolean isStripAnim = filename.contains("StripAnim");

        if (isStripAnim) {
            vip = 0x4061; iip = 0x4065; vs = 0x408D;
        } else {
            // Find first 0x01 byte
            int p01 = -1;
            for (int i = 0; i < data.length; i++) {
                if (data[i] == 0x01) { p01 = i; break; }
            }
            if (p01 == -1) throw new IOException("v17: marker 0x01 not found");
            vip = p01 + 45; iip = 0x75; vs = 0x9D;
        }

        if (vip + 4 > data.length || iip + 4 > data.length)
            throw new IOException("v17: header out of bounds");

        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int vnum = bb.getInt(vip);
        int inum = bb.getInt(iip);
        if (vnum <= 0 || inum <= 0) throw new IOException("v17: bad counts v=" + vnum + " i=" + inum);

        int vbufLen = vnum * 16;
        if (vs + vbufLen > data.length) throw new IOException("v17: vertex buffer OOB");

        List<float[]> vertices = new ArrayList<>();
        List<byte[]> packedAttrs = new ArrayList<>();
        List<float[]> uv0 = new ArrayList<>();

        for (int i = 0; i < vnum; i++) {
            int off = vs + i * 16;
            vertices.add(new float[]{bb.getFloat(off), bb.getFloat(off + 4), bb.getFloat(off + 8)});
            packedAttrs.add(new byte[]{data[off+12], data[off+13], data[off+14], data[off+15]});
        }

        int gap = vbufLen / 4;
        int us = vs + vbufLen + gap;
        if (us + vbufLen > data.length) throw new IOException("v17: UV buffer OOB");

        for (int i = 0; i < vnum; i++) {
            int off = us + i * 16;
            uv0.add(new float[]{readHalf(bb, off), readHalf(bb, off + 2)});
        }

        int idxS = isStripAnim ? (us + vbufLen + vnum * 8) : (us + vbufLen);
        if (idxS + inum * 4 > data.length) throw new IOException("v17: index buffer OOB");

        List<int[]> indices = new ArrayList<>();
        for (int i = 0; i < inum / 3; i++) {
            int off = idxS + i * 12;
            indices.add(new int[]{bb.getInt(off), bb.getInt(off + 4), bb.getInt(off + 8)});
        }

        return new MeshData(name, path, vertices, packedAttrs, uv0, indices,
                           new ArrayList<>(), null, version, isStripAnim);
    }

    /**
     * Parse version 0x19/0x1A/0x1B mesh files.
     * No compression. Vertex at 0x92.
     */
    private static MeshData parseLegacy1A(byte[] data, String name, String path,
                                          int version, String filename) throws IOException {
        int vco = 0x66, ico = 0x6A, vs = 0x92;
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int vnum = bb.getInt(vco);
        int inum = bb.getInt(ico);
        if (vnum <= 0 || inum <= 0) throw new IOException("v1A: bad counts");

        int vbufLen = vnum * 16;
        if (vs + vbufLen > data.length) throw new IOException("v1A: vertex OOB");

        List<float[]> vertices = new ArrayList<>();
        List<byte[]> packedAttrs = new ArrayList<>();
        List<float[]> uv0 = new ArrayList<>();

        for (int i = 0; i < vnum; i++) {
            int off = vs + i * 16;
            vertices.add(new float[]{bb.getFloat(off), bb.getFloat(off + 4), bb.getFloat(off + 8)});
            packedAttrs.add(new byte[]{data[off+12], data[off+13], data[off+14], data[off+15]});
        }

        int gap = vbufLen / 4;
        int us = vs + vbufLen + gap;
        if (us + vbufLen > data.length) throw new IOException("v1A: UV OOB");

        for (int i = 0; i < vnum; i++) {
            int off = us + i * 16;
            uv0.add(new float[]{readHalf(bb, off), readHalf(bb, off + 2)});
        }

        String fnl = filename.toLowerCase();
        boolean isSp = (fnl.contains("anim") || fnl.contains("anc")) && !fnl.contains("ancestor");
        int idxS = us + vbufLen + (isSp ? vnum * 8 : 0);
        if (idxS + inum * 4 > data.length) throw new IOException("v1A: index OOB");

        List<int[]> indices = new ArrayList<>();
        for (int i = 0; i < inum / 3; i++) {
            int off = idxS + i * 12;
            indices.add(new int[]{bb.getInt(off), bb.getInt(off + 4), bb.getInt(off + 8)});
        }

        return new MeshData(name, path, vertices, packedAttrs, uv0, indices,
                           new ArrayList<>(), null, version, isSp);
    }

    /**
     * Parse version 0x1C/0x1D mesh files.
     * LZ4 compressed from offset 0x56.
     */
    private static MeshData parseLegacy1C(byte[] data, String name, String path,
                                          int version, String filename) throws IOException {
        ByteBuffer bb0 = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 0x56) throw new IOException("v1C: too small");

        int cs = bb0.getInt(0x4E);
        int us = bb0.getInt(0x52);
        if (cs <= 0 || us <= 0 || 0x56 + cs > data.length)
            throw new IOException("v1C: bad LZ4 bounds");

        byte[] comp = new byte[cs];
        System.arraycopy(data, 0x56, comp, 0, cs);
        byte[] dr = lz4Decompress(comp, us);

        ByteBuffer bb = ByteBuffer.wrap(dr).order(ByteOrder.LITTLE_ENDIAN);
        int vco = 0x34, ico = 0x38, vs = 0x60;

        int vnum = bb.getInt(vco);
        int inum = bb.getInt(ico);
        if (vnum <= 0 || inum <= 0) throw new IOException("v1C: bad counts");

        int vbufLen = vnum * 16;
        if (vs + vbufLen > dr.length) throw new IOException("v1C: vertex OOB");

        List<float[]> vertices = new ArrayList<>();
        List<byte[]> packedAttrs = new ArrayList<>();
        List<float[]> uv0 = new ArrayList<>();

        for (int i = 0; i < vnum; i++) {
            int off = vs + i * 16;
            vertices.add(new float[]{bb.getFloat(off), bb.getFloat(off + 4), bb.getFloat(off + 8)});
            packedAttrs.add(new byte[]{dr[off+12], dr[off+13], dr[off+14], dr[off+15]});
        }

        int gap = vbufLen / 4;
        int usStart = vs + vbufLen + gap;
        if (usStart + vbufLen > dr.length) throw new IOException("v1C: UV OOB");

        for (int i = 0; i < vnum; i++) {
            int off = usStart + i * 16;
            uv0.add(new float[]{readHalf(bb, off), readHalf(bb, off + 2)});
        }

        String fnl = filename.toLowerCase();
        boolean isSp = (fnl.contains("anim") || fnl.contains("anc")) && !fnl.contains("ancestor");
        int idxS = usStart + vbufLen + (isSp ? vnum * 8 : 0);
        if (idxS + inum * 4 > dr.length) throw new IOException("v1C: index OOB");

        List<int[]> indices = new ArrayList<>();
        for (int i = 0; i < inum / 3; i++) {
            int off = idxS + i * 12;
            indices.add(new int[]{bb.getInt(off), bb.getInt(off + 4), bb.getInt(off + 8)});
        }

        return new MeshData(name, path, vertices, packedAttrs, uv0, indices,
                           new ArrayList<>(), null, version, isSp);
    }

    /**
     * Parse version 0x1E mesh files.
     * LZ4 compressed from offset 0x56. u16 indices, half-float UV.
     */
    private static MeshData parseLegacy1E(byte[] data, String name, String path,
                                          int version, String filename) throws IOException {
        ByteBuffer bb0 = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 0x56) throw new IOException("v1E: too small");

        int cs = bb0.getInt(0x4E);
        int us = bb0.getInt(0x52);
        if (cs <= 0 || us <= 0 || 0x56 + cs > data.length)
            throw new IOException("v1E: bad LZ4 bounds");

        byte[] comp = new byte[cs];
        System.arraycopy(data, 0x56, comp, 0, cs);
        byte[] dr = lz4Decompress(comp, us);

        ByteBuffer bb = ByteBuffer.wrap(dr).order(ByteOrder.LITTLE_ENDIAN);
        int vnum = bb.getInt(0x74);
        int inum = bb.getInt(0x78);
        if (vnum <= 0 || inum <= 0) throw new IOException("v1E: bad counts");

        int vs = 0xB3;
        int vbufLen = vnum * 16;
        if (vs + vbufLen > dr.length) throw new IOException("v1E: vertex OOB");

        List<float[]> vertices = new ArrayList<>();
        List<byte[]> packedAttrs = new ArrayList<>();
        List<float[]> uv0 = new ArrayList<>();

        for (int i = 0; i < vnum; i++) {
            int off = vs + i * 16;
            vertices.add(new float[]{bb.getFloat(off), bb.getFloat(off + 4), bb.getFloat(off + 8)});
            packedAttrs.add(new byte[]{dr[off+12], dr[off+13], dr[off+14], dr[off+15]});
        }

        String fnl = filename.toLowerCase();
        boolean isSp = fnl.contains("anim") || (fnl.contains("anc") && !fnl.contains("ancestor"));

        int usStart, uvsz, idxS;
        if (isSp) {
            int gap = vbufLen / 4;
            usStart = vs + vbufLen + gap;
            uvsz = vbufLen;
            idxS = usStart + uvsz + vnum * 8;
        } else {
            int gap = vnum * 4 - 4;
            usStart = vs + vbufLen + gap;
            uvsz = vnum * 16;
            idxS = usStart + uvsz + 4;
        }

        if (usStart + uvsz > dr.length) throw new IOException("v1E: UV OOB");
        for (int i = 0; i < vnum; i++) {
            int off = usStart + i * 16;
            // Half-float UV at offset 4 in 16-byte stride
            uv0.add(new float[]{readHalf(bb, off + 4), readHalf(bb, off + 6)});
        }

        if (idxS + inum * 2 > dr.length) throw new IOException("v1E: index OOB");
        List<int[]> indices = new ArrayList<>();
        for (int i = 0; i < inum / 3; i++) {
            int off = idxS + i * 6;
            indices.add(new int[]{readUInt16(bb, off), readUInt16(bb, off + 2), readUInt16(bb, off + 4)});
        }

        return new MeshData(name, path, vertices, packedAttrs, uv0, indices,
                           new ArrayList<>(), null, version, isSp);
    }

    /**
     * LZ4 decompress using lz4-java library.
     */
    private static byte[] lz4Decompress(byte[] src, int expectedSize) throws IOException {
        byte[] out = new byte[expectedSize];
        int decoded = LZ4Factory.safeInstance().fastDecompressor()
                .decompress(src, 0, out, 0, out.length);
        if (decoded <= 0) throw new IOException("LZ4 decompress failed");
        return out;
    }

    /**
     * Parse version 0x1F/0x20 mesh files (modern format).
     * This is the original readMesh logic.
     */
    private static MeshData parseModern(byte[] raw, String filePath, int version) throws IOException {
        if (raw.length < 0x58) {
            throw new IOException("File is too small to be a valid .mesh file.");
        }

        final ByteBuffer rawBb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        final boolean animated = rawBb.get(0x48) != 0;
        final int payloadOffset = (version >= 0x20) ? 0x4e : 0x4a;

        final int isCompressed = rawBb.getInt(payloadOffset);
        final int compressedSize = rawBb.getInt(payloadOffset + 4);
        final int uncompressedSize = rawBb.getInt(payloadOffset + 8);

        if (compressedSize <= 0 || uncompressedSize <= 0 ||
            payloadOffset + 12 + compressedSize > raw.length) {
            throw new IOException("Compressed payload bounds are invalid.");
        }

        final byte[] src = new byte[compressedSize];
        System.arraycopy(raw, payloadOffset + 12, src, 0, compressedSize);
        final int embeddedSkeletonLength = raw.length - (payloadOffset + 12 + compressedSize);
        final byte[] embeddedSkeletonRaw = new byte[embeddedSkeletonLength];
        System.arraycopy(raw, payloadOffset + 12 + compressedSize,
                embeddedSkeletonRaw, 0, embeddedSkeletonLength);

        // NOTE on LZ4 semantics: lz4-java's FastDecompressor.decompress(...) returns
        // the number of bytes *read from src* (the compressed length), whereas the
        // C# K4os LZ4Codec.Decode returns the number of bytes *written to dest*
        // (the decompressed length). Because we allocate dest with the exact
        // declared uncompressedSize, the buffer is filled to that size on success,
        // so the C# "resize when decoded != uncompressedSize" step has no Java
        // equivalent here -- dest is already correctly sized.
        final byte[] dest;
        if (isCompressed != 0) {
            final byte[] outBuf = new byte[uncompressedSize];
            final int decoded = LZ4Factory.safeInstance().fastDecompressor()
                    .decompress(src, 0, outBuf, 0, outBuf.length);
            if (decoded <= 0) {
                throw new IOException("Failed to LZ4-decode mesh payload.");
            }
            dest = outBuf;
        } else {
            dest = src;
        }

        final ByteBuffer bb = ByteBuffer.wrap(dest).order(ByteOrder.LITTLE_ENDIAN);

        int p = 4;
        final float[] aabbA = readVec3(bb, p); p += 12;
        final float[] aabbB = readVec3(bb, p); p += 12;
        final float[] aabbA2 = readVec3(bb, p); p += 12;
        final float[] aabbB2 = readVec3(bb, p); p += 12;

        final float[] quantMin = readFloatArray(bb, p, 8); p += 8 * 4;
        final float[] quantMax = readFloatArray(bb, p, 8); p += 8 * 4;

        final int sharedVertices = (int) readUInt32(bb, p); p += 4;
        final int totalVertices = (int) readUInt32(bb, p); p += 4;
        final boolean isIdx32 = readUInt32(bb, p) != 0L; p += 4;
        final long numPoints = readUInt32(bb, p); p += 4;
        final long prop11 = readUInt32(bb, p); p += 4;
        final long prop12 = readUInt32(bb, p); p += 4;
        final long prop13 = readUInt32(bb, p); p += 4;
        final long prop14 = readUInt32(bb, p); p += 4;

        final boolean loadMeshNorms = bb.get(p) != 0; p += 1;
        final boolean loadInfo2 = bb.get(p) != 0; p += 1;
        p += 1;

        final long skipMeshPos = readUInt32(bb, p); p += 4;
        final long skipUvs = readUInt32(bb, p); p += 4;
        final long flag3 = readUInt32(bb, p); p += 4;
        p += 0x10;

        final int faceCount = totalVertices / 3;
        final int idxUnit = isIdx32 ? 4 : 2;

        final List<float[]> vertices = new ArrayList<>();
        final List<byte[]> packedVertexAttrs = new ArrayList<>();
        if (skipMeshPos == 0L) {
            for (int i = 0; i < sharedVertices; i++) {
                final int off = p + i * 16;
                final float x = readFloat(bb, off);
                final float y = readFloat(bb, off + 4);
                final float z = readFloat(bb, off + 8);
                vertices.add(new float[]{x, y, z});

                // The final four bytes in the 16-byte position record are not
                // padding in CompOcc samples, but they are not decoded material
                // or texture bindings.
                packedVertexAttrs.add(
                    new byte[]{
                        bb.get(off + 12),
                        bb.get(off + 13),
                        bb.get(off + 14),
                        bb.get(off + 15)
                    }
                );
            }

            p += sharedVertices * 16;
        }

        if (loadMeshNorms) {
            p += sharedVertices * 4;
        }

        final List<float[]> uv0 = new ArrayList<>();
        if (skipUvs == 0L) {
            for (int i = 0; i < sharedVertices; i++) {
                final float u = readHalf(bb, p + i * 16);
                final float v = readHalf(bb, p + i * 16 + 2);
                uv0.add(new float[]{u, v});
            }

            p += sharedVertices * 16;
        }

        final List<List<BoneWeight>> boneWeights = new ArrayList<>();
        if (animated) {
            for (int i = 0; i < sharedVertices; i++) {
                final List<BoneWeight> row = new ArrayList<>();
                final int off = p + i * 8;
                for (int j = 0; j < 4; j++) {
                    final int bi = bb.get(off + j) & 0xFF;
                    final int wi = bb.get(off + 4 + j) & 0xFF;
                    if (bi > 0 && wi > 0) {
                        row.add(new BoneWeight(bi - 1, wi / 255.0f));
                    }
                }
                boneWeights.add(row);
            }

            p += sharedVertices * 8;
        }

        final List<int[]> indices = new ArrayList<>();
        for (int i = 0; i < faceCount; i++) {
            if (isIdx32) {
                final int a = readInt32(bb, p);
                final int b = readInt32(bb, p + 4);
                final int c = readInt32(bb, p + 8);
                indices.add(new int[]{a, b, c});
                p += 12;
            } else {
                final int a = readUInt16(bb, p);
                final int b = readUInt16(bb, p + 2);
                final int c = readUInt16(bb, p + 4);
                indices.add(new int[]{a, b, c});
                p += 6;
            }
        }

        if (loadInfo2) {
            p += totalVertices * idxUnit;
        }

        if (numPoints > 0L) {
            p += sharedVertices * idxUnit;
        }

        if (prop11 > 0L) {
            p += sharedVertices * idxUnit;
        }

        if (prop12 > 0L) {
            p += (int) prop12 * idxUnit;
        }

        if (prop13 > 0L) {
            p += (int) prop13 * 4;
        }

        if (prop14 > 0L) {
            p += (int) prop14 * (isIdx32 ? 8 : 4);
        }

        p += faceCount * 4;

        if (skipMeshPos > 0L) {
            final float ax = aabbA2[0];
            final float ay = aabbA2[1];
            final float az = aabbA2[2];
            final float sx = aabbB2[0] - ax;
            final float sy = aabbB2[1] - ay;
            final float sz = aabbB2[2] - az;

            for (int i = 0; i < sharedVertices; i++) {
                final long packed = readUInt32(bb, p + i * 4);
                final int qz = (int) (packed & 0x3ffL);
                final int qy = (int) ((packed >>> 10) & 0x3ffL);
                final int qx = (int) ((packed >>> 20) & 0x3ffL);

                final float x = ax + (qx / 1023.0f) * sx;
                final float y = ay + (qy / 1023.0f) * sy;
                final float z = az + (qz / 1023.0f) * sz;
                vertices.add(new float[]{x, y, z});
            }

            p += sharedVertices * 4;
            p += sharedVertices;
        }

        if (skipUvs > 0L) {
            final float uvMinU = quantMin[0];
            final float uvMinV = quantMin[1];
            final float uvSizeU = quantMax[0] - uvMinU;
            final float uvSizeV = quantMax[1] - uvMinV;

            for (int i = 0; i < sharedVertices; i++) {
                final int off = p + i * 4;
                final int uHi = bb.get(off) & 0xFF;
                final int vHi = bb.get(off + 1) & 0xFF;
                final int uLo = bb.get(off + 2) & 0xFF;
                final int vLo = bb.get(off + 3) & 0xFF;

                final float uNorm = ((uHi << 8) | uLo) / 65535.0f;
                final float vNorm = ((vHi << 8) | vLo) / 65535.0f;
                final float u = uvMinU + uNorm * uvSizeU;
                final float v = uvMinV + vNorm * uvSizeV;
                uv0.add(new float[]{u, v});
            }

            p += sharedVertices * 4;
        }

        if (flag3 > 0L) {
            if (packedVertexAttrs.isEmpty()) {
                for (int i = 0; i < sharedVertices; i++) {
                    final int off = p + i * 4;
                    packedVertexAttrs.add(
                        new byte[]{
                            bb.get(off),
                            bb.get(off + 1),
                            bb.get(off + 2),
                            bb.get(off + 3)
                        }
                    );
                }
            }

            p += sharedVertices * 4;
        }

        final List<SkeletonBone> embeddedSkeleton =
            (animated && embeddedSkeletonRaw.length >= 85)
                ? tryParseSkeleton(embeddedSkeletonRaw)
                : null;

        return new MeshData(
            nameWithoutExtension(new File(filePath).getName()),
            filePath,
            vertices,
            packedVertexAttrs,
            uv0,
            indices,
            boneWeights,
            embeddedSkeleton,
            version,
            animated
        );
    }

    private static List<SkeletonBone> tryParseSkeleton(byte[] raw) {
        try {
            return parseSkeleton(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<SkeletonBone> parseSkeleton(byte[] raw) {
        final ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        int p = 0;
        p += 4; // skip first uint32
        p += 64;
        final long h0 = readUInt32(bb, p); p += 4;
        p += 4; // skip
        p += 4; // skip
        p += 4; // skip
        p += 1;

        final int numBones = (int) h0;
        final List<SkeletonBone> bones = new ArrayList<>();
        for (int i = 0; i < numBones; i++) {
            // name: 64-byte fixed field, null-terminated, ASCII.
            int len = 64;
            for (int k = 0; k < 64; k++) {
                if (raw[p + k] == 0) {
                    len = k;
                    break;
                }
            }
            final String name = new String(raw, p, len,
                    java.nio.charset.StandardCharsets.US_ASCII);
            p += 64;

            final float[] mat = new float[16];
            for (int j = 0; j < 16; j++) {
                mat[j] = readFloat(bb, p + j * 4);
            }

            p += 64;
            final long parent1Based = readUInt32(bb, p); p += 4;
            final int parentIndex = (parent1Based > 0L) ? (int) parent1Based - 1 : -1;

            bones.add(new SkeletonBone(name, parentIndex, mat));
        }

        return bones;
    }

    private static float[] readVec3(ByteBuffer bb, int offset) {
        return new float[]{
            bb.getFloat(offset),
            bb.getFloat(offset + 4),
            bb.getFloat(offset + 8)
        };
    }

    private static float[] readFloatArray(ByteBuffer bb, int offset, int count) {
        final float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            result[i] = bb.getFloat(offset + i * 4);
        }
        return result;
    }

    private static int readInt32(ByteBuffer bb, int offset) {
        return bb.getInt(offset);
    }

    private static long readUInt32(ByteBuffer bb, int offset) {
        return bb.getInt(offset) & 0xFFFFFFFFL;
    }

    private static float readFloat(ByteBuffer bb, int offset) {
        return bb.getFloat(offset);
    }

    private static int readUInt16(ByteBuffer bb, int offset) {
        return bb.getShort(offset) & 0xFFFF;
    }

    private static float readHalf(ByteBuffer bb, int offset) {
        return halfToFloat(readUInt16(bb, offset));
    }

    /**
     * Converts an IEEE 754 half-precision (binary16) bit pattern to a Float.
     *
     * Equivalent to C#'s (float)BitConverter.UInt16BitsToHalf(bits). Implemented
     * manually because Float.float16ToFloat is only available on Java 21+ /
     * Android API 35+, while this project targets Java 17 / minSdk 24.
     */
    private static float halfToFloat(int halfBits) {
        final int sign = (halfBits >>> 15) & 0x1;
        int exp = (halfBits >>> 10) & 0x1f;
        int mant = halfBits & 0x3ff;

        if (exp == 0) {
            if (mant == 0) {
                // +/- zero
                return Float.intBitsToFloat(sign << 31);
            }
            // Subnormal: normalize by shifting left until the implicit bit is set.
            while ((mant & 0x0400) == 0) {
                mant = mant << 1;
                exp--;
            }
            exp++; // account for the implicit leading bit now consumed.
            mant = mant & 0x3ff;
        } else if (exp == 31) {
            // Inf or NaN.
            return Float.intBitsToFloat((sign << 31) | 0x7f800000 | (mant << 13));
        }

        final int bits = (sign << 31) | ((exp + 112) << 23) | (mant << 13);
        return Float.intBitsToFloat(bits);
    }

    /**
     * Returns the file name without its final extension, mirroring Kotlin's
     * File.nameWithoutExtension (which strips the substring after the last '.').
     */
    private static String nameWithoutExtension(String name) {
        final int dotIndex = name.lastIndexOf('.');
        return (dotIndex >= 0) ? name.substring(0, dotIndex) : name;
    }
}
