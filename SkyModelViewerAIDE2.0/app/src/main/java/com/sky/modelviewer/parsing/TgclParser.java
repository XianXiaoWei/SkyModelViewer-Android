package com.sky.modelviewer.parsing;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TGCL (Objects.level.bin) parser.
 * Ported from ThoseCloudObjects-python by XianXiaoWei.
 *
 * Parses the binary BST format into a tree of TgclNode objects,
 * each containing typed properties that can be inspected and edited.
 */
public class TgclParser {

    // === Data structures ===

    public static class TgclFile {
        public int version;
        public int memorySize;
        public List<ClassDef> classes = new ArrayList<>();
        public List<PropertyDef> allProperties = new ArrayList<>();
        public List<List<PropertyDef>> propertiesByClass = new ArrayList<>();
        public List<String> propertyNames = new ArrayList<>();
        public List<String> classNames = new ArrayList<>();
        public List<BstNode> nodes = new ArrayList<>();
    }

    public static class ClassDef {
        public int classPropertyNameOffset;
        public int classPropertyStartingIndex;
        public int classPropertyCount;
        public String className = "";
    }

    public static class PropertyDef {
        public int propertyType;     // 0=Value, 1=String, 2=Clump, 3=Array
        public int propertyNameOffset;
        public int objectByteSize;
        public int arrayIndex;       // 0xFFFFFFFF = N/A
        public String propertyName = "";
    }

    public static class BstNode {
        public int classIndex;
        public String name;
        public Map<String, Object> properties = new HashMap<>();  // propertyName -> value
        public int dataOffset;  // offset in file where object data starts
    }

    // Property value types
    public static class PropertyValue {
        public int type;       // 0=Value, 1=String, 2=Clump, 3=Array
        public int byteSize;
        public Object value;   // Boolean, Float, Integer, String, float[], List<PropertyValue>, List<Map>, etc.
        public String propertyName;

        public PropertyValue(String name, int type, int byteSize) {
            this.propertyName = name;
            this.type = type;
            this.byteSize = byteSize;
        }
    }

    // === Parser ===

    private ByteBuffer buf;
    private TgclFile result;

    public TgclFile parse(byte[] data) {
        buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        result = new TgclFile();
        parseHeader();
        parseClasses();
        parsePropertyNames();
        parseBstNodes();
        return result;
    }

    private void parseHeader() {
        byte[] magic = new byte[4];
        buf.get(magic);
        if (magic[0] != 'T' || magic[1] != 'G' || magic[2] != 'C' || magic[3] != 'L') {
            throw new RuntimeException("Not a TGCL file");
        }
        result.version = buf.getInt();
        int classLength = buf.getInt();
        int propertyCount = buf.getInt();
        int bstNodeCount = buf.getInt();
        result.memorySize = buf.getInt(); // object_ptr_count
        int classOffset = buf.getInt();
        int propertyOffset = buf.getInt();
        int propertyNameOffset = buf.getInt();
        int bstNodeOffset = buf.getInt();
        int fileSize = buf.getInt();

        // Store offsets for later use
        result.classes = new ArrayList<>(classLength);
    }

    private void parseClasses() {
        // Re-read header to get offsets
        buf.position(4);
        int version = buf.getInt();
        int classLength = buf.getInt();
        int propertyCount = buf.getInt();
        int bstNodeCount = buf.getInt();
        int objectPtrCount = buf.getInt();
        int classOffset = buf.getInt();
        int propertyOffset = buf.getInt();
        int propertyNameOffset = buf.getInt();
        int bstNodeOffset = buf.getInt();
        int fileSize = buf.getInt();

        // Read class definitions
        buf.position(classOffset);
        for (int i = 0; i < classLength; i++) {
            ClassDef cls = new ClassDef();
            cls.classPropertyNameOffset = buf.getInt();
            cls.classPropertyStartingIndex = buf.getInt();
            cls.classPropertyCount = buf.getInt();
            result.classes.add(cls);
        }

        // Read property definitions
        for (int i = 0; i < classLength; i++) {
            ClassDef cls = result.classes.get(i);
            List<PropertyDef> props = new ArrayList<>();
            buf.position(propertyOffset + cls.classPropertyStartingIndex * 16);
            for (int j = 0; j < cls.classPropertyCount; j++) {
                PropertyDef p = new PropertyDef();
                p.propertyType = buf.getInt();
                p.propertyNameOffset = buf.getInt();
                p.objectByteSize = buf.getInt();
                p.arrayIndex = buf.getInt();
                props.add(p);
                result.allProperties.add(p);
            }
            result.propertiesByClass.add(props);
        }

        // Read class names
        for (int i = 0; i < classLength; i++) {
            ClassDef cls = result.classes.get(i);
            buf.position(propertyNameOffset + cls.classPropertyNameOffset);
            cls.className = readNullTerminatedString();
            result.classNames.add(cls.className);
        }

        // Read property names
        for (int i = 0; i < classLength; i++) {
            List<PropertyDef> props = result.propertiesByClass.get(i);
            for (PropertyDef p : props) {
                int savePos = buf.position();
                buf.position(propertyNameOffset + p.propertyNameOffset);
                p.propertyName = readNullTerminatedString();
                buf.position(savePos);
            }
        }

        // Read BST node names and data
        buf.position(bstNodeOffset);
        for (int i = 0; i < bstNodeCount; i++) {
            BstNode node = new BstNode();
            node.classIndex = buf.getInt();
            node.name = readNullTerminatedString();
            node.dataOffset = buf.position();
            result.nodes.add(node);

            // Skip past this node's object data
            skipClassData(node.classIndex);
        }
    }

    private void parsePropertyNames() {
        // Already done in parseClasses
    }

    private void parseBstNodes() {
        // Already done in parseClasses — nodes list is populated
        // Now parse actual property values for each node
        for (BstNode node : result.nodes) {
            buf.position(node.dataOffset);
            readClassProperties(node.properties, node.classIndex);
        }
    }

    private void readClassProperties(Map<String, Object> out, int classIndex) {
        if (classIndex >= result.propertiesByClass.size()) return;
        List<PropertyDef> props = result.propertiesByClass.get(classIndex);
        for (PropertyDef prop : props) {
            readProperty(out, prop);
        }
    }

    @SuppressWarnings("unchecked")
    private void readProperty(Map<String, Object> out, PropertyDef prop) {
        switch (prop.propertyType) {
            case 0: // General Value
                readValueProperty(out, prop);
                break;
            case 1: // String
                String s = readNullTerminatedString();
                out.put(prop.propertyName, s);
                break;
            case 2: // Clump
                int rawVal = buf.getInt();
                float fval = Float.intBitsToFloat(rawVal);
                String key = "[CLUMP]" + prop.propertyName;
                if (Float.isNaN(fval)) {
                    out.put(key, String.valueOf(rawVal));
                } else {
                    out.put(key, String.valueOf(rawVal));
                }
                break;
            case 3: // Array
                int customSize = buf.getInt();
                if (prop.arrayIndex != 0xFFFFFFFF) {
                    // Array of class instances
                    List<Map<String, Object>> arr = new ArrayList<>();
                    for (int i = 0; i < customSize; i++) {
                        Map<String, Object> elem = new HashMap<>();
                        readClassProperties(elem, prop.arrayIndex);
                        arr.add(elem);
                    }
                    out.put(prop.propertyName, arr);
                } else {
                    // Array of raw clump values
                    Map<String, Object> arrObj = new HashMap<>();
                    arrObj.put("Num", String.valueOf(customSize));
                    List<String> clumpData = new ArrayList<>();
                    for (int i = 0; i < customSize; i++) {
                        int v = buf.getInt();
                        clumpData.add(String.valueOf(v));
                    }
                    arrObj.put("[CLUMP]data", clumpData);
                    out.put(prop.propertyName, arrObj);
                }
                break;
            default:
                // Unknown type — skip
                int skip = prop.objectByteSize > 0 ? prop.objectByteSize : 4;
                buf.position(buf.position() + skip);
                out.put("[UNKNOWN]" + prop.propertyName, "[Size]" + prop.objectByteSize);
                break;
        }
    }

    private void readValueProperty(Map<String, Object> out, PropertyDef prop) {
        int size = prop.objectByteSize;
        switch (size) {
            case 1: { // Bool
                int val = buf.get() & 0xFF;
                if (val <= 1) {
                    out.put(prop.propertyName, val == 1);
                } else {
                    out.put(prop.propertyName, "[Unknown]" + String.format("%02X", val));
                }
                break;
            }
            case 2: { // Unsigned short
                int val = buf.getShort() & 0xFFFF;
                out.put(prop.propertyName, String.valueOf(val));
                break;
            }
            case 4: { // Float or Int
                int val = buf.getInt();
                if (shouldReadAsInteger(prop.propertyName)) {
                    out.put(prop.propertyName, String.valueOf(val));
                } else {
                    float fval = Float.intBitsToFloat(val);
                    if (fval == 0f || fval == -0f) {
                        out.put(prop.propertyName, String.valueOf(val));
                    } else {
                        out.put(prop.propertyName, String.format("%.6f", fval));
                    }
                }
                break;
            }
            case 8: { // Double
                double dval = buf.getDouble();
                out.put(prop.propertyName, String.valueOf(dval));
                break;
            }
            case 10: { // Long double (80-bit)
                byte[] raw = new byte[10];
                buf.get(raw);
                StringBuilder hex = new StringBuilder();
                for (byte b : raw) hex.append(String.format("%02x", b));
                out.put(prop.propertyName, hex.toString());
                break;
            }
            case 16: { // Vector4
                float[] vec = new float[4];
                for (int i = 0; i < 4; i++) vec[i] = buf.getFloat();
                out.put(prop.propertyName, vec);
                break;
            }
            case 64: { // Transform (4x4 matrix)
                float[] mat = new float[16];
                for (int i = 0; i < 16; i++) mat[i] = buf.getFloat();
                out.put(prop.propertyName, mat);
                break;
            }
            default: {
                // Unknown size — skip
                out.put("[Unknown Property]" + prop.propertyName, "[Size]" + size);
                if (size > 0) {
                    buf.position(buf.position() + size);
                }
                break;
            }
        }
    }

    private void skipClassData(int classIndex) {
        if (classIndex >= result.propertiesByClass.size()) return;
        List<PropertyDef> props = result.propertiesByClass.get(classIndex);
        for (PropertyDef prop : props) {
            skipProperty(prop);
        }
    }

    private void skipProperty(PropertyDef prop) {
        switch (prop.propertyType) {
            case 0: { // Value
                int size = prop.objectByteSize > 0 ? prop.objectByteSize : 4;
                buf.position(buf.position() + size);
                break;
            }
            case 1: // String
                readNullTerminatedString();
                break;
            case 2: // Clump
                buf.position(buf.position() + 4);
                break;
            case 3: { // Array
                int customSize = buf.getInt();
                if (prop.arrayIndex != 0xFFFFFFFF) {
                    for (int i = 0; i < customSize; i++) {
                        skipClassData(prop.arrayIndex);
                    }
                } else {
                    buf.position(buf.position() + customSize * 4);
                }
                break;
            }
            default: {
                int skip = prop.objectByteSize > 0 ? prop.objectByteSize : 4;
                buf.position(buf.position() + skip);
                break;
            }
        }
    }

    private boolean shouldReadAsInteger(String propertyName) {
        if (propertyName.equals("bstGuid")) return true;
        return propertyName.contains("BstGuid");
    }

    private String readNullTerminatedString() {
        StringBuilder sb = new StringBuilder();
        while (buf.remaining() > 0) {
            byte b = buf.get();
            if (b == 0) break;
            sb.append((char) (b & 0xFF));
        }
        // Decode as UTF-8
        byte[] bytes = new byte[sb.length()];
        for (int i = 0; i < sb.length(); i++) {
            bytes[i] = (byte) sb.charAt(i);
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Resolve clump index references to node names.
     * Call after parsing all nodes.
     */
    public void resolveClumpReferences() {
        if (result == null) return;
        List<String> nodeNames = new ArrayList<>();
        for (BstNode n : result.nodes) {
            nodeNames.add(n.name);
        }
        for (BstNode node : result.nodes) {
            resolveClumpInMap(node.properties, node.classIndex, nodeNames);
        }
    }

    @SuppressWarnings("unchecked")
    private void resolveClumpInMap(Map<String, Object> data, int classIndex, List<String> nodeNames) {
        if (classIndex >= result.propertiesByClass.size()) return;
        List<PropertyDef> props = result.propertiesByClass.get(classIndex);
        for (PropertyDef prop : props) {
            if (prop.propertyType == 2) { // Clump
                String key = "[CLUMP]" + prop.propertyName;
                Object val = data.get(key);
                if (val instanceof String) {
                    String resolved = resolveClumpIndex((String) val, nodeNames);
                    data.put(key, resolved);
                }
            } else if (prop.propertyType == 3) { // Array
                Object arrObj = data.get(prop.propertyName);
                if (arrObj instanceof Map) {
                    Map<String, Object> arrMap = (Map<String, Object>) arrObj;
                    Object clumpData = arrMap.get("[CLUMP]data");
                    if (clumpData instanceof List) {
                        List<String> list = (List<String>) clumpData;
                        for (int i = 0; i < list.size(); i++) {
                            list.set(i, resolveClumpIndex(list.get(i), nodeNames));
                        }
                    }
                } else if (arrObj instanceof List && prop.arrayIndex != 0xFFFFFFFF) {
                    List<Map<String, Object>> list = (List<Map<String, Object>>) arrObj;
                    for (Map<String, Object> elem : list) {
                        resolveClumpInMap(elem, prop.arrayIndex, nodeNames);
                    }
                }
            }
        }
    }

    private String resolveClumpIndex(String value, List<String> nodeNames) {
        try {
            int index = Integer.parseInt(value);
            if (index >= 0 && index < nodeNames.size()) {
                return nodeNames.get(index);
            }
        } catch (NumberFormatException e) {
            // Not a number — return as-is
        }
        return value;
    }

    /**
     * Get the class name for a node.
     */
    public String getClassName(int classIndex) {
        if (classIndex >= 0 && classIndex < result.classNames.size()) {
            return result.classNames.get(classIndex);
        }
        return "Unknown";
    }

    /**
     * Get property definitions for a class.
     */
    public List<PropertyDef> getProperties(int classIndex) {
        if (classIndex >= 0 && classIndex < result.propertiesByClass.size()) {
            return result.propertiesByClass.get(classIndex);
        }
        return new ArrayList<>();
    }
}
