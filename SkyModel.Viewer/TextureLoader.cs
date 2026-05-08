using BCnEncoder.Decoder;
using BCnEncoder.Shared;
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
            GlCompressedRgbaBptcUnorm or GlCompressedSrgbAlphaBptcUnorm => DecodeCompressedKtx(image),
            GlCompressedRedRgtc1 => DecodeCompressedKtx(image),
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

    private static byte[]? DecodeCompressedKtx(KtxImage image)
    {
        var format = image.InternalFormat switch
        {
            GlCompressedRgbaBptcUnorm or GlCompressedSrgbAlphaBptcUnorm => CompressionFormat.Bc7,
            GlCompressedRedRgtc1 => CompressionFormat.Bc4,
            _ => CompressionFormat.Unknown,
        };

        if (format == CompressionFormat.Unknown)
        {
            return null;
        }

        try
        {
            var pixels = new BcDecoder().DecodeRaw(image.Data, image.Width, image.Height, format);
            if (pixels.Length != image.Width * image.Height)
            {
                return null;
            }

            return RgbaToBgra(pixels);
        }
        catch (Exception ex)
        {
            Program.LogMessage($"Texture decoder failed: format=0x{image.InternalFormat:X} size={image.Width}x{image.Height} error={ex.Message}");
            return null;
        }
    }

    private static byte[] RgbaToBgra(ColorRgba32[] pixels)
    {
        var bgra = new byte[pixels.Length * 4];
        for (var i = 0; i < pixels.Length; i++)
        {
            var pixel = pixels[i];
            var offset = i * 4;
            bgra[offset] = pixel.b;
            bgra[offset + 1] = pixel.g;
            bgra[offset + 2] = pixel.r;
            bgra[offset + 3] = pixel.a;
        }

        return bgra;
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
