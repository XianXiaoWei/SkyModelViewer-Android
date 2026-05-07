using System.Buffers.Binary;
using System.Runtime.InteropServices;
using K4os.Compression.LZ4;
using SkyModel.Core.Models;

namespace SkyModel.Core.Parsing;

public static class TgcMeshReader
{
    public static MeshData ReadMesh(string filepath)
    {
        var raw = File.ReadAllBytes(filepath);
        if (raw.Length < 0x58)
        {
            throw new InvalidDataException("File is too small to be a valid .mesh file.");
        }

        var version = ReadInt32(raw, 0x00);
        var animated = raw[0x48] != 0;
        var payloadOffset = version >= 0x20 ? 0x4e : 0x4a;

        var isCompressed = ReadInt32(raw, payloadOffset);
        var compressedSize = ReadInt32(raw, payloadOffset + 4);
        var uncompressedSize = ReadInt32(raw, payloadOffset + 8);

        if (compressedSize <= 0 || uncompressedSize <= 0 || payloadOffset + 12 + compressedSize > raw.Length)
        {
            throw new InvalidDataException("Compressed payload bounds are invalid.");
        }

        var src = raw.AsSpan(payloadOffset + 12, compressedSize).ToArray();
        var embeddedSkeletonRaw = raw.AsSpan(payloadOffset + 12 + compressedSize).ToArray();

        byte[] dest;
        if (isCompressed != 0)
        {
            dest = new byte[uncompressedSize];
            var decoded = LZ4Codec.Decode(src, 0, src.Length, dest, 0, dest.Length);
            if (decoded <= 0)
            {
                throw new InvalidDataException("Failed to LZ4-decode mesh payload.");
            }

            if (decoded != uncompressedSize)
            {
                Array.Resize(ref dest, decoded);
            }
        }
        else
        {
            dest = src;
        }

        var p = 4;
        var aabbA = ReadVec3(dest, ref p);
        var aabbB = ReadVec3(dest, ref p);
        var aabbA2 = ReadVec3(dest, ref p);
        var aabbB2 = ReadVec3(dest, ref p);

        var quantMin = ReadFloatArray(dest, ref p, 8);
        var quantMax = ReadFloatArray(dest, ref p, 8);

        var sharedVertices = ReadUInt32(dest, ref p);
        var totalVertices = ReadUInt32(dest, ref p);
        var isIdx32 = ReadUInt32(dest, ref p) != 0;
        var numPoints = ReadUInt32(dest, ref p);
        var prop11 = ReadUInt32(dest, ref p);
        var prop12 = ReadUInt32(dest, ref p);
        var prop13 = ReadUInt32(dest, ref p);
        var prop14 = ReadUInt32(dest, ref p);

        var loadMeshNorms = dest[p++] != 0;
        var loadInfo2 = dest[p++] != 0;
        p += 1;

        var skipMeshPos = ReadUInt32(dest, ref p);
        var skipUvs = ReadUInt32(dest, ref p);
        var flag3 = ReadUInt32(dest, ref p);
        p += 0x10;

        var faceCount = (int)totalVertices / 3;
        var idxUnit = isIdx32 ? 4 : 2;

        var vertices = new List<(float X, float Y, float Z)>((int)sharedVertices);
        var packedVertexAttrs = new List<(byte B0, byte B1, byte B2, byte B3)>((int)sharedVertices);
        if (skipMeshPos == 0)
        {
            for (var i = 0; i < sharedVertices; i++)
            {
                var off = p + (i * 16);
                var x = ReadSingle(dest, off);
                var y = ReadSingle(dest, off + 4);
                var z = ReadSingle(dest, off + 8);
                vertices.Add((x, y, z));

                // The final four bytes in the 16-byte position record are not
                // padding in CompOcc samples, but they are not decoded material
                // or texture bindings.
                packedVertexAttrs.Add((dest[off + 12], dest[off + 13], dest[off + 14], dest[off + 15]));
            }

            p += (int)sharedVertices * 16;
        }

        if (loadMeshNorms)
        {
            p += (int)sharedVertices * 4;
        }

        var uv0 = new List<(float U, float V)>((int)sharedVertices);
        if (skipUvs == 0)
        {
            for (var i = 0; i < sharedVertices; i++)
            {
                var u = ReadHalf(dest, p + (i * 16));
                var v = ReadHalf(dest, p + (i * 16) + 2);
                uv0.Add((u, v));
            }

            p += (int)sharedVertices * 16;
        }

        var boneWeights = new List<List<BoneWeight>>((int)sharedVertices);
        if (animated)
        {
            for (var i = 0; i < sharedVertices; i++)
            {
                var row = new List<BoneWeight>(4);
                var off = p + (i * 8);
                for (var j = 0; j < 4; j++)
                {
                    var bi = dest[off + j];
                    var wi = dest[off + 4 + j];
                    if (bi > 0 && wi > 0)
                    {
                        row.Add(new BoneWeight(bi - 1, wi / 255.0f));
                    }
                }

                boneWeights.Add(row);
            }

            p += (int)sharedVertices * 8;
        }

        var indices = new List<(int A, int B, int C)>(faceCount);
        for (var i = 0; i < faceCount; i++)
        {
            if (isIdx32)
            {
                var a = BinaryPrimitives.ReadInt32LittleEndian(dest.AsSpan(p, 4));
                var b = BinaryPrimitives.ReadInt32LittleEndian(dest.AsSpan(p + 4, 4));
                var c = BinaryPrimitives.ReadInt32LittleEndian(dest.AsSpan(p + 8, 4));
                indices.Add((a, b, c));
                p += 12;
            }
            else
            {
                var a = BinaryPrimitives.ReadUInt16LittleEndian(dest.AsSpan(p, 2));
                var b = BinaryPrimitives.ReadUInt16LittleEndian(dest.AsSpan(p + 2, 2));
                var c = BinaryPrimitives.ReadUInt16LittleEndian(dest.AsSpan(p + 4, 2));
                indices.Add((a, b, c));
                p += 6;
            }
        }

        if (loadInfo2)
        {
            p += (int)totalVertices * idxUnit;
        }

        if (numPoints > 0)
        {
            p += (int)sharedVertices * idxUnit;
        }

        if (prop11 > 0)
        {
            p += (int)sharedVertices * idxUnit;
        }

        if (prop12 > 0)
        {
            p += (int)prop12 * idxUnit;
        }

        if (prop13 > 0)
        {
            p += (int)prop13 * 4;
        }

        if (prop14 > 0)
        {
            p += (int)prop14 * (isIdx32 ? 8 : 4);
        }

        p += faceCount * 4;

        if (skipMeshPos > 0)
        {
            var ax = aabbA2.X;
            var ay = aabbA2.Y;
            var az = aabbA2.Z;
            var sx = aabbB2.X - ax;
            var sy = aabbB2.Y - ay;
            var sz = aabbB2.Z - az;

            for (var i = 0; i < sharedVertices; i++)
            {
                var packed = ReadUInt32(dest, p + (i * 4));
                var qz = packed & 0x3ff;
                var qy = (packed >> 10) & 0x3ff;
                var qx = (packed >> 20) & 0x3ff;

                var x = ax + ((qx / 1023.0f) * sx);
                var y = ay + ((qy / 1023.0f) * sy);
                var z = az + ((qz / 1023.0f) * sz);
                vertices.Add((x, y, z));
            }

            p += (int)sharedVertices * 4;
            p += (int)sharedVertices;
        }

        if (skipUvs > 0)
        {
            var uvMinU = quantMin[0];
            var uvMinV = quantMin[1];
            var uvSizeU = quantMax[0] - uvMinU;
            var uvSizeV = quantMax[1] - uvMinV;

            for (var i = 0; i < sharedVertices; i++)
            {
                var off = p + (i * 4);
                var uHi = dest[off];
                var vHi = dest[off + 1];
                var uLo = dest[off + 2];
                var vLo = dest[off + 3];

                var uNorm = (((uHi << 8) | uLo) / 65535.0f);
                var vNorm = (((vHi << 8) | vLo) / 65535.0f);
                var u = uvMinU + (uNorm * uvSizeU);
                var v = uvMinV + (vNorm * uvSizeV);
                uv0.Add((u, v));
            }

            p += (int)sharedVertices * 4;
        }

        if (flag3 > 0)
        {
            if (packedVertexAttrs.Count == 0)
            {
                for (var i = 0; i < sharedVertices; i++)
                {
                    var off = p + (i * 4);
                    packedVertexAttrs.Add((dest[off], dest[off + 1], dest[off + 2], dest[off + 3]));
                }
            }

            p += (int)sharedVertices * 4;
        }

        var embeddedSkeleton = animated && embeddedSkeletonRaw.Length >= 85
            ? TryParseSkeleton(embeddedSkeletonRaw)
            : null;

        return new MeshData
        {
            Name = Path.GetFileNameWithoutExtension(filepath),
            SourcePath = filepath,
            Vertices = vertices,
            PackedVertexAttrs = packedVertexAttrs,
            Uv0 = uv0,
            Indices = indices,
            BoneWeights = boneWeights,
            EmbeddedSkeleton = embeddedSkeleton,
            Version = version,
            IsAnimated = animated,
        };
    }

    private static List<SkeletonBone>? TryParseSkeleton(byte[] raw)
    {
        try
        {
            return ParseSkeleton(raw);
        }
        catch
        {
            return null;
        }
    }

    private static List<SkeletonBone> ParseSkeleton(byte[] raw)
    {
        var p = 0;
        _ = ReadUInt32(raw, ref p);
        p += 64;
        var h0 = ReadUInt32(raw, ref p);
        _ = ReadUInt32(raw, ref p);
        _ = ReadUInt32(raw, ref p);
        _ = ReadUInt32(raw, ref p);
        p += 1;

        var numBones = (int)h0;
        var bones = new List<SkeletonBone>(numBones);
        for (var i = 0; i < numBones; i++)
        {
            var nameBytes = raw.AsSpan(p, 64);
            var nul = nameBytes.IndexOf((byte)0);
            var len = nul >= 0 ? nul : 64;
            var name = System.Text.Encoding.ASCII.GetString(nameBytes[..len]);
            p += 64;

            var mat = new float[16];
            for (var j = 0; j < 16; j++)
            {
                mat[j] = ReadSingle(raw, p + (j * 4));
            }

            p += 64;
            var parent1Based = ReadUInt32(raw, ref p);
            var parentIndex = parent1Based > 0 ? (int)parent1Based - 1 : -1;

            bones.Add(new SkeletonBone
            {
                Name = name,
                ParentIndex = parentIndex,
                InverseBindMatrix = mat,
            });
        }

        return bones;
    }

    private static (float X, float Y, float Z) ReadVec3(byte[] data, ref int offset)
    {
        var v = (
            ReadSingle(data, offset),
            ReadSingle(data, offset + 4),
            ReadSingle(data, offset + 8)
        );
        offset += 12;
        return v;
    }

    private static float[] ReadFloatArray(byte[] data, ref int offset, int count)
    {
        var result = new float[count];
        for (var i = 0; i < count; i++)
        {
            result[i] = ReadSingle(data, offset + (i * 4));
        }

        offset += count * 4;
        return result;
    }

    private static int ReadInt32(byte[] data, int offset)
    {
        return BinaryPrimitives.ReadInt32LittleEndian(data.AsSpan(offset, 4));
    }

    private static uint ReadUInt32(byte[] data, int offset)
    {
        return BinaryPrimitives.ReadUInt32LittleEndian(data.AsSpan(offset, 4));
    }

    private static uint ReadUInt32(byte[] data, ref int offset)
    {
        var value = ReadUInt32(data, offset);
        offset += 4;
        return value;
    }

    private static float ReadSingle(byte[] data, int offset)
    {
        return BitConverter.ToSingle(data, offset);
    }

    private static float ReadHalf(byte[] data, int offset)
    {
        var bits = BinaryPrimitives.ReadUInt16LittleEndian(data.AsSpan(offset, 2));
        return (float)BitConverter.UInt16BitsToHalf(bits);
    }
}
