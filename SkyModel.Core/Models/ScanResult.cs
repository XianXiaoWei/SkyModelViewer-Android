namespace SkyModel.Core.Models;

public sealed class ScanResult
{
    public required string InstallRoot { get; init; }
    public required string AssetsRoot { get; init; }
    public required List<MeshCatalogEntry> Meshes { get; init; }
}
