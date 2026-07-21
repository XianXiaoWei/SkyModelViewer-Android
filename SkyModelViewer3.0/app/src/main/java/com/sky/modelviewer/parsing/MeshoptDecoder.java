package com.sky.modelviewer.parsing;

import android.util.Log;

/**
 * Pure Java port of the meshopt vertex buffer decoder.
 * Ported from meshes2obj_json.py (that-sky-level-meshes project).
 *
 * Decodes meshopt-compressed vertex buffers used in .meshes files.
 * Supports both version 0 (game-compatible) and version 1.
 */
public class MeshoptDecoder {

    private static final int K_VERTEX_HEADER = 0xA0;
    private static final int K_VERTEX_BLOCK_SIZE_BYTES = 8192;
    private static final int K_VERTEX_BLOCK_MAX_SIZE = 256;
    private static final int K_BYTE_GROUP_SIZE = 16;
    private static final String TAG = "MeshoptDecoder";

    // Precomputed bit-reversal table for 8-bit values
    private static final int[] REVERSE_BITS8 = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int v = i;
            int r = 0;
            for (int j = 0; j < 8; j++) {
                r = (r << 1) | (v & 1);
                v >>= 1;
            }
            REVERSE_BITS8[i] = r;
        }
    }

    private MeshoptDecoder() {}

    private static int getVertexBlockSize(int vertexSize) {
        int result = (K_VERTEX_BLOCK_SIZE_BYTES / vertexSize) & ~(K_BYTE_GROUP_SIZE - 1);
        return result < K_VERTEX_BLOCK_MAX_SIZE ? result : K_VERTEX_BLOCK_MAX_SIZE;
    }

    /**
     * Decode a byte group: 16 bytes output from packed data.
     * Python: _decode_bytes_group
     */
    private static int decodeBytesGroup(byte[] data, int pos, byte[] out, int outOff, int bits) {
        if (bits == 0) {
            for (int i = 0; i < 16; i++) out[outOff + i] = 0;
            return pos;
        }
        if (bits == 8) {
            System.arraycopy(data, pos, out, outOff, 16);
            return pos + 16;
        }

        int sentinel = (1 << bits) - 1;
        int byteSize = 8 / bits;
        int fixedCount = 16 / byteSize;
        int varPos = pos + fixedCount;
        int idx = outOff;

        for (int fb = 0; fb < fixedCount; fb++) {
            int b = data[pos + fb] & 0xFF;
            if (bits == 1) {
                b = REVERSE_BITS8[b];
            }
            for (int j = 0; j < byteSize; j++) {
                int enc = b >> (8 - bits);
                b = (b << bits) & 0xFF;
                if (enc == sentinel) {
                    out[idx] = data[varPos];
                    varPos++;
                } else {
                    out[idx] = (byte) enc;
                }
                idx++;
            }
        }
        return varPos;
    }

    /**
     * Decode bytes: multiple groups from header-controlled bit widths.
     * Python: _decode_bytes
     */
    private static int decodeBytes(byte[] data, int pos, int bufferSize, int[] bitsTable, byte[] out) {
        int numGroups = bufferSize / 16;
        int headerSize = (numGroups + 3) / 4;
        // Copy header bytes (Python: header = data[pos:pos+header_size])
        byte[] header = new byte[headerSize];
        System.arraycopy(data, pos, header, 0, headerSize);
        pos += headerSize;
        for (int g = 0; g < numGroups; g++) {
            int bitsk = ((header[g / 4] & 0xFF) >> ((g % 4) * 2)) & 3;
            pos = decodeBytesGroup(data, pos, out, g * 16, bitsTable[bitsk]);
        }
        return pos;
    }

    /**
     * Decode u8 zigzag deltas.
     * Python: _decode_deltas_u8
     * CRITICAL: Python operator precedence — + has higher priority than &
     * So: v = (((255 if v&1 else 0) ^ (v>>1)) + p) & 0xFF
     */
    private static void decodeDeltasU8(byte[][] planes, byte[] result, int base,
            int vertexCount, int vertexSize, byte[] lastVertex, int k) {
        for (int kb = 0; kb < 4; kb++) {
            byte[] plane = planes[kb];
            int p = lastVertex[k + kb] & 0xFF;
            int off = base + kb;
            for (int i = 0; i < vertexCount; i++) {
                int v = plane[i] & 0xFF;
                int zigzag = ((v & 1) != 0 ? 255 : 0) ^ (v >> 1);
                v = (zigzag + p) & 0xFF;
                result[off] = (byte) v;
                p = v;
                off += vertexSize;
            }
        }
    }

    /**
     * Decode u16 zigzag deltas.
     * Python: _decode_deltas_u16
     * Same operator precedence trap as u8.
     */
    private static void decodeDeltasU16(byte[][] planes, byte[] result, int base,
            int vertexCount, int vertexSize, byte[] lastVertex, int k) {
        for (int kb = 0; kb <= 2; kb += 2) {
            int p = (lastVertex[k + kb] & 0xFF) | ((lastVertex[k + kb + 1] & 0xFF) << 8);
            int off = base + kb;
            byte[] p0 = planes[kb];
            byte[] p1 = planes[kb + 1];
            for (int i = 0; i < vertexCount; i++) {
                int v = (p0[i] & 0xFF) | ((p1[i] & 0xFF) << 8);
                int zigzag = ((v & 1) != 0 ? 0xFFFF : 0) ^ (v >> 1);
                v = (zigzag + p) & 0xFFFF;
                result[off] = (byte) (v & 0xFF);
                result[off + 1] = (byte) ((v >> 8) & 0xFF);
                p = v;
                off += vertexSize;
            }
        }
    }

    /**
     * Decode u32 XOR deltas with rotation.
     * Python: _decode_deltas_u32_xor
     * v = (((v << rot) | (v >> rshift)) & 0xFFFFFFFF) ^ p
     * In Java, int is already 32-bit, but we need unsigned right shift (>>>).
     */
    private static void decodeDeltasU32Xor(byte[][] planes, byte[] result, int base,
            int vertexCount, int vertexSize, byte[] lastVertex, int k, int rot) {
        int p = (lastVertex[k] & 0xFF) |
                ((lastVertex[k + 1] & 0xFF) << 8) |
                ((lastVertex[k + 2] & 0xFF) << 16) |
                ((lastVertex[k + 3] & 0xFF) << 24);
        int off = base;
        byte[] p0 = planes[0], p1 = planes[1], p2 = planes[2], p3 = planes[3];

        if (rot == 0) {
            for (int i = 0; i < vertexCount; i++) {
                int v = ((p0[i] & 0xFF) |
                        ((p1[i] & 0xFF) << 8) |
                        ((p2[i] & 0xFF) << 16) |
                        ((p3[i] & 0xFF) << 24)) ^ p;
                result[off] = (byte) (v & 0xFF);
                result[off + 1] = (byte) ((v >> 8) & 0xFF);
                result[off + 2] = (byte) ((v >> 16) & 0xFF);
                result[off + 3] = (byte) ((v >> 24) & 0xFF);
                p = v;
                off += vertexSize;
            }
        } else {
            int rshift = 32 - rot;
            for (int i = 0; i < vertexCount; i++) {
                int v = (p0[i] & 0xFF) |
                        ((p1[i] & 0xFF) << 8) |
                        ((p2[i] & 0xFF) << 16) |
                        ((p3[i] & 0xFF) << 24);
                // Rotate left by rot bits, then XOR with previous vertex
                // Python: (((v << rot) | (v >> rshift)) & 0xFFFFFFFF) ^ p
                // Java int is 32-bit, << truncates automatically, but >> must be >>> (unsigned)
                v = ((v << rot) | (v >>> rshift)) ^ p;
                result[off] = (byte) (v & 0xFF);
                result[off + 1] = (byte) ((v >> 8) & 0xFF);
                result[off + 2] = (byte) ((v >> 16) & 0xFF);
                result[off + 3] = (byte) ((v >> 24) & 0xFF);
                p = v;
                off += vertexSize;
            }
        }
    }

    /**
     * Decode one vertex block.
     * Python: _decode_vertex_block
     */
    private static int decodeVertexBlock(byte[] data, int pos, byte[] result,
            int vertexOffset, int vertexCount, int vertexSize,
            byte[] lastVertex, byte[] channels, int version) {

        int vertexCountAligned = (vertexCount + 15) & ~15;
        int controlSize = (version == 0) ? 0 : vertexSize / 4;
        // Copy control bytes (Python: control = data[pos:pos+control_size])
        byte[] control = null;
        if (controlSize > 0) {
            control = new byte[controlSize];
            System.arraycopy(data, pos, control, 0, controlSize);
        }
        pos += controlSize;

        byte[][] planes = new byte[4][];

        for (int k = 0; k < vertexSize; k += 4) {
            int ctrlByte = (version == 0) ? 0 : (control[k / 4] & 0xFF);

            for (int j = 0; j < 4; j++) {
                int ctrl = (ctrlByte >> (j * 2)) & 3;
                if (ctrl == 3) {
                    // Literal
                    planes[j] = new byte[vertexCount];
                    System.arraycopy(data, pos, planes[j], 0, vertexCount);
                    pos += vertexCount;
                } else if (ctrl == 2) {
                    // Zero
                    planes[j] = new byte[vertexCount];
                } else {
                    // Bit-packed
                    int[] bitsTable;
                    if (version == 0) {
                        bitsTable = new int[]{0, 2, 4, 8};
                    } else {
                        bitsTable = (ctrl == 0) ? new int[]{0, 1, 2, 4} : new int[]{1, 2, 4, 8};
                    }
                    planes[j] = new byte[vertexCountAligned];
                    pos = decodeBytes(data, pos, vertexCountAligned, bitsTable, planes[j]);
                }
            }

            int channel = (version == 0) ? 0 : (channels[k / 4] & 0xFF);
            int ctype = channel & 3;
            int base = vertexOffset * vertexSize + k;

            if (ctype == 0) {
                decodeDeltasU8(planes, result, base, vertexCount, vertexSize, lastVertex, k);
            } else if (ctype == 1) {
                decodeDeltasU16(planes, result, base, vertexCount, vertexSize, lastVertex, k);
            } else {
                int rot = (32 - (channel >> 4)) & 31;
                decodeDeltasU32Xor(planes, result, base, vertexCount, vertexSize, lastVertex, k, rot);
            }
        }

        // Update last vertex
        int lastStart = vertexOffset * vertexSize + (vertexCount - 1) * vertexSize;
        System.arraycopy(result, lastStart, lastVertex, 0, vertexSize);

        return pos;
    }

    /**
     * Decodes a meshopt-compressed vertex buffer.
     *
     * @param vertexCount Number of vertices
     * @param vertexSize  Size of each vertex in bytes (must be multiple of 4)
     * @param data        Compressed vertex data
     * @return Decoded vertex data as byte array
     */
    public static byte[] decodeVertexBuffer(int vertexCount, int vertexSize, byte[] data) {
        if (vertexSize % 4 != 0) {
            throw new IllegalArgumentException("vertex size must be multiple of 4");
        }
        int dataEnd = data.length;
        if (dataEnd < 1) {
            throw new IllegalArgumentException("meshopt data empty");
        }

        int header = data[0] & 0xFF;
        if ((header & 0xF0) != K_VERTEX_HEADER) {
            Log.e(TAG, "Header mismatch: 0x" + Integer.toHexString(header));
            throw new IllegalArgumentException("meshopt header mismatch: 0x" + Integer.toHexString(header));
        }
        int version = header & 0x0F;
        if (version > 1) {
            Log.e(TAG, "Unsupported version: " + version);
            throw new IllegalArgumentException("unsupported meshopt version: " + version);
        }

        Log.i(TAG, "Decoding: vCount=" + vertexCount + " vSize=" + vertexSize +
              " dataLen=" + data.length + " moVersion=" + version);

        int tailSize = vertexSize + (version == 0 ? 0 : vertexSize / 4);
        int tailSizeMin = version == 0 ? 32 : 24;
        int tailSizePad = Math.max(tailSize, tailSizeMin);
        if (dataEnd < 1 + tailSizePad) {
            Log.e(TAG, "Data too short: need " + (1 + tailSizePad) + " have " + dataEnd +
                  " (v" + version + " tailSize=" + tailSize + ")");
            throw new IllegalArgumentException("meshopt data too short: need " + (1 + tailSizePad) + " have " + dataEnd);
        }

        int tailStart = dataEnd - tailSize;
        byte[] lastVertex = new byte[256];
        System.arraycopy(data, tailStart, lastVertex, 0, vertexSize);

        byte[] channels = null;
        if (version != 0) {
            channels = new byte[vertexSize / 4];
            System.arraycopy(data, tailStart + vertexSize, channels, 0, vertexSize / 4);
        }

        int vertexBlockSize = getVertexBlockSize(vertexSize);
        byte[] result = new byte[vertexCount * vertexSize];
        int pos = 1;
        int vertexOffset = 0;

        while (vertexOffset < vertexCount) {
            int blockSize = Math.min(vertexBlockSize, vertexCount - vertexOffset);
            pos = decodeVertexBlock(data, pos, result, vertexOffset, blockSize,
                    vertexSize, lastVertex, channels, version);
            vertexOffset += blockSize;
        }

        Log.i(TAG, "Decoded OK: " + vertexCount + " vertices, result=" + result.length + " bytes");
        return result;
    }
}
