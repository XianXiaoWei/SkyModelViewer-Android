package com.sky.modelviewer.parsing;

/**
 * Parses Sky: Children of the Light (China client) luac bytecode format.
 * Ported from that-sky-project/that-luac-decompiler src/parser/luac-parser.js
 */
public class LuacParser {

    public LuacFunction parse(byte[] buffer) {
        LuacBinaryReader reader = new LuacBinaryReader(buffer);

        byte[] signature = reader.readBytes(4);
        for (int i = 0; i < 4; i++) {
            if (signature[i] != LuacConstants.LUA_SIGNATURE[i]) {
                throw new RuntimeException("Invalid Lua bytecode signature");
            }
        }

        skipHeader(reader);
        return parseFunction(reader, null);
    }

    private void skipHeader(LuacBinaryReader reader) {
        reader.readByte();
        reader.readByte();
        reader.readByte();
        reader.readByte();
        reader.readByte();
        reader.readByte();
        reader.readByte();
        reader.readByte();
        reader.readBytes(6);
    }

    private LuacFunction parseFunction(LuacBinaryReader reader, String parentSource) {
        LuacFunction func = new LuacFunction();

        func.linedefined = reader.readInt32();
        func.lastlinedefined = reader.readInt32();
        func.numParams = reader.readByte();
        func.isVararg = reader.readByte();
        func.maxStackSize = reader.readByte();

        int numInstructions = reader.readInt32();
        func.instructions = new int[numInstructions];
        for (int i = 0; i < numInstructions; i++) {
            func.instructions[i] = reader.readUint32Int();
        }

        int numConstants = reader.readInt32();
        for (int i = 0; i < numConstants; i++) {
            func.constants.add(readConstant(reader));
        }

        int numProtos = reader.readInt32();
        for (int i = 0; i < numProtos; i++) {
            func.protos.add(parseFunction(reader, null));
        }

        int numUpvalues = reader.readInt32();
        for (int i = 0; i < numUpvalues; i++) {
            int instack = reader.readByte();
            int idx = reader.readByte();
            func.upvalues.add(new LuacFunction.Upvalue(instack, idx));
        }

        func.source = reader.readString();
        if (func.source == null || func.source.isEmpty()) {
            func.source = parentSource;
        }

        int numLineInfo = reader.readInt32();
        func.lineInfo = new int[numLineInfo];
        for (int i = 0; i < numLineInfo; i++) {
            func.lineInfo[i] = reader.readInt32();
        }

        int numLocalVars = reader.readInt32();
        for (int i = 0; i < numLocalVars; i++) {
            String name = reader.readString();
            int startPC = reader.readInt32();
            int endPC = reader.readInt32();
            func.localVars.add(new LuacFunction.LocalVar(name, startPC, endPC));
        }

        int numUpvalueNames = reader.readInt32();
        for (int i = 0; i < numUpvalueNames; i++) {
            String name = reader.readString();
            if (i < func.upvalues.size()) {
                func.upvalues.get(i).name = name;
            }
        }

        return func;
    }

    private LuacFunction.Constant readConstant(LuacBinaryReader reader) {
        int tag = reader.readByte();
        switch (tag) {
            case LuacConstants.TAG_NIL:
                return new LuacFunction.Constant("nil", null);
            case LuacConstants.TAG_BOOLEAN:
                return new LuacFunction.Constant("boolean", reader.readByte() != 0);
            case LuacConstants.TAG_NUMBER:
                return new LuacFunction.Constant("number", reader.readLuaNumber());
            case LuacConstants.TAG_STRING:
                return new LuacFunction.Constant("string", reader.readString());
            default:
                throw new RuntimeException("Unknown constant tag: " + tag);
        }
    }
}
