package com.sky.modelviewer.parsing;

import android.util.Log;

/**
 * Pure Java LZ4 block decompression.
 * Ported from bstbake_unpack.py (lz4_decompress_block).
 *
 * Uses byte array with dynamic resizing for memory efficiency.
 */
public class LZ4BlockDecoder {

    private static final String TAG = "LZ4BlockDecoder";

    /**
     * Decompress LZ4 block data.
     *
     * @param src          Compressed data
     * @param expectedMax  Expected maximum decompressed size (safety limit)
     * @return Decompressed data
     */
    public static byte[] decompress(byte[] src, int expectedMax) {
        if (src == null || src.length == 0) {
            return new byte[0];
        }

        int maxOut = Math.min(expectedMax, 16 * 1024 * 1024); // 16MB hard limit
        byte[] out = new byte[Math.min(maxOut, src.length * 4)];
        int outLen = 0;

        int i = 0;
        int srcLen = src.length;

        while (i < srcLen) {
            int token = src[i] & 0xFF;
            i++;

            // Literal length
            int litLen = (token >> 4) & 0x0F;
            if (litLen == 15) {
                while (i < srcLen) {
                    int s = src[i] & 0xFF;
                    i++;
                    litLen += s;
                    if (s != 255) break;
                }
            }

            // Ensure capacity for literals
            if (outLen + litLen > out.length) {
                out = growArray(out, outLen + litLen, maxOut);
            }

            // Copy literal bytes
            if (i + litLen > srcLen) {
                Log.e(TAG, "Literal run exceeds input: i=" + i + " litLen=" + litLen + " srcLen=" + srcLen);
                break;
            }
            System.arraycopy(src, i, out, outLen, litLen);
            i += litLen;
            outLen += litLen;

            if (i >= srcLen) break;

            // Match offset
            if (i + 2 > srcLen) {
                Log.e(TAG, "Missing match offset at i=" + i);
                break;
            }
            int offset = (src[i] & 0xFF) | ((src[i + 1] & 0xFF) << 8);
            i += 2;

            if (offset == 0 || offset > outLen) {
                Log.e(TAG, "Invalid match offset=" + offset + " outLen=" + outLen);
                break;
            }

            // Match length
            int matchLen = (token & 0x0F) + 4;
            if ((token & 0x0F) == 15) {
                while (i < srcLen) {
                    int s = src[i] & 0xFF;
                    i++;
                    matchLen += s;
                    if (s != 255) break;
                }
            }

            // Ensure capacity for match
            if (outLen + matchLen > out.length) {
                out = growArray(out, outLen + matchLen, maxOut);
            }

            // Copy match (byte by byte to handle overlap)
            int start = outLen - offset;
            for (int k = 0; k < matchLen; k++) {
                out[outLen + k] = out[start + k];
            }
            outLen += matchLen;

            if (outLen > maxOut) {
                Log.e(TAG, "Output exceeds max " + maxOut);
                break;
            }
        }

        // Trim to actual size
        byte[] result = new byte[outLen];
        System.arraycopy(out, 0, result, 0, outLen);

        Log.i(TAG, "Decompressed: " + src.length + " -> " + result.length + " bytes");
        return result;
    }

    private static byte[] growArray(byte[] arr, int needed, int maxOut) {
        int newSize = Math.min(Math.max(arr.length * 2, needed), maxOut);
        if (newSize <= arr.length) {
            newSize = Math.min(needed, maxOut);
        }
        byte[] newArr = new byte[newSize];
        System.arraycopy(arr, 0, newArr, 0, arr.length);
        return newArr;
    }

    /**
     * Decompress with default max size (12MB).
     */
    public static byte[] decompress(byte[] src) {
        return decompress(src, 0xC00000);
    }
}
