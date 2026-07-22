package com.sky.modelviewer.parsing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decompiles parsed luac functions back into Lua source code.
 * Ported from that-sky-project/that-luac-decompiler src/decompiler/function-decompiler.js
 */
public class LuacDecompiler {

    private final LuacFunction func;
    private final int indent;
    private final List<String> lines = new ArrayList<>();
    private final String[] registers;
    private final Set<String> declaredLocals = new HashSet<>();
    private final Set<Integer> labelTargets = new HashSet<>();
    private final Set<Integer> suppressedLabels = new HashSet<>();

    public LuacDecompiler(LuacFunction func) {
        this(func, 0);
    }

    public LuacDecompiler(LuacFunction func, int indent) {
        this.func = func;
        this.indent = indent;
        this.registers = new String[func.maxStackSize];
        collectLabelTargets();
        for (int i = 0; i < func.numParams && i < func.localVars.size(); i++) {
            LuacFunction.LocalVar lv = func.localVars.get(i);
            if (lv != null && lv.name != null) declaredLocals.add(lv.name);
        }
    }

    public List<String> decompile() {
        decompileRange(0, func.instructions.length);
        flushOpenLocals();
        return lines;
    }

    private void collectLabelTargets() {
        for (int pc = 0; pc < func.instructions.length; pc++) {
            LuacFunction.Instruction inst = new LuacFunction.Instruction(func.instructions[pc]);
            if ("JMP".equals(inst.name) || "FORLOOP".equals(inst.name) ||
                "FORPREP".equals(inst.name) || "TFORLOOP".equals(inst.name)) {
                labelTargets.add(resolveJumpTarget(pc, inst.sBx));
            }
            if ("EQ".equals(inst.name) || "LT".equals(inst.name) || "LE".equals(inst.name) ||
                "TEST".equals(inst.name) || "TESTSET".equals(inst.name)) {
                if (pc + 1 < func.instructions.length) {
                    LuacFunction.Instruction next = new LuacFunction.Instruction(func.instructions[pc + 1]);
                    if ("JMP".equals(next.name)) {
                        labelTargets.add(resolveJumpTarget(pc + 1, next.sBx));
                    }
                }
            }
        }
    }

    private int resolveJumpTarget(int pc, int sBx) {
        return pc + 1 + sBx;
    }

    private void decompileRange(int startPc, int endPc) {
        int pc = startPc;
        while (pc < endPc) {
            emitLabelIfNeeded(pc);
            LuacFunction.Instruction inst = new LuacFunction.Instruction(func.instructions[pc]);

            switch (inst.name) {
                case "GETTABUP":
                    setRegister(inst.A, formatUpvalueAccess(getUpvalueName(inst.B), getRKValue(inst.C, pc), getKeyConstant(inst.C)));
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                case "LOADKX":
                    pc = handleLoadKx(inst, pc);
                    break;
                case "LOADK":
                    setRegister(inst.A, getConstantExpression(inst.Bx));
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                case "CALL":
                    pc = handleCall(inst, pc);
                    break;
                case "NEWTABLE":
                    setRegister(inst.A, "{}");
                    pc += 1;
                    break;
                case "SETTABLE":
                    pc = handleSetTable(inst, pc);
                    break;
                case "SETLIST":
                    pc = handleSetList(inst, pc);
                    break;
                case "RETURN":
                    pc = handleReturn(inst, pc);
                    break;
                case "SETTABUP":
                    pc = handleSetTabUp(inst, pc);
                    break;
                case "MOVE":
                    setRegister(inst.A, getRegisterExpression(inst.B, pc));
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                case "LOADBOOL":
                    setRegister(inst.A, String.valueOf(inst.B != 0));
                    emitRegisterAssignment(inst.A, pc);
                    pc += inst.C != 0 ? 2 : 1;
                    break;
                case "LOADNIL":
                    for (int i = inst.A; i <= inst.A + inst.B; i++) {
                        setRegister(i, "nil");
                        emitRegisterAssignment(i, pc);
                    }
                    pc += 1;
                    break;
                case "GETUPVAL":
                    setRegister(inst.A, getUpvalueName(inst.B));
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                case "GETTABLE":
                    setRegister(inst.A, formatTableAccess(getRegisterExpression(inst.B, pc), getRKValue(inst.C, pc), getKeyConstant(inst.C)));
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                case "SETUPVAL":
                    emit(getUpvalueName(inst.B) + " = " + getRegisterExpression(inst.A, pc));
                    pc += 1;
                    break;
                case "SELF":
                    setRegister(inst.A, formatTableAccess(getRegisterExpression(inst.B, pc), getRKValue(inst.C, pc), getKeyConstant(inst.C)));
                    setRegister(inst.A + 1, getRegisterExpression(inst.B, pc));
                    pc += 1;
                    break;
                case "ADD": case "SUB": case "MUL": case "DIV": case "MOD": case "POW":
                    String op = inst.name.equals("ADD") ? "+" : inst.name.equals("SUB") ? "-" :
                               inst.name.equals("MUL") ? "*" : inst.name.equals("DIV") ? "/" :
                               inst.name.equals("MOD") ? "%" : "^";
                    setRegister(inst.A, getRKValue(inst.B, pc) + " " + op + " " + getRKValue(inst.C, pc));
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                case "UNM":
                    setRegister(inst.A, "-" + getRKValue(inst.B, pc));
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                case "NOT":
                    setRegister(inst.A, "not " + getRKValue(inst.B, pc));
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                case "LEN":
                    setRegister(inst.A, "#" + getRKValue(inst.B, pc));
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                case "CONCAT": {
                    StringBuilder sb = new StringBuilder();
                    for (int i = inst.B; i <= inst.C; i++) {
                        if (i > inst.B) sb.append(" .. ");
                        sb.append(getRegisterExpression(i, pc));
                    }
                    setRegister(inst.A, sb.toString());
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                }
                case "JMP":
                    flushOpenLocals();
                    int targetPc = resolveJumpTarget(pc, inst.sBx);
                    if (pc > 0) {
                        LuacFunction.Instruction prev = new LuacFunction.Instruction(func.instructions[pc - 1]);
                        if ("RETURN".equals(prev.name) || "TAILCALL".equals(prev.name)) { pc += 1; break; }
                    }
                    if (!suppressedLabels.contains(targetPc)) emit("goto L" + targetPc);
                    pc += 1;
                    break;
                case "TEST":
                    pc = handleConditionalNext(pc, getRegisterExpression(inst.A, pc), inst.C != 0);
                    break;
                case "TESTSET":
                    pc = handleTestSet(inst, pc);
                    break;
                case "EQ": case "LT": case "LE":
                    String cmpOp = inst.name.equals("EQ") ? "==" : inst.name.equals("LT") ? "<" : "<=";
                    pc = handleConditionalNext(pc, getRKValue(inst.B, pc) + " " + cmpOp + " " + getRKValue(inst.C, pc), inst.A != 0);
                    break;
                case "FORPREP":
                    pc = handleForPrep(inst, pc);
                    break;
                case "TFORCALL":
                    pc = handleTForCall(inst, pc);
                    break;
                case "FORLOOP":
                case "TFORLOOP":
                    pc += 1;
                    break;
                case "VARARG":
                    setRegister(inst.A, "...");
                    emitRegisterAssignment(inst.A, pc);
                    pc += 1;
                    break;
                case "EXTRAARG":
                    pc += 1;
                    break;
                case "CLOSURE":
                    pc = handleClosure(inst, pc);
                    break;
                case "TAILCALL":
                    flushOpenLocals();
                    String tcFunc = getRegisterExpression(inst.A, pc);
                    List<String> tcArgs = new ArrayList<>();
                    int tcArgCount = inst.B == 0 ? -1 : inst.B - 1;
                    if (tcArgCount < 0) tcArgs.add("...");
                    else for (int i = 1; i <= tcArgCount; i++) tcArgs.add(getRegisterExpression(inst.A + i, pc));
                    emit("return " + tcFunc + "(" + String.join(", ", tcArgs) + ")");
                    pc += 1;
                    break;
                default:
                    emit("-- " + inst.name + " A=" + inst.A + " B=" + inst.B + " C=" + inst.C);
                    pc += 1;
                    break;
            }
        }
    }

    private int handleLoadKx(LuacFunction.Instruction inst, int pc) {
        if (pc + 1 < func.instructions.length) {
            LuacFunction.Instruction extra = new LuacFunction.Instruction(func.instructions[pc + 1]);
            if ("EXTRAARG".equals(extra.name)) {
                setRegister(inst.A, getConstantExpression(extra.Ax));
                emitRegisterAssignment(inst.A, pc);
                return pc + 2;
            }
        }
        setRegister(inst.A, "KX" + pc);
        return pc + 1;
    }

    private int handleCall(LuacFunction.Instruction inst, int pc) {
        int funcReg = inst.A;
        int returnCount = inst.C;
        String funcExpr = registers[funcReg];
        List<String> args = new ArrayList<>();
        int argCount = inst.B == 0 ? -1 : inst.B - 1;
        if (argCount < 0) {
            for (int i = funcReg + 1; i < func.maxStackSize; i++) {
                if (registers[i] == null) break;
                args.add(registers[i]);
            }
        } else {
            for (int i = 1; i <= argCount; i++) {
                args.add(registers[funcReg + i] != null ? registers[funcReg + i] : "R" + (funcReg + i));
            }
        }
        String callText = (funcExpr != null ? funcExpr : "nil") + "(" + String.join(", ", args) + ")";

        if (returnCount == 1) {
            emit(callText);
            clearRegistersFrom(funcReg);
        } else if (returnCount == 0 || returnCount == 2) {
            setRegister(funcReg, callText);
            if (returnCount == 2) emitRegisterAssignment(funcReg, pc);
            clearRegistersFrom(funcReg + 1);
        } else {
            List<String> localNames = new ArrayList<>();
            for (int i = 0; i < returnCount - 1; i++) {
                String name = getLocalName(funcReg + i, pc);
                if (name != null && !isImplicitLoopName(name)) localNames.add(name);
            }
            if (!localNames.isEmpty()) {
                boolean allUndeclared = true;
                for (String n : localNames) if (declaredLocals.contains(n)) allUndeclared = false;
                for (String n : localNames) declaredLocals.add(n);
                emit((allUndeclared ? "local " : "") + String.join(", ", localNames) + " = " + callText);
            }
            clearRegistersFrom(funcReg);
        }
        return pc + 1;
    }

    private int handleSetTable(LuacFunction.Instruction inst, int pc) {
        String tableVal = registers[inst.A];
        LuacFunction.Constant keyConst = getKeyConstant(inst.B);
        String keyText = keyConst != null && "string".equals(keyConst.type) ? (String) keyConst.value : getRKValue(inst.B, pc);
        String valueText = getRKValue(inst.C, pc);
        if ("{}".equals(tableVal) || (tableVal != null && tableVal.startsWith("{"))) {
            String entry = isIdentifier(keyText) ? keyText + " = " + valueText : "[" + keyText + "] = " + valueText;
            setRegister(inst.A, addToTable(tableVal, entry));
        } else {
            emit(formatTableAccess(getRegisterExpression(inst.A, pc), getRKValue(inst.B, pc), keyConst) + " = " + valueText);
        }
        return pc + 1;
    }

    private int handleSetList(LuacFunction.Instruction inst, int pc) {
        int base = inst.A;
        int count = inst.B;
        if (inst.C == 0 && pc + 1 < func.instructions.length) pc += 1;
        int itemCount = count == 0 ? countTopItems(base) : count;
        List<String> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) items.add(getRegisterExpression(base + 1 + i, pc));
        String tableVal = registers[base];
        if (!items.isEmpty() && "{}".equals(tableVal)) {
            setRegister(base, "{ " + String.join(", ", items) + " }");
        }
        return pc + 1;
    }

    private int handleReturn(LuacFunction.Instruction inst, int pc) {
        if (inst.B == 1) {
            if (pc < func.instructions.length - 1) {
                if (pc > 0) {
                    LuacFunction.Instruction prev = new LuacFunction.Instruction(func.instructions[pc - 1]);
                    if ("TAILCALL".equals(prev.name)) return pc + 1;
                }
                emit("return");
            }
            return pc + 1;
        }
        List<String> results = new ArrayList<>();
        if (inst.B == 0) {
            for (int i = inst.A; i < func.maxStackSize; i++) {
                if (registers[i] == null) break;
                results.add(getRegisterExpression(i, pc));
            }
        } else {
            for (int i = 0; i < inst.B - 1; i++) results.add(getRegisterExpression(inst.A + i, pc));
        }
        emit("return " + String.join(", ", results));
        return pc + 1;
    }

    private int handleSetTabUp(LuacFunction.Instruction inst, int pc) {
        String upvalueName = getUpvalueName(inst.A);
        String key = getRKValue(inst.B, pc);
        LuacFunction.Constant keyConst = getKeyConstant(inst.B);
        String value = getRKValue(inst.C, pc);
        String target = formatUpvalueAccess(upvalueName, key, keyConst);
        if (value != null && value.startsWith("function(") && isIdentifier(target)) {
            emit("function " + target + value.substring("function".length()));
        } else {
            emit(target + " = " + value);
        }
        return pc + 1;
    }

    private int handleConditionalNext(int pc, String condition, boolean executeNextWhenTrue) {
        String jumpCondition = executeNextWhenTrue ? condition : "not (" + condition + ")";
        String bodyCondition = negateCondition(jumpCondition);
        if (pc + 1 < func.instructions.length) {
            LuacFunction.Instruction next = new LuacFunction.Instruction(func.instructions[pc + 1]);
            if ("JMP".equals(next.name)) {
                int targetPc = resolveJumpTarget(pc + 1, next.sBx);
                if (targetPc > pc + 2) {
                    flushOpenLocals();
                    emit("if " + bodyCondition + " then");
                    int savedIndent = indent;
                    decompileRangeInline(pc + 2, targetPc, 1);
                    emit("end");
                    suppressedLabels.add(targetPc);
                    return targetPc;
                }
            }
        }
        emit("-- branch when " + jumpCondition);
        return pc + 1;
    }

    private int handleTestSet(LuacFunction.Instruction inst, int pc) {
        String source = getRegisterExpression(inst.B, pc);
        String localName = getLocalName(inst.A, pc);
        if (localName != null && !isImplicitLoopName(localName)) {
            declaredLocals.add(localName);
            setRegister(inst.A, source);
            if (pc + 1 < func.instructions.length) {
                LuacFunction.Instruction next = new LuacFunction.Instruction(func.instructions[pc + 1]);
                if ("JMP".equals(next.name)) {
                    int targetPc = resolveJumpTarget(pc + 1, next.sBx);
                    String cond = inst.C != 0 ? source : "not (" + source + ")";
                    flushOpenLocals();
                    emit("if " + cond + " then");
                    decompileRangeInline(pc + 2, targetPc, 1);
                    emit("end");
                    return targetPc + 1;
                }
            }
        } else {
            setRegister(inst.A, source);
        }
        return pc + 1;
    }

    private int handleForPrep(LuacFunction.Instruction inst, int pc) {
        int loopEndPc = resolveJumpTarget(pc, inst.sBx);
        int loopBodyPc = pc + 1;
        String loopVar = getLocalName(inst.A + 3, loopBodyPc);
        if (loopVar == null) loopVar = getLocalName(inst.A, loopBodyPc);
        if (loopVar == null) loopVar = "R" + (inst.A + 3);
        String initExpr = getRegisterExpression(inst.A, pc);
        String limitExpr = getRegisterExpression(inst.A + 1, pc);
        String stepExpr = getRegisterExpression(inst.A + 2, pc);
        String stepText = "1".equals(stepExpr) ? "" : ", " + stepExpr;
        flushOpenLocals();
        if (!isImplicitLoopName(loopVar)) declaredLocals.add(loopVar);
        emit("for " + loopVar + " = " + initExpr + ", " + limitExpr + stepText + " do");
        suppressedLabels.add(loopBodyPc);
        suppressedLabels.add(loopEndPc);
        decompileRangeInline(loopBodyPc, loopEndPc, 1);
        emit("end");
        return loopEndPc + 1;
    }

    private int handleTForCall(LuacFunction.Instruction inst, int pc) {
        if (pc + 1 < func.instructions.length) {
            LuacFunction.Instruction loopInst = new LuacFunction.Instruction(func.instructions[pc + 1]);
            if ("TFORLOOP".equals(loopInst.name)) {
                int loopStartPc = resolveJumpTarget(pc + 1, loopInst.sBx);
                int setupJumpPc = -1;
                for (int i = pc - 1; i >= 0; i--) {
                    LuacFunction.Instruction ji = new LuacFunction.Instruction(func.instructions[i]);
                    if ("JMP".equals(ji.name) && resolveJumpTarget(i, ji.sBx) == loopStartPc) { setupJumpPc = i; break; }
                }
                if (setupJumpPc >= 0) {
                    int bodyStartPc = setupJumpPc + 1;
                    int bodyEndPc = pc;
                    String iteratorExpr = getRegisterExpression(inst.A, pc);
                    String stateExpr = getRegisterExpression(inst.A + 1, pc);
                    String controlExpr = getRegisterExpression(inst.A + 2, pc);
                    List<String> vars = new ArrayList<>();
                    for (int i = 0; i < inst.C; i++) {
                        String name = getLocalName(inst.A + 3 + i, bodyStartPc);
                        if (name != null && !isImplicitLoopName(name)) { vars.add(name); declaredLocals.add(name); }
                    }
                    if (vars.isEmpty()) for (int i = 0; i < Math.max(1, inst.C); i++) vars.add(i == 0 ? "k" : "v" + i);
                    flushOpenLocals();
                    emit("for " + String.join(", ", vars) + " in " + iteratorExpr + ", " + stateExpr + ", " + controlExpr + " do");
                    suppressedLabels.add(loopStartPc);
                    suppressedLabels.add(bodyStartPc);
                    suppressedLabels.add(pc);
                    suppressedLabels.add(pc + 1);
                    decompileRangeInline(bodyStartPc, bodyEndPc, 1);
                    emit("end");
                    return pc + 2;
                }
            }
        }
        emit("-- tforcall A=" + inst.A + " C=" + inst.C);
        return pc + 1;
    }

    private int handleClosure(LuacFunction.Instruction inst, int pc) {
        if (inst.Bx < func.protos.size()) {
            LuacFunction proto = func.protos.get(inst.Bx);
            List<String> params = new ArrayList<>();
            for (int i = 0; i < proto.numParams; i++) {
                params.add(i < proto.localVars.size() ? proto.localVars.get(i).name : "p" + i);
            }
            LuacDecompiler nested = new LuacDecompiler(proto, indent + 1);
            List<String> nestedLines = nested.decompile();
            if (nestedLines.isEmpty() || (nestedLines.size() == 1 && "return".equals(nestedLines.get(0).trim()))) {
                setRegister(inst.A, "function(" + String.join(", ", params) + ") end");
            } else {
                StringBuilder body = new StringBuilder();
                body.append("function(").append(String.join(", ", params)).append(")");
                for (String line : nestedLines) body.append("\n").append(line);
                body.append("\n").append(indentStr(indent)).append("end");
                setRegister(inst.A, body.toString());
                emitRegisterAssignment(inst.A, pc);
            }
        }
        return pc + 1;
    }

    // === Helpers ===

    private void decompileRangeInline(int start, int end, int extraIndent) {
        LuacDecompiler nested = new LuacDecompiler(func, indent + extraIndent);
        System.arraycopy(registers, 0, nested.registers, 0, Math.min(registers.length, nested.registers.length));
        nested.declaredLocals.addAll(declaredLocals);
        nested.labelTargets.addAll(labelTargets);
        nested.suppressedLabels.addAll(suppressedLabels);
        List<String> savedLines = new ArrayList<>(lines);
        lines.clear();
        nested.decompileRange(start, end);
        lines.addAll(savedLines);
        lines.addAll(nested.lines);
    }

    private String getRKValue(int value, int pc) {
        if ((value & 0x100) != 0) return getConstantExpression(value & 0xff);
        return getRegisterExpression(value, pc);
    }

    private String getConstantExpression(int index) {
        if (index < func.constants.size()) return func.constants.get(index).toString();
        return "K" + index;
    }

    private LuacFunction.Constant getKeyConstant(int value) {
        if ((value & 0x100) != 0) {
            int idx = value & 0xff;
            if (idx < func.constants.size()) return func.constants.get(idx);
        }
        return null;
    }

    private String getRegisterExpression(int index, int pc) {
        if (index < registers.length && registers[index] != null) return registers[index];
        String localName = getLocalName(index, pc);
        return localName != null ? localName : "R" + index;
    }

    private String getLocalName(int registerIndex, int pc) {
        for (int i = 0; i < func.localVars.size(); i++) {
            LuacFunction.LocalVar lv = func.localVars.get(i);
            if (lv.startPC <= pc && pc < lv.endPC && i == registerIndex) return lv.name;
        }
        return null;
    }

    private String getUpvalueName(int index) {
        if (index < func.upvalues.size()) {
            String name = func.upvalues.get(index).name;
            return name != null ? name : "_ENV";
        }
        return "_U" + index;
    }

    private String formatUpvalueAccess(String upvalueName, String keyExpr, LuacFunction.Constant keyConst) {
        if ("_ENV".equals(upvalueName) && keyConst != null && "string".equals(keyConst.type) && isIdentifier((String) keyConst.value)) {
            return (String) keyConst.value;
        }
        return formatTableAccess(upvalueName, keyExpr, keyConst);
    }

    private String formatTableAccess(String base, String keyExpr, LuacFunction.Constant keyConst) {
        if (keyConst != null && "string".equals(keyConst.type) && isIdentifier((String) keyConst.value)) {
            return base + "." + (String) keyConst.value;
        }
        return base + "[" + keyExpr + "]";
    }

    private boolean isIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isLetter(s.charAt(0)) && s.charAt(0) != '_') return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    private String addToTable(String table, String entry) {
        if ("{}".equals(table)) return "{ " + entry + " }";
        if (table.startsWith("{ ") && table.endsWith(" }")) {
            String inner = table.substring(2, table.length() - 2);
            if (inner.trim().isEmpty()) return "{ " + entry + " }";
            return "{ " + inner + ", " + entry + " }";
        }
        return table;
    }

    private int countTopItems(int base) {
        int count = 0;
        for (int i = base + 1; i < registers.length; i++) {
            if (registers[i] == null) break;
            count++;
        }
        return count;
    }

    private void setRegister(int index, String value) {
        if (index < registers.length) registers[index] = value;
    }

    private void clearRegistersFrom(int start) {
        for (int i = start; i < registers.length; i++) registers[i] = null;
    }

    private void emitRegisterAssignment(int registerIndex, int pc) {
        String localName = getLocalName(registerIndex, pc);
        if (localName == null || isImplicitLoopName(localName)) return;
        String value = registers[registerIndex];
        if (value == null) return;
        if (value.startsWith("{") || (value.startsWith("function(") && value.contains("\n"))) {
            if (value.startsWith("function(") && !value.contains("\n")) {
                boolean firstDecl = !declaredLocals.contains(localName);
                declaredLocals.add(localName);
                emit((firstDecl ? "local " : "") + localName + " = " + value);
            }
            return;
        }
        boolean firstDecl = !declaredLocals.contains(localName);
        declaredLocals.add(localName);
        emit((firstDecl ? "local " : "") + localName + " = " + value);
    }

    private void flushOpenLocals() {
        for (int i = func.numParams; i < func.maxStackSize; i++) {
            String localName = i < func.localVars.size() ? func.localVars.get(i).name : null;
            if (localName == null || isImplicitLoopName(localName)) continue;
            if (declaredLocals.contains(localName)) continue;
            String value = registers[i];
            if (value == null || value.startsWith("function(")) continue;
            declaredLocals.add(localName);
            emit("local " + localName + " = " + value);
        }
    }

    private boolean isImplicitLoopName(String name) {
        return name != null && (name.startsWith("(for ") || name.startsWith("("));
    }

    private String negateCondition(String condition) {
        if (condition.startsWith("not (") && condition.endsWith(")")) {
            return condition.substring(5, condition.length() - 1);
        }
        if (condition.contains(" == ")) return condition.replace(" == ", " ~= ");
        if (condition.contains(" ~= ")) return condition.replace(" ~= ", " == ");
        if (condition.contains(" < ") && !condition.contains(" <=")) return condition.replace(" < ", " >= ");
        if (condition.contains(" > ") && !condition.contains(" >=")) return condition.replace(" > ", " <= ");
        if (condition.contains(" <= ")) return condition.replace(" <= ", " > ");
        if (condition.contains(" >= ")) return condition.replace(" >= ", " < ");
        return "not (" + condition + ")";
    }

    private void emitLabelIfNeeded(int pc) {
        if (labelTargets.contains(pc) && !suppressedLabels.contains(pc)) {
            emit("::L" + pc + "::");
        }
    }

    private String indentStr(int level) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) sb.append("  ");
        return sb.toString();
    }

    private void emit(String line) {
        String prefix = indentStr(indent);
        for (String part : line.split("\n")) lines.add(prefix + part);
    }
}
