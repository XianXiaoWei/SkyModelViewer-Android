namespace SkyModel.Core.Models;

public sealed class SkeletonBone
{
    public required string Name { get; init; }
    public required int ParentIndex { get; init; }
    public required float[] InverseBindMatrix { get; init; }
}
