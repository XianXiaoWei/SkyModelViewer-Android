package com.sky.modelviewer.parsing;

import java.util.ArrayList;
import java.util.List;

/**
 * Directly extracts ImageRegion definitions from luac bytecode.
 * Bypasses text decompilation entirely - walks the instruction stream
 * to find resource("ImageRegion")("name")({ image=..., uv=... }) patterns.
 *
 * Lua DSL "resource A B {...}" compiles to curried calls:
 *   resource("A")("B")({...})
 * which in bytecode is 3 CALL instructions.
 *
 * This is a backup method. The primary method is decompiling to text
 * and using regex matching in UIAtlasParser.
 */
public class LuacAtlasExtractor {

    public static class AtlasEntry {
        public String iconName;
        public String atlasImage;
        public float u0, v0, u1, v1;
    }

    private final LuacFunction func;

    public LuacAtlasExtractor(LuacFunction func) {
        this.func = func;
    }

    public List<AtlasEntry> extract() {
        List<AtlasEntry> result = new ArrayList<>();
        extractFromFunction(func, result);
        return result;
    }

    private void extractFromFunction(LuacFunction f, List<AtlasEntry> result) {
        int[] instrs = f.instructions;
        if (instrs == null || instrs.length == 0) return;

        for (int pc = 0; pc < instrs.length; pc++) {
            LuacFunction.Instruction inst = new LuacFunction.Instruction(instrs[pc]);

            // Look for: GETTABUP R(A) = _ENV.resource
            if ("GETTABUP".equals(inst.name)) {
                String upvalName = getUpvalueName(f, inst.B);
                LuacFunction.Constant keyConst = getConstantRK(f, inst.C);
                if ("_ENV".equals(upvalName) && keyConst != null &&
                    "string".equals(keyConst.type) && "resource".equals(keyConst.value)) {

                    AtlasEntry entry = traceCurriedResourceCall(f, pc);
                    if (entry != null) {
                        result.add(entry);
                    }
                }
            }
        }

        // Also search nested prototypes
        for (LuacFunction proto : f.protos) {
            extractFromFunction(proto, result);
        }
    }

    /**
     * Trace a curried resource call: resource("ImageRegion")("name")({...})
     * Bytecode pattern:
     *   GETTABUP R(A), _ENV, "resource"
     *   LOADK    R(A+1), "ImageRegion"
     *   CALL     R(A), 2, 2        ; R(A) = resource("ImageRegion")
     *   LOADK    R(A+1), "IconName"
     *   CALL     R(A), 2, 2        ; R(A) = result("IconName")
     *   NEWTABLE R(A+1), 0, 2      ; R(A+1) = {}
     *   ... set table fields ...
     *   CALL     R(A), 2, 1        ; result({...})
     */
    private AtlasEntry traceCurriedResourceCall(LuacFunction f, int startPc) {
        int[] instrs = f.instructions;
        LuacFunction.Instruction getTabUpInst = new LuacFunction.Instruction(instrs[startPc]);
        int funcReg = getTabUpInst.A;

        // Register tracking
        String[] regs = new String[f.maxStackSize + 16];
        boolean[] regIsTable = new boolean[regs.length];

        String resourceType = null;  // e.g. "ImageRegion"
        String iconName = null;      // e.g. "UiOutfitBodyClassicDress"
        String imageName = null;     // e.g. "UIPackedAtlas7"
        float[] uv = null;

        int callCount = 0;  // Track which CALL we're at (0=first, 1=second, 2=third)

        for (int pc = startPc + 1; pc < instrs.length && pc < startPc + 80; pc++) {
            LuacFunction.Instruction inst = new LuacFunction.Instruction(instrs[pc]);

            if ("CALL".equals(inst.name) && inst.A == funcReg) {
                callCount++;
                if (callCount == 1) {
                    // First CALL: resource("ImageRegion")
                    // R(A+1) should have been loaded with "ImageRegion"
                    if (funcReg + 1 < regs.length && regs[funcReg + 1] != null) {
                        resourceType = regs[funcReg + 1];
                    }
                    // Clear registers for next call
                    for (int i = funcReg + 1; i < regs.length; i++) {
                        regs[i] = null;
                        regIsTable[i] = false;
                    }
                } else if (callCount == 2) {
                    // Second CALL: result("IconName")
                    if (funcReg + 1 < regs.length && regs[funcReg + 1] != null) {
                        iconName = regs[funcReg + 1];
                    }
                    // Clear registers for table construction
                    for (int i = funcReg + 1; i < regs.length; i++) {
                        regs[i] = null;
                        regIsTable[i] = false;
                    }
                } else if (callCount == 3) {
                    // Third CALL: result({...})
                    // We should have all the data now
                    if ("ImageRegion".equals(resourceType) && iconName != null &&
                        imageName != null && uv != null && uv.length == 4) {
                        AtlasEntry entry = new AtlasEntry();
                        entry.iconName = iconName;
                        entry.atlasImage = imageName.toLowerCase();
                        entry.u0 = uv[0];
                        entry.v0 = uv[1];
                        entry.u1 = uv[2];
                        entry.v1 = uv[3];
                        return entry;
                    }
                    return null;
                }
                continue;
            }

            // Track register values
            switch (inst.name) {
                case "LOADK":
                    if (inst.Bx < f.constants.size()) {
                        LuacFunction.Constant c = f.constants.get(inst.Bx);
                        if ("string".equals(c.type)) {
                            regs[inst.A] = (String) c.value;
                        } else if ("number".equals(c.type)) {
                            double d = ((Number) c.value).doubleValue();
                            regs[inst.A] = String.valueOf(d);
                        }
                    }
                    break;

                case "LOADKX":
                    if (pc + 1 < instrs.length) {
                        LuacFunction.Instruction extra = new LuacFunction.Instruction(instrs[pc + 1]);
                        if ("EXTRAARG".equals(extra.name) && extra.Ax < f.constants.size()) {
                            LuacFunction.Constant c = f.constants.get(extra.Ax);
                            if ("string".equals(c.type)) {
                                regs[inst.A] = (String) c.value;
                            } else if ("number".equals(c.type)) {
                                double d = ((Number) c.value).doubleValue();
                                regs[inst.A] = String.valueOf(d);
                            }
                        }
                    }
                    break;

                case "MOVE":
                    if (inst.B < regs.length && regs[inst.B] != null) {
                        regs[inst.A] = regs[inst.B];
                    }
                    break;

                case "NEWTABLE":
                    if (inst.A < regs.length) {
                        regs[inst.A] = "{}";
                        regIsTable[inst.A] = true;
                    }
                    break;

                case "SETTABLE":
                    // R(A)[RK(B)] = RK(C)
                    if (inst.A < regs.length && regIsTable[inst.A]) {
                        LuacFunction.Constant keyConst = getConstantRK(f, inst.B);
                        if (keyConst != null && "string".equals(keyConst.type)) {
                            String keyName = (String) keyConst.value;

                            if ("image".equals(keyName)) {
                                String val = getRKValueStr(f, inst.C, regs);
                                if (val != null) {
                                    imageName = val;
                                }
                            } else if ("uv".equals(keyName)) {
                                // Value should be a register holding a table
                                if ((inst.C & 0x100) == 0) {
                                    int valReg = inst.C & 0xff;
                                    float[] uvVals = extractUvFromRegister(f, valReg, pc, regs, regIsTable);
                                    if (uvVals != null) {
                                        uv = uvVals;
                                    }
                                }
                            }
                        }
                    }
                    break;

                case "SETLIST":
                    // R(A)[n] = R(A+1), R(A+2), ... (array items)
                    if (inst.A < regs.length && regIsTable[inst.A]) {
                        int base = inst.A;
                        int count = inst.B;
                        if (count == 0) {
                            count = 0;
                            for (int i = base + 1; i < regs.length && regs[i] != null; i++) {
                                count++;
                            }
                        }
                        if (count == 4) {
                            // UV table: { u0, v0, u1, v1 }
                            uv = new float[4];
                            boolean valid = true;
                            for (int i = 0; i < 4; i++) {
                                if (base + 1 + i < regs.length && regs[base + 1 + i] != null) {
                                    uv[i] = parseFloat(regs[base + 1 + i]);
                                    if (Float.isNaN(uv[i])) valid = false;
                                } else {
                                    valid = false;
                                }
                            }
                            if (!valid) uv = null;
                        }
                    }
                    break;
            }
        }

        return null;
    }

    private float[] extractUvFromRegister(LuacFunction f, int reg, int currentPc, String[] regs, boolean[] regIsTable) {
        int[] instrs = f.instructions;
        for (int pc = currentPc - 1; pc >= 0 && pc >= currentPc - 30; pc--) {
            LuacFunction.Instruction inst = new LuacFunction.Instruction(instrs[pc]);
            if ("SETLIST".equals(inst.name) && inst.A == reg) {
                int count = inst.B;
                if (count == 0) {
                    count = 0;
                    for (int i = reg + 1; i < regs.length && regs[i] != null; i++) count++;
                }
                if (count == 4) {
                    float[] uv = new float[4];
                    for (int i = 0; i < 4; i++) {
                        if (reg + 1 + i < regs.length && regs[reg + 1 + i] != null) {
                            uv[i] = parseFloat(regs[reg + 1 + i]);
                        } else {
                            return null;
                        }
                    }
                    return uv;
                }
            }
        }
        return null;
    }

    // === Helpers ===

    private LuacFunction.Constant getConstantRK(LuacFunction f, int rkValue) {
        if ((rkValue & 0x100) != 0) {
            int idx = rkValue & 0xff;
            if (idx < f.constants.size()) return f.constants.get(idx);
        }
        return null;
    }

    private String getRKValueStr(LuacFunction f, int rkValue, String[] regs) {
        if ((rkValue & 0x100) != 0) {
            int idx = rkValue & 0xff;
            if (idx < f.constants.size()) {
                LuacFunction.Constant c = f.constants.get(idx);
                if ("string".equals(c.type)) {
                    return (String) c.value;
                } else if ("number".equals(c.type)) {
                    return String.valueOf(((Number) c.value).doubleValue());
                }
                return c.toString();
            }
            return null;
        }
        if (rkValue < regs.length) {
            return regs[rkValue];
        }
        return null;
    }

    private String getUpvalueName(LuacFunction f, int index) {
        if (index < f.upvalues.size()) {
            String name = f.upvalues.get(index).name;
            return name != null ? name : "_ENV";
        }
        return "_ENV";
    }

    private float parseFloat(String s) {
        if (s == null) return Float.NaN;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }
}
