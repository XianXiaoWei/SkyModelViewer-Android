namespace SkyModel.Core.Models;

public sealed class MeshData
{
    public required string Name { get; init; }
    public required string SourcePath { get; init; }
    public required List<(float X, float Y, float Z)> Vertices { get; init; }
    public required List<(byte B0, byte B1, byte B2, byte B3)> PackedVertexAttrs { get; init; }
    public required List<(float U, float V)> Uv0 { get; init; }
    public required List<(int A, int B, int C)> Indices { get; init; }
    public required List<List<BoneWeight>> BoneWeights { get; init; }
    public required List<SkeletonBone>? EmbeddedSkeleton { get; init; }
    public required int Version { get; init; }
    public required bool IsAnimated { get; init; }
}
