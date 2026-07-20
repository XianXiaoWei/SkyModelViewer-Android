package com.sky.modelviewer.parsing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Extracts event logic (triggers, actions, chains) from a parsed TGCL file.
 *
 * Ported from HTML extractLevelEvents (line 36366-36427) + constant tables
 * (line 36310-36345, 39079-39090).
 *
 * Event nodes: any node whose type name matches /Event|Trigger/i.
 * Each event has: type, typeCn, nodeName, eventName, autoStart, outs[], props[].
 */
public class LevelEventExtractor {

    // === Data structures ===

    public static class EventInfo {
        public List<EventItem> events = new ArrayList<>();
        public int total = 0;
    }

    public static class EventItem {
        public String type;       // original type name (English)
        public String typeCn;     // Chinese type name
        public String nodeName;   // node name in TGCL
        public String eventName;  // event name (from name/eventName/param/behaviorTree)
        public boolean autoStart; // true = auto-start on level load
        public List<EventOut> outs = new ArrayList<>();
        public List<EventProp> props = new ArrayList<>();
    }

    public static class EventOut {
        public String field;       // field name (events, onActive, etc.)
        public String fieldCn;     // Chinese field name
        public String targetType;  // target node type name
        public String targetName;  // target node name
    }

    public static class EventProp {
        public String label;  // Chinese label
        public String value;  // formatted value ("3 秒", "是", "50%")
        public EventProp() {}
        public EventProp(String label, String value) { this.label = label; this.value = value; }
    }

    // === Constant tables (ported from HTML line 36310-36345, 39079-39090) ===

    private static final Map<String, String> EVENT_TYPE_CN = new HashMap<>();
    static {
        EVENT_TYPE_CN.put("FireTriggerAtRandom", "随机触发");
        EVENT_TYPE_CN.put("ScheduledEvent", "计划事件");
        EVENT_TYPE_CN.put("TriggerLevelLink", "触发关卡门");
        EVENT_TYPE_CN.put("TempleEvent", "庙会事件");
        EVENT_TYPE_CN.put("EventSeries", "事件系列");
        EVENT_TYPE_CN.put("SetGlobalEventParameter", "设置全局参数");
        EVENT_TYPE_CN.put("BehaviorTreeEvent", "行为树事件");
        EVENT_TYPE_CN.put("BehaviorEvent", "行为事件");
        EVENT_TYPE_CN.put("SendAnalyticsEvent", "埋点上报");
        EVENT_TYPE_CN.put("OnEventSeries", "事件系列触发");
        EVENT_TYPE_CN.put("ScheduledEventTrigger", "计划事件触发器");
        EVENT_TYPE_CN.put("BeaconEvent", "信标事件");
        EVENT_TYPE_CN.put("ThorNodeTrigger", "雷神节点触发");
        EVENT_TYPE_CN.put("DissolveEvent", "溶解事件");
        EVENT_TYPE_CN.put("DisableVisibilityEvent", "禁用可见性");
        EVENT_TYPE_CN.put("TriggerTeleportBeacon", "触发传送信标");
        EVENT_TYPE_CN.put("TriggerToyEvent", "触发玩具事件");
        EVENT_TYPE_CN.put("StormEventSpawner", "暴风生成器");
    }

    // Event name fields (priority order)
    private static final String[] EVENT_NAME_FIELDS = {
        "name", "eventName", "analyticsEventName", "param", "behaviorTree"
    };

    // Downstream reference fields (trigger chain)
    private static final String[] EVENT_OUT_FIELDS = {
        "events", "onActive", "onInactive", "onFinish", "onClose", "onTap",
        "afterfireOneAtRandomEvents", "shoutMarkers", "beamMeshes", "levelLink",
        "effects", "series", "outTarget"
    };

    // Downstream field name → Chinese
    private static final Map<String, String> EVENT_FIELD_CN = new HashMap<>();
    static {
        EVENT_FIELD_CN.put("events", "触发");
        EVENT_FIELD_CN.put("onActive", "激活时");
        EVENT_FIELD_CN.put("onInactive", "失活时");
        EVENT_FIELD_CN.put("onFinish", "结束时");
        EVENT_FIELD_CN.put("onClose", "关闭时");
        EVENT_FIELD_CN.put("onTap", "点击时");
        EVENT_FIELD_CN.put("afterfireOneAtRandomEvents", "随机后触发");
        EVENT_FIELD_CN.put("shoutMarkers", "喊话点");
        EVENT_FIELD_CN.put("beamMeshes", "光束网格");
        EVENT_FIELD_CN.put("levelLink", "关卡门");
        EVENT_FIELD_CN.put("effects", "特效");
        EVENT_FIELD_CN.put("series", "事件系列");
        EVENT_FIELD_CN.put("outTarget", "输出目标");
    }

    // Downstream target type → Chinese
    private static final Map<String, String> EVENT_TARGET_CN = new HashMap<>();
    static {
        EVENT_TARGET_CN.put("Clump", "节点集合");
        EVENT_TARGET_CN.put("LevelLink", "关卡门");
        EVENT_TARGET_CN.put("CandleObject", "烛火");
        EVENT_TARGET_CN.put("WingBuff", "光翼");
        EVENT_TARGET_CN.put("Npc", "NPC");
        EVENT_TARGET_CN.put("Portal", "传送门");
        EVENT_TARGET_CN.put("LevelMesh", "网格");
        EVENT_TARGET_CN.put("TerrainBlob", "地形块");
    }

    // Readable property definitions: fieldName → {label, type}
    private static final Map<String, String[]> EVENT_PROP_DEFS = new HashMap<>();
    static {
        // type codes: sec, bool, pct, num, str
        EVENT_PROP_DEFS.put("delayMin", new String[]{"最小延迟", "sec"});
        EVENT_PROP_DEFS.put("delayMax", new String[]{"最大延迟", "sec"});
        EVENT_PROP_DEFS.put("delayInSeconds", new String[]{"延迟", "sec"});
        EVENT_PROP_DEFS.put("countdownTime", new String[]{"倒计时", "sec"});
        EVENT_PROP_DEFS.put("secondsActive", new String[]{"持续", "sec"});
        EVENT_PROP_DEFS.put("resetTime", new String[]{"重置时间", "sec"});
        EVENT_PROP_DEFS.put("fireOnce", new String[]{"只触发一次", "bool"});
        EVENT_PROP_DEFS.put("fireRepeatedly", new String[]{"重复触发", "bool"});
        EVENT_PROP_DEFS.put("fireOneAtRandom", new String[]{"随机触发其一", "bool"});
        EVENT_PROP_DEFS.put("avoidRepeats", new String[]{"避免重复", "bool"});
        EVENT_PROP_DEFS.put("keepFiring", new String[]{"持续触发", "bool"});
        EVENT_PROP_DEFS.put("useResetTimer", new String[]{"用重置计时", "bool"});
        EVENT_PROP_DEFS.put("usePlayerSpecificRandomSeed", new String[]{"玩家专属随机种子", "bool"});
        EVENT_PROP_DEFS.put("useNetworkedRandomSeed", new String[]{"联机随机种子", "bool"});
        EVENT_PROP_DEFS.put("activatePercent", new String[]{"激活百分比", "pct"});
        EVENT_PROP_DEFS.put("deactivePercent", new String[]{"失活百分比", "pct"});
        EVENT_PROP_DEFS.put("setPercentEmptyBeforeEvent", new String[]{"事件前清空进度", "bool"});
        EVENT_PROP_DEFS.put("setPercentFullAfterEvent", new String[]{"事件后填满进度", "bool"});
        EVENT_PROP_DEFS.put("minSeriesRequired", new String[]{"所需系列数", "num"});
        EVENT_PROP_DEFS.put("value", new String[]{"参数值", "num"});
        EVENT_PROP_DEFS.put("behaviorTree", new String[]{"行为树", "str"});
        EVENT_PROP_DEFS.put("param", new String[]{"参数名", "str"});
        EVENT_PROP_DEFS.put("networkedRandomSeedEventName", new String[]{"随机种子事件名", "str"});
        EVENT_PROP_DEFS.put("analyticsEventName", new String[]{"埋点事件", "str"});
        EVENT_PROP_DEFS.put("analyticsParam1Name", new String[]{"埋点参数1名", "str"});
    }

    private static final Pattern EVENT_TRIGGER_PATTERN = Pattern.compile("Event|Trigger", Pattern.CASE_INSENSITIVE);

    // === Extraction ===

    /**
     * Extract events from a parsed TGCL file.
     * @param file parsed TgclFile
     * @return EventInfo, never null
     */
    public static EventInfo extract(TgclParser.TgclFile file) {
        EventInfo result = new EventInfo();
        if (file == null || file.nodes == null) return result;

        // Build name → node index map for resolveRef
        Map<String, Integer> nameIndex = new HashMap<>();
        for (int i = 0; i < file.nodes.size(); i++) {
            TgclParser.BstNode n = file.nodes.get(i);
            if (n.name != null) nameIndex.put(n.name, i);
        }

        for (TgclParser.BstNode nd : file.nodes) {
            String typeName = getTypeName(file, nd);
            if (typeName == null || !EVENT_TRIGGER_PATTERN.matcher(typeName).find()) continue;

            EventItem ev = new EventItem();
            ev.type = typeName;
            ev.typeCn = EVENT_TYPE_CN.getOrDefault(typeName, typeName);
            ev.nodeName = nd.name != null ? nd.name : "";

            // Event name: first non-empty string field from EVENT_NAME_FIELDS
            for (String key : EVENT_NAME_FIELDS) {
                TgclParser.Field f = TgclParser.nodeField(nd, key);
                if (f != null && f.kind == TgclParser.SUB_STR && f.strValue != null && !f.strValue.isEmpty()) {
                    ev.eventName = f.strValue;
                    break;
                }
            }
            if (ev.eventName == null) ev.eventName = "";

            // autoStart: 1-byte bool
            TgclParser.Field asf = TgclParser.nodeField(nd, "autoStart");
            ev.autoStart = asf != null && asf.kind == TgclParser.SUB_POD &&
                          asf.bytes != null && asf.bytes.length >= 1 && asf.bytes[0] != 0;

            // Downstream references
            for (TgclParser.Field f : nd.fields) {
                if (!isOutField(f.name)) continue;
                List<Integer> vals = new ArrayList<>();
                if (f.kind == TgclParser.SUB_CLUMP) {
                    vals.add(f.clumpValue);
                } else if (f.kind == TgclParser.SUB_ARR && f.elemType == 0xFFFFFFFF && f.rawElems != null) {
                    vals.addAll(f.rawElems);
                }
                for (int v : vals) {
                    int idx = resolveRef(file, nameIndex, v);
                    if (idx < 0) continue;
                    TgclParser.BstNode tgt = file.nodes.get(idx);
                    EventOut out = new EventOut();
                    out.field = f.name;
                    out.fieldCn = EVENT_FIELD_CN.getOrDefault(f.name, f.name);
                    out.targetType = getTypeName(file, tgt);
                    out.targetName = tgt.name != null ? tgt.name : "";
                    ev.outs.add(out);
                }
            }

            // Readable properties
            for (TgclParser.Field f : nd.fields) {
                String[] def = EVENT_PROP_DEFS.get(f.name);
                if (def == null) continue;
                String label = def[0];
                String type = def[1];

                if ("str".equals(type)) {
                    if (f.kind == TgclParser.SUB_STR && f.strValue != null && !f.strValue.isEmpty()) {
                        ev.props.add(new EventProp(label, f.strValue));
                    }
                } else if ("bool".equals(type)) {
                    if (f.kind == TgclParser.SUB_POD && f.bytes != null && f.bytes.length >= 1 && f.bytes[0] != 0) {
                        ev.props.add(new EventProp(label, "是"));
                    }
                } else {
                    // sec, pct, num — read as f32 or u32
                    float[] fu = podF32U32(f);
                    if (fu == null) continue;
                    float fVal = fu[0];
                    int uVal = (int) fu[1];
                    float num = (Float.isFinite(fVal) && Math.abs(fVal) < 1e6f) ? fVal : uVal;
                    if (num == 0) continue;
                    String value;
                    if ("sec".equals(type)) {
                        value = (num == (int) num ? String.valueOf((int) num) : String.format("%.2f", num)) + " 秒";
                    } else if ("pct".equals(type)) {
                        value = (num == (int) num ? String.valueOf((int) num) : String.format("%.1f", num)) + "%";
                    } else {
                        value = (num == (int) num) ? String.valueOf((int) num) : String.format("%.2f", num);
                    }
                    ev.props.add(new EventProp(label, value));
                }
            }

            result.events.add(ev);
        }
        result.total = result.events.size();
        return result;
    }

    /**
     * Resolve a clump/u32 reference to a node index.
     * Ported from HTML resolveRef (line 36356-36364).
     * Tries: "AutoClump"+v → "BstNode_"+v → v as direct index.
     */
    private static int resolveRef(TgclParser.TgclFile file, Map<String, Integer> nameIndex, int v) {
        if (v == 0xFFFFFFFF) return -1;
        Integer a = nameIndex.get("AutoClump" + v);
        if (a != null) return a;
        Integer b = nameIndex.get("BstNode_" + v);
        if (b != null) return b;
        if (v >= 0 && v < file.nodes.size()) return v;
        return -1;
    }

    /**
     * Read a pod field simultaneously as float and uint32.
     * Ported from HTML podF32U32 (line 36348-36354).
     * @return [floatVal, uintVal] or null if not a pod field
     */
    private static float[] podF32U32(TgclParser.Field f) {
        if (f == null || f.kind != TgclParser.SUB_POD || f.bytes == null || f.bytes.length < 4) return null;
        ByteBuffer bb = ByteBuffer.wrap(f.bytes).order(ByteOrder.LITTLE_ENDIAN);
        float fv = bb.getFloat(0);
        int uv = bb.getInt(0);
        return new float[]{fv, (float)(uv & 0xFFFFFFFFL)};
    }

    private static boolean isOutField(String name) {
        for (String s : EVENT_OUT_FIELDS) if (s.equals(name)) return true;
        return false;
    }

    private static String getTypeName(TgclParser.TgclFile file, TgclParser.BstNode nd) {
        if (nd.type >= 0 && nd.type < file.typeNames.size()) {
            return file.typeNames.get(nd.type);
        }
        return "";
    }

    /**
     * Filter events by search query (matches eventName, type, typeCn).
     * Ported from HTML filterEvents (line 39152).
     */
    public static List<EventItem> filter(EventInfo info, String query) {
        List<EventItem> result = new ArrayList<>();
        if (info == null || info.events == null) return result;
        if (query == null || query.isEmpty()) {
            result.addAll(info.events);
            return result;
        }
        String q = query.toLowerCase();
        for (EventItem ev : info.events) {
            if ((ev.eventName != null && ev.eventName.toLowerCase().contains(q)) ||
                (ev.type != null && ev.type.toLowerCase().contains(q)) ||
                (ev.typeCn != null && ev.typeCn.toLowerCase().contains(q))) {
                result.add(ev);
            }
        }
        return result;
    }

    /**
     * Format events as readable text (for copy/share).
     * Matches HTML copyEventsText() Markdown format.
     */
    public static String toText(EventInfo info) {
        if (info == null || info.events == null || info.events.isEmpty()) return "无事件";
        StringBuilder sb = new StringBuilder();
        sb.append("# 事件逻辑 (").append(info.total).append(")\n\n");
        for (EventItem ev : info.events) {
            sb.append("## ").append(ev.typeCn);
            sb.append(ev.autoStart ? " [自动]" : " [待触发]").append("\n");
            if (ev.eventName != null && !ev.eventName.isEmpty()) {
                sb.append("- 名称: ").append(ev.eventName).append("\n");
            }
            for (EventProp p : ev.props) {
                sb.append("- ").append(p.label).append(": ").append(p.value).append("\n");
            }
            if (!ev.outs.isEmpty()) {
                sb.append("- 下游:\n");
                int limit = Math.min(ev.outs.size(), 12);
                for (int i = 0; i < limit; i++) {
                    EventOut o = ev.outs.get(i);
                    String tcn = EVENT_TARGET_CN.getOrDefault(o.targetType, o.targetType);
                    sb.append("  - ").append(o.fieldCn).append(" → ").append(tcn).append("\n");
                }
                if (ev.outs.size() > 12) {
                    sb.append("  - …共 ").append(ev.outs.size()).append(" 项\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
