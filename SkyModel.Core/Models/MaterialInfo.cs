namespace SkyModel.Core.Models;

public sealed class MaterialInfo
{
    public string Name { get; init; } = string.Empty;
    public string Shader { get; init; } = string.Empty;
    public string DiffuseTex { get; init; } = string.Empty;
    public string Diffuse2Tex { get; init; } = string.Empty;
    public string NormTex { get; init; } = string.Empty;
    public string MaskTex { get; init; } = string.Empty;
    public string AttribTex { get; init; } = string.Empty;
    public float[]? BaseRgba { get; init; }
    public float[]? SelfLit { get; init; }
    public float EmissionScale { get; init; }
    public float NormalScale { get; init; } = 1.0f;
    public string? Source { get; init; }
}
