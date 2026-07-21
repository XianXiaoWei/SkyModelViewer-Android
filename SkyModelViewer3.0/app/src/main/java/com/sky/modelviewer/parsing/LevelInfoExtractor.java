package com.sky.modelviewer.parsing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts level info (teleport links, quests, music, dialogs, world name) from
 * a parsed TGCL file (Objects.level.bin).
 *
 * Ported from HTML extractLevelInfo (line 36431-36476).
 * Data source: same TgclFile as LevelMeshExtractor — no re-parsing needed.
 */
public class LevelInfoExtractor {

    public static class LevelInfo {
        public List<LevelLink> links = new ArrayList<>();
        public List<String> quests = new ArrayList<>();
        public List<String> music = new ArrayList<>();
        public List<String> dialogs = new ArrayList<>();
        public String worldName = "";
    }

    public static class LevelLink {
        public String link;   // linkName (may be empty for fade transitions)
        public String to;     // target level name
        public String kind;   // "关卡门" or "淡入切换"

        public LevelLink(String link, String to, String kind) {
            this.link = link;
            this.to = to;
            this.kind = kind;
        }
    }

    /**
     * Extract level info from a parsed TGCL file.
     * @param file parsed TgclFile (from TgclParser.parse)
     * @return LevelInfo object, never null (fields may be empty)
     */
    public static LevelInfo extract(TgclParser.TgclFile file) {
        LevelInfo info = new LevelInfo();
        if (file == null || file.nodes == null) return info;

        // Helper: get all nodes of a given type name
        // (matches HTML byType function, line 36436-36441)

        // --- Teleport links ---
        // LevelLink: linkName + levelName → "关卡门"
        // ChangeLevelWithFade: levelName → "淡入切换"
        Set<String> seenLink = new LinkedHashSet<>();
        for (TgclParser.BstNode nd : file.nodes) {
            String typeName = getTypeName(file, nd);
            if ("LevelLink".equals(typeName)) {
                String to = TgclParser.getStringProp(nd, "levelName");
                if (to == null || to.isEmpty()) continue;
                String link = TgclParser.getStringProp(nd, "linkName");
                String key = link + "|" + to;
                if (seenLink.contains(key)) continue;
                seenLink.add(key);
                info.links.add(new LevelLink(link, to, "关卡门"));
            }
        }
        for (TgclParser.BstNode nd : file.nodes) {
            String typeName = getTypeName(file, nd);
            if ("ChangeLevelWithFade".equals(typeName)) {
                String to = TgclParser.getStringProp(nd, "levelName");
                if (to == null || to.isEmpty()) continue;
                String key = "淡入淡出|" + to;
                if (seenLink.contains(key)) continue;
                seenLink.add(key);
                info.links.add(new LevelLink("", to, "淡入切换"));
            }
        }

        // --- Quests ---
        // Any node whose type name contains "Quest" → questName / questDef
        Set<String> questSet = new LinkedHashSet<>();
        for (TgclParser.BstNode nd : file.nodes) {
            String tn = getTypeName(file, nd);
            if (tn == null || !tn.contains("Quest")) continue;
            String q = TgclParser.getStringProp(nd, "questName");
            if (q == null || q.isEmpty()) q = TgclParser.getStringProp(nd, "questDef");
            if (q != null && !q.isEmpty()) questSet.add(q);
        }
        info.quests.addAll(questSet);

        // --- Background music ---
        // PlayMusic.music
        Set<String> musicSet = new LinkedHashSet<>();
        for (TgclParser.BstNode nd : file.nodes) {
            String tn = getTypeName(file, nd);
            if (!"PlayMusic".equals(tn)) continue;
            String m = TgclParser.getStringProp(nd, "music");
            if (m != null && !m.isEmpty()) musicSet.add(m);
        }
        info.music.addAll(musicSet);

        // --- Dialog hints ---
        // DialogHint / DialogHintTimed → text
        Set<String> dialogSet = new LinkedHashSet<>();
        for (TgclParser.BstNode nd : file.nodes) {
            String tn = getTypeName(file, nd);
            if (!"DialogHint".equals(tn) && !"DialogHintTimed".equals(tn)) continue;
            String d = TgclParser.getStringProp(nd, "text");
            if (d != null && !d.isEmpty()) dialogSet.add(d);
        }
        info.dialogs.addAll(dialogSet);

        // --- World name ---
        // LevelParameters.worldName
        for (TgclParser.BstNode nd : file.nodes) {
            String tn = getTypeName(file, nd);
            if (!"LevelParameters".equals(tn)) continue;
            String w = TgclParser.getStringProp(nd, "worldName");
            if (w != null && !w.isEmpty()) info.worldName = w;
            break;
        }

        return info;
    }

    /** Get type name for a node, safely handling index bounds. */
    private static String getTypeName(TgclParser.TgclFile file, TgclParser.BstNode nd) {
        if (nd.type >= 0 && nd.type < file.typeNames.size()) {
            return file.typeNames.get(nd.type);
        }
        return "";
    }

    /** Check if info has any content to display. */
    public static boolean hasContent(LevelInfo info) {
        if (info == null) return false;
        return !info.links.isEmpty() || !info.quests.isEmpty() ||
               !info.music.isEmpty() || !info.dialogs.isEmpty() ||
               (info.worldName != null && !info.worldName.isEmpty());
    }

    /**
     * Format LevelInfo as readable text (for copy/share).
     * Matches HTML copyInfoText() Markdown format.
     */
    public static String toText(LevelInfo info) {
        if (info == null) return "无信息";
        StringBuilder sb = new StringBuilder();
        sb.append("# 关卡信息\n\n");
        if (info.worldName != null && !info.worldName.isEmpty()) {
            sb.append("## 世界\n- ").append(info.worldName).append("\n\n");
        }
        if (!info.links.isEmpty()) {
            sb.append("## 传送连接 (").append(info.links.size()).append(")\n");
            for (LevelLink l : info.links) {
                sb.append("- ");
                if (l.link != null && !l.link.isEmpty()) sb.append(l.link).append(" → ");
                sb.append(l.to).append("（").append(l.kind).append("）\n");
            }
            sb.append("\n");
        }
        if (!info.quests.isEmpty()) {
            sb.append("## 任务 (").append(info.quests.size()).append(")\n");
            for (String q : info.quests) sb.append("- ").append(q).append("\n");
            sb.append("\n");
        }
        if (!info.music.isEmpty()) {
            sb.append("## 背景音乐 (").append(info.music.size()).append(")\n");
            for (String m : info.music) sb.append("- ").append(m).append("\n");
            sb.append("\n");
        }
        if (!info.dialogs.isEmpty()) {
            sb.append("## 对白提示 (").append(info.dialogs.size()).append(")\n");
            for (String d : info.dialogs) sb.append("- ").append(d).append("\n");
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
