using System.Text.Json;
using System.Text.RegularExpressions;
using SkyModel.Core.Models;

namespace SkyModel.Core.Resources;

public static class SkyResourceResolver
{
    private sealed record BinaryString(int Offset, string Text);

    private static readonly Dictionary<string, Dictionary<string, string>> ImageDefsCache = new(StringComparer.OrdinalIgnoreCase);
    private static readonly Dictionary<string, HashSet<string>> ShaderNamesCache = new(StringComparer.OrdinalIgnoreCase);
    private static readonly Dictionary<string, MaterialInfo?> ObjectMaterialCache = new(StringComparer.OrdinalIgnoreCase);

    public static MaterialInfo? ResolveMaterial(string meshPath)
    {
        var meshName = Path.GetFileNameWithoutExtension(meshPath);

        var outfitDefs = FindOutfitDefs(meshPath);
        if (outfitDefs is not null)
        {
            var outfit = LookupOutfitForMesh(outfitDefs, meshName);
            if (outfit is not null)
            {
                var outfitValue = outfit.Value;
                return new MaterialInfo
                {
                    Source = "OutfitDefs",
                    Name = GetString(outfitValue, "name"),
                    Shader = GetString(outfitValue, "shader"),
                    DiffuseTex = GetString(outfitValue, "diffuseTex"),
                    NormTex = GetString(outfitValue, "normTex"),
                    MaskTex = GetString(outfitValue, "maskTex"),
                    AttribTex = GetString(outfitValue, "attribTex"),
                };
            }
        }

        var placeableDefs = FindResourceJson(meshPath, "PlaceableDefs.json");
        if (placeableDefs is not null)
        {
            var placeable = LookupPlaceableMaterial(placeableDefs, meshName);
            if (placeable is not null)
            {
                var placeableValue = placeable.Value;

                return new MaterialInfo
                {
                    Source = "PlaceableDefs",
                    Name = GetString(placeableValue, "name"),
                    Shader = GetString(placeableValue, "shader"),
                    DiffuseTex = GetString(placeableValue, "diffuse1Tex"),
                    Diffuse2Tex = GetString(placeableValue, "diffuse2Tex"),
                    NormTex = GetString(placeableValue, "normTex"),
                    AttribTex = GetString(placeableValue, "attribTex"),
                    EmissionScale = GetFloat(placeableValue, "emission_scale"),
                    NormalScale = GetFloat(placeableValue, "normal_scale", 1.0f),
                };
            }
        }

        var objectMaterial = LookupObjectBinMaterial(meshPath, meshName);
        if (objectMaterial is not null)
        {
            return objectMaterial;
        }

        return LookupImageDefsMaterial(meshPath, meshName);
    }

    public static float? ResolveScale(string meshPath)
    {
        var placeableDefs = FindResourceJson(meshPath, "PlaceableDefs.json");
        if (placeableDefs is null)
        {
            return null;
        }

        return LookupPlaceableScale(placeableDefs, Path.GetFileNameWithoutExtension(meshPath));
    }

    public static string? FindTextureFile(string meshPath, string textureName)
    {
        if (string.IsNullOrWhiteSpace(textureName))
        {
            return null;
        }

        var textureNames = BuildTextureNameCandidates(meshPath, textureName);
        return FindSourceTextureFile(meshPath, textureNames);
    }

    private static string? FindSourceTextureFile(string meshPath, IReadOnlyList<string> textureNames)
    {
        var assetsRoot = FindAssetsRoot(meshPath);
        if (assetsRoot is not null)
        {
            foreach (var dir in EnumerateAssetImageDirs(assetsRoot, meshPath))
            {
                var match = FindTextureInDirectory(dir, textureNames);
                if (match is not null)
                {
                    return match;
                }
            }
        }

        var cur = Path.GetDirectoryName(meshPath);
        while (!string.IsNullOrWhiteSpace(cur))
        {
            foreach (var sub in new[] { "images", "initial" })
            {
                var imagesBase = Path.Combine(cur, sub, "Data", "Images");
                foreach (var dir in new[] { imagesBase, Path.Combine(imagesBase, "Bin", "BC") })
                {
                    if (!Directory.Exists(dir))
                    {
                        continue;
                    }

                    var match = FindTextureInDirectory(dir, textureNames);
                    if (match is not null)
                    {
                        return match;
                    }
                }
            }

            cur = Directory.GetParent(cur)?.FullName;
        }

        return null;
    }

    private static IEnumerable<string> EnumerateAssetImageDirs(string assetsRoot, string meshPath)
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var packageName in EnumeratePreferredAssetPackages(assetsRoot, meshPath))
        {
            var imagesBase = Path.Combine(assetsRoot, packageName, "Data", "Images");
            foreach (var dir in new[] { Path.Combine(imagesBase, "Bin", "BC"), imagesBase })
            {
                if (Directory.Exists(dir) && seen.Add(dir))
                {
                    yield return dir;
                }
            }
        }
    }

    private static IEnumerable<string> EnumeratePreferredAssetPackages(string assetsRoot, string meshPath)
    {
        var preferred = GetAssetPackageName(assetsRoot, meshPath);
        var yielded = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var packageName in new[] { preferred, "images", "initial" })
        {
            if (!string.IsNullOrWhiteSpace(packageName)
                && Directory.Exists(Path.Combine(assetsRoot, packageName))
                && yielded.Add(packageName))
            {
                yield return packageName;
            }
        }

        foreach (var dir in Directory.EnumerateDirectories(assetsRoot))
        {
            var packageName = Path.GetFileName(dir);
            if (yielded.Add(packageName))
            {
                yield return packageName;
            }
        }
    }

    private static string? GetAssetPackageName(string assetsRoot, string meshPath)
    {
        var relative = Path.GetRelativePath(assetsRoot, meshPath);
        if (relative.StartsWith("..", StringComparison.Ordinal)
            || Path.IsPathRooted(relative))
        {
            return null;
        }

        var separator = relative.IndexOf(Path.DirectorySeparatorChar);
        if (separator <= 0)
        {
            return null;
        }

        return relative[..separator];
    }

    private static MaterialInfo? LookupImageDefsMaterial(string meshPath, string meshName)
    {
        var imageDefs = LoadImageDefs(meshPath);
        if (imageDefs.Count == 0)
        {
            return null;
        }

        foreach (var candidate in new[] { meshName, StripMeshVariantSuffix(meshName) })
        {
            if (imageDefs.ContainsKey(candidate))
            {
                return new MaterialInfo
                {
                    Source = "ImageDefs",
                    Name = candidate,
                    Shader = "Mesh",
                    DiffuseTex = candidate,
                };
            }
        }

        return null;
    }

    private static MaterialInfo? LookupObjectBinMaterial(string meshPath, string meshName)
    {
        var assetsRoot = FindAssetsRoot(meshPath);
        if (assetsRoot is null)
        {
            return null;
        }

        var cacheKey = assetsRoot + "|" + meshName;
        if (ObjectMaterialCache.TryGetValue(cacheKey, out var cached))
        {
            return cached;
        }

        var shaderNames = LoadShaderNames(meshPath);
        var needle = System.Text.Encoding.ASCII.GetBytes(meshName);
        MaterialInfo? best = null;
        var bestScore = -1;

        foreach (var levelPath in Directory.EnumerateFiles(assetsRoot, "Objects.level.bin", SearchOption.AllDirectories))
        {
            byte[] raw;
            try
            {
                raw = File.ReadAllBytes(levelPath);
            }
            catch
            {
                continue;
            }

            var search = raw.AsSpan();
            var baseOffset = 0;
            while (search.Length >= needle.Length)
            {
                var rel = search.IndexOf(needle);
                if (rel < 0)
                {
                    break;
                }

                var hit = baseOffset + rel;
                var material = ExtractObjectMaterial(raw, hit, meshName, shaderNames);
                if (material is not null)
                {
                    var score = ScoreObjectMaterial(material, meshName);
                    if (score > bestScore)
                    {
                        best = material;
                        bestScore = score;
                    }
                }

                var next = rel + 1;
                baseOffset += next;
                search = search[next..];
            }

            if (bestScore >= 13)
            {
                break;
            }
        }

        ObjectMaterialCache[cacheKey] = best;
        return best;
    }

    private static MaterialInfo? ExtractObjectMaterial(byte[] raw, int meshOffset, string meshName, HashSet<string> shaderNames)
    {
        var start = Math.Max(0, meshOffset - 0x300);
        var end = Math.Min(raw.Length, meshOffset + 0x80);
        var prior = ExtractAsciiStrings(raw, start, end)
            .Where(s => s.Offset < meshOffset)
            .ToList();
        if (prior.Count == 0)
        {
            return null;
        }

        var parameters = new Dictionary<string, string>(StringComparer.Ordinal);
        var valueOffsets = new HashSet<int>();
        for (var i = 0; i + 1 < prior.Count; i++)
        {
            var current = prior[i];
            if (!current.Text.StartsWith("u_", StringComparison.Ordinal))
            {
                continue;
            }

            var next = prior[i + 1];
            if (next.Text.StartsWith("u_", StringComparison.Ordinal) || next.Text.StartsWith("BstNode_", StringComparison.Ordinal))
            {
                continue;
            }

            parameters[current.Text] = next.Text;
            valueOffsets.Add(next.Offset);
        }

        var diffuse = GetFirstParameter(parameters, "u_diffuse1Tex", "u_diffuseTex", "u_tex", "u_mainTex");
        var normal = GetFirstParameter(parameters, "u_normTex", "u_normalTex");
        var attrib = GetFirstParameter(parameters, "u_attribTex");
        var shader = string.Empty;
        for (var i = prior.Count - 1; i >= 0; i--)
        {
            var candidate = prior[i];
            if (valueOffsets.Contains(candidate.Offset)
                || candidate.Text.StartsWith("u_", StringComparison.Ordinal)
                || candidate.Text.StartsWith("BstNode_", StringComparison.Ordinal))
            {
                continue;
            }

            if (shaderNames.Contains(candidate.Text))
            {
                shader = candidate.Text;
                break;
            }
        }

        if (string.IsNullOrWhiteSpace(diffuse)
            && string.IsNullOrWhiteSpace(normal)
            && string.IsNullOrWhiteSpace(attrib)
            && string.IsNullOrWhiteSpace(shader))
        {
            return null;
        }

        return new MaterialInfo
        {
            Source = "Objects.level.bin",
            Name = meshName,
            Shader = shader,
            DiffuseTex = diffuse ?? string.Empty,
            NormTex = normal ?? string.Empty,
            AttribTex = attrib ?? string.Empty,
        };
    }

    private static List<BinaryString> ExtractAsciiStrings(byte[] raw, int start, int end, int minLength = 3)
    {
        var result = new List<BinaryString>();
        var textStart = -1;
        var chars = new List<char>();

        for (var i = start; i < end; i++)
        {
            var b = raw[i];
            if (b is >= 32 and <= 126)
            {
                if (textStart < 0)
                {
                    textStart = i;
                }

                chars.Add((char)b);
                continue;
            }

            if (textStart >= 0 && chars.Count >= minLength)
            {
                result.Add(new BinaryString(textStart, new string(chars.ToArray())));
            }

            textStart = -1;
            chars.Clear();
        }

        if (textStart >= 0 && chars.Count >= minLength)
        {
            result.Add(new BinaryString(textStart, new string(chars.ToArray())));
        }

        return result;
    }

    private static IReadOnlyList<string> BuildTextureNameCandidates(string meshPath, string textureName)
    {
        var result = new List<string>();
        AddTextureNameVariants(result, textureName);

        var imageDefs = LoadImageDefs(meshPath);
        if (imageDefs.TryGetValue(textureName, out var source))
        {
            AddTextureNameVariants(result, source);
        }

        return result;
    }

    private static string? FindTextureInDirectory(string directory, IReadOnlyList<string> textureNames)
    {
        foreach (var name in textureNames)
        {
            if (string.IsNullOrWhiteSpace(name))
            {
                continue;
            }

            var relativeName = name.Replace('/', Path.DirectorySeparatorChar).Replace('\\', Path.DirectorySeparatorChar);
            var hasExtension = !string.IsNullOrWhiteSpace(Path.GetExtension(relativeName));

            if (hasExtension)
            {
                var exactCandidate = Path.Combine(directory, relativeName);
                if (File.Exists(exactCandidate))
                {
                    return exactCandidate;
                }
            }

            foreach (var ext in new[] { ".ktx", ".png", ".jpg", ".jpeg" })
            {
                var candidate = Path.Combine(directory, relativeName + ext);
                if (File.Exists(candidate))
                {
                    return candidate;
                }
            }
        }

        return null;
    }

    private static void AddTextureNameVariants(List<string> results, string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return;
        }

        var normalized = value.Trim().Trim('"').Replace('\\', '/').TrimStart('/');
        if (string.IsNullOrWhiteSpace(normalized))
        {
            return;
        }

        AddUnique(results, normalized);

        var withoutExtension = Path.ChangeExtension(normalized, null);
        if (!string.IsNullOrWhiteSpace(withoutExtension))
        {
            AddUnique(results, withoutExtension.Replace('\\', '/'));
        }

        var fileBase = Path.GetFileNameWithoutExtension(normalized);
        AddUnique(results, fileBase);

        AddSubpathAfterSegment(results, normalized, "/data/images/bin/bc/");
        AddSubpathAfterSegment(results, normalized, "/data/images/");
        AddSubpathAfterSegment(results, normalized, "/images/bin/bc/");
        AddSubpathAfterSegment(results, normalized, "/images/");

        AddTrimmedPrefix(results, normalized, "data/images/bin/bc/");
        AddTrimmedPrefix(results, normalized, "data/images/");
        AddTrimmedPrefix(results, normalized, "images/bin/bc/");
        AddTrimmedPrefix(results, normalized, "images/");
        AddTrimmedPrefix(results, normalized, "bin/bc/");
    }

    private static void AddTrimmedPrefix(List<string> results, string value, string prefix)
    {
        if (value.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            AddUnique(results, value[prefix.Length..]);
        }
    }

    private static void AddSubpathAfterSegment(List<string> results, string value, string segment)
    {
        var index = value.IndexOf(segment, StringComparison.OrdinalIgnoreCase);
        if (index >= 0)
        {
            AddUnique(results, value[(index + segment.Length)..]);
        }
    }

    private static void AddUnique(List<string> results, string? value)
    {
        if (!string.IsNullOrWhiteSpace(value)
            && !results.Contains(value, StringComparer.OrdinalIgnoreCase))
        {
            results.Add(value);
        }
    }

    private static Dictionary<string, string> LoadImageDefs(string meshPath)
    {
        var path = FindResourceLua(meshPath, "ImageDefs.lua");
        if (path is null)
        {
            return new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        }

        if (ImageDefsCache.TryGetValue(path, out var cached))
        {
            return cached;
        }

        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var text = File.ReadAllText(path);
        foreach (Match match in Regex.Matches(
            text,
            "resourcedef\\s+\"Image\"\\s+\"([^\"]+)\"\\s*\\{[^}]*source\\s*=\\s*\"([^\"]+)\"",
            RegexOptions.Singleline))
        {
            result[match.Groups[1].Value] = match.Groups[2].Value;
        }

        ImageDefsCache[path] = result;
        return result;
    }

    private static HashSet<string> LoadShaderNames(string meshPath)
    {
        var path = FindResourceLua(meshPath, "ShaderDefs.lua");
        if (path is null)
        {
            return new HashSet<string>(StringComparer.Ordinal);
        }

        if (ShaderNamesCache.TryGetValue(path, out var cached))
        {
            return cached;
        }

        var result = new HashSet<string>(StringComparer.Ordinal);
        var text = File.ReadAllText(path);
        foreach (Match match in Regex.Matches(text, "resource\\s+\"Shader\"\\s+\"([^\"]+)\""))
        {
            result.Add(match.Groups[1].Value);
        }

        ShaderNamesCache[path] = result;
        return result;
    }

    private static string? FindAssetsRoot(string meshPath)
    {
        var cur = Path.GetDirectoryName(meshPath);
        while (!string.IsNullOrWhiteSpace(cur))
        {
            if (Path.GetFileName(cur).Equals("assets", StringComparison.OrdinalIgnoreCase))
            {
                return cur;
            }

            cur = Directory.GetParent(cur)?.FullName;
        }

        return null;
    }

    private static string? FindResourceLua(string meshFilePath, string resourceName)
    {
        var cur = Path.GetDirectoryName(meshFilePath);
        while (!string.IsNullOrWhiteSpace(cur))
        {
            var candidate1 = Path.Combine(cur, "initial", "Data", "Resources", resourceName);
            if (File.Exists(candidate1))
            {
                return candidate1;
            }

            var candidate2 = Path.Combine(cur, "Data", "Resources", resourceName);
            if (File.Exists(candidate2))
            {
                return candidate2;
            }

            cur = Directory.GetParent(cur)?.FullName;
        }

        return null;
    }

    private static string StripMeshVariantSuffix(string meshName)
    {
        var name = Regex.Replace(meshName, @"_(StripAnim|CompOcc|ZipPos|ZipUvs|StripNorm|StripUv13|NoOcc|NoCollision).*$", string.Empty);
        return Regex.Replace(name, @"_\d+$", string.Empty);
    }

    private static string? GetFirstParameter(Dictionary<string, string> parameters, params string[] names)
    {
        foreach (var name in names)
        {
            if (parameters.TryGetValue(name, out var value) && !string.IsNullOrWhiteSpace(value))
            {
                return value;
            }
        }

        return null;
    }

    private static int ScoreObjectMaterial(MaterialInfo material, string meshName)
    {
        var baseName = StripMeshVariantSuffix(meshName);
        var score = 0;

        if (!string.IsNullOrWhiteSpace(material.DiffuseTex))
        {
            score += 4;
            if (material.DiffuseTex.Equals(baseName, StringComparison.OrdinalIgnoreCase))
            {
                score += 4;
            }
            else if (material.DiffuseTex.StartsWith(baseName, StringComparison.OrdinalIgnoreCase)
                || baseName.StartsWith(material.DiffuseTex, StringComparison.OrdinalIgnoreCase))
            {
                score += 2;
            }
        }

        if (!string.IsNullOrWhiteSpace(material.NormTex))
        {
            score += 2;
            if (!material.NormTex.Equals("UpNormal", StringComparison.OrdinalIgnoreCase)
                && !material.NormTex.Equals("FlatNormal", StringComparison.OrdinalIgnoreCase)
                && !material.NormTex.Equals("DefaultNormal", StringComparison.OrdinalIgnoreCase))
            {
                score += 2;
            }
        }

        if (!string.IsNullOrWhiteSpace(material.AttribTex))
        {
            score += 2;
        }

        if (!string.IsNullOrWhiteSpace(material.Shader))
        {
            score += 3;
        }

        return score;
    }

    private static string? FindOutfitDefs(string meshFilePath)
    {
        var cur = Path.GetDirectoryName(meshFilePath);
        while (!string.IsNullOrWhiteSpace(cur))
        {
            var candidate1 = Path.Combine(cur, "initial", "Data", "Resources", "OutfitDefs.json");
            if (File.Exists(candidate1))
            {
                return candidate1;
            }

            var candidate2 = Path.Combine(cur, "Data", "Resources", "OutfitDefs.json");
            if (File.Exists(candidate2))
            {
                return candidate2;
            }

            cur = Directory.GetParent(cur)?.FullName;
        }

        return null;
    }

    private static string? FindResourceJson(string meshFilePath, string resourceName)
    {
        var cur = Path.GetDirectoryName(meshFilePath);
        while (!string.IsNullOrWhiteSpace(cur))
        {
            var candidate1 = Path.Combine(cur, "initial", "Data", "Resources", resourceName);
            if (File.Exists(candidate1))
            {
                return candidate1;
            }

            var candidate2 = Path.Combine(cur, "Data", "Resources", resourceName);
            if (File.Exists(candidate2))
            {
                return candidate2;
            }

            cur = Directory.GetParent(cur)?.FullName;
        }

        return null;
    }

    private static JsonElement? LookupOutfitForMesh(string outfitDefsPath, string meshName)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(outfitDefsPath));
        var outfits = doc.RootElement;
        foreach (var entry in outfits.EnumerateArray())
        {
            if (GetString(entry, "mesh").Equals(meshName, StringComparison.Ordinal))
            {
                return entry.Clone();
            }
        }

        var meshLower = meshName.ToLowerInvariant();
        JsonElement? best = null;
        var bestLen = 0;

        foreach (var entry in outfits.EnumerateArray())
        {
            var entryMesh = GetString(entry, "mesh");
            if (string.IsNullOrWhiteSpace(entryMesh))
            {
                continue;
            }

            var em = entryMesh.ToLowerInvariant();
            if (meshLower == em || meshLower.StartsWith(em + "_", StringComparison.Ordinal))
            {
                if (em.Length > bestLen)
                {
                    best = entry.Clone();
                    bestLen = em.Length;
                }
            }
        }

        return best;
    }

    private static JsonElement? LookupPlaceableMaterial(string placeableDefsPath, string meshName)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(placeableDefsPath));
        var root = doc.RootElement;

        var meshLower = meshName.ToLowerInvariant();
        JsonElement? best = null;
        var bestLen = 0;
        foreach (var entry in root.EnumerateArray())
        {
            var em = GetString(entry, "mesh");
            if (string.IsNullOrWhiteSpace(em))
            {
                continue;
            }

            var emLower = em.ToLowerInvariant();
            if (meshLower == emLower
                || meshLower.StartsWith(emLower + "_", StringComparison.Ordinal)
                || meshLower.EndsWith("_" + emLower, StringComparison.Ordinal)
                || meshLower.EndsWith(emLower, StringComparison.Ordinal))
            {
                if (emLower.Length > bestLen)
                {
                    best = entry.Clone();
                    bestLen = emLower.Length;
                }
            }
        }

        return best;
    }

    private static float? LookupPlaceableScale(string placeableDefsPath, string meshName)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(placeableDefsPath));

        var meshLower = meshName.ToLowerInvariant();
        float? best = null;
        var bestLen = 0;

        foreach (var entry in doc.RootElement.EnumerateArray())
        {
            var em = GetString(entry, "mesh");
            if (string.IsNullOrWhiteSpace(em))
            {
                continue;
            }

            var emLower = em.ToLowerInvariant();
            if (meshLower == emLower
                || meshLower.StartsWith(emLower + "_", StringComparison.Ordinal)
                || meshLower.EndsWith("_" + emLower, StringComparison.Ordinal)
                || meshLower.EndsWith(emLower, StringComparison.Ordinal))
            {
                if (emLower.Length <= bestLen)
                {
                    continue;
                }

                if (entry.TryGetProperty("scale", out var scale) && scale.ValueKind == JsonValueKind.Array && scale.GetArrayLength() > 0)
                {
                    best = scale[0].GetSingle();
                    bestLen = emLower.Length;
                }
            }
        }

        return best;
    }

    private static string GetString(JsonElement element, string propertyName)
    {
        if (element.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.String)
        {
            return value.GetString() ?? string.Empty;
        }

        return string.Empty;
    }

    private static float GetFloat(JsonElement element, string propertyName, float defaultValue = 0.0f)
    {
        if (element.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.Number)
        {
            return value.GetSingle();
        }

        return defaultValue;
    }
}
