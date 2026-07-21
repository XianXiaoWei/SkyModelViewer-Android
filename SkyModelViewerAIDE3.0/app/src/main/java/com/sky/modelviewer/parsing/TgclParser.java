package com.sky.modelviewer.parsing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * TGCL (Objects.level.bin) parser — EXACT port of HTML TGCL class (line 35222-35281).
 *
 * Data structure mirrors HTML exactly:
 *   - types: [[fc, th, cum], ...] + gap type
 *   - typeNames[0]='Transform', typeNames[i>0]=nameAt(types[i-1][1])
 *   - fieldrecs: [[sub, nameOff, sz, col3], ...]
 *   - nodes: [{type, name, fields}]
 *   - fields: [{kind, name, ...}] where kind = 'pod'|'str'|'clump'|'arr'
 *
 * This ensures shaderParams extraction (shaderTex/shaderVec) matches HTML exactly.
 */
public class TgclParser {

    // === Field kinds (matching HTML) ===
    public static final int SUB_POD = 0;
    public static final int SUB_STR = 1;
    public static final int SUB_CLUMP = 2;
    public static final int SUB_ARR = 3;

    // === Data structures ===

    /** A field in a node record — mirrors HTML {kind, name, ...} */
    public static class Field {
        public int kind;       // SUB_POD / SUB_STR / SUB_CLUMP / SUB_ARR
        public String name;
        public int sz;         // for pod: byte size
        public byte[] bytes;   // for pod: raw bytes
        public String strValue; // for str: string value
        public int clumpValue; // for clump: u32 value
        public int elemType;   // for arr: element type index (0xFFFFFFFF = raw)
        public List<Field[]> elems; // for arr: each element is Field[] (sub-record)
        public List<Integer> rawElems; // for arr with raw: u32 values
    }

    // === Backward-compatible structures (for editor/writer/topology) ===

    public static class ClassDef {
        public int classPropertyNameOffset;
        public int classPropertyStartingIndex;
        public int classPropertyCount;
        public String className = "";
    }

    public static class PropertyDef {
        public int propertyType;
        public int propertyNameOffset;
        public int objectByteSize;
        public int arrayIndex;
        public String propertyName = "";
    }

    /** A parsed node — mirrors HTML {type, name, fields} */
    public static class BstNode {
        public int type;       // type index
        public String name;
        public List<Field> fields = new ArrayList<>();
        // Convenience: classIndex for backward compatibility
        public int classIndex;
        // Backward compat: properties Map (built from fields during parse)
        public java.util.Map<String, Object> properties = new java.util.HashMap<>();
    }

    public static class TgclFile {
        public int version;
        public List<int[]> types = new ArrayList<>();      // [fc, th, cum]
        public List<String> typeNames = new ArrayList<>();
        public List<int[]> fieldrecs = new ArrayList<>();   // [sub, nameOff, sz, col3]
        public List<BstNode> nodes = new ArrayList<>();
        // Backward compat
        public List<String> classNames = new ArrayList<>();
        public int memorySize;
        // Integrity check (HTML line 35251-35253)
        public int parseEnd;
        public boolean eofOk;
        // Backward compat: class/property definitions for editor
        public List<ClassDef> classes = new ArrayList<>();
        public List<PropertyDef> allProperties = new ArrayList<>();
        public List<List<PropertyDef>> propertiesByClass = new ArrayList<>();
    }

    // === Parser ===

    private byte[] raw;
    private ByteBuffer dv;
    private TgclFile result;
    private byte[] pool;
    private int dataOff;
    private List<int[]> types;
    private List<int[]> fieldrecs;
    private List<Field[]> typeFieldsCache;

    public TgclFile parse(byte[] data) {
        raw = data;
        dv = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        result = new TgclFile();
        _parse();
        return result;
    }

    /**
     * Backward-compat stub: old parser resolved clump references post-parse.
     * New parser resolves everything inline during parse, so this is a no-op.
     */
    public void resolveClumpReferences() {
        // No-op: clump fields are already populated during parse()
    }

    private int u32(int o) {
        return dv.getInt(o);
    }

    private String nameAt(int off) {
        if (off < 0 || off >= pool.length) return "";
        int e = off;
        while (e < pool.length && pool[e] != 0) e++;
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < e; i++) sb.append((char)(pool[i] & 0xFF));
        return sb.toString();
    }

    private void _parse() {
        // Magic check (HTML line 35236)
        if (raw[0] != 0x54 || raw[1] != 0x47 || raw[2] != 0x43 || raw[3] != 0x4C) {
            throw new RuntimeException("Not a TGCL file");
        }
        result.version = u32(4);

        // Header: 11 u32 from offset 8 (HTML line 35238)
        int[] header = new int[11];
        for (int i = 0; i < 11; i++) header[i] = u32(8 + i * 4);
        result.memorySize = header[3]; // objectPtrCount

        // Types: from offset 52, each 12 bytes [fc, th, cum] (HTML line 35239-35241)
        int o = 52, run = 0;
        types = new ArrayList<>();
        while (true) {
            int fc = u32(o), th = u32(o + 4), cum = u32(o + 8);
            if (cum != run + fc) break;
            run += fc;
            types.add(new int[]{fc, th, cum});
            o += 12;
        }
        int gap = u32(o);
        types.add(new int[]{gap, 0, run + gap});
        result.types = types;

        // Offsets (HTML line 35242)
        int fieldRecOff = header[5];
        int nameOff = header[6];
        dataOff = header[7];

        // Field records: [sub, nameOff, sz, col3] each 16 bytes (HTML line 35243-35244)
        int nrec = (nameOff - fieldRecOff) / 16;
        fieldrecs = new ArrayList<>();
        for (int i = 0; i < nrec; i++) {
            int b = fieldRecOff + i * 16;
            fieldrecs.add(new int[]{u32(b), u32(b + 4), u32(b + 8), u32(b + 12)});
        }
        result.fieldrecs = fieldrecs;

        // Pool (name region) — EXACT match to HTML line 35245:
        //   this.pool = d.subarray(this.nameOff, this.dataOff);
        // When nameOff >= dataOff, HTML produces empty pool (subarray returns empty).
        // Java must match: only use [nameOff, dataOff) range.
        int poolLen = (dataOff > nameOff) ? (dataOff - nameOff) : 0;
        pool = new byte[poolLen];
        if (poolLen > 0) {
            System.arraycopy(raw, nameOff, pool, 0, poolLen);
        }

        // Type names (HTML line 35246-35247)
        // typeNames[0]='Transform', typeNames[i>0]=nameAt(types[i-1][1])
        for (int i = 0; i < types.size(); i++) {
            result.typeNames.add(i == 0 ? "Transform" : nameAt(types.get(i - 1)[1]));
        }
        result.classNames = result.typeNames;

        // Type fields cache
        typeFieldsCache = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) typeFieldsCache.add(null);

        // Nodes (HTML line 35248-35249)
        int p = dataOff;
        while (p < raw.length) {
            int[] readResult = readNode(p);
            if (readResult == null) break;
            p = readResult[0];
        }
        // HTML line 35251-35253: parseEnd must equal raw.length (integrity check)
        result.parseEnd = p;
        result.eofOk = (p == raw.length);
        android.util.Log.d("TgclParser", "parse: nodes=" + result.nodes.size() +
            " types=" + types.size() + " fieldrecs=" + fieldrecs.size() +
            " parseEnd=" + p + " rawLen=" + raw.length +
            " eofOk=" + result.eofOk +
            " dataOff=" + dataOff + " nameOff=" + u32(8 + 6 * 4) + " fieldRecOff=" + u32(8 + 5 * 4));

        // Build backward-compatible structures for editor/writer/topology
        buildBackwardCompat();
    }

    /**
     * Build backward-compatible ClassDef/PropertyDef/properties Map from
     * the new types/fieldrecs/fields structures, so existing editor code works unchanged.
     */
    private void buildBackwardCompat() {
        int fieldRecOff = u32(8 + 5 * 4); // header[5]
        int nameOff = u32(8 + 6 * 4);     // header[6]

        // Build classes (ClassDef) and propertiesByClass
        for (int i = 0; i < types.size(); i++) {
            int[] t = types.get(i); // [fc, th, cum]
            ClassDef cls = new ClassDef();
            cls.classPropertyCount = t[0];
            cls.classPropertyNameOffset = t[1];
            cls.classPropertyStartingIndex = i > 0 ? types.get(i - 1)[2] : 0;
            cls.className = i == 0 ? "Transform" : nameAt(types.get(i - 1)[1]);
            result.classes.add(cls);

            List<PropertyDef> props = new ArrayList<>();
            int prev = i > 0 ? types.get(i - 1)[2] : 0;
            for (int j = prev; j < t[2]; j++) {
                int[] fr = fieldrecs.get(j);
                PropertyDef pd = new PropertyDef();
                pd.propertyType = fr[0];   // sub
                pd.propertyNameOffset = fr[1];
                pd.objectByteSize = fr[2];  // sz
                pd.arrayIndex = fr[3];      // col3
                pd.propertyName = nameAt(fr[1]);
                props.add(pd);
                result.allProperties.add(pd);
            }
            result.propertiesByClass.add(props);
        }

        // Build properties Map for each node from its fields
        for (BstNode nd : result.nodes) {
            buildPropertiesMap(nd, nd.fields);
        }
    }

    /** Recursively build a properties Map from Field list (for backward compat) */
    @SuppressWarnings("unchecked")
    private void buildPropertiesMap(BstNode nd, List<Field> fields) {
        for (Field f : fields) {
            switch (f.kind) {
                case SUB_POD:
                    if (f.sz == 1 && f.bytes != null) {
                        nd.properties.put(f.name, f.bytes[0] != 0);
                    } else if (f.sz == 2 && f.bytes != null) {
                        ByteBuffer bb = ByteBuffer.wrap(f.bytes).order(ByteOrder.LITTLE_ENDIAN);
                        nd.properties.put(f.name, String.valueOf(bb.getShort() & 0xFFFF));
                    } else if (f.sz == 4 && f.bytes != null) {
                        ByteBuffer bb = ByteBuffer.wrap(f.bytes).order(ByteOrder.LITTLE_ENDIAN);
                        int val = bb.getInt(0);
                        float fval = Float.intBitsToFloat(val);
                        if (fval == 0f || fval == -0f || Float.isNaN(fval)) {
                            nd.properties.put(f.name, String.valueOf(val));
                        } else {
                            nd.properties.put(f.name, String.format("%.6f", fval));
                        }
                    } else if (f.sz == 16 && f.bytes != null) {
                        float[] vec = new float[4];
                        ByteBuffer bb = ByteBuffer.wrap(f.bytes).order(ByteOrder.LITTLE_ENDIAN);
                        for (int k = 0; k < 4; k++) vec[k] = bb.getFloat(k * 4);
                        nd.properties.put(f.name, vec);
                    } else if (f.sz == 64 && f.bytes != null) {
                        float[] mat = new float[16];
                        ByteBuffer bb = ByteBuffer.wrap(f.bytes).order(ByteOrder.LITTLE_ENDIAN);
                        for (int k = 0; k < 16; k++) mat[k] = bb.getFloat(k * 4);
                        nd.properties.put(f.name, mat);
                    } else if (f.bytes != null) {
                        StringBuilder hex = new StringBuilder();
                        for (byte b : f.bytes) hex.append(String.format("%02x", b));
                        nd.properties.put(f.name, hex.toString());
                    }
                    break;
                case SUB_STR:
                    nd.properties.put(f.name, f.strValue != null ? f.strValue : "");
                    break;
                case SUB_CLUMP:
                    nd.properties.put("[CLUMP]" + f.name, String.valueOf(f.clumpValue));
                    break;
                case SUB_ARR:
                    if (f.elems != null) {
                        List<java.util.Map<String, Object>> arr = new ArrayList<>();
                        for (Field[] subFields : f.elems) {
                            java.util.Map<String, Object> elem = new java.util.HashMap<>();
                            for (Field sf : subFields) {
                                switch (sf.kind) {
                                    case SUB_STR:
                                        elem.put(sf.name, sf.strValue != null ? sf.strValue : "");
                                        break;
                                    case SUB_POD:
                                        if (sf.sz == 16 && sf.bytes != null) {
                                            float[] vec = new float[4];
                                            ByteBuffer bb = ByteBuffer.wrap(sf.bytes).order(ByteOrder.LITTLE_ENDIAN);
                                            for (int k = 0; k < 4; k++) vec[k] = bb.getFloat(k * 4);
                                            elem.put(sf.name, vec);
                                        } else if (sf.sz == 4 && sf.bytes != null) {
                                            ByteBuffer bb = ByteBuffer.wrap(sf.bytes).order(ByteOrder.LITTLE_ENDIAN);
                                            elem.put(sf.name, String.valueOf(bb.getInt(0)));
                                        } else if (sf.bytes != null) {
                                            elem.put(sf.name, sf.bytes);
                                        }
                                        break;
                                    case SUB_CLUMP:
                                        elem.put("[CLUMP]" + sf.name, String.valueOf(sf.clumpValue));
                                        break;
                                }
                            }
                            arr.add(elem);
                        }
                        nd.properties.put(f.name, arr);
                    } else if (f.rawElems != null) {
                        java.util.Map<String, Object> arrObj = new java.util.HashMap<>();
                        arrObj.put("Num", String.valueOf(f.rawElems.size()));
                        List<String> clumpData = new ArrayList<>();
                        for (int v : f.rawElems) clumpData.add(String.valueOf(v));
                        arrObj.put("[CLUMP]data", clumpData);
                        nd.properties.put(f.name, arrObj);
                    }
                    break;
            }
        }
    }

    /** typeFields(ti) — returns field definitions for type ti (HTML line 35254-35258) */
    private int[][] typeFields(int ti) {
        if (ti < 0 || ti >= types.size()) return new int[0][];
        int prev = ti > 0 ? types.get(ti - 1)[2] : 0;
        int end = types.get(ti)[2];
        int count = end - prev;
        int[][] r = new int[count][];
        for (int j = 0; j < count; j++) {
            r[j] = fieldrecs.get(prev + j);
        }
        return r;
    }

    /** Read a record at offset o for type ti (HTML _readRecord line 35260-35274) */
    private Object[] readRecord(int o, int ti) {
        int[][] fields = typeFields(ti);
        List<Field> vals = new ArrayList<>(fields.length);

        for (int fi = 0; fi < fields.length; fi++) {
            int[] f = fields[fi];
            int sub = f[0];
            String fname = nameAt(f[1]);
            int sz = f[2];
            int col3 = f[3];

            Field fld = new Field();
            fld.kind = sub;
            fld.name = fname;

            if (sub == SUB_POD) {
                fld.sz = sz;
                fld.bytes = new byte[sz];
                System.arraycopy(raw, o, fld.bytes, 0, sz);
                o += sz;
            } else if (sub == SUB_STR) {
                int e = o;
                while (e < raw.length && raw[e] != 0) e++;
                StringBuilder sb = new StringBuilder();
                for (int i = o; i < e; i++) sb.append((char)(raw[i] & 0xFF));
                fld.strValue = sb.toString();
                o = e + 1;
            } else if (sub == SUB_CLUMP) {
                fld.clumpValue = u32(o);
                o += 4;
            } else if (sub == SUB_ARR) {
                int cnt = u32(o);
                o += 4;
                if (col3 == 0xFFFFFFFF) {
                    // Raw clump array (HTML line 35269)
                    fld.elemType = col3;
                    fld.rawElems = new ArrayList<>(cnt);
                    for (int k = 0; k < cnt; k++) {
                        fld.rawElems.add(u32(o));
                        o += 4;
                    }
                } else {
                    // Array of sub-records (HTML line 35270)
                    fld.elemType = col3;
                    fld.elems = new ArrayList<>(cnt);
                    for (int k = 0; k < cnt; k++) {
                        Object[] rr = readRecord(o, col3);
                        @SuppressWarnings("unchecked")
                        List<Field> subVals = (List<Field>) rr[0];
                        fld.elems.add(subVals.toArray(new Field[0]));
                        o = (Integer) rr[1];
                    }
                }
            } else {
                throw new RuntimeException("Unknown sub type: " + sub);
            }
            vals.add(fld);
        }
        return new Object[]{vals, o};
    }

    /** Read a node at offset o (HTML _readNode line 35276-35280) */
    private int[] readNode(int o) {
        if (o + 4 > raw.length) return null;
        int tid = u32(o);
        o += 4;

        // Read null-terminated name
        int e = o;
        while (e < raw.length && raw[e] != 0) e++;
        StringBuilder name = new StringBuilder();
        for (int i = o; i < e; i++) name.append((char)(raw[i] & 0xFF));
        o = e + 1;

        // Read record
        Object[] rr = readRecord(o, tid);
        @SuppressWarnings("unchecked")
        List<Field> fields = (List<Field>) rr[0];
        int newO = (Integer) rr[1];

        BstNode node = new BstNode();
        node.type = tid;
        node.classIndex = tid;
        node.name = name.toString();
        node.fields = fields;
        result.nodes.add(node);

        return new int[]{newO};
    }

    // === Convenience accessors (matching HTML helper functions) ===

    /** nodeField(nd, name) — find field by name (HTML line 35297-35299) */
    public static Field nodeField(BstNode nd, String name) {
        for (Field f : nd.fields) {
            if (name.equals(f.name)) return f;
        }
        return null;
    }

    /** nodeTransform(nd) — extract 64-byte transform as 16 floats (HTML line 35284-35295) */
    public static float[] nodeTransform(BstNode nd) {
        for (Field f : nd.fields) {
            if (f.kind == SUB_POD && "transform".equals(f.name) && f.sz == 64) {
                float[] m = new float[16];
                ByteBuffer bb = ByteBuffer.wrap(f.bytes).order(ByteOrder.LITTLE_ENDIAN);
                for (int k = 0; k < 16; k++) m[k] = bb.getFloat(k * 4);
                return m;
            }
        }
        return null;
    }

    /** nodeResourceName(nd) — resourceName/mesh/meshName (HTML line 35340-35347) */
    public static String nodeResourceName(BstNode nd) {
        // Match HTML nodeResourceName (line 35966-35972): check kind === 'str'
        // HTML: if (f && f.kind === 'str' && f.value) return f.value;
        for (Field f : nd.fields) {
            if (("resourceName".equals(f.name) || "mesh".equals(f.name) || "meshName".equals(f.name))) {
                String v = getFieldStringValue(f);
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return "";
    }

    /** nodePodU32(nd, name) — read pod field as u32 (HTML line 35349-35354) */
    public static int nodePodU32(BstNode nd, String name) {
        Field f = nodeField(nd, name);
        if (f != null && f.kind == SUB_POD && f.bytes != null && f.bytes.length >= 4) {
            ByteBuffer bb = ByteBuffer.wrap(f.bytes).order(ByteOrder.LITTLE_ENDIAN);
            return bb.getInt(0);
        }
        return 0;
    }

    /** nodePodVec4(nd, name) — read pod field as float[4] (HTML line 35356-35361) */
    public static float[] nodePodVec4(BstNode nd, String name) {
        Field f = nodeField(nd, name);
        if (f != null && f.kind == SUB_POD && f.bytes != null && f.bytes.length >= 16) {
            ByteBuffer bb = ByteBuffer.wrap(f.bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new float[]{bb.getFloat(0), bb.getFloat(4), bb.getFloat(8), bb.getFloat(12)};
        }
        return null;
    }

    /** nodePodBool(nd, name, dflt) — read pod field as bool (HTML line 35363) */
    public static boolean nodePodBool(BstNode nd, String name, boolean dflt) {
        Field f = nodeField(nd, name);
        if (f != null && f.kind == SUB_POD && f.bytes != null && f.bytes.length >= 1) {
            return f.bytes[0] != 0;
        }
        return dflt;
    }

    /**
     * shaderTex(nd, uniform) — extract texValue from shaderParams (HTML line 35929-35942)
     * shaderParams is an arr, each element is Field[] (sub-record),
     * containing {uniformName:str, texValue:str, vecValue:pod}.
     * 
     * EXACT match to HTML: does NOT check field kind, just reads .value
     * (HTML line 35937-35938: if (e[j].name === 'uniformName') un = e[j].value)
     */
    public static String shaderTex(BstNode nd, String uniform) {
        Field sp = nodeField(nd, "shaderParams");
        if (sp == null || sp.kind != SUB_ARR || sp.elems == null) return "";
        for (Field[] e : sp.elems) {
            String un = null, tv = null;
            for (Field fj : e) {
                // Match HTML: no kind check, just read the value
                if ("uniformName".equals(fj.name)) un = getFieldStringValue(fj);
                else if ("texValue".equals(fj.name)) tv = getFieldStringValue(fj);
            }
            if (uniform.equals(un) && tv != null && !tv.isEmpty()) return tv;
        }
        return "";
    }

    /**
     * Get string value from a field regardless of kind (matches HTML .value access).
     * HTML: str→string, clump→number, pod→undefined, arr→undefined
     * Java: str→strValue, clump→String.valueOf(clumpValue), pod→null, arr→null
     */
    private static String getFieldStringValue(Field f) {
        if (f == null) return null;
        if (f.kind == SUB_STR) return f.strValue;
        if (f.kind == SUB_CLUMP) return String.valueOf(f.clumpValue);
        return null;  // pod/arr have no string value in HTML either
    }

    /**
     * shaderVec(nd, uniform) — extract vecValue from shaderParams (HTML line 35946-35962)
     * Returns float[4] or null.
     * EXACT match to HTML: no kind check on uniformName, reads .value
     */
    public static float[] shaderVec(BstNode nd, String uniform) {
        Field sp = nodeField(nd, "shaderParams");
        if (sp == null || sp.kind != SUB_ARR || sp.elems == null) return null;
        for (Field[] e : sp.elems) {
            String un = null;
            float[] vv = null;
            for (Field fj : e) {
                if ("uniformName".equals(fj.name)) un = getFieldStringValue(fj);
                else if ("vecValue".equals(fj.name) && fj.bytes != null && fj.bytes.length >= 16) {
                    ByteBuffer bb = ByteBuffer.wrap(fj.bytes).order(ByteOrder.LITTLE_ENDIAN);
                    vv = new float[]{bb.getFloat(0), bb.getFloat(4), bb.getFloat(8), bb.getFloat(12)};
                }
            }
            if (uniform.equals(un) && vv != null) return vv;
        }
        return null;
    }

    /** getStringProp — convenience for string fields (matches HTML (nodeField(nd,name)||{}).value) */
    public static String getStringProp(BstNode nd, String name) {
        Field f = nodeField(nd, name);
        String v = getFieldStringValue(f);
        return v != null ? v : "";
    }

    /**
     * matParamTex(nd, uniform) — extract tex from LevelMaterial's shaderParams.
     * Ported from HTML matParamTex (line 35996-36005):
     *   LevelMaterial elements use field names 'name'/'tex' (NOT 'uniformName'/'texValue'
     *   which are used by LevelMesh instances). This is critical for materialBstGuid fallback.
     */
    public static String matParamTex(BstNode nd, String uniform) {
        Field sp = nodeField(nd, "shaderParams");
        if (sp == null || sp.kind != SUB_ARR || sp.elems == null) return "";
        for (Field[] e : sp.elems) {
            String un = null, tv = null;
            for (Field s : e) {
                // Match HTML: no kind check, just read .value
                if ("name".equals(s.name)) un = getFieldStringValue(s);
                else if ("tex".equals(s.name)) tv = getFieldStringValue(s);
            }
            if (uniform.equals(un) && tv != null && !tv.isEmpty()) return tv;
        }
        return "";
    }

    /**
     * matParamVec(nd, uniform) — extract vec from LevelMaterial's shaderParams.
     * Ported from HTML matParamVec (line 36007-36020):
     *   LevelMaterial elements use field names 'name'/'vec' (NOT 'uniformName'/'vecValue').
     */
    public static float[] matParamVec(BstNode nd, String uniform) {
        Field sp = nodeField(nd, "shaderParams");
        if (sp == null || sp.kind != SUB_ARR || sp.elems == null) return null;
        for (Field[] e : sp.elems) {
            String un = null;
            float[] vv = null;
            for (Field s : e) {
                if ("name".equals(s.name)) un = getFieldStringValue(s);
                else if ("vec".equals(s.name) && s.bytes != null && s.bytes.length >= 16) {
                    ByteBuffer bb = ByteBuffer.wrap(s.bytes).order(ByteOrder.LITTLE_ENDIAN);
                    vv = new float[]{bb.getFloat(0), bb.getFloat(4), bb.getFloat(8), bb.getFloat(12)};
                }
            }
            if (uniform.equals(un) && vv != null) return vv;
        }
        return null;
    }
}
