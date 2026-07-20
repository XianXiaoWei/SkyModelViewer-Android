package com.sky.modelviewer.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ETC2/EAC software decoder — decodes compressed KTX texture data to RGBA8.
 * Ported from SkyMeshViewer HTML decodeKtx + decodeEtc2RgbBlock + decodeEacBlock.
 *
 * Decodes ETC2 RGB (0x9274), ETC2 SRGB (0x9275),
 * ETC2 RGBA+EAC (0x9278), ETC2 SRGBA+EAC (0x9279) to RGBA8 bytes.
 * Also decodes R11 EAC (0x9270) for UI atlas textures.
 *
 * Output is RGBA8 in KTX native row order (no Y flip) — matches HTML flipY=false.
 */
public class Etc2Decoder {

    // === Lookup tables (HTML line 34534-34564) ===

    private static final int[][] ETC1_MODIFIER = {
        {2, 8, -2, -8},
        {5, 17, -5, -17},
        {9, 29, -9, -29},
        {13, 42, -13, -42},
        {18, 60, -18, -60},
        {24, 80, -24, -80},
        {33, 106, -33, -106},
        {47, 183, -47, -183}
    };

    private static final int[] ETC2_DISTANCE = {3, 6, 11, 16, 23, 32, 41, 64};

    private static final int[][] EAC_MODIFIER = {
        {-3, -6, -9, -15, 2, 5, 8, 14},
        {-3, -7, -10, -13, 2, 6, 9, 12},
        {-2, -5, -8, -13, 1, 4, 7, 12},
        {-2, -4, -6, -13, 1, 3, 5, 12},
        {-3, -6, -8, -12, 2, 5, 7, 11},
        {-3, -7, -9, -11, 2, 6, 8, 10},
        {-4, -7, -8, -11, 3, 6, 7, 10},
        {-3, -5, -8, -11, 2, 4, 7, 10},
        {-2, -6, -8, -10, 1, 5, 7, 9},
        {-2, -5, -8, -10, 1, 4, 7, 9},
        {-2, -4, -8, -10, 1, 3, 7, 9},
        {-2, -5, -7, -10, 1, 4, 6, 9},
        {-3, -4, -7, -10, 2, 3, 6, 9},
        {-1, -2, -3, -10, 0, 1, 2, 9},
        {-4, -6, -8, -9, 3, 5, 7, 8},
        {-3, -5, -7, -9, 2, 4, 6, 8}
    };

    // === Format constants ===
    public static final int GL_ETC2_RGB = 0x9274;
    public static final int GL_ETC2_SRGB = 0x9275;
    public static final int GL_ETC2_RGBA = 0x9278;
    public static final int GL_ETC2_SRGBA = 0x9279;
    public static final int GL_R11_EAC = 0x9270;
    public static final int GL_RG11_EAC = 0x9271;

    public static class DecodedImage {
        public int width;
        public int height;
        public byte[] data;  // RGBA8
        public boolean srgb;
    }

    private Etc2Decoder() {}

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    private static int extend4to8(int c) { return (c << 4) | c; }
    private static int extend5to8(int c) { return (c << 3) | (c >> 2); }
    private static int extend6to8(int c) { return (c << 2) | (c >> 4); }
    private static int extend7to8(int c) { return (c << 1) | (c >> 6); }
    private static int signed3(int v) { return v >= 4 ? v - 8 : v; }

    /**
     * Decode a KTX file's first mip level to RGBA8.
     * Returns null if format is not ETC2/EAC.
     * Ported from HTML decodeKtx (line 34761-34798).
     */
    public static DecodedImage decodeKtx(byte[] data) {
        if (data.length < 68) return null;
        // Check KTX magic
        byte[] magic = {(byte)0xAB, 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, (byte)0xBB,
                        0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < 12; i++) {
            if (data[i] != magic[i]) return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt(12) != 0x04030201) return null;

        int glInternalFormat = bb.getInt(28);
        int width = bb.getInt(36);
        int height = bb.getInt(40);
        int kvSize = bb.getInt(60);
        int offset = 64 + kvSize;
        if (offset + 4 > data.length) return null;
        int imageSize = bb.getInt(offset);
        offset += 4;
        if (offset + imageSize > data.length) return null;

        boolean hasAlpha = (glInternalFormat == GL_ETC2_RGBA || glInternalFormat == GL_ETC2_SRGBA);
        boolean isEtc2 = (glInternalFormat == GL_ETC2_RGB || glInternalFormat == GL_ETC2_SRGB || hasAlpha);
        boolean isR11 = (glInternalFormat == GL_R11_EAC);

        if (!isEtc2 && !isR11) return null;

        DecodedImage out = new DecodedImage();
        out.width = width;
        out.height = height;
        out.data = new byte[width * height * 4];
        out.srgb = (glInternalFormat == GL_ETC2_SRGB || glInternalFormat == GL_ETC2_SRGBA);

        // Block data view (big-endian for ETC2 bit reading)
        // CRITICAL: must use slice() so arrayOffset() = offset, making get(0) read data[offset].
        // ByteBuffer.wrap(data, offset, imageSize) has arrayOffset()=0, so get(0) reads data[0] (file header!)
        bb.position(offset);
        ByteBuffer bdv = bb.slice().order(ByteOrder.BIG_ENDIAN);

        if (isR11) {
            decodeR11(bdv, out.data, width, height);
        } else {
            int bxCount = (width + 3) / 4;
            int byCount = (height + 3) / 4;
            int p = 0;
            for (int by = 0; by < byCount; by++) {
                for (int bx = 0; bx < bxCount; bx++) {
                    int[] alpha = null;
                    if (hasAlpha) {
                        alpha = decodeEacBlock(bdv, p);
                        p += 8;
                    }
                    decodeEtc2RgbBlock(bdv, p, out.data, width, height, bx, by);
                    p += 8;
                    if (alpha != null) {
                        for (int i = 0; i < 16; i++) {
                            int px = bx * 4 + (i >> 2);
                            int py = by * 4 + (i & 3);
                            if (px >= width || py >= height) continue;
                            out.data[(py * width + px) * 4 + 3] = (byte) alpha[i];
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * ETC2 RGB block decode (8 bytes -> 4x4 RGBA).
     * Ported from HTML decodeEtc2RgbBlock (line 34572-34618).
     */
    private static void decodeEtc2RgbBlock(ByteBuffer dv, int off, byte[] out, int width, int height, int bx, int by) {
        int bhi = dv.getInt(off);
        int blo = dv.getInt(off + 4);

        int R = (bhi >>> 27) & 0x1f;
        int dR = signed3((bhi >>> 24) & 0x07);
        int G = (bhi >>> 19) & 0x1f;
        int dG = signed3((bhi >>> 16) & 0x07);
        int B = (bhi >>> 11) & 0x1f;
        int dB = signed3((bhi >>> 8) & 0x07);

        int diffbit = (bhi >>> 1) & 1;
        int flipbit = bhi & 1;

        int[][] pixels;
        if (diffbit == 0) {
            pixels = decodeIndividual(bhi, blo, flipbit);
        } else {
            int r5 = R + dR, g5 = G + dG, b5 = B + dB;
            if (r5 < 0 || r5 > 31) {
                pixels = decodeT(bhi, blo);
            } else if (g5 < 0 || g5 > 31) {
                pixels = decodeH(bhi, blo);
            } else if (b5 < 0 || b5 > 31) {
                pixels = decodePlanar(bhi, blo);
            } else {
                pixels = decodeDifferential(bhi, blo, flipbit);
            }
        }

        // Write output (ETC block pixel order: column-major, x*4+y)
        for (int i = 0; i < 16; i++) {
            int px = bx * 4 + (i >> 2);
            int py = by * 4 + (i & 3);
            if (px >= width || py >= height) continue;
            int o = (py * width + px) * 4;
            out[o] = (byte) pixels[i][0];
            out[o + 1] = (byte) pixels[i][1];
            out[o + 2] = (byte) pixels[i][2];
            out[o + 3] = (byte) 255;
        }
    }

    private static int pixelIndex(int blo, int i) {
        int lsb = (blo >>> i) & 1;
        int msb = (blo >>> (16 + i)) & 1;
        return (msb << 1) | lsb;
    }

    private static int[][] decodeIndividual(int bhi, int blo, int flip) {
        int r1 = extend4to8((bhi >>> 28) & 0x0f), r2 = extend4to8((bhi >>> 24) & 0x0f);
        int g1 = extend4to8((bhi >>> 20) & 0x0f), g2 = extend4to8((bhi >>> 16) & 0x0f);
        int b1 = extend4to8((bhi >>> 12) & 0x0f), b2 = extend4to8((bhi >>> 8) & 0x0f);
        int t1 = (bhi >>> 5) & 0x07, t2 = (bhi >>> 2) & 0x07;
        return assembleEtc1(new int[]{r1, g1, b1}, new int[]{r2, g2, b2}, t1, t2, flip, blo);
    }

    private static int[][] decodeDifferential(int bhi, int blo, int flip) {
        int R = (bhi >>> 27) & 0x1f, dR = signed3((bhi >>> 24) & 0x07);
        int G = (bhi >>> 19) & 0x1f, dG = signed3((bhi >>> 16) & 0x07);
        int B = (bhi >>> 11) & 0x1f, dB = signed3((bhi >>> 8) & 0x07);
        int[] c1 = {extend5to8(R), extend5to8(G), extend5to8(B)};
        int[] c2 = {extend5to8(R + dR), extend5to8(G + dG), extend5to8(B + dB)};
        int t1 = (bhi >>> 5) & 0x07, t2 = (bhi >>> 2) & 0x07;
        return assembleEtc1(c1, c2, t1, t2, flip, blo);
    }

    private static int[][] assembleEtc1(int[] c1, int[] c2, int t1, int t2, int flip, int blo) {
        int[][] px = new int[16][3];
        for (int i = 0; i < 16; i++) {
            int x = i >> 2, y = i & 3;
            int sub;
            if (flip == 0) sub = x < 2 ? 0 : 1;
            else sub = y < 2 ? 0 : 1;
            int[] base = sub == 0 ? c1 : c2;
            int tbl = sub == 0 ? t1 : t2;
            int idx = pixelIndex(blo, i);
            int mod = ETC1_MODIFIER[tbl][idx];
            px[i][0] = clamp255(base[0] + mod);
            px[i][1] = clamp255(base[1] + mod);
            px[i][2] = clamp255(base[2] + mod);
        }
        return px;
    }

    private static int[][] decodeT(int bhi, int blo) {
        int r0a = (bhi >>> 27) & 0x03, r0b = (bhi >>> 24) & 0x03;
        int R0 = (r0a << 2) | r0b;
        int G0 = (bhi >>> 20) & 0x0f, B0 = (bhi >>> 16) & 0x0f;
        int R1 = (bhi >>> 12) & 0x0f, G1 = (bhi >>> 8) & 0x0f, B1 = (bhi >>> 4) & 0x0f;
        int da = (bhi >>> 2) & 0x03, db = bhi & 0x01;
        int dist = ETC2_DISTANCE[(da << 1) | db];
        int[] c0 = {extend4to8(R0), extend4to8(G0), extend4to8(B0)};
        int[] c1 = {extend4to8(R1), extend4to8(G1), extend4to8(B1)};
        int[][] paint = {
            c0,
            {clamp255(c1[0] + dist), clamp255(c1[1] + dist), clamp255(c1[2] + dist)},
            c1,
            {clamp255(c1[0] - dist), clamp255(c1[1] - dist), clamp255(c1[2] - dist)}
        };
        int[][] px = new int[16][3];
        for (int i = 0; i < 16; i++) px[i] = paint[pixelIndex(blo, i)];
        return px;
    }

    private static int[][] decodeH(int bhi, int blo) {
        int R0 = (bhi >>> 27) & 0x0f, G0a = (bhi >>> 24) & 0x07, G0b = (bhi >>> 20) & 0x01;
        int G0 = (G0a << 1) | G0b;
        int B0a = (bhi >>> 19) & 0x01, B0b = (bhi >>> 15) & 0x07;
        int B0 = (B0a << 3) | B0b;
        int R1 = (bhi >>> 11) & 0x0f, G1 = (bhi >>> 7) & 0x0f, B1 = (bhi >>> 3) & 0x0f;
        int[] c0 = {extend4to8(R0), extend4to8(G0), extend4to8(B0)};
        int[] c1 = {extend4to8(R1), extend4to8(G1), extend4to8(B1)};
        int v0 = (c0[0] << 16) | (c0[1] << 8) | c0[2];
        int v1 = (c1[0] << 16) | (c1[1] << 8) | c1[2];
        int da = (bhi >>> 2) & 0x01, db = bhi & 0x01;
        int dIdx = (da << 2) | (db << 1) | (v0 >= v1 ? 1 : 0);
        int dist = ETC2_DISTANCE[dIdx & 0x07];
        int[][] paint = {
            {clamp255(c0[0] + dist), clamp255(c0[1] + dist), clamp255(c0[2] + dist)},
            {clamp255(c0[0] - dist), clamp255(c0[1] - dist), clamp255(c0[2] - dist)},
            {clamp255(c1[0] + dist), clamp255(c1[1] + dist), clamp255(c1[2] + dist)},
            {clamp255(c1[0] - dist), clamp255(c1[1] - dist), clamp255(c1[2] - dist)}
        };
        int[][] px = new int[16][3];
        for (int i = 0; i < 16; i++) px[i] = paint[pixelIndex(blo, i)];
        return px;
    }

    private static int[][] decodePlanar(int bhi, int blo) {
        int R0 = (bhi >>> 25) & 0x3f;
        int G0a = (bhi >>> 24) & 0x01, G0b = (bhi >>> 17) & 0x3f;
        int G0 = (G0a << 6) | G0b;
        int B0a = (bhi >>> 16) & 0x01, B0b = (bhi >>> 11) & 0x07, B0c = (bhi >>> 7) & 0x07;
        int B0 = (B0a << 5) | (B0b << 2) | B0c;
        int RH1 = (bhi >>> 2) & 0x1f, RH0 = bhi & 0x01;
        int RH = (RH1 << 1) | RH0;
        int GH = (blo >>> 25) & 0x7f;
        int BH = (blo >>> 19) & 0x3f;
        int RV = (blo >>> 13) & 0x3f;
        int GV = (blo >>> 6) & 0x7f;
        int BV = blo & 0x3f;
        int ro = extend6to8(R0), go = extend7to8(G0), bo = extend6to8(B0);
        int rh = extend6to8(RH), gh = extend7to8(GH), bh = extend6to8(BH);
        int rv = extend6to8(RV), gv = extend7to8(GV), bv = extend6to8(BV);
        int[][] px = new int[16][3];
        for (int i = 0; i < 16; i++) {
            int x = i >> 2, y = i & 3;
            px[i][0] = clamp255((x * (rh - ro) + y * (rv - ro) + 4 * ro + 2) >> 2);
            px[i][1] = clamp255((x * (gh - go) + y * (gv - go) + 4 * go + 2) >> 2);
            px[i][2] = clamp255((x * (bh - bo) + y * (bv - bo) + 4 * bo + 2) >> 2);
        }
        return px;
    }

    /**
     * EAC alpha block decode (8 bytes -> 16 alpha values).
     * Ported from HTML decodeEacBlock (line 34735-34753).
     */
    private static int[] decodeEacBlock(ByteBuffer dv, int off) {
        int base = dv.get(off) & 0xFF;
        int mulTbl = dv.get(off + 1) & 0xFF;
        int mult = (mulTbl >>> 4) & 0x0f;
        int tblIdx = mulTbl & 0x0f;
        // 48-bit index (big-endian)
        long bits = 0;
        for (int k = 0; k < 6; k++) {
            bits = (bits << 8) | (dv.get(off + 2 + k) & 0xFF);
        }
        int[] alpha = new int[16];
        int[] tbl = EAC_MODIFIER[tblIdx];
        for (int i = 0; i < 16; i++) {
            int shift = 45 - i * 3;
            int idx = (int)((bits >>> shift) & 0x07);
            int m = mult == 0 ? 1 : mult;
            alpha[i] = clamp255(base + tbl[idx] * m);
        }
        return alpha;
    }

    /**
     * R11 EAC decode (UI atlas, single channel intensity).
     * Ported from HTML decodeR11Ktx pattern — intensity into R=G=B=intensity, A=intensity.
     */
    private static void decodeR11(ByteBuffer bdv, byte[] out, int width, int height) {
        int bxCount = (width + 3) / 4;
        int byCount = (height + 3) / 4;
        int p = 0;
        for (int by = 0; by < byCount; by++) {
            for (int bx = 0; bx < bxCount; bx++) {
                int[] inten = decodeEacBlock(bdv, p);
                p += 8;
                for (int i = 0; i < 16; i++) {
                    int px = bx * 4 + (i >> 2);
                    int py = by * 4 + (i & 3);
                    if (px >= width || py >= height) continue;
                    int o = (py * width + px) * 4;
                    int v = inten[i];
                    out[o] = (byte) v;
                    out[o + 1] = (byte) v;
                    out[o + 2] = (byte) v;
                    out[o + 3] = (byte) v;
                }
            }
        }
    }
}
