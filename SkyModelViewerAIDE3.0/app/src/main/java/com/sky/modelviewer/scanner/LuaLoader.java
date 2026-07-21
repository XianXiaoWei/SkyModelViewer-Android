package com.sky.modelviewer.scanner;

import android.util.Log;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads Lua scripts from APK, with automatic .luac fallback for NetEase China client.
 *
 * When code requests a .lua file:
 * 1. Try .lua (global client - plain text)
 * 2. If not found, try .lua.luac (China client - bytecode)
 * 3. If bytecode found, decode it to Lua source automatically
 *
 * No UI buttons - this is purely internal, called by existing code that reads lua files.
 */
public class LuaLoader {

    private static final String TAG = "LuaLoader";
    private static final byte[] LUA_SIGNATURE = {0x1b, 0x4c, 0x75, 0x61}; // \x1bLua

    /**
     * Load a Lua script as text from APK.
     * Automatically falls back to .lua.luac and decodes if needed.
     *
     * @param apkPath path to the APK file
     * @param scriptName script name, e.g. "UIPackedAtlas.lua"
     * @return Lua source text, or null if not found / cannot decode
     */
    public static String loadLuaScript(String apkPath, String scriptName) {
        // findResourceEntry already handles .lua -> .lua.luac fallback
        String entryPath = SkyAssetScanner.findResourceEntry(apkPath, scriptName);
        if (entryPath == null) {
            Log.d(TAG, "Script not found: " + scriptName);
            return null;
        }

        byte[] bytes = SkyAssetScanner.readApkEntry(apkPath, entryPath);
        if (bytes == null) {
            return null;
        }

        // Check if it's bytecode (\x1bLua signature)
        if (isLuacBytecode(bytes)) {
            Log.i(TAG, "Found luac bytecode, decoding: " + entryPath);
            return decodeLuac(bytes, entryPath);
        }

        // Plain text Lua source (global client)
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Check if bytes are Lua bytecode (starts with \x1bLua signature).
     */
    public static boolean isLuacBytecode(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        return bytes[0] == LUA_SIGNATURE[0] &&
               bytes[1] == LUA_SIGNATURE[1] &&
               bytes[2] == LUA_SIGNATURE[2] &&
               bytes[3] == LUA_SIGNATURE[3];
    }

    /**
     * Decode luac bytecode to Lua source using built-in decoder.
     */
    private static String decodeLuac(byte[] bytes, String entryPath) {
        try {
            com.sky.modelviewer.parsing.LuacParser parser = new com.sky.modelviewer.parsing.LuacParser();
            com.sky.modelviewer.parsing.LuacFunction func = parser.parse(bytes);
            com.sky.modelviewer.parsing.LuacDecompiler decompiler = new com.sky.modelviewer.parsing.LuacDecompiler(func, 0);
            java.util.List<String> lines = decompiler.decompile();

            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append("\n");
            }
            Log.i(TAG, "Successfully decoded luac: " + entryPath + " (" + lines.size() + " lines)");
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "Cannot decode luac " + entryPath + ": " + e.getMessage());
            return null;
        }
    }
}
