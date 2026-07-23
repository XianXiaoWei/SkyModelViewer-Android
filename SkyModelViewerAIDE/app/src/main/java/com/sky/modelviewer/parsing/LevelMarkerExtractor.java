package com.sky.modelviewer.parsing;

import com.sky.modelviewer.parsing.TgclParser.TgclFile;
import com.sky.modelviewer.parsing.TgclParser.BstNode;
import com.sky.modelviewer.parsing.TgclParser.Field;

import java.util.*;

/**
 * 从 Objects.level.bin 提取点位标记。
 * 移植自 HTML extractLevelMarkers (line 35647-35682) + LEVEL_MARKER_DEFS (line 35535-35556)。
 *
 * Uses new TgclParser Field-based API (matching HTML exactly).
 */
public class LevelMarkerExtractor {

    public static class MarkerDef {
        public final String typeName;
        public final String label;
        public final int color;
        public MarkerDef(String typeName, String label, int color) {
            this.typeName = typeName;
            this.label = label;
            this.color = color;
        }
    }

    public static class MarkerPoint {
        public float[] pos;
        public String name;
        public List<String> detail;
    }

    public static class MarkerGroup {
        public String key;
        public String label;
        public int color;
        public List<MarkerPoint> points;
    }

    private static final MarkerDef[] MARKER_DEFS = {
        new MarkerDef("CandleObject", "烛火", 0xFFFF9A3C),
        new MarkerDef("WingBuff", "光翼", 0xFF5FD3FF),
        new MarkerDef("Pickup", "拾取物", 0xFFFFD54A),
        new MarkerDef("PickupEmitter", "蜡堆", 0xFFFFB74A),
        new MarkerDef("MeditationArea", "冥想点", 0xFFB98CFF),
        new MarkerDef("Portal", "传送门", 0xFF00E0C0),
        new MarkerDef("Checkpoint", "存档点", 0xFF7DFF8A),
        new MarkerDef("ConstellationMarker", "星座点", 0xFFFFE680),
        new MarkerDef("MapShrine", "地图石", 0xFFC0C8D0),
        new MarkerDef("Npc", "NPC", 0xFFFF8FD0),
        new MarkerDef("LevelLink", "关卡门", 0xFFA0B4FF),
        new MarkerDef("StarFragment", "星之碎片", 0xFFFFE680),
        new MarkerDef("Collectible", "收集品", 0xFFFFD54A),
        new MarkerDef("Flame", "火焰", 0xFFFF7A3C),
        new MarkerDef("SoundEmitter", "音效点", 0xFF9AD0FF),
        new MarkerDef("DisplayText", "显示文字", 0xFFD0D8E0),
        new MarkerDef("PointLight", "点光源", 0xFFFFEF9E),
        new MarkerDef("ConstellationGate", "星座门", 0xFFC8A8FF),
        new MarkerDef("StreamingCrystal", "回忆水晶", 0xFF8AD0FF),
    };

    private static final java.util.regex.Pattern QUEST_RE =
        java.util.regex.Pattern.compile("Quest", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final int QUEST_COLOR = 0xFF7DFFC4;

    private static final String[] POS_FIELDS = {
        "pos", "position", "worldPos", "worldPosition", "spawnPos",
        "origin", "point", "location", "center", "target", "targetPos"
    };

    public static List<MarkerGroup> extract(TgclFile tgclFile) {
        List<MarkerGroup> groups = new ArrayList<>();
        Set<Integer> knownTids = new HashSet<>();

        // 1. 固定点位类型
        for (MarkerDef def : MARKER_DEFS) {
            int tid = -1;
            for (int i = 0; i < tgclFile.typeNames.size(); i++) {
                if (def.typeName.equals(tgclFile.typeNames.get(i))) { tid = i; break; }
            }
            if (tid < 0) continue;
            knownTids.add(tid);

            List<MarkerPoint> points = new ArrayList<>();
            for (BstNode nd : tgclFile.nodes) {
                if (nd.type != tid) continue;
                float[] p = getNodeMarkerPos(nd);
                if (p != null && (p[0] != 0 || p[1] != 0 || p[2] != 0)) {
                    MarkerPoint pt = new MarkerPoint();
                    pt.pos = p;
                    pt.name = def.label;
                    List<String> detail = extractDetail(def.typeName, nd);
                    if (detail != null && !detail.isEmpty()) pt.detail = detail;
                    points.add(pt);
                }
            }
            if (!points.isEmpty()) {
                MarkerGroup g = new MarkerGroup();
                g.key = def.typeName;
                g.label = def.label;
                g.color = def.color;
                g.points = points;
                groups.add(g);
            }
        }

        // 2. 任务点
        List<MarkerPoint> questPoints = new ArrayList<>();
        for (BstNode nd : tgclFile.nodes) {
            if (knownTids.contains(nd.type)) continue;
            String typeName = nd.type < tgclFile.typeNames.size() ? tgclFile.typeNames.get(nd.type) : "";
            if (!QUEST_RE.matcher(typeName).find()) continue;
            float[] p = getNodeMarkerPos(nd);
            if (p == null || (p[0] == 0 && p[1] == 0 && p[2] == 0)) continue;
            MarkerPoint pt = new MarkerPoint();
            pt.pos = p;
            pt.name = "任务点·" + typeName;
            questPoints.add(pt);
        }
        if (!questPoints.isEmpty()) {
            MarkerGroup g = new MarkerGroup();
            g.key = "Quest";
            g.label = "任务点";
            g.color = QUEST_COLOR;
            g.points = questPoints;
            groups.add(g);
        }

        return groups;
    }

    /**
     * 取点位坐标 — 优先 transform 平移分量，回退 pos/position 等字段。
     * Uses new TgclParser.nodeTransform / nodePodVec4 API.
     */
    private static float[] getNodeMarkerPos(BstNode nd) {
        // 优先: transform 字段的平移分量 (列主序 m[12],m[13],m[14])
        float[] m = TgclParser.nodeTransform(nd);
        if (m != null && m.length >= 16) {
            return new float[]{m[12], m[13], m[14]};
        }
        // 回退: pos/position/worldPos 等字段 (vec3/vec4 pod)
        for (String key : POS_FIELDS) {
            float[] v = TgclParser.nodePodVec4(nd, key);
            if (v != null && v.length >= 3) {
                return new float[]{v[0], v[1], v[2]};
            }
        }
        return null;
    }

    private static List<String> extractDetail(String typeName, BstNode nd) {
        List<String> detail = new ArrayList<>();
        switch (typeName) {
            case "Npc":
                addStringDetail(detail, "nickname", nd);
                addStringDetail(detail, "defName", nd);
                addStringDetail(detail, "body", nd);
                addStringDetail(detail, "hair", nd);
                addStringDetail(detail, "mask", nd);
                addStringDetail(detail, "animPack1", nd);
                addStringDetail(detail, "animPack2", nd);
                addStringDetail(detail, "animPack3", nd);
                addStringDetail(detail, "animPack4", nd);
                break;
            case "StreamingCrystal":
                addStringDetail(detail, "subtitle", nd);
                addStringDetail(detail, "textMessage", nd);
                break;
            case "Collectible":
                addStringDetail(detail, "name", nd);
                addStringDetail(detail, "type", nd);
                break;
            case "DisplayText":
                addStringDetail(detail, "text", nd);
                break;
            case "SoundEmitter":
                addStringDetail(detail, "soundName", nd);
                break;
            case "MeditationArea":
                addStringDetail(detail, "confirmHintText", nd);
                break;
            case "MapShrine":
                addStringDetail(detail, "landmarkId", nd);
                break;
            case "Portal":
                addStringDetail(detail, "texture", nd);
                break;
            case "LevelLink":
                addStringDetail(detail, "linkName", nd);
                addStringDetail(detail, "levelName", nd);
                break;
        }
        return detail;
    }

    private static void addStringDetail(List<String> detail, String prop, BstNode nd) {
        String v = TgclParser.getStringProp(nd, prop);
        if (v != null && !v.isEmpty()) {
            detail.add(prop + ": " + v);
        }
    }
}
