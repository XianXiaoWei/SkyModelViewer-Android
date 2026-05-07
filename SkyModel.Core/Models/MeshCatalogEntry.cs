namespace SkyModel.Core.Models;

public sealed class MeshCatalogEntry
{
    public required string Name { get; init; }
    public required string FullPath { get; init; }
    public required string RelativePath { get; init; }
    public required string Category { get; init; }
}
