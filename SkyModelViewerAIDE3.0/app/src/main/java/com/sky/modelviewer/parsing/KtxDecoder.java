package com.sky.modelviewer.parsing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure Java KTX texture decoder.
 *
 * Supports:
 *   - Uncompressed: GL_RGBA (0x1908), GL_RGBA8 (0x8058), GL_RGB (0x1907)
 *   - ETC2 RGB8 (0x9274), ETC2 SRGB8 (0x9275)
 *   - ETC2 RGBA8 (0x9270), ETC2 SRGBA8 (0x9271)
 *   - ETC2 RGB8 Punchthrough (0x9278, 0x9279)
 *   - DXT1/BC1 (0x83F0), DXT5/BC3 (0x83F3)
 *   - BC4/RGTC1 (0x8DBB, 0x8DBC)
 *   - BC7/BPTC (0x8E8C, 0x8E8D — note: some implementations use these; standard BC7 is 0x4B83... we handle both)
 *
 * Ported from https://github.com/that-sky-project/that-sky-unktx
 */
public class KtxDecoder {

    // KTX1 magic
    private static final byte[] KTX_MAGIC = {
        (byte)0xAB, 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, (byte)0xBB,
        0x0D, 0x0A, 0x1A, 0x0A
    };

    /**
     * Decode a KTX file to an ARGB pixel array.
     *
     * @return int[width * height] of ARGB pixels, or null on failure
     */
    public static int[] decode(byte[] ktxData) {
        if (ktxData == null || ktxData.length < 64) return null;

        // Verify magic
        for (int i = 0; i < 12; i++) {
            if (ktxData[i] != KTX_MAGIC[i]) return null;
        }

        ByteBuffer bb = ByteBuffer.wrap(ktxData).order(ByteOrder.LITTLE_ENDIAN);

        int glInternalFormat = bb.getInt(28);
        int width = bb.getInt(36);
        int height = bb.getInt(40);
        int bytesOfKeyValueData = bb.getInt(60);

        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return null;

        int dataOffset = 64 + bytesOfKeyValueData;
        if (dataOffset + 4 > ktxData.length) return null;

        int imageSize = bb.getInt(dataOffset);
        dataOffset += 4;

        if (dataOffset + imageSize > ktxData.length) return null;

        byte[] imageData = new byte[imageSize];
        System.arraycopy(ktxData, dataOffset, imageData, 0, imageSize);

        return decompress(imageData, width, height, glInternalFormat);
    }

    /**
     * Get texture dimensions from KTX header.
     */
    public static int[] getDimensions(byte[] ktxData) {
        if (ktxData == null || ktxData.length < 64) return null;
        ByteBuffer bb = ByteBuffer.wrap(ktxData).order(ByteOrder.LITTLE_ENDIAN);
        int w = bb.getInt(36);
        int h = bb.getInt(40);
        int fmt = bb.getInt(28);
        return new int[]{w, h, fmt};
    }

    private static int[] decompress(byte[] data, int width, int height, int format) {
        switch (format) {
            // Uncompressed
            case 0x1908: // GL_RGBA
            case 0x8058: // GL_RGBA8
            case 0x8C43: // SRGB8_ALPHA8
                return decodeRGBA(data, width, height);

            case 0x1907: // GL_RGB
            case 0x8051: // GL_RGB8
                return decodeRGB(data, width, height);

            // ETC2 compressed
            case 0x9274: // ETC2_RGB8
            case 0x9275: // ETC2_SRGB8
                return decodeETC2(data, width, height, false);

            case 0x9270: // ETC2_RGBA8
            case 0x9271: // ETC2_SRGBA8
                return decodeETC2(data, width, height, true);

            case 0x9278: // ETC2_RGB8_Punchthrough
            case 0x9279: // ETC2_SRGB8_Punchthrough
                return decodeETC2Punchthrough(data, width, height);

            // DXT/BC
            case 0x83F0: // DXT1 (BC1)
                return decodeDXT1(data, width, height);

            case 0x83F3: // DXT5 (BC3)
                return decodeDXT5(data, width, height);

            case 0x8DBB: // BC4 (signed)
            case 0x8DBC: // BC4 (unsigned)
                return decodeBC4(data, width, height);

            case 0x8E8C: // BC7 (some KTX use this)
            case 0x8E8D:
                return decodeBC7(data, width, height);

            default:
                return null;
        }
    }

    // ==================== Uncompressed ====================

    private static int[] decodeRGBA(byte[] data, int w, int h) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int p = i * 4;
            if (p + 3 >= data.length) break;
            int r = data[p] & 0xFF;
            int g = data[p + 1] & 0xFF;
            int b = data[p + 2] & 0xFF;
            int a = data[p + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return pixels;
    }

    private static int[] decodeRGB(byte[] data, int w, int h) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int p = i * 3;
            if (p + 2 >= data.length) break;
            int r = data[p] & 0xFF;
            int g = data[p + 1] & 0xFF;
            int b = data[p + 2] & 0xFF;
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return pixels;
    }

    // ==================== ETC2 ====================

    private static final int[][] ETC_MOD = {
        {2, 8, -2, -8}, {5, 17, -5, -17}, {9, 29, -9, -29}, {13, 42, -13, -42},
        {18, 60, -18, -60}, {24, 80, -24, -80}, {33, 106, -33, -106}, {47, 183, -47, -183}
    };
    private static final int[] DIST = {3, 6, 11, 16, 23, 32, 41, 64};

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    private static int e4(int v) { return (v << 4) | v; }
    private static int e5(int v) { return (v << 3) | (v >> 2); }
    private static int e6(int v) { return (v << 2) | (v >> 4); }
    private static int e7(int v) { return (v << 1) | (v >> 6); }

    private static int[] decodeETC2(byte[] data, int w, int h, boolean hasAlpha) {
        int[] pixels = new int[w * h];
        int blockSize = hasAlpha ? 16 : 8;
        int blocksX = (w + 3) / 4;
        int blocksY = (h + 3) / 4;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int blockIdx = by * blocksX + bx;
                int blockOff = blockIdx * blockSize;

                byte[] block = new byte[blockSize];
                if (blockOff + blockSize > data.length) {
                    // Pad incomplete block
                    System.arraycopy(data, blockOff, block, 0, Math.min(blockSize, data.length - blockOff));
                } else {
                    System.arraycopy(data, blockOff, block, 0, blockSize);
                }

                // For ETC2_RGBA8, alpha is in first 8 bytes, color in last 8
                byte[] alphaBlock = null;
                byte[] colorBlock;
                if (hasAlpha) {
                    alphaBlock = new byte[8];
                    System.arraycopy(block, 0, alphaBlock, 0, 8);
                    colorBlock = new byte[8];
                    System.arraycopy(block, 8, colorBlock, 0, 8);
                } else {
                    colorBlock = block;
                }

                // Decode color
                int[] rgba = new int[16]; // 4x4 block, each pixel packed as RGBA in int
                decodeETC2ColorBlock(colorBlock, rgba, w, h, bx, by);

                // Apply alpha if present
                if (hasAlpha && alphaBlock != null) {
                    int[] alphas = decodeETC2AlphaBlock(alphaBlock);
                    for (int i = 0; i < 16; i++) {
                        rgba[i] = (alphas[i] << 24) | (rgba[i] & 0x00FFFFFF);
                    }
                }

                // Write to pixel array
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        int px = bx * 4 + x;
                        int py = by * 4 + y;
                        if (px < w && py < h) {
                            pixels[py * w + px] = rgba[y * 4 + x];
                        }
                    }
                }
            }
        }
        return pixels;
    }

    private static int[] decodeETC2AlphaBlock(byte[] block) {
        int a0 = block[0] & 0xFF;
        int a1 = block[1] & 0xFF;
        int[] lut = new int[8];
        lut[0] = a0; lut[1] = a1;

        if (a0 > a1) {
            for (int i = 1; i < 7; i++) lut[i + 1] = ((7 - i) * a0 + i * a1) / 7;
        } else {
            for (int i = 1; i < 5; i++) lut[i + 1] = ((5 - i) * a0 + i * a1) / 5;
            lut[6] = 0; lut[7] = 255;
        }

        long bits = ((block[2] & 0xFFL)) | ((block[3] & 0xFFL) << 8) |
                    ((block[4] & 0xFFL) << 16) | ((block[5] & 0xFFL) << 24) |
                    ((block[6] & 0xFFL) << 32) | ((block[7] & 0xFFL) << 40);

        int[] alphas = new int[16];
        for (int i = 0; i < 16; i++) {
            int idx = (int)((bits >> (i * 3)) & 7);
            alphas[i] = lut[idx];
        }
        return alphas;
    }

    private static void decodeETC2ColorBlock(byte[] blk, int[] rgba, int w, int h, int bx, int by) {
        int b0 = blk[0] & 0xFF, b1 = blk[1] & 0xFF, b2 = blk[2] & 0xFF, b3 = blk[3] & 0xFF;
        int diff = (b3 >> 1) & 1;
        int flip = b3 & 1;
        int r = b0 >> 3, dr = (b0 & 7) - ((b0 & 4) != 0 ? 8 : 0);
        int g = b1 >> 3, dg = (b1 & 7) - ((b1 & 4) != 0 ? 8 : 0);
        int bb = b2 >> 3, db = (b2 & 7) - ((b2 & 4) != 0 ? 8 : 0);

        if (diff == 0) {
            // Individual mode (ETC1)
            int[][] c = {{e4(b0 >> 4), e4(b1 >> 4), e4(b2 >> 4)},
                         {e4(b0 & 0xF), e4(b1 & 0xF), e4(b2 & 0xF)}};
            int[] t = {(b3 >> 5) & 7, (b3 >> 2) & 7};
            etc1(c, t, flip, blk, rgba);
        } else if (r + dr < 0 || r + dr > 31) {
            tMode(blk, rgba);
        } else if (g + dg < 0 || g + dg > 31) {
            hMode(blk, rgba);
        } else if (bb + db < 0 || bb + db > 31) {
            planar(blk, rgba);
        } else {
            // Differential mode
            int[][] c = {{e5(r), e5(g), e5(bb)},
                         {e5(r + dr), e5(g + dg), e5(bb + db)}};
            int[] t = {(b3 >> 5) & 7, (b3 >> 2) & 7};
            etc1(c, t, flip, blk, rgba);
        }
    }

    private static void etc1(int[][] c, int[] t, int flip, byte[] blk, int[] rgba) {
        int bits = ((blk[4] & 0xFF) << 24) | ((blk[5] & 0xFF) << 16) | ((blk[6] & 0xFF) << 8) | (blk[7] & 0xFF);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int sb = flip != 0 ? (y >= 2 ? 1 : 0) : (x >= 2 ? 1 : 0);
                int i = x * 4 + y;
                int bit1 = (bits >> (i + 16)) & 1;
                int bit2 = (bits >> i) & 1;
                int mIdx = (bit1 << 1) | bit2;
                int m = ETC_MOD[t[sb]][mIdx];
                int p = y * 4 + x;
                int rr = clamp(c[sb][0] + m);
                int gg = clamp(c[sb][1] + m);
                int bbb = clamp(c[sb][2] + m);
                rgba[p] = 0xFF000000 | (rr << 16) | (gg << 8) | bbb;
            }
        }
    }

    private static void tMode(byte[] blk, int[] rgba) {
        int r0 = e4(((blk[0] & 0x18) >> 1) | (blk[0] & 0x3));
        int g0 = e4((blk[1] & 0xFF) >> 4);
        int b0 = e4(blk[1] & 0xF);
        int r1 = e4((blk[2] & 0xFF) >> 4);
        int g1 = e4(blk[2] & 0xF);
        int b1 = e4((blk[3] & 0xFF) >> 4);
        int d = DIST[(((blk[3] & 0xFF) >> 1) & 6) | (blk[3] & 0x01)];
        int[][] c = {
            {r0, g0, b0},
            {clamp(r1 + d), clamp(g1 + d), clamp(b1 + d)},
            {r1, g1, b1},
            {clamp(r1 - d), clamp(g1 - d), clamp(b1 - d)}
        };
        paintBlock(c, blk, rgba);
    }

    private static void hMode(byte[] blk, int[] rgba) {
        int r0 = e4((blk[0] & 0xFF) >> 3);
        int g0 = e4(((blk[0] & 0x7) << 1) | ((blk[1] & 0xFF) >> 7));
        int b0 = e4(((blk[1] & 0x8) >> 1) | ((blk[1] & 0x3) << 1) | ((blk[2] & 0xFF) >> 7));
        int r1 = e4((blk[2] & 0xFF) >> 3);
        int g1 = e4(((blk[2] & 0x7) << 1) | ((blk[3] & 0xFF) >> 7));
        int b1 = e4((blk[3] & 0xFF) >> 3);
        int di = ((blk[3] & 0x4) >> 1) | (blk[3] & 0x1);
        int base = ((r0 << 16) | (g0 << 8) | b0) >= ((r1 << 16) | (g1 << 8) | b1) ? 1 : 0;
        int d = DIST[di | (base << 2)];
        int[][] c = {
            {clamp(r0 + d), clamp(g0 + d), clamp(b0 + d)},
            {clamp(r0 - d), clamp(g0 - d), clamp(b0 - d)},
            {clamp(r1 + d), clamp(g1 + d), clamp(b1 + d)},
            {clamp(r1 - d), clamp(g1 - d), clamp(b1 - d)}
        };
        paintBlock(c, blk, rgba);
    }

    private static void paintBlock(int[][] c, byte[] blk, int[] rgba) {
        int bits = ((blk[4] & 0xFF) << 24) | ((blk[5] & 0xFF) << 16) | ((blk[6] & 0xFF) << 8) | (blk[7] & 0xFF);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int i = x * 4 + y;
                int bit1 = (bits >> (i + 16)) & 1;
                int bit2 = (bits >> i) & 1;
                int idx = (bit1 << 1) | bit2;
                int p = y * 4 + x;
                rgba[p] = 0xFF000000 | (c[idx][0] << 16) | (c[idx][1] << 8) | c[idx][2];
            }
        }
    }

    private static void planar(byte[] blk, int[] rgba) {
        int ro = e6((blk[0] & 0xFF) >> 1);
        int go = e7(((blk[0] & 1) << 6) | ((blk[1] & 0xFF) >> 1));
        int bo = e6(((blk[1] & 1) << 5) | ((blk[2] & 0xFF) >> 2) | (blk[2] & 1));
        int rh = e6(((blk[3] & 0xFF) >> 3) | ((blk[3] & 0x4) >> 2));
        int gh = e7(((blk[3] & 0x3) << 5) | ((blk[4] & 0xFF) >> 3));
        int bh = e6(((blk[4] & 0x7) << 3) | ((blk[5] & 0xFF) >> 5));
        int rv = e6(((blk[5] & 0x1F) << 1) | ((blk[6] & 0xFF) >> 7));
        int gv = e7(blk[6] & 0x7F);
        int bv = e6((blk[7] & 0xFF) >> 2);

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int p = y * 4 + x;
                int rr = clamp((x * (rh - ro) + y * (rv - ro) + 4 * ro + 2) >> 2);
                int gg = clamp((x * (gh - go) + y * (gv - go) + 4 * go + 2) >> 2);
                int bbb = clamp((x * (bh - bo) + y * (bv - bo) + 4 * bo + 2) >> 2);
                rgba[p] = 0xFF000000 | (rr << 16) | (gg << 8) | bbb;
            }
        }
    }

    private static int[] decodeETC2Punchthrough(byte[] data, int w, int h) {
        // Punchthrough is ETC2 without alpha block, but with 1-bit transparency
        int[] pixels = new int[w * h];
        int blocksX = (w + 3) / 4;
        int blocksY = (h + 3) / 4;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int blockOff = (by * blocksX + bx) * 8;
                byte[] block = new byte[8];
                if (blockOff + 8 > data.length) {
                    System.arraycopy(data, blockOff, block, 0, Math.min(8, data.length - blockOff));
                } else {
                    System.arraycopy(data, blockOff, block, 0, 8);
                }
                int[] rgba = new int[16];
                decodeETC2ColorBlock(block, rgba, w, h, bx, by);
                // Punchthrough: if diff=0 and bit pattern matches, alpha=0
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        int px = bx * 4 + x, py = by * 4 + y;
                        if (px < w && py < h) pixels[py * w + px] = rgba[y * 4 + x];
                    }
                }
            }
        }
        return pixels;
    }

    // ==================== DXT1 (BC1) ====================

    private static int[] decodeDXT1(byte[] data, int w, int h) {
        int[] pixels = new int[w * h];
        int blocksX = (w + 3) / 4;
        int blocksY = (h + 3) / 4;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int blockOff = (by * blocksX + bx) * 8;
                if (blockOff + 8 > data.length) break;
                decodeDXT1Block(data, blockOff, pixels, w, h, bx, by);
            }
        }
        return pixels;
    }

    private static void decodeDXT1Block(byte[] data, int off, int[] pixels, int w, int h, int bx, int by) {
        int c0 = (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
        int c1 = (data[off + 2] & 0xFF) | ((data[off + 3] & 0xFF) << 8);
        long bits = ((data[off + 4] & 0xFFL)) | ((data[off + 5] & 0xFFL) << 8) |
                    ((data[off + 6] & 0xFFL) << 16) | ((data[off + 7] & 0xFFL) << 24);

        int[][] colors = {rgb565ToRGBA(c0), rgb565ToRGBA(c1), {0, 0, 0, 255}, {0, 0, 0, 255}};

        if (c0 > c1) {
            colors[2] = mixColors(colors[0], colors[1], 2, 1);
            colors[3] = mixColors(colors[0], colors[1], 1, 2);
        } else {
            colors[2] = mixColors(colors[0], colors[1], 1, 1);
            colors[3] = new int[]{0, 0, 0, 0};
        }

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int px = bx * 4 + x, py = by * 4 + y;
                if (px >= w || py >= h) continue;
                int idx = (int)((bits >> ((y * 4 + x) * 2)) & 3);
                int[] c = colors[idx];
                pixels[py * w + px] = (c[3] << 24) | (c[0] << 16) | (c[1] << 8) | c[2];
            }
        }
    }

    private static int[] rgb565ToRGBA(int c) {
        int r = Math.round(((c >> 11) & 0x1F) * 255.0f / 31.0f);
        int g = Math.round(((c >> 5) & 0x3F) * 255.0f / 63.0f);
        int b = Math.round((c & 0x1F) * 255.0f / 31.0f);
        return new int[]{r, g, b, 255};
    }

    private static int[] mixColors(int[] c0, int[] c1, int w0, int w1) {
        int total = w0 + w1;
        return new int[]{
            Math.round((c0[0] * w0 + c1[0] * w1) / (float)total),
            Math.round((c0[1] * w0 + c1[1] * w1) / (float)total),
            Math.round((c0[2] * w0 + c1[2] * w1) / (float)total),
            255
        };
    }

    // ==================== DXT5 (BC3) ====================

    private static int[] decodeDXT5(byte[] data, int w, int h) {
        int[] pixels = new int[w * h];
        int blocksX = (w + 3) / 4;
        int blocksY = (h + 3) / 4;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int blockOff = (by * blocksX + bx) * 16;
                if (blockOff + 16 > data.length) break;

                // Alpha
                int a0 = data[blockOff] & 0xFF;
                int a1 = data[blockOff + 1] & 0xFF;
                int[] alphas = new int[8];
                alphas[0] = a0; alphas[1] = a1;
                if (a0 > a1) {
                    for (int i = 2; i < 8; i++) alphas[i] = ((8 - i) * a0 + (i - 1) * a1) / 7;
                } else {
                    for (int i = 2; i < 6; i++) alphas[i] = ((6 - i) * a0 + (i - 1) * a1) / 5;
                    alphas[6] = 0; alphas[7] = 255;
                }

                long alphaBits = ((data[blockOff + 2] & 0xFFL)) | ((data[blockOff + 3] & 0xFFL) << 8) |
                                 ((data[blockOff + 4] & 0xFFL) << 16) | ((data[blockOff + 5] & 0xFFL) << 24) |
                                 ((data[blockOff + 6] & 0xFFL) << 32) | ((data[blockOff + 7] & 0xFFL) << 40);

                // Color (DXT1 block at offset 8)
                decodeDXT1Block(data, blockOff + 8, pixels, w, h, bx, by);

                // Apply alpha
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        int px = bx * 4 + x, py = by * 4 + y;
                        if (px >= w || py >= h) continue;
                        int aIdx = (int)((alphaBits >> ((y * 4 + x) * 3)) & 7);
                        int pIdx = py * w + px;
                        pixels[pIdx] = (alphas[aIdx] << 24) | (pixels[pIdx] & 0x00FFFFFF);
                    }
                }
            }
        }
        return pixels;
    }

    // ==================== BC4 (RGTC1) ====================

    private static int[] decodeBC4(byte[] data, int w, int h) {
        int[] pixels = new int[w * h];
        int blocksX = (w + 3) / 4;
        int blocksY = (h + 3) / 4;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int blockOff = (by * blocksX + bx) * 8;
                if (blockOff + 8 > data.length) break;

                int a0 = data[blockOff] & 0xFF;
                int a1 = data[blockOff + 1] & 0xFF;
                int[] lut = new int[8];
                lut[0] = a0; lut[1] = a1;
                if (a0 > a1) {
                    for (int i = 1; i < 7; i++) lut[i + 1] = ((7 - i) * a0 + i * a1) / 7;
                } else {
                    for (int i = 1; i < 5; i++) lut[i + 1] = ((5 - i) * a0 + i * a1) / 5;
                    lut[6] = 0; lut[7] = 255;
                }

                long bits = ((data[blockOff + 2] & 0xFFL)) | ((data[blockOff + 3] & 0xFFL) << 8) |
                            ((data[blockOff + 4] & 0xFFL) << 16);
                long bits2 = ((data[blockOff + 5] & 0xFFL)) | ((data[blockOff + 6] & 0xFFL) << 8) |
                             ((data[blockOff + 7] & 0xFFL) << 16);

                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        int px = bx * 4 + x, py = by * 4 + y;
                        if (px >= w || py >= h) continue;
                        int idx = y * 4 + x;
                        int val;
                        if (idx < 8) {
                            val = (int)((bits >> (idx * 3)) & 7);
                        } else {
                            val = (int)((bits2 >> ((idx - 8) * 3)) & 7);
                        }
                        int v = lut[val];
                        pixels[py * w + px] = 0xFF000000 | (v << 16) | (v << 8) | v; // grayscale
                    }
                }
            }
        }
        return pixels;
    }

    // ==================== BC7 (BPTC) ====================

    private static final int[] WEIGHTS2 = {0, 21, 43, 64};
    private static final int[] WEIGHTS3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] WEIGHTS4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    // Mode: [numSubsets, partBits, rotBits, idxSelBits, cBits, aBits, pBitType, idxBits, idx2Bits]
    private static final int[][] BC7_MODES = {
        {3, 4, 0, 0, 4, 0, 1, 3, 0},
        {2, 6, 0, 0, 6, 0, 1, 3, 0},
        {3, 6, 0, 0, 5, 0, 0, 2, 0},
        {2, 6, 0, 0, 7, 0, 1, 2, 0},
        {1, 0, 2, 1, 5, 6, 0, 2, 3},
        {1, 0, 2, 0, 7, 8, 0, 2, 2},
        {1, 0, 0, 0, 7, 7, 1, 4, 0},
        {2, 6, 0, 0, 5, 5, 1, 2, 0},
    };

    private static int[] decodeBC7(byte[] data, int w, int h) {
        int[] pixels = new int[w * h];
        int blocksX = (w + 3) / 4;
        int blocksY = (h + 3) / 4;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int blockOff = (by * blocksX + bx) * 16;
                if (blockOff + 16 > data.length) break;
                byte[] block = new byte[16];
                System.arraycopy(data, blockOff, block, 0, 16);
                decodeBC7Block(block, pixels, w, h, bx, by);
            }
        }
        return pixels;
    }

    private static int[] bitPosRef = new int[1];

    private static int readBits(byte[] block, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            int byteIdx = bitPosRef[0] / 8;
            int bitIdx = bitPosRef[0] % 8;
            if (byteIdx < block.length) {
                v |= ((block[byteIdx] >> bitIdx) & 1) << i;
            }
            bitPosRef[0]++;
        }
        return v;
    }

    private static int expand(int v, int bits) {
        if (bits >= 8) return v;
        return (v << (8 - bits)) | (v >> (2 * bits - 8));
    }

    private static int interp(int a, int b, int w) {
        return ((64 - w) * a + w * b + 32) >> 6;
    }

    private static void decodeBC7Block(byte[] block, int[] pixels, int w, int h, int bx, int by) {
        int mode = 0;
        while (mode < 8 && (block[0] & (1 << mode)) == 0) mode++;
        if (mode >= 8) {
            // Fill black
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 4; x++) {
                    int px = bx * 4 + x, py = by * 4 + y;
                    if (px < w && py < h) pixels[py * w + px] = 0xFF000000;
                }
            return;
        }

        int[] M = BC7_MODES[mode];
        int numSubsets = M[0], partBits = M[1], rotBits = M[2], idxSelBits = M[3];
        int cBits = M[4], aBits = M[5], pBitType = M[6], idxBits = M[7], idx2Bits = M[8];

        bitPosRef[0] = mode + 1;

        int partition = partBits > 0 ? readBits(block, partBits) : 0;
        int rotation = rotBits > 0 ? readBits(block, rotBits) : 0;
        int idxSel = idxSelBits > 0 ? readBits(block, idxSelBits) : 0;

        // Endpoints
        int numEPs = numSubsets * 2;
        int[][] eps = new int[numEPs][4];
        for (int i = 0; i < numEPs; i++) { eps[i][3] = 255; }
        for (int c = 0; c < 3; c++)
            for (int i = 0; i < numEPs; i++)
                eps[i][c] = readBits(block, cBits);
        if (aBits > 0)
            for (int i = 0; i < numEPs; i++)
                eps[i][3] = readBits(block, aBits);

        // P-bits
        if (pBitType != 0) {
            boolean shared = (mode == 1);
            int pCount = shared ? numSubsets : numSubsets * 2;
            for (int i = 0; i < pCount; i++) {
                int p = readBits(block, 1);
                int idx = shared ? i * 2 : i;
                int maxC = aBits > 0 ? 4 : 3;
                for (int c = 0; c < maxC; c++) eps[idx][c] = (eps[idx][c] << 1) | p;
                if (shared) for (int c = 0; c < maxC; c++) eps[idx + 1][c] = (eps[idx + 1][c] << 1) | p;
            }
        }

        // Expand to 8-bit
        int totalCBits = cBits + (pBitType != 0 ? 1 : 0);
        int totalABits = aBits > 0 ? aBits + (pBitType != 0 ? 1 : 0) : 8;
        for (int i = 0; i < numEPs; i++) {
            for (int c = 0; c < 3; c++) eps[i][c] = expand(eps[i][c], totalCBits);
            if (aBits > 0) eps[i][3] = expand(eps[i][3], totalABits);
        }

        // Weights
        int[] weights = idxBits == 2 ? WEIGHTS2 : idxBits == 3 ? WEIGHTS3 : WEIGHTS4;
        int[] weights2 = idx2Bits == 2 ? WEIGHTS2 : idx2Bits == 3 ? WEIGHTS3 : null;

        int[] anchors = getBC7Anchors(numSubsets, partition);

        int[] indices = new int[16];
        for (int i = 0; i < 16; i++) {
            boolean isAnchor = false;
            for (int a : anchors) if (a == i) { isAnchor = true; break; }
            indices[i] = readBits(block, isAnchor ? idxBits - 1 : idxBits);
        }

        int[] indices2 = null;
        if (idx2Bits > 0) {
            indices2 = new int[16];
            for (int i = 0; i < 16; i++) {
                indices2[i] = readBits(block, i == 0 ? idx2Bits - 1 : idx2Bits);
            }
        }

        // Decode pixels
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int px = bx * 4 + x, py = by * 4 + y;
                if (px >= w || py >= h) continue;
                int i = y * 4 + x;
                int subset = numSubsets > 1 ? getBC7Subset(partition, numSubsets, i) : 0;
                int[] e0 = eps[subset * 2], e1 = eps[subset * 2 + 1];

                int ci = indices[i], ai = idx2Bits > 0 ? indices2[i] : ci;
                if (idxSel != 0) { int tmp = ci; ci = ai; ai = tmp; }

                int cw = weights[ci];
                int aw = (idx2Bits > 0 ? weights2 : weights)[ai];

                int r = interp(e0[0], e1[0], cw);
                int g = interp(e0[1], e1[1], cw);
                int b = interp(e0[2], e1[2], cw);
                int a = interp(e0[3], e1[3], aw);

                if (rotation != 0) {
                    int tmp;
                    switch (rotation) {
                        case 1: tmp = a; a = r; r = tmp; break;
                        case 2: tmp = a; a = g; g = tmp; break;
                        case 3: tmp = a; a = b; b = tmp; break;
                    }
                }

                pixels[py * w + px] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static int[] getBC7Anchors(int numSubsets, int partition) {
        if (numSubsets == 1) return new int[]{0};
        if (numSubsets == 2) return new int[]{0, ANCHOR2[partition]};
        return new int[]{0, ANCHOR3A[partition], ANCHOR3B[partition]};
    }

    private static int getBC7Subset(int partition, int numSubsets, int idx) {
        if (numSubsets == 1) return 0;
        if (numSubsets == 2) return (PARTITION2[partition] >> idx) & 1;
        return (PARTITION3[partition] >> (idx * 2)) & 3;
    }

    private static final int[] ANCHOR2 = {15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,2,8,2,2,8,8,15,2,8,2,2,8,8,2,2,15,15,6,8,2,8,15,15,2,8,2,2,2,15,15,6,6,2,6,8,15,15,2,2,15,15,15,15,15,2,2,15};
    private static final int[] ANCHOR3A = {3,3,15,15,8,3,15,15,8,8,6,6,6,5,3,3,3,3,8,15,3,3,6,10,5,8,8,6,8,5,15,15,8,15,3,5,6,10,8,15,15,3,15,5,15,15,15,15,3,15,5,5,5,8,5,10,5,10,8,13,15,12,3,3};
    private static final int[] ANCHOR3B = {15,8,8,3,15,15,3,8,15,15,15,15,15,15,15,8,15,8,15,3,15,8,15,8,3,15,6,10,15,15,10,8,15,3,15,10,10,8,9,10,6,15,8,15,3,6,6,8,15,3,15,15,15,15,15,15,15,15,15,15,3,15,15,8};

    private static final int[] PARTITION2 = {
        0xCCCC,0x8888,0xEEEE,0xECC8,0xC880,0xFEEC,0xFEC8,0xEC80,0xC800,0xFFEC,0xFE80,0xE800,0xFFE8,0xFF00,0xFFF0,0xF000,
        0xF710,0x008E,0x7100,0x08CE,0x008C,0x7310,0x3100,0x8CCE,0x088C,0x3110,0x6666,0x366C,0x17E8,0x0FF0,0x718E,0x399C,
        0xAAAA,0xF0F0,0x5A5A,0x33CC,0x3C3C,0x55AA,0x9696,0xA55A,0x73CE,0x13C8,0x324C,0x3BDC,0x6996,0xC33C,0x9966,0x0660,
        0x0272,0x04E4,0x4E40,0x2720,0xC936,0x936C,0x39C6,0x639C,0x9336,0x9CC6,0x817E,0xE718,0xCCF0,0x0FCC,0x7744,0xEE22
    };

    private static final int[] PARTITION3 = {
        0xAA685050,0x6A5A5040,0x5A5A4200,0x5450A0A8,0xA5A50000,0xA0A05050,0x5555A0A0,0x5A5A5050,
        0xAA550000,0xAA555500,0xAAAA5500,0x90909090,0x94949494,0xA4A4A4A4,0xA9A59450,0x2A0A4250,
        0xA5945040,0x0A425054,0xA5A5A500,0x55A0A0A0,0xA8A85454,0x6A6A4040,0xA4A45000,0x1A1A0500,
        0x0050A4A4,0xAAA59090,0x14696914,0x69691469,0xA08585A0,0xAA821414,0x50A4A450,0x6A5A0200,
        0xA9A58000,0x5090A0A8,0xA8A09050,0x24242424,0x00AA5500,0x24924924,0x24499224,0x50A50A50,
        0x500AA550,0xAAAA4444,0x66660000,0xA5A0A5A0,0x50A050A0,0x69286928,0x44AAAA44,0x66666600,
        0xAA444444,0x54A854A8,0x95809580,0x96969600,0xA85454A8,0x80959580,0xAA141414,0x96960000,
        0xAAAA1414,0xA05050A0,0xA0A0A5A5,0x96000000,0x40804080,0xA9A8A9A8,0xAAAAAA44,0x2A4A5254
    };
}
