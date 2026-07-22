package com.sky.modelviewer.parsing;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a decoded Lua function from luac bytecode.
 * Ported from that-sky-project/that-luac-decompiler parser output structure.
 */
public class LuacFunction {

    public String source;
    public int linedefined;
    public int lastlinedefined;
    public int numParams;
    public int isVararg;
    public int maxStackSize;
    public int[] instructions;
    public List<Constant> constants;
    public List<LuacFunction> protos;
    public List<Upvalue> upvalues;
    public List<LocalVar> localVars;
    public int[] lineInfo;

    public LuacFunction() {
        constants = new ArrayList<>();
        protos = new ArrayList<>();
        upvalues = new ArrayList<>();
        localVars = new ArrayList<>();
    }

    public static class Constant {
        public String type;
        public Object value;

        public Constant(String type, Object value) {
            this.type = type;
            this.value = value;
        }

        @Override
        public String toString() {
            if ("string".equals(type)) {
                return luaString((String) value);
            } else if ("number".equals(type)) {
                double d = ((Number) value).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            } else if ("boolean".equals(type)) {
                return String.valueOf(value);
            }
            return "nil";
        }

        public static String luaString(String s) {
            if (s == null) return "nil";
            StringBuilder sb = new StringBuilder();
            boolean needsLongBracket = s.contains("\n") || s.contains("\r");
            if (needsLongBracket) {
                sb.append("[[").append(s).append("]]");
            } else {
                sb.append("\"");
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    switch (c) {
                        case '"': sb.append("\\\""); break;
                        case '\\': sb.append("\\\\"); break;
                        case '\n': sb.append("\\n"); break;
                        case '\r': sb.append("\\r"); break;
                        case '\t': sb.append("\\t"); break;
                        default:
                            if (c < 32 || c > 126) {
                                sb.append(String.format("\\%d", (int) c));
                            } else {
                                sb.append(c);
                            }
                    }
                }
                sb.append("\"");
            }
            return sb.toString();
        }
    }

    public static class Upvalue {
        public int instack;
        public int idx;
        public String name;

        public Upvalue(int instack, int idx) {
            this.instack = instack;
            this.idx = idx;
        }
    }

    public static class LocalVar {
        public String name;
        public int startPC;
        public int endPC;

        public LocalVar(String name, int startPC, int endPC) {
            this.name = name;
            this.startPC = startPC;
            this.endPC = endPC;
        }
    }

    public static class Instruction {
        public int opcode;
        public String name;
        public int A, B, C, Bx, sBx, Ax;

        public Instruction(int raw) {
            opcode = raw & 0x3f;
            A = (raw >> 6) & 0xff;
            C = (raw >> 14) & 0x1ff;
            B = (raw >> 23) & 0x1ff;
            Bx = (raw >> 14) & 0x3ffff;
            sBx = Bx - 0x1ffff;
            Ax = (raw >> 6) & 0x3ffffff;
            name = (opcode < LuacConstants.OPCODE_NAMES.length)
                ? LuacConstants.OPCODE_NAMES[opcode]
                : "OP_" + opcode;
        }
    }
}
