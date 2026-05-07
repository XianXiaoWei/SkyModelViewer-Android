using SkyModel.Core.Models;

namespace SkyModel.Core.Indexing;

public static class SkyInstallScanner
{
    private const string InstallDirectoryName = "Sky Children of the Light";
    private const string ExecutableName = "Sky.exe";
    private const string SteamSkyAppId = "2325290";

    public static ScanResult Scan(string installLocation)
    {
        if (string.IsNullOrWhiteSpace(installLocation) || !Directory.Exists(installLocation))
        {
            throw new DirectoryNotFoundException("Install location does not exist.");
        }

        var installRoot = ResolveInstallRoot(installLocation)
            ?? throw new DirectoryNotFoundException(
                "Select the Sky install location. Expected Sky.exe and data/assets in the selected folder.");

        var assetsRoot = Path.Combine(installRoot, "data", "assets");

        var meshFiles = Directory.EnumerateFiles(assetsRoot, "*.mesh", SearchOption.AllDirectories)
            .Where(path => path.Contains("Data", StringComparison.OrdinalIgnoreCase))
            .OrderBy(path => path, StringComparer.OrdinalIgnoreCase);

        var entries = new List<MeshCatalogEntry>();
        foreach (var meshFile in meshFiles)
        {
            var name = Path.GetFileNameWithoutExtension(meshFile);
            var relative = Path.GetRelativePath(assetsRoot, meshFile).Replace('\\', '/');
            entries.Add(new MeshCatalogEntry
            {
                Name = name,
                FullPath = meshFile,
                RelativePath = relative,
                Category = InferCategory(relative, name),
            });
        }

        return new ScanResult
        {
            InstallRoot = installRoot,
            AssetsRoot = assetsRoot,
            Meshes = entries,
        };
    }

    public static bool IsValidInstallLocation(string installLocation)
    {
        return ResolveInstallRoot(installLocation) is not null;
    }

    public static string? FindDefaultInstallLocation()
    {
        foreach (var candidate in EnumerateDefaultInstallCandidates())
        {
            var installRoot = ResolveInstallRoot(candidate);
            if (installRoot is not null)
            {
                return installRoot;
            }
        }

        return null;
    }

    private static string? ResolveInstallRoot(string installLocation)
    {
        string fullPath;
        try
        {
            fullPath = Path.GetFullPath(installLocation);
        }
        catch
        {
            return null;
        }

        if (IsInstallRoot(fullPath))
        {
            return fullPath;
        }

        var nested = Path.Combine(fullPath, InstallDirectoryName);
        return IsInstallRoot(nested) ? nested : null;
    }

    private static bool IsInstallRoot(string path)
    {
        return Directory.Exists(path)
            && File.Exists(Path.Combine(path, ExecutableName))
            && Directory.Exists(Path.Combine(path, "data", "assets"));
    }

    private static IEnumerable<string> EnumerateDefaultInstallCandidates()
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var steamRoot in EnumerateSteamRoots())
        {
            foreach (var candidate in EnumerateSteamInstallCandidates(steamRoot))
            {
                if (seen.Add(candidate))
                {
                    yield return candidate;
                }
            }
        }
    }

    private static IEnumerable<string> EnumerateSteamRoots()
    {
        foreach (var candidate in new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Steam"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Steam"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Steam"),
        })
        {
            if (!string.IsNullOrWhiteSpace(candidate) && Directory.Exists(candidate))
            {
                yield return candidate;
            }
        }
    }

    private static IEnumerable<string> EnumerateSteamInstallCandidates(string steamRoot)
    {
        foreach (var libraryRoot in EnumerateSteamLibraryRoots(steamRoot))
        {
            var steamApps = Path.Combine(libraryRoot, "steamapps");
            var common = Path.Combine(steamApps, "common");
            yield return Path.Combine(common, InstallDirectoryName);

            var skyManifest = Path.Combine(steamApps, $"appmanifest_{SteamSkyAppId}.acf");
            foreach (var candidate in EnumerateManifestInstallCandidates(common, skyManifest))
            {
                yield return candidate;
            }

            if (!Directory.Exists(steamApps))
            {
                continue;
            }

            foreach (var manifest in EnumerateFilesSafe(steamApps, "appmanifest_*.acf"))
            {
                foreach (var candidate in EnumerateManifestInstallCandidates(common, manifest))
                {
                    yield return candidate;
                }
            }
        }
    }

    private static IEnumerable<string> EnumerateSteamLibraryRoots(string steamRoot)
    {
        yield return steamRoot;

        var libraryFolders = Path.Combine(steamRoot, "steamapps", "libraryfolders.vdf");
        if (!File.Exists(libraryFolders))
        {
            yield break;
        }

        foreach (var line in ReadLinesSafe(libraryFolders))
        {
            var path = ReadVdfLineValue(line, "path");
            if (!string.IsNullOrWhiteSpace(path) && Directory.Exists(path))
            {
                yield return path;
            }
        }
    }

    private static IEnumerable<string> EnumerateManifestInstallCandidates(string commonRoot, string manifestPath)
    {
        if (!File.Exists(manifestPath))
        {
            yield break;
        }

        var installDir = string.Empty;
        var name = string.Empty;
        foreach (var line in ReadLinesSafe(manifestPath))
        {
            installDir = ReadVdfLineValue(line, "installdir") ?? installDir;
            name = ReadVdfLineValue(line, "name") ?? name;
        }

        if (string.IsNullOrWhiteSpace(installDir))
        {
            yield break;
        }

        if (installDir.Equals(InstallDirectoryName, StringComparison.OrdinalIgnoreCase)
            || name.Equals("Sky: Children of the Light", StringComparison.OrdinalIgnoreCase)
            || name.Equals(InstallDirectoryName, StringComparison.OrdinalIgnoreCase))
        {
            yield return Path.Combine(commonRoot, installDir);
        }
    }

    private static string? ReadVdfLineValue(string line, string key)
    {
        var trimmed = line.Trim();
        var quotedKey = $"\"{key}\"";
        if (!trimmed.StartsWith(quotedKey, StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        var remainder = trimmed[quotedKey.Length..].Trim();
        if (!remainder.StartsWith('"'))
        {
            return null;
        }

        var end = remainder.IndexOf('"', 1);
        if (end <= 1)
        {
            return null;
        }

        return remainder[1..end].Replace(@"\\", @"\");
    }

    private static IEnumerable<string> EnumerateFilesSafe(string path, string searchPattern)
    {
        try
        {
            return Directory.EnumerateFiles(path, searchPattern, SearchOption.TopDirectoryOnly).ToList();
        }
        catch
        {
            return [];
        }
    }

    private static IEnumerable<string> ReadLinesSafe(string path)
    {
        try
        {
            return File.ReadLines(path).ToList();
        }
        catch
        {
            return [];
        }
    }

    private static string InferCategory(string relativePath, string meshName)
    {
        var lowerPath = relativePath.ToLowerInvariant();
        var lowerName = meshName.ToLowerInvariant();

        if (lowerName.StartsWith("body_") || lowerName.StartsWith("hair_") || lowerName.StartsWith("cape_"))
        {
            return "Character";
        }

        if (lowerName.StartsWith("char") || lowerPath.Contains("/meshes/data/meshes/bin/"))
        {
            return "Character";
        }

        if (lowerPath.Contains("/levels/"))
        {
            return "Level";
        }

        if (lowerName.StartsWith("prop_") || lowerName.StartsWith("furn_") || lowerName.StartsWith("bonfire"))
        {
            return "Prop";
        }

        return "Other";
    }
}
