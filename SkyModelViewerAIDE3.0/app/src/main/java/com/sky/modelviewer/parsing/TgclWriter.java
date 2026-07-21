package com.sky.modelviewer.parsing;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serializes a TgclFile back to binary TGCL format.
 */
public class TgclWriter {

    public static byte[] write(TgclParser.TgclFile file) {
        try {
            // Collect all property names and class names into a string pool
            ByteArrayOutputStream stringPool = new ByteArrayOutputStream();
            List<Integer> classNameOffsets = new ArrayList<>();
            List<Integer> propNameOffsets = new ArrayList<>();

            // Write class names to string pool
            for (String name : file.classNames) {
                classNameOffsets.add(stringPool.size());
                writeNullString(stringPool, name);
            }

            // Write property names to string pool
            for (List<TgclParser.PropertyDef> props : file.propertiesByClass) {
                for (TgclParser.PropertyDef p : props) {
                    propNameOffsets.add(stringPool.size());
                    writeNullString(stringPool, p.propertyName);
                }
            }

            int propertyCount = 0;
            for (List<TgclParser.PropertyDef> props : file.propertiesByClass) {
                propertyCount += props.size();
            }

            // Build property definitions
            int propStartIdx = 0;
            ByteArrayOutputStream propDefs = new ByteArrayOutputStream();
            int propNameIdx = 0;
            for (int ci = 0; ci < file.classes.size(); ci++) {
                TgclParser.ClassDef cls = file.classes.get(ci);
                cls.classPropertyStartingIndex = propStartIdx;
                cls.classPropertyNameOffset = classNameOffsets.get(ci);

                List<TgclParser.PropertyDef> props = file.propertiesByClass.get(ci);
                cls.classPropertyCount = props.size();
                for (TgclParser.PropertyDef p : props) {
                    p.propertyNameOffset = propNameOffsets.get(propNameIdx++);
                    writeInt(propDefs, p.propertyType);
                    writeInt(propDefs, p.propertyNameOffset);
                    writeInt(propDefs, p.objectByteSize);
                    writeInt(propDefs, p.arrayIndex);
                }
                propStartIdx += props.size();
            }

            // Build BST node data
            ByteArrayOutputStream nodeData = new ByteArrayOutputStream();
            for (TgclParser.BstNode node : file.nodes) {
                writeInt(nodeData, node.classIndex);
                writeNullString(nodeData, node.name);
                // Write property values
                List<TgclParser.PropertyDef> props = file.propertiesByClass.size() > node.classIndex ?
                    file.propertiesByClass.get(node.classIndex) : new ArrayList<>();
                for (TgclParser.PropertyDef prop : props) {
                    Object val = node.properties.get(prop.propertyName);
                    if (val == null) val = node.properties.get("[CLUMP]" + prop.propertyName);
                    if (val == null) val = node.properties.get("[Unknown Property]" + prop.propertyName);
                    if (val == null) val = node.properties.get("[UNKNOWN]" + prop.propertyName);
                    writePropertyValue(nodeData, val, prop);
                }
            }

            // Calculate offsets
            int headerSize = 4 + 10 * 4; // magic + 10 ints
            int classDefSize = file.classes.size() * 12; // 3 ints per class
            int propDefSize = propertyCount * 16; // 4 ints per property

            int classOffset = headerSize;
            int propertyOffset = classOffset + classDefSize;
            int propertyNameOffset = propertyOffset + propDefSize;
            int bstNodeOffset = propertyNameOffset + stringPool.size();
            int fileSize = bstNodeOffset + nodeData.size();

            // Build final output
            ByteArrayOutputStream out = new ByteArrayOutputStream(fileSize);

            // Header
            out.write('T'); out.write('G'); out.write('C'); out.write('L');
            writeInt(out, file.version);
            writeInt(out, file.classes.size());
            writeInt(out, propertyCount);
            writeInt(out, file.nodes.size());
            writeInt(out, file.memorySize);
            writeInt(out, classOffset);
            writeInt(out, propertyOffset);
            writeInt(out, propertyNameOffset);
            writeInt(out, bstNodeOffset);
            writeInt(out, fileSize);

            // Class definitions
            for (TgclParser.ClassDef cls : file.classes) {
                writeInt(out, cls.classPropertyNameOffset);
                writeInt(out, cls.classPropertyStartingIndex);
                writeInt(out, cls.classPropertyCount);
            }

            // Property definitions
            out.write(propDefs.toByteArray());

            // String pool (property names + class names)
            out.write(stringPool.toByteArray());

            // BST nodes
            out.write(nodeData.toByteArray());

            return out.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void writePropertyValue(ByteArrayOutputStream out, Object val,
                                           TgclParser.PropertyDef prop) {
        switch (prop.propertyType) {
            case 0: // Value
                writeValueProperty(out, val, prop);
                break;
            case 1: // String
                writeNullString(out, val != null ? String.valueOf(val) : "");
                break;
            case 2: // Clump
                if (val instanceof String) {
                    try {
                        writeInt(out, Integer.parseInt((String) val));
                    } catch (NumberFormatException e) {
                        // Try float interpretation
                        try {
                            float f = Float.parseFloat((String) val);
                            writeInt(out, Float.floatToIntBits(f));
                        } catch (NumberFormatException e2) {
                            writeInt(out, -1);
                        }
                    }
                } else if (val instanceof Number) {
                    writeInt(out, ((Number) val).intValue());
                } else {
                    writeInt(out, -1);
                }
                break;
            case 3: // Array
                if (val instanceof List) {
                    List<?> list = (List<?>) val;
                    writeInt(out, list.size());
                    if (prop.arrayIndex != 0xFFFFFFFF) {
                        // Array of class instances
                        for (Object elem : list) {
                            if (elem instanceof Map) {
                                // This is complex - skip for now
                            }
                        }
                    } else {
                        // Array of raw int values
                        for (Object elem : list) {
                            try {
                                writeInt(out, Integer.parseInt(String.valueOf(elem)));
                            } catch (NumberFormatException e) {
                                writeInt(out, 0);
                            }
                        }
                    }
                } else {
                    writeInt(out, 0);
                }
                break;
            default:
                // Unknown — write zeros
                int size = prop.objectByteSize > 0 ? prop.objectByteSize : 4;
                for (int i = 0; i < size; i++) out.write(0);
                break;
        }
    }

    private static void writeValueProperty(ByteArrayOutputStream out, Object val,
                                           TgclParser.PropertyDef prop) {
        int size = prop.objectByteSize;
        switch (size) {
            case 1: // Bool
                if (val instanceof Boolean) {
                    out.write(((Boolean) val) ? 1 : 0);
                } else {
                    out.write(0);
                }
                break;
            case 2: // Unsigned short
                if (val instanceof String) {
                    try {
                        int v = Integer.parseInt((String) val);
                        out.write(v & 0xFF);
                        out.write((v >> 8) & 0xFF);
                    } catch (NumberFormatException e) {
                        out.write(0); out.write(0);
                    }
                } else {
                    out.write(0); out.write(0);
                }
                break;
            case 4: // Float or Int
                if (val instanceof String) {
                    try {
                        float f = Float.parseFloat((String) val);
                        writeInt(out, Float.floatToIntBits(f));
                    } catch (NumberFormatException e) {
                        try {
                            writeInt(out, Integer.parseInt((String) val));
                        } catch (NumberFormatException e2) {
                            writeInt(out, 0);
                        }
                    }
                } else if (val instanceof Number) {
                    writeInt(out, Float.floatToIntBits(((Number) val).floatValue()));
                } else {
                    writeInt(out, 0);
                }
                break;
            case 8: // Double
                if (val instanceof String) {
                    try {
                        double d = Double.parseDouble((String) val);
                        long bits = Double.doubleToLongBits(d);
                        for (int i = 0; i < 8; i++) out.write((int) ((bits >> (i * 8)) & 0xFF));
                    } catch (NumberFormatException e) {
                        for (int i = 0; i < 8; i++) out.write(0);
                    }
                } else {
                    for (int i = 0; i < 8; i++) out.write(0);
                }
                break;
            case 10: // Long double (80-bit)
                for (int i = 0; i < 10; i++) out.write(0);
                break;
            case 16: // Vector4
                if (val instanceof float[]) {
                    float[] arr = (float[]) val;
                    for (int i = 0; i < 4 && i < arr.length; i++) {
                        writeInt(out, Float.floatToIntBits(arr[i]));
                    }
                    for (int i = arr.length; i < 4; i++) writeInt(out, 0);
                } else {
                    for (int i = 0; i < 4; i++) writeInt(out, 0);
                }
                break;
            case 64: // Transform (4x4 matrix)
                if (val instanceof float[]) {
                    float[] arr = (float[]) val;
                    for (int i = 0; i < 16 && i < arr.length; i++) {
                        writeInt(out, Float.floatToIntBits(arr[i]));
                    }
                    for (int i = arr.length; i < 16; i++) writeInt(out, 0);
                } else {
                    for (int i = 0; i < 16; i++) writeInt(out, 0);
                }
                break;
            default:
                for (int i = 0; i < size; i++) out.write(0);
                break;
        }
    }

    private static void writeNullString(ByteArrayOutputStream out, String s) {
        if (s != null) {
            byte[] bytes = s.getBytes();
            out.write(bytes, 0, bytes.length);
        }
        out.write(0); // null terminator
    }

    private static void writeInt(ByteArrayOutputStream out, int val) {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 24) & 0xFF);
    }
}
