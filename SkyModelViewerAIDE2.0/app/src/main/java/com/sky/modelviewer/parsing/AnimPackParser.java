package com.sky.modelviewer.parsing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import net.jpountz.lz4.LZ4Decompressor;
import net.jpountz.lz4.LZ4Factory;

/**
 * Parser for Sky .animpack binary files.
 * Ported from animpack_all_in_one.py (corrected version).
 *
 * Layout:
 *   Header (80 bytes: version+name+boneCount+boneDefsFlag+refSqtFlag)
 *   Compression info (1 byte compression + optional 4 byte nameTableSize)
 *   Bone Records (boneCount × 132: name(64) + matrix(64) + parentIndex(4))
 *   Animation Segments (loop: refSQT? + compressedBlock?)
 *     → decompresses to: clipHeader + SQT list + keyframe data
 */
public final class AnimPackParser {

    private AnimPackParser() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int HEADER_SIZE = 80;
    private static final int NAME_FIELD_SIZE = 64;
    private static final int BONE_RECORD_SIZE = 132;
    private static final int SQT_DISK_SIZE = 40;

    // ═══════════════════════════════════════════════════════════════
    //  Data structures
    // ═══════════════════════════════════════════════════════════════

    public static final class AnimPack {
        public int version;
        public String name;
        public int boneCount;
        public int boneDefsFlag;
        public int refSqtFlag;
        public int compression;
        public int nameTableSize;
        public List<Bone> bones;
        public List<AnimSegment> segments;
        public int animOffset;
        public int fileSize;

        public AnimPack() {
            bones = new ArrayList<Bone>();
            segments = new ArrayList<AnimSegment>();
        }
    }

    public static final class Bone {
        public int index;
        public String name;
        public float[] matrix;       // 16 floats, ROW-MAJOR (InverseBindMatrix)
        public int parentIndex;      // 0-based: -1 = root

        public boolean isRoot() { return parentIndex < 0; }
        public float[] position() { return new float[] { matrix[12], matrix[13], matrix[14] }; }
    }

    public static final class AnimSQT {
        public float[] scale;        // 3
        public float[] rotation;     // 4 (x,y,z,w)
        public float[] translation;  // 3

        public AnimSQT() {
            scale = new float[] {1,1,1};
            rotation = new float[] {0,0,0,1};
            translation = new float[] {0,0,0};
        }
        public AnimSQT(float[] s, float[] r, float[] t) {
            scale = s; rotation = r; translation = t;
        }
    }

    public static final class KeyframeHeader {
        public int field1;           // start frame
        public int field2;           // end frame
        public int flags;            // bit0 = int16 trans
        public float[] bboxMin;
        public float[] bboxMax;
        public float[] extra1;       // quantization offset (v>=11)
        public float[] extra2;       // quantization scale  (v>=11)
        public byte[] perBoneFlags;
        public byte[] remainingData; // channel data after header

        public int frameCount() {
            return (field2 >= field1) ? (field2 - field1 + 1) : 0;
        }
        public boolean hasKeyframes() {
            if (perBoneFlags == null) return false;
            for (int i = 0; i < perBoneFlags.length; i++) {
                if (perBoneFlags[i] != 0) return true;
            }
            return false;
        }
    }

    public static final class ClipData {
        public int[] header;         // 5 or 6 u32
        public List<AnimSQT> sqtList;
        public KeyframeHeader keyframeHeader;
        public byte[] rawBytes;      // full decompressed data

        public ClipData() {
            sqtList = new ArrayList<AnimSQT>();
        }
    }

    public static final class AnimSegment {
        public int index;
        public List<AnimSQT> sqtList;    // reference SQT (may be empty)
        public int compressedSize;
        public int decompressedSize;
        public byte[] compressedData;
        public ClipData clipData;
        public String decompressionError;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Main parse
    // ═══════════════════════════════════════════════════════════════

    public static AnimPack parse(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("File too small");
        }

        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        AnimPack result = new AnimPack();
        result.fileSize = data.length;

        // Header
        result.version = bb.getInt(0x00);
        result.name = readFixedString(data, 0x04, NAME_FIELD_SIZE);
        result.boneCount = bb.getInt(0x44);
        result.boneDefsFlag = bb.getInt(0x48);
        result.refSqtFlag = bb.getInt(0x4C);

        // Compression info
        // For version >= 10: compression(u8) at 0x50, nameTableSize(u32) at 0x51, bone area at 0x55
        // For version < 10: no compression byte, bone area starts at 0x50
        int boneAreaOffset;
        if (result.version >= 10) {
            result.compression = data[0x50] & 0xFF;
            result.nameTableSize = bb.getInt(0x51);
            boneAreaOffset = 0x55;
        } else {
            result.compression = 0;
            result.nameTableSize = 0;
            boneAreaOffset = 0x50;
        }

        // Bone records: name(64) + matrix(64) + parentIndex(4) = 132
        for (int i = 0; i < result.boneCount; i++) {
            int base = boneAreaOffset + i * BONE_RECORD_SIZE;
            if (base + BONE_RECORD_SIZE > data.length) break;

            Bone bone = new Bone();
            bone.index = i;
            bone.name = readFixedString(data, base, NAME_FIELD_SIZE);

            bone.matrix = new float[16];
            for (int j = 0; j < 16; j++) {
                bone.matrix[j] = bb.getFloat(base + 64 + j * 4);
            }

            int rawParent = bb.getInt(base + 128);
            bone.parentIndex = (rawParent > 0) ? (rawParent - 1) : -1;
            result.bones.add(bone);
        }

        // Animation segments
        result.animOffset = boneAreaOffset + result.boneCount * BONE_RECORD_SIZE;
        result.segments = parseAnimSegments(data, result.animOffset,
            result.boneCount, result.version, result.refSqtFlag,
            result.boneDefsFlag, result.compression);

        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Segment parsing (loop, matching Python _parse_anim_segments)
    // ═══════════════════════════════════════════════════════════════

    private static List<AnimSegment> parseAnimSegments(byte[] data, int startOffset,
            int boneCount, int version, int refSqtFlag, int boneDefsFlag, int compression) {

        List<AnimSegment> segments = new ArrayList<AnimSegment>();
        int offset = startOffset;
        int segIdx = 0;

        while (offset < data.length) {
            AnimSegment seg = new AnimSegment();
            seg.index = segIdx;
            seg.sqtList = new ArrayList<AnimSQT>();
            seg.decompressionError = "";

            // 1. Reference SQT (condition: refSqtFlag > 0 AND boneDefsFlag > 0)
            if (refSqtFlag > 0 && boneDefsFlag > 0) {
                int sqtEnd = offset + boneCount * SQT_DISK_SIZE;
                if (sqtEnd > data.length) break;
                for (int i = 0; i < boneCount; i++) {
                    seg.sqtList.add(parseSQT(data, offset + i * SQT_DISK_SIZE));
                }
                offset = sqtEnd;
            }

            // 2. Compressed block or inline clip data
            if (compression > 0) {
                if (offset + 8 > data.length) break;
                int totalSize = readU32(data, offset);
                int decompSize = readU32(data, offset + 4);
                int compStart = offset + 8;
                if (totalSize == 0 || compStart + totalSize > data.length) break;

                seg.compressedSize = totalSize;
                seg.decompressedSize = decompSize;
                seg.compressedData = new byte[totalSize];
                System.arraycopy(data, compStart, seg.compressedData, 0, totalSize);

                // LZ4 decompress
                try {
                    byte[] decomp = lz4Decompress(seg.compressedData, decompSize);
                    if (decomp != null && decomp.length > 0) {
                        seg.clipData = parseClipData(decomp, boneCount, version);
                    }
                } catch (Exception e) {
                    seg.decompressionError = e.getMessage();
                }

                segments.add(seg);
                segIdx++;
                offset = compStart + totalSize;
            } else {
                // Inline clip data (no compression)
                int remaining = data.length - offset;
                int clipHeaderSize = (version >= 10 ? 6 : 5) * 4;
                if (remaining < clipHeaderSize + boneCount * SQT_DISK_SIZE) break;

                byte[] inlineData = new byte[remaining];
                System.arraycopy(data, offset, inlineData, 0, remaining);
                seg.compressedSize = remaining;
                seg.decompressedSize = remaining;
                seg.clipData = parseClipData(inlineData, boneCount, version);

                segments.add(seg);
                break;
            }
        }
        return segments;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Clip data parsing
    // ═══════════════════════════════════════════════════════════════

    private static ClipData parseClipData(byte[] d, int boneCount, int version) {
        ClipData clip = new ClipData();
        clip.rawBytes = d;

        int u32Count = (version >= 10) ? 6 : 5;
        int headerSize = u32Count * 4;
        if (d.length < headerSize) return clip;

        clip.header = new int[u32Count];
        for (int i = 0; i < u32Count; i++) {
            clip.header[i] = readU32(d, i * 4);
        }

        int pos = headerSize;
        for (int i = 0; i < boneCount; i++) {
            if (pos + SQT_DISK_SIZE > d.length) break;
            clip.sqtList.add(parseSQT(d, pos));
            pos += SQT_DISK_SIZE;
        }

        // Keyframe header (single, if data remains)
        if (pos < d.length) {
            clip.keyframeHeader = parseKeyframeHeader(d, pos, boneCount, version);
        }

        return clip;
    }

    private static KeyframeHeader parseKeyframeHeader(byte[] d, int offset, int boneCount, int version) {
        KeyframeHeader kf = new KeyframeHeader();
        kf.bboxMin = new float[] {0,0,0};
        kf.bboxMax = new float[] {0,0,0};
        kf.extra1 = new float[] {0,0,0};
        kf.extra2 = new float[] {0,0,0};

        int pos = offset;
        if (pos + 12 > d.length) return kf;

        kf.field1 = readU32(d, pos); pos += 4;
        kf.field2 = readU32(d, pos); pos += 4;
        kf.flags = readU32(d, pos); pos += 4;

        if (version > 8) {
            if (pos + 24 <= d.length) {
                kf.bboxMin = readVec3(d, pos); pos += 12;
                kf.bboxMax = readVec3(d, pos); pos += 12;
            }
        }
        if (version >= 11) {
            if (pos + 24 <= d.length) {
                kf.extra1 = readVec3(d, pos); pos += 12;
                kf.extra2 = readVec3(d, pos); pos += 12;
            }
        }

        int flagLen = Math.min(boneCount, d.length - pos);
        if (flagLen < 0) flagLen = 0;
        kf.perBoneFlags = new byte[boneCount];
        if (flagLen > 0) {
            System.arraycopy(d, pos, kf.perBoneFlags, 0, flagLen);
        }
        pos += boneCount;

        // Store remaining data (channel values)
        int remainingLen = d.length - pos;
        if (remainingLen > 0) {
            kf.remainingData = new byte[remainingLen];
            System.arraycopy(d, pos, kf.remainingData, 0, remainingLen);
        } else {
            kf.remainingData = new byte[0];
        }

        return kf;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Keyframe decoding — multi-set, frame-based lookup
    // ═══════════════════════════════════════════════════════════════

    /**
     * Decoded animation data. Provides per-frame SQT lookup for all bones.
     * Built by decoding ALL keyframe sets in the clip data.
     */
    public static final class DecodedAnimation {
        public int minFrame;
        public int maxFrame;
        public int frameCount;
        public int boneCount;
        // Per-frame, per-bone SQT: frames[boneCount * frameIdx + boneIdx]
        // Each entry is {scale[3], quat[4], trans[3]} = 10 floats
        // null entry = use base SQT
        public float[] frameData;  // boneCount * frameCount * 10, or null if no keyframe data
        public boolean hasAnimation;  // true if any keyframe set has channel data
        public List<AnimSQT> baseSqt;  // base pose from clip data

        public AnimSQT getSqt(int frameIdx, int boneIdx) {
            if (frameData == null || frameIdx < 0 || frameIdx >= frameCount) {
                return baseSqt != null && boneIdx < baseSqt.size() ? baseSqt.get(boneIdx) : new AnimSQT();
            }
            int base = (boneIdx * frameCount + frameIdx) * 10;
            if (base + 10 > frameData.length) {
                return baseSqt != null && boneIdx < baseSqt.size() ? baseSqt.get(boneIdx) : new AnimSQT();
            }
            float[] s = {frameData[base], frameData[base+1], frameData[base+2]};
            float[] r = {frameData[base+3], frameData[base+4], frameData[base+5], frameData[base+6]};
            float[] t = {frameData[base+7], frameData[base+8], frameData[base+9]};
            return new AnimSQT(s, r, t);
        }

        public boolean hasFrameData(int frameIdx, int boneIdx) {
            if (frameData == null || frameIdx < 0 || frameIdx >= frameCount) return false;
            int base = (boneIdx * frameCount + frameIdx) * 10;
            return base + 10 <= frameData.length && frameData[base + 3] != 0 || frameData[base + 6] != 1;
        }
    }

    /**
     * Decode all keyframe sets from the first segment's clip data.
     * Returns a DecodedAnimation that provides per-frame SQT lookup.
     *
     * Each keyframe set covers a frame range [f1, f2]:
     *   - Main channels (bit3/4/5): single value at frame f1
     *   - Sub channels (bit0/1/2): per-frame values for frames f1..f2
     * Multiple sets cover different frame ranges and different bones.
     */
    public static DecodedAnimation decodeAnimation(AnimPack ap) {
        if (ap == null || ap.segments == null || ap.segments.isEmpty()) return null;
        AnimSegment seg = ap.segments.get(0);
        if (seg.clipData == null || seg.clipData.rawBytes == null) return null;

        byte[] decomp = seg.clipData.rawBytes;
        int bc = ap.boneCount;
        int comp = ap.compression;
        int version = ap.version;

        DecodedAnimation result = new DecodedAnimation();
        result.boneCount = bc;
        result.baseSqt = seg.clipData.sqtList;

        // Start position: after clip header + SQT list
        int u32Count = (version >= 10) ? 6 : 5;
        int headerSize = u32Count * 4;
        int sqtSize = bc * SQT_DISK_SIZE;
        int pos = headerSize + sqtSize;

        if (pos >= decomp.length) {
            result.hasAnimation = false;
            return result;
        }

        int numSets = (seg.clipData.header != null && seg.clipData.header.length > 0)
            ? seg.clipData.header[0] : 0;
        if (numSets == 0) {
            result.hasAnimation = false;
            return result;
        }

        // First pass: find frame range
        int scanOff = 0;
        int scanRemaining = decomp.length - pos;
        result.minFrame = Integer.MAX_VALUE;
        result.maxFrame = Integer.MIN_VALUE;
        boolean anySubChannel = false;

        for (int si = 0; si < numSets; si++) {
            if (scanOff + 12 > scanRemaining) break;
            int f1 = readU32(decomp, pos + scanOff); scanOff += 4;
            int f2 = readU32(decomp, pos + scanOff); scanOff += 4;
            int flags = readU32(decomp, pos + scanOff); scanOff += 4;

            if (f1 < result.minFrame) result.minFrame = f1;
            if (f2 > result.maxFrame) result.maxFrame = f2;

            // Skip bbox + extra
            int skip = 0;
            if (version > 8) skip += 24;
            if (version >= 11) skip += 24;
            scanOff += skip;

            // Per-bone flags
            if (scanOff + bc > scanRemaining) break;
            byte[] pbf = new byte[bc];
            System.arraycopy(decomp, pos + scanOff, pbf, 0, bc);
            scanOff += bc;

            boolean useI16Trans = (comp == 2) && ((flags & 1) != 0);
            int quatSz = (comp == 2) ? 8 : 16;
            int transSz = useI16Trans ? 6 : 12;
            int scaleSz = 12;

            int sc = 0, qc = 0, tc = 0, s2c = 0, q2c = 0, t2c = 0;
            for (int bi = 0; bi < bc; bi++) {
                int f = pbf[bi] & 0xFF;
                if ((f & 0x08) != 0) sc++;
                if ((f & 0x10) != 0) qc++;
                if ((f & 0x20) != 0) tc++;
                if ((f & 0x01) != 0) s2c++;
                if ((f & 0x02) != 0) q2c++;
                if ((f & 0x04) != 0) t2c++;
            }
            if (q2c > 0 || t2c > 0 || s2c > 0) anySubChannel = true;

            int fc = (f2 >= f1) ? (f2 - f1 + 1) : 1;
            int mainSz = sc * scaleSz + qc * quatSz + tc * transSz;
            int subSz = s2c * scaleSz + q2c * quatSz + t2c * transSz;
            scanOff += mainSz + fc * subSz;
        }

        if (result.minFrame == Integer.MAX_VALUE) {
            result.hasAnimation = false;
            return result;
        }

        result.frameCount = result.maxFrame - result.minFrame + 1;
        if (result.frameCount <= 0) result.frameCount = 1;
        result.hasAnimation = anySubChannel;

        // Allocate frame data: boneCount * frameCount * 10 floats
        // Initialize with NaN to indicate "not set"
        result.frameData = new float[bc * result.frameCount * 10];
        for (int i = 0; i < result.frameData.length; i++) result.frameData[i] = Float.NaN;

        // Second pass: decode all sets and fill frame data
        int off = 0;
        int remaining = decomp.length - pos;

        for (int si = 0; si < numSets; si++) {
            if (off + 12 > remaining) break;

            int f1 = readU32(decomp, pos + off); off += 4;
            int f2 = readU32(decomp, pos + off); off += 4;
            int flags = readU32(decomp, pos + off); off += 4;

            float[] extra1 = new float[] {0,0,0};
            float[] extra2 = new float[] {0,0,0};

            if (version > 8) {
                if (off + 24 > remaining) break;
                off += 12; // bbox min
                off += 12; // bbox max
            }
            if (version >= 11) {
                if (off + 24 > remaining) break;
                extra1 = readVec3(decomp, pos + off); off += 12;
                extra2 = readVec3(decomp, pos + off); off += 12;
            }

            if (off + bc > remaining) break;
            byte[] pbf = new byte[bc];
            System.arraycopy(decomp, pos + off, pbf, 0, bc);
            off += bc;

            int fc = (f2 >= f1) ? (f2 - f1 + 1) : 1;
            boolean useI16Trans = (comp == 2) && ((flags & 1) != 0);
            int quatSz = (comp == 2) ? 8 : 16;
            int transSz = useI16Trans ? 6 : 12;
            int scaleSz = 12;

            // Main channels (read 1 time) — store at frame f1
            int mainFrameIdx = f1 - result.minFrame;
            if (mainFrameIdx < 0) mainFrameIdx = 0;

            // Scale (bit3)
            for (int bi = 0; bi < bc; bi++) {
                if ((pbf[bi] & 0x08) == 0) continue;
                if (off + scaleSz > remaining) break;
                float[] s = readVec3(decomp, pos + off); off += scaleSz;
                setFrameSqt(result, mainFrameIdx, bi, s, null, null);
            }
            // Quat (bit4)
            for (int bi = 0; bi < bc; bi++) {
                if ((pbf[bi] & 0x10) == 0) continue;
                if (off + quatSz > remaining) break;
                float[] q;
                if (comp == 2) {
                    q = decodeI16Quat(decomp, pos + off);
                } else {
                    q = readQuat(decomp, pos + off);
                }
                normalizeQuat(q);
                off += quatSz;
                setFrameSqt(result, mainFrameIdx, bi, null, q, null);
            }
            // Trans (bit5)
            for (int bi = 0; bi < bc; bi++) {
                if ((pbf[bi] & 0x20) == 0) continue;
                if (off + transSz > remaining) break;
                float[] t;
                if (useI16Trans) {
                    t = decodeI16Trans(decomp, pos + off, extra1, extra2);
                } else {
                    t = readVec3(decomp, pos + off);
                }
                off += transSz;
                setFrameSqt(result, mainFrameIdx, bi, null, null, t);
            }

            // Sub channels (read fc times) — store at frames f1..f2
            for (int fi = 0; fi < fc; fi++) {
                int frameIdx = (f1 + fi) - result.minFrame;
                if (frameIdx < 0 || frameIdx >= result.frameCount) {
                    // Still need to skip the data
                    for (int bi = 0; bi < bc; bi++) {
                        if ((pbf[bi] & 0x01) != 0) off += scaleSz;
                    }
                    for (int bi = 0; bi < bc; bi++) {
                        if ((pbf[bi] & 0x02) != 0) off += quatSz;
                    }
                    for (int bi = 0; bi < bc; bi++) {
                        if ((pbf[bi] & 0x04) != 0) off += transSz;
                    }
                    continue;
                }

                // Scale2 (bit0)
                for (int bi = 0; bi < bc; bi++) {
                    if ((pbf[bi] & 0x01) == 0) continue;
                    if (off + scaleSz > remaining) break;
                    float[] s = readVec3(decomp, pos + off); off += scaleSz;
                    setFrameSqt(result, frameIdx, bi, s, null, null);
                }
                // Quat2 (bit1)
                for (int bi = 0; bi < bc; bi++) {
                    if ((pbf[bi] & 0x02) == 0) continue;
                    if (off + quatSz > remaining) break;
                    float[] q;
                    if (comp == 2) {
                        q = decodeI16Quat(decomp, pos + off);
                    } else {
                        q = readQuat(decomp, pos + off);
                    }
                    normalizeQuat(q);
                    off += quatSz;
                    setFrameSqt(result, frameIdx, bi, null, q, null);
                }
                // Trans2 (bit2)
                for (int bi = 0; bi < bc; bi++) {
                    if ((pbf[bi] & 0x04) == 0) continue;
                    if (off + transSz > remaining) break;
                    float[] t;
                    if (useI16Trans) {
                        t = decodeI16Trans(decomp, pos + off, extra1, extra2);
                    } else {
                        t = readVec3(decomp, pos + off);
                    }
                    off += transSz;
                    setFrameSqt(result, frameIdx, bi, null, null, t);
                }
            }
        }

        return result;
    }

    /** Set SQT components for a specific frame and bone (merges with existing values). */
    private static void setFrameSqt(DecodedAnimation anim, int frameIdx, int boneIdx,
                                     float[] scale, float[] quat, float[] trans) {
        if (frameIdx < 0 || frameIdx >= anim.frameCount) return;
        if (boneIdx < 0 || boneIdx >= anim.boneCount) return;
        int base = (boneIdx * anim.frameCount + frameIdx) * 10;
        if (base + 10 > anim.frameData.length) return;

        if (scale != null) {
            anim.frameData[base + 0] = scale[0];
            anim.frameData[base + 1] = scale[1];
            anim.frameData[base + 2] = scale[2];
        }
        if (quat != null) {
            anim.frameData[base + 3] = quat[0];
            anim.frameData[base + 4] = quat[1];
            anim.frameData[base + 5] = quat[2];
            anim.frameData[base + 6] = quat[3];
        }
        if (trans != null) {
            anim.frameData[base + 7] = trans[0];
            anim.frameData[base + 8] = trans[1];
            anim.frameData[base + 9] = trans[2];
        }
    }

    private static void normalizeQuat(float[] q) {
        float mag = q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3];
        if (mag > 1e-10f) {
            float inv = 1f / (float)Math.sqrt(mag);
            q[0] *= inv; q[1] *= inv; q[2] *= inv; q[3] *= inv;
        }
    }

    // int16 quaternion decode: (u16 - 32768) / 32767.0
    private static float[] decodeI16Quat(byte[] d, int pos) {
        int r0 = readU16(d, pos);
        int r1 = readU16(d, pos + 2);
        int r2 = readU16(d, pos + 4);
        int r3 = readU16(d, pos + 6);
        return new float[] {
            (r0 - 32768) / 32767.0f,
            (r1 - 32768) / 32767.0f,
            (r2 - 32768) / 32767.0f,
            (r3 - 32768) / 32767.0f
        };
    }

    // int16 translation decode: extra1[i] + (u16[i] / 65535.0) * extra2[i]
    private static float[] decodeI16Trans(byte[] d, int pos, float[] extra1, float[] extra2) {
        int r0 = readU16(d, pos);
        int r1 = readU16(d, pos + 2);
        int r2 = readU16(d, pos + 4);
        return new float[] {
            extra1[0] + (r0 / 65535.0f) * extra2[0],
            extra1[1] + (r1 / 65535.0f) * extra2[1],
            extra1[2] + (r2 / 65535.0f) * extra2[2]
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  Hierarchy & matrices
    // ═══════════════════════════════════════════════════════════════

    public static int[] getParentIndices(List<Bone> bones) {
        int n = bones == null ? 0 : bones.size();
        int[] parents = new int[n];
        for (int i = 0; i < n; i++) parents[i] = bones.get(i).parentIndex;
        return parents;
    }

    public static int findRootIndex(List<Bone> bones) {
        if (bones == null || bones.isEmpty()) return 0;
        for (int i = 0; i < bones.size(); i++) {
            if (bones.get(i).parentIndex < 0) return i;
        }
        return 0;
    }

    /**
     * Get the CLIP SQT list (animation base pose / first frame).
     * This is used as fallback for bones without keyframe data.
     * Returns clip SQT from decompressed data, NOT reference SQT (which is bind/T-pose).
     */
    public static List<AnimSQT> getBoneSqtList(AnimPack ap) {
        if (ap.segments != null && !ap.segments.isEmpty()) {
            AnimSegment seg = ap.segments.get(0);
            // Prefer clip SQT (animation base pose) — NOT reference SQT (bind/T-pose)
            if (seg.clipData != null && seg.clipData.sqtList != null && !seg.clipData.sqtList.isEmpty())
                return seg.clipData.sqtList;
            // Fallback to reference SQT only if clip SQT not available
            if (seg.sqtList != null && !seg.sqtList.isEmpty()) return seg.sqtList;
        }
        // Fallback: identity SQTs
        List<AnimSQT> result = new ArrayList<AnimSQT>(ap.boneCount);
        for (int i = 0; i < ap.boneCount; i++) result.add(new AnimSQT());
        return result;
    }

    /**
     * Get the REFERENCE SQT list (bind pose / T-pose).
     * Used for computing inverse bind matrices.
     */
    public static List<AnimSQT> getReferenceSqtList(AnimPack ap) {
        if (ap.segments != null && !ap.segments.isEmpty()) {
            AnimSegment seg = ap.segments.get(0);
            if (seg.sqtList != null && !seg.sqtList.isEmpty()) return seg.sqtList;
            if (seg.clipData != null && seg.clipData.sqtList != null && !seg.clipData.sqtList.isEmpty())
                return seg.clipData.sqtList;
        }
        List<AnimSQT> result = new ArrayList<AnimSQT>(ap.boneCount);
        for (int i = 0; i < ap.boneCount; i++) result.add(new AnimSQT());
        return result;
    }

    /**
     * Normalize a bone name by stripping common prefixes like "Rig:".
     * Mesh bone names: "M_hip", "L_armRoot"
     * Animpack bone names: "Rig:M_hip", "Rig:L_armRoot"
     */
    public static String normalizeBoneName(String name) {
        if (name == null) return "";
        // Strip "Rig:" prefix
        if (name.startsWith("Rig:")) return name.substring(4);
        // Strip other common prefixes
        if (name.startsWith("rig:")) return name.substring(4);
        return name;
    }

    /**
     * Check if two bone names match, accounting for prefix differences.
     */
    public static boolean boneNamesMatch(String meshName, String animName) {
        if (meshName == null || animName == null) return false;
        if (meshName.equals(animName)) return true;
        // Try normalized comparison
        String nm = normalizeBoneName(meshName);
        String na = normalizeBoneName(animName);
        if (nm.equals(na)) return true;
        // Try suffix match (animName ends with meshName or vice versa)
        if (animName.endsWith(meshName) && meshName.length() > 0) return true;
        if (meshName.endsWith(animName) && animName.length() > 0) return true;
        return false;
    }

    /** Compose 4×4 from TRS (column-major, OpenGL). */
    public static float[] composeMat4(float[] t, float[] r, float[] s) {
        float x=r[0], y=r[1], z=r[2], w=r[3];
        float xx=x*x, yy=y*y, zz=z*z, xy=x*y, xz=x*z, yz=y*z;
        float wx=w*x, wy=w*y, wz=w*z;
        float r00=1-2*(yy+zz), r01=2*(xy-wz), r02=2*(xz+wy);
        float r10=2*(xy+wz), r11=1-2*(xx+zz), r12=2*(yz-wx);
        float r20=2*(xz-wy), r21=2*(yz+wx), r22=1-2*(xx+yy);
        float sx=s[0], sy=s[1], sz=s[2];
        return new float[] {
            r00*sx, r10*sx, r20*sx, 0,
            r01*sy, r11*sy, r21*sy, 0,
            r02*sz, r12*sz, r22*sz, 0,
            t[0], t[1], t[2], 1
        };
    }

    public static float[] multiplyMat4(float[] a, float[] b) {
        float[] r = new float[16];
        for (int col=0; col<4; col++)
            for (int row=0; row<4; row++) {
                float sum=0;
                for (int k=0; k<4; k++) sum += a[k*4+row]*b[col*4+k];
                r[col*4+row] = sum;
            }
        return r;
    }

    public static float[] invertMat4(float[] m) {
        float[] r = new float[16];
        float a0=m[0]*m[5]-m[1]*m[4], a1=m[0]*m[6]-m[2]*m[4], a2=m[0]*m[7]-m[3]*m[4];
        float a3=m[1]*m[6]-m[2]*m[5], a4=m[1]*m[7]-m[3]*m[5], a5=m[2]*m[7]-m[3]*m[6];
        float b0=m[8]*m[13]-m[9]*m[12], b1=m[8]*m[14]-m[10]*m[12], b2=m[8]*m[15]-m[11]*m[12];
        float b3=m[9]*m[14]-m[10]*m[13], b4=m[9]*m[15]-m[11]*m[13], b5=m[10]*m[15]-m[11]*m[14];
        float det = a0*b5-a1*b4+a2*b3+a3*b2-a4*b1+a5*b0;
        if (Math.abs(det) < 1e-10f) {
            for (int i=0; i<16; i++) r[i] = (i%5==0)?1f:0f;
            return r;
        }
        float inv = 1f/det;
        r[0]=(m[5]*b5-m[6]*b4+m[7]*b3)*inv; r[1]=(-m[1]*b5+m[2]*b4-m[3]*b3)*inv;
        r[2]=(m[13]*a5-m[14]*a4+m[15]*a3)*inv; r[3]=(-m[9]*a5+m[10]*a4-m[11]*a3)*inv;
        r[4]=(-m[4]*b5+m[6]*b2-m[7]*b1)*inv; r[5]=(m[0]*b5-m[2]*b2+m[3]*b1)*inv;
        r[6]=(-m[12]*a5+m[14]*a2-m[15]*a1)*inv; r[7]=(m[8]*a5-m[10]*a2+m[11]*a1)*inv;
        r[8]=(m[4]*b4-m[5]*b2+m[7]*b0)*inv; r[9]=(-m[0]*b4+m[1]*b2-m[3]*b0)*inv;
        r[10]=(m[12]*a4-m[13]*a2+m[15]*a0)*inv; r[11]=(-m[8]*a4+m[9]*a2-m[11]*a0)*inv;
        r[12]=(-m[4]*b3+m[5]*b1-m[6]*b0)*inv; r[13]=(m[0]*b3-m[1]*b1+m[2]*b0)*inv;
        r[14]=(-m[12]*a3+m[13]*a1-m[14]*a0)*inv; r[15]=(m[8]*a3-m[9]*a1+m[10]*a0)*inv;
        return r;
    }

    /** Row-major 4×4 → column-major (transpose). */
    public static float[] rowMajorToColumnMajor(float[] rm) {
        return new float[] {
            rm[0], rm[4], rm[8],  rm[12],
            rm[1], rm[5], rm[9],  rm[13],
            rm[2], rm[6], rm[10], rm[14],
            rm[3], rm[7], rm[11], rm[15]
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  Low-level readers
    // ═══════════════════════════════════════════════════════════════

    private static AnimSQT parseSQT(byte[] data, int offset) {
        return new AnimSQT(
            readVec3(data, offset),
            readQuat(data, offset + 12),
            readVec3(data, offset + 28)
        );
    }

    private static int readU32(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off+1] & 0xFF) << 8) |
               ((d[off+2] & 0xFF) << 16) | ((d[off+3] & 0xFF) << 24);
    }

    private static int readU16(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off+1] & 0xFF) << 8);
    }

    private static float readF32(byte[] d, int off) {
        return Float.intBitsToFloat(readU32(d, off));
    }

    private static float[] readVec3(byte[] d, int off) {
        return new float[] { readF32(d, off), readF32(d, off+4), readF32(d, off+8) };
    }

    private static float[] readQuat(byte[] d, int off) {
        return new float[] { readF32(d, off), readF32(d, off+4), readF32(d, off+8), readF32(d, off+12) };
    }

    private static String readFixedString(byte[] data, int offset, int maxLen) {
        int end = offset + maxLen;
        if (end > data.length) end = data.length;
        int len = maxLen;
        for (int i = offset; i < end; i++) {
            if (data[i] == 0) { len = i - offset; break; }
        }
        if (offset + len > end) len = end - offset;
        return new String(data, offset, len, UTF8);
    }

    private static byte[] lz4Decompress(byte[] src, int decompressedSize) {
        if (decompressedSize <= 0) return new byte[0];
        if (src == null || src.length == 0) return null;

        // Try standalone pure Java LZ4 block decompressor first (no external dependencies)
        byte[] result = lz4BlockDecompressPure(src, decompressedSize);
        if (result != null) return result;

        // Fallback: try lz4-java library
        byte[] out = new byte[decompressedSize];
        try {
            LZ4Decompressor decompressor = LZ4Factory.fastestJavaInstance().decompressor();
            decompressor.decompress(src, 0, out, 0, out.length);
            return out;
        } catch (Throwable e1) {
            try {
                LZ4Decompressor decompressor = LZ4Factory.fastestInstance().decompressor();
                decompressor.decompress(src, 0, out, 0, out.length);
                return out;
            } catch (Throwable e2) {
                return null;
            }
        }
    }

    /**
     * Pure Java LZ4 block format decompressor.
     * No external dependencies, works on Android without native libraries.
     * Based on https://github.com/lz4/lz4/blob/dev/doc/lz4_Block_format.md
     */
    private static byte[] lz4BlockDecompressPure(byte[] src, int dstSize) {
        if (dstSize <= 0) return new byte[0];
        byte[] dst = new byte[dstSize];
        int si = 0, di = 0;
        int slen = src.length;

        while (si < slen) {
            // Token: high 4 bits = literal length, low 4 bits = match length - 4
            int token = src[si++] & 0xFF;
            int literalLen = token >>> 4;
            int matchLen = (token & 0xF);

            // Extra literal length bytes
            if (literalLen == 15) {
                while (si < slen) {
                    int b = src[si++] & 0xFF;
                    literalLen += b;
                    if (b != 255) break;
                }
            }

            // Copy literals
            if (literalLen > 0) {
                if (si + literalLen > slen || di + literalLen > dstSize) return null;
                System.arraycopy(src, si, dst, di, literalLen);
                si += literalLen;
                di += literalLen;
            }

            // End of block? (no match after last literals)
            if (si >= slen) break;

            // Match offset (16-bit little-endian)
            if (si + 2 > slen) return null;
            int offset = (src[si] & 0xFF) | ((src[si + 1] & 0xFF) << 8);
            si += 2;

            // Extra match length bytes
            if (matchLen == 15) {
                while (si < slen) {
                    int b = src[si++] & 0xFF;
                    matchLen += b;
                    if (b != 255) break;
                }
            }
            matchLen += 4; // minimum match length

            // Copy match (may overlap, so byte-by-byte)
            if (offset == 0 || di - offset < 0) return null;
            int matchStart = di - offset;
            if (di + matchLen > dstSize) return null;
            for (int i = 0; i < matchLen; i++) {
                dst[di] = dst[matchStart + i];
                di++;
            }
        }

        return dst;
    }
}
