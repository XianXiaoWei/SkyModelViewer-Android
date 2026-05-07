using System.Diagnostics;
using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace SkyModel.Viewer;

internal static class TextureLoader
{
    private const uint KtxEndianLittle = 0x04030201;
    private const uint GlUnsignedByte = 0x1401;
    private const uint GlRgba = 0x1908;
    private const uint GlRgba8 = 0x8058;
    private const uint GlCompressedRedRgtc1 = 0x8DBB;
    private const uint GlCompressedRgbaBptcUnorm = 0x8E8C;
    private const uint GlCompressedSrgbAlphaBptcUnorm = 0x8E8D;

    private static readonly byte[] KtxIdentifier =
    [
        0xAB, 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A,
    ];

    private static readonly Dictionary<string, ImageSource?> Cache = new(StringComparer.OrdinalIgnoreCase);
    private static readonly object DecoderPythonLock = new();
    private static string? DecoderPython;
    private static bool DecoderPythonResolved;
    private static bool MissingDecoderLogged;

    public static ImageSource? Load(string texturePath)
    {
        if (string.IsNullOrWhiteSpace(texturePath) || !File.Exists(texturePath))
        {
            return null;
        }

        if (Cache.TryGetValue(texturePath, out var cached))
        {
            return cached;
        }

        ImageSource? image = null;
        try
        {
            var ext = Path.GetExtension(texturePath);
            image = ext.Equals(".ktx", StringComparison.OrdinalIgnoreCase)
                ? LoadKtx(texturePath)
                : LoadBitmap(texturePath);
        }
        catch
        {
            image = null;
        }

        Cache[texturePath] = image;
        return image;
    }

    private static ImageSource LoadBitmap(string texturePath)
    {
        var bmp = new BitmapImage();
        bmp.BeginInit();
        bmp.UriSource = new Uri(texturePath, UriKind.Absolute);
        bmp.CacheOption = BitmapCacheOption.OnLoad;
        bmp.EndInit();
        bmp.Freeze();
        return bmp;
    }

    private static ImageSource? LoadKtx(string texturePath)
    {
        var image = ReadKtx(texturePath);
        if (image is null)
        {
            return null;
        }

        byte[]? bgra = image.InternalFormat switch
        {
            GlRgba8 when image.GlType == GlUnsignedByte && image.GlFormat == GlRgba => SwizzleRgbaToBgra(image.Data),
            GlCompressedRgbaBptcUnorm or GlCompressedSrgbAlphaBptcUnorm => DecodeCompressedKtxWithPython(texturePath),
            GlCompressedRedRgtc1 => DecodeCompressedKtxWithPython(texturePath),
            _ => null,
        };

        if (bgra is null || bgra.Length != image.Width * image.Height * 4)
        {
            return null;
        }

        var bitmap = BitmapSource.Create(
            image.Width,
            image.Height,
            96.0,
            96.0,
            PixelFormats.Bgra32,
            null,
            bgra,
            image.Width * 4);
        bitmap.Freeze();
        return bitmap;
    }

    private static KtxImage? ReadKtx(string texturePath)
    {
        var raw = File.ReadAllBytes(texturePath);
        if (raw.Length < 68 || !raw.AsSpan(0, KtxIdentifier.Length).SequenceEqual(KtxIdentifier))
        {
            return null;
        }

        if (ReadUInt32(raw, 12) != KtxEndianLittle)
        {
            return null;
        }

        var glType = ReadUInt32(raw, 16);
        var glFormat = ReadUInt32(raw, 24);
        var internalFormat = ReadUInt32(raw, 28);
        var width = checked((int)ReadUInt32(raw, 36));
        var height = checked((int)ReadUInt32(raw, 40));
        var depth = ReadUInt32(raw, 44);
        var arrayElements = ReadUInt32(raw, 48);
        var faces = ReadUInt32(raw, 52);
        var keyValueBytes = checked((int)ReadUInt32(raw, 60));

        if (width <= 0 || height <= 0 || depth != 0 || arrayElements != 0 || faces != 1)
        {
            return null;
        }

        var imageSizeOffset = 64 + keyValueBytes;
        if (imageSizeOffset + 4 > raw.Length)
        {
            return null;
        }

        var imageSize = checked((int)ReadUInt32(raw, imageSizeOffset));
        var imageOffset = imageSizeOffset + 4;
        if (imageSize < 0 || imageOffset + imageSize > raw.Length)
        {
            return null;
        }

        var data = new byte[imageSize];
        Buffer.BlockCopy(raw, imageOffset, data, 0, imageSize);

        return new KtxImage(width, height, glType, glFormat, internalFormat, data);
    }

    private static byte[] SwizzleRgbaToBgra(byte[] rgba)
    {
        var bgra = new byte[rgba.Length];
        for (var i = 0; i + 3 < rgba.Length; i += 4)
        {
            bgra[i] = rgba[i + 2];
            bgra[i + 1] = rgba[i + 1];
            bgra[i + 2] = rgba[i];
            bgra[i + 3] = rgba[i + 3];
        }

        return bgra;
    }

    private static byte[]? DecodeCompressedKtxWithPython(string texturePath)
    {
        var python = FindDecoderPython();
        if (python is null)
        {
            LogMissingDecoder();
            return null;
        }

        const string script =
            "import struct, sys\n"
            + "import texture2ddecoder\n"
            + "p = sys.argv[1]\n"
            + "b = open(p, 'rb').read()\n"
            + "fmt = struct.unpack_from('<I', b, 28)[0]\n"
            + "w, h = struct.unpack_from('<II', b, 36)\n"
            + "kv = struct.unpack_from('<I', b, 60)[0]\n"
            + "off = 64 + kv\n"
            + "size = struct.unpack_from('<I', b, off)[0]\n"
            + "payload = b[off + 4:off + 4 + size]\n"
            + "if fmt in (0x8E8C, 0x8E8D):\n"
            + "    rgba = texture2ddecoder.decode_bc7(payload, w, h)\n"
            + "elif fmt == 0x8DBB:\n"
            + "    rgba = texture2ddecoder.decode_bc4(payload, w, h)\n"
            + "else:\n"
            + "    raise SystemExit(3)\n"
            + "pixels = bytearray(rgba)\n"
            + "for i in range(0, len(pixels), 4):\n"
            + "    pixels[i], pixels[i + 2] = pixels[i + 2], pixels[i]\n"
            + "sys.stdout.buffer.write(struct.pack('<II', w, h))\n"
            + "sys.stdout.buffer.write(pixels)\n";

        using var process = new Process();
        process.StartInfo = new ProcessStartInfo
        {
            FileName = python,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        process.StartInfo.ArgumentList.Add("-c");
        process.StartInfo.ArgumentList.Add(script);
        process.StartInfo.ArgumentList.Add(texturePath);

        using var output = new MemoryStream();
        process.Start();
        var copyOutput = process.StandardOutput.BaseStream.CopyToAsync(output);
        var readError = process.StandardError.ReadToEndAsync();

        if (!process.WaitForExit(30_000))
        {
            try
            {
                process.Kill(entireProcessTree: true);
            }
            catch
            {
                // Best-effort cleanup; texture load will fall back to solid color.
            }

            return null;
        }

        copyOutput.GetAwaiter().GetResult();
        var error = readError.GetAwaiter().GetResult();

        if (process.ExitCode != 0 || output.Length < 8)
        {
            Program.LogMessage($"Texture decoder failed for {texturePath}: exit={process.ExitCode} error={error.Trim()}");
            return null;
        }

        var decoded = output.ToArray();
        var width = checked((int)ReadUInt32(decoded, 0));
        var height = checked((int)ReadUInt32(decoded, 4));
        var pixelBytes = width * height * 4;
        if (decoded.Length != 8 + pixelBytes)
        {
            return null;
        }

        var bgra = new byte[pixelBytes];
        Buffer.BlockCopy(decoded, 8, bgra, 0, pixelBytes);
        return bgra;
    }

    private static string? FindDecoderPython()
    {
        lock (DecoderPythonLock)
        {
            if (DecoderPythonResolved)
            {
                return DecoderPython;
            }

            foreach (var candidate in EnumerateDecoderPythonCandidates())
            {
                if (CanImportTextureDecoder(candidate))
                {
                    DecoderPython = candidate;
                    DecoderPythonResolved = true;
                    Program.LogMessage($"Using texture decoder Python: {candidate}");
                    return candidate;
                }
            }

            DecoderPythonResolved = true;
            return null;
        }
    }

    private static IEnumerable<string> EnumerateDecoderPythonCandidates()
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var envName in new[] { "SKYMODELVIEWER_TEXTURE_PYTHON", "SKYMODELVIEWER_PYTHON" })
        {
            foreach (var candidate in ExpandPythonPath(Environment.GetEnvironmentVariable(envName)))
            {
                if (seen.Add(candidate))
                {
                    yield return candidate;
                }
            }
        }

        foreach (var start in new[] { AppContext.BaseDirectory, Environment.CurrentDirectory })
        {
            foreach (var root in EnumerateAncestorDirectories(start))
            {
                foreach (var candidate in ExpandPythonPath(Path.Combine(root, ".venv")))
                {
                    if (seen.Add(candidate))
                    {
                        yield return candidate;
                    }
                }

                foreach (var child in EnumerateDirectoriesSafe(root))
                {
                    foreach (var candidate in ExpandPythonPath(Path.Combine(child, ".venv")))
                    {
                        if (seen.Add(candidate))
                        {
                            yield return candidate;
                        }
                    }
                }
            }
        }

        foreach (var command in new[] { "python.exe", "python", "py.exe", "py" })
        {
            if (seen.Add(command))
            {
                yield return command;
            }
        }
    }

    private static IEnumerable<string> ExpandPythonPath(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            yield break;
        }

        var path = value.Trim().Trim('"');
        if (File.Exists(path))
        {
            yield return path;
            yield break;
        }

        if (!Directory.Exists(path))
        {
            yield break;
        }

        foreach (var candidate in new[]
        {
            Path.Combine(path, "Scripts", "python.exe"),
            Path.Combine(path, ".venv", "Scripts", "python.exe"),
            Path.Combine(path, "python.exe"),
        })
        {
            if (File.Exists(candidate))
            {
                yield return candidate;
            }
        }
    }

    private static IEnumerable<string> EnumerateAncestorDirectories(string start)
    {
        var cur = Directory.Exists(start) ? Path.GetFullPath(start) : Path.GetDirectoryName(start);
        while (!string.IsNullOrWhiteSpace(cur))
        {
            yield return cur;
            cur = Directory.GetParent(cur)?.FullName;
        }
    }

    private static IEnumerable<string> EnumerateDirectoriesSafe(string path)
    {
        try
        {
            return Directory.EnumerateDirectories(path).ToList();
        }
        catch
        {
            return [];
        }
    }

    private static bool CanImportTextureDecoder(string python)
    {
        try
        {
            using var process = new Process();
            process.StartInfo = new ProcessStartInfo
            {
                FileName = python,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            process.StartInfo.ArgumentList.Add("-c");
            process.StartInfo.ArgumentList.Add("import texture2ddecoder");

            process.Start();
            if (!process.WaitForExit(5_000))
            {
                try
                {
                    process.Kill(entireProcessTree: true);
                }
                catch
                {
                    // Best-effort cleanup; another candidate may still work.
                }

                return false;
            }

            return process.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }

    private static void LogMissingDecoder()
    {
        if (MissingDecoderLogged)
        {
            return;
        }

        MissingDecoderLogged = true;
        Program.LogMessage("No Python with texture2ddecoder was found; compressed KTX textures cannot be decoded.");
    }

    private static uint ReadUInt32(byte[] data, int offset)
    {
        return BitConverter.ToUInt32(data, offset);
    }

    private sealed record KtxImage(
        int Width,
        int Height,
        uint GlType,
        uint GlFormat,
        uint InternalFormat,
        byte[] Data);
}
