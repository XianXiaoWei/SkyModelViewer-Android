package com.sky.modelviewer.piano;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * SkyStudio 格式乐谱解析器
 *
 * 解析 SkyStudio 导出的 .txt/.json 文件，文件通常是 UTF-16LE 或 UTF-8 编码。
 * 公开接口:
 *   parseFile(File)           解析单个文件
 *   parseJson(String, String)  解析 JSON 字符串
 *   scanDirectory(File)        扫描目录下所有乐谱
 *
 * 注意: 使用 org.json 全限定名调用，AIDE 兼容（无 lambda、无 try-with-resources、
 *       margin 字段用 setMargins，泛型用完整类型）。
 */
public class SheetParser {

    /** 单个音符：时间和按下的键索引列表（支持多键同按） */
    public static class ParsedNote {
        public int time;
        public List<Integer> keys = new ArrayList<Integer>();
    }

    /** 一首完整乐谱 */
    public static class ParsedSheet {
        public String name = "";
        public int keyCount = 15;
        public List<ParsedNote> notes = new ArrayList<ParsedNote>();
        public boolean failed = false;
        public String fileName = "";
    }

    /**
     * 解析单个乐谱文件
     */
    public static ParsedSheet parseFile(File file) {
        ParsedSheet sheet = new ParsedSheet();
        if (file == null || !file.exists() || !file.isFile()) {
            sheet.failed = true;
            return sheet;
        }
        sheet.fileName = file.getName();
        byte[] buffer = null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            int len = (int) file.length();
            buffer = new byte[len];
            int read = 0;
            while (read < len) {
                int n = fis.read(buffer, read, len - read);
                if (n < 0) break;
                read += n;
            }
        } catch (Exception e) {
            sheet.failed = true;
            if (fis != null) {
                try { fis.close(); } catch (Exception ex) { /* 忽略 */ }
            }
            return sheet;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Exception ex) { /* 忽略 */ }
            }
        }
        if (buffer == null || buffer.length == 0) {
            sheet.failed = true;
            return sheet;
        }
        String json = decodeText(buffer);
        return parseJson(json, file.getName());
    }

    /**
     * 解析 JSON 字符串为乐谱对象
     */
    public static ParsedSheet parseJson(String json, String fileName) {
        ParsedSheet sheet = new ParsedSheet();
        sheet.fileName = fileName == null ? "" : fileName;
        if (json == null) {
            sheet.failed = true;
            return sheet;
        }
        json = json.trim();
        if (json.length() == 0) {
            sheet.failed = true;
            return sheet;
        }
        try {
            org.json.JSONArray arr = null;
            org.json.JSONObject obj = null;
            if (json.startsWith("[")) {
                arr = new org.json.JSONArray(json);
                if (arr.length() > 0) {
                    obj = arr.optJSONObject(0);
                }
            } else if (json.startsWith("{")) {
                obj = new org.json.JSONObject(json);
            }
            if (obj == null) {
                sheet.failed = true;
                return sheet;
            }
            fillSheetFromObject(obj, sheet);
        } catch (Exception e) {
            sheet.failed = true;
        }
        // 按时间升序排序
        Collections.sort(sheet.notes, new Comparator<ParsedNote>() {
            public int compare(ParsedNote a, ParsedNote b) {
                return a.time - b.time;
            }
        });
        return sheet;
    }

    /** 从 JSONObject 填充 ParsedSheet */
    private static void fillSheetFromObject(org.json.JSONObject obj, ParsedSheet sheet) throws Exception {
        if (obj.has("name")) {
            sheet.name = obj.optString("name", "");
        }
        if (obj.has("keyCount")) {
            sheet.keyCount = obj.optInt("keyCount", 15);
        }
        if (!obj.has("songNotes")) {
            return;
        }
        org.json.JSONArray notesArr = obj.getJSONArray("songNotes");
        int len = notesArr.length();
        for (int i = 0; i < len; i++) {
            org.json.JSONObject noteObj = notesArr.optJSONObject(i);
            if (noteObj == null) continue;
            ParsedNote note = new ParsedNote();
            note.time = noteObj.optInt("time", 0);
            String keyStr = noteObj.optString("key", "");
            if (keyStr == null || keyStr.length() == 0) continue;
            // SkyStudio 组合键用 "_" 分隔，如 "1Key0_1Key2"
            String[] parts = keyStr.split("_");
            for (int j = 0; j < parts.length; j++) {
                int idx = parseKeyIndex(parts[j]);
                if (idx >= 0) {
                    note.keys.add(new Integer(idx));
                }
            }
            if (!note.keys.isEmpty()) {
                sheet.notes.add(note);
            }
        }
    }

    /**
     * 解析键索引: "1Key0" -> 0, "1Key14" -> 14
     */
    public static int parseKeyIndex(String key) {
        if (key == null) return -1;
        key = key.trim();
        int keyPos = key.indexOf("Key");
        if (keyPos < 0) {
            // 兼容纯数字
            try {
                return Integer.parseInt(key);
            } catch (Exception e) {
                return -1;
            }
        }
        String numStr = key.substring(keyPos + 3);
        try {
            return Integer.parseInt(numStr);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 扫描目录下所有乐谱文件
     */
    public static List<ParsedSheet> scanDirectory(File dir) {
        List<ParsedSheet> result = new ArrayList<ParsedSheet>();
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return result;
        }
        File[] files = dir.listFiles();
        if (files == null) return result;
        // 按文件名排序
        List<File> sortedFiles = new ArrayList<File>();
        for (int i = 0; i < files.length; i++) {
            sortedFiles.add(files[i]);
        }
        Collections.sort(sortedFiles, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (int i = 0; i < sortedFiles.size(); i++) {
            File f = sortedFiles.get(i);
            if (f.isDirectory()) continue;
            String name = f.getName().toLowerCase();
            if (!name.endsWith(".txt") && !name.endsWith(".json")) continue;
            ParsedSheet sheet = parseFile(f);
            if (!sheet.failed && sheet.notes.size() > 0) {
                result.add(sheet);
            }
        }
        return result;
    }

    /**
     * 解码字节数组为字符串
     * 策略: 检测 BOM -> UTF-16LE 优先 -> UTF-8 回退
     */
    private static String decodeText(byte[] data) {
        if (data == null || data.length == 0) return "";

        // 1. UTF-16LE BOM: FF FE
        if (data.length >= 2) {
            int b0 = data[0] & 0xFF;
            int b1 = data[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                byte[] trimmed = new byte[data.length - 2];
                System.arraycopy(data, 2, trimmed, 0, trimmed.length);
                try {
                    return new String(trimmed, "UTF-16LE");
                } catch (Exception e) { /* 忽略 */ }
            }
            // UTF-16BE BOM: FE FF
            if (b0 == 0xFE && b1 == 0xFF) {
                byte[] trimmed = new byte[data.length - 2];
                System.arraycopy(data, 2, trimmed, 0, trimmed.length);
                try {
                    return new String(trimmed, "UTF-16BE");
                } catch (Exception e) { /* 忽略 */ }
            }
        }
        // 2. UTF-8 BOM: EF BB BF
        if (data.length >= 3) {
            int b0 = data[0] & 0xFF;
            int b1 = data[1] & 0xFF;
            int b2 = data[2] & 0xFF;
            if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) {
                byte[] trimmed = new byte[data.length - 3];
                System.arraycopy(data, 3, trimmed, 0, trimmed.length);
                try {
                    return new String(trimmed, "UTF-8");
                } catch (Exception e) { /* 忽略 */ }
            }
        }
        // 3. 无 BOM: 优先尝试 UTF-16LE（SkyStudio 默认导出）
        if (data.length >= 2 && looksLikeUtf16Le(data)) {
            try {
                String s = new String(data, "UTF-16LE");
                if (s.indexOf('{') >= 0) return s;
            } catch (Exception e) { /* 忽略 */ }
        }
        // 4. 默认 UTF-8
        try {
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return new String(data);
        }
    }

    /** 启发式判断数据是否为 UTF-16LE 编码（ASCII 字符的高字节为 0） */
    private static boolean looksLikeUtf16Le(byte[] data) {
        int sampleSize = Math.min(data.length, 400);
        int total = sampleSize / 2;
        if (total == 0) return false;
        int zeroHigh = 0;
        for (int i = 1; i < sampleSize; i += 2) {
            if (data[i] == 0) zeroHigh++;
        }
        // 一半字符高字节为 0 -> 通常是 ASCII 文本以 UTF-16LE 编码
        return zeroHigh > total / 2;
    }
}
