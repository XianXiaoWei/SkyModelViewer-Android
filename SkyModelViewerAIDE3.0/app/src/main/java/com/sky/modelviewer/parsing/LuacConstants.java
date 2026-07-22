package com.sky.modelviewer.parsing;

/**
 * Lua bytecode constants for Sky: Children of the Light (China client) luac format.
 * Ported from that-sky-project/that-luac-decompiler src/constants.js
 *
 * Key difference from standard Lua 5.2: arithmetic opcodes (DIV/MOD/POW/ADD/SUB/MUL)
 * are moved to positions 0-5, and the rest shift by 6.
 */
public final class LuacConstants {

    /** Lua bytecode signature: "\x1bLua" */
    public static final byte[] LUA_SIGNATURE = {0x1b, 0x4c, 0x75, 0x61};

    /** Client (China) opcode names - opcode index -> mnemonic */
    public static final String[] OPCODE_NAMES = {
        "DIV", "MOD", "POW", "ADD", "SUB", "MUL",
        "MOVE", "LOADK", "LOADKX", "LOADBOOL", "LOADNIL", "GETUPVAL",
        "GETTABUP", "GETTABLE", "SETTABUP", "SETUPVAL", "SETTABLE",
        "NEWTABLE", "SELF", "UNM", "NOT", "LEN", "CONCAT", "JMP",
        "EQ", "LT", "LE", "TEST", "TESTSET", "CALL", "TAILCALL", "RETURN",
        "FORLOOP", "FORPREP", "TFORCALL", "TFORLOOP", "SETLIST",
        "CLOSURE", "VARARG", "EXTRAARG",
    };

    /** Constant tags */
    public static final int TAG_NIL = 0;
    public static final int TAG_BOOLEAN = 1;
    public static final int TAG_NUMBER = 3;
    public static final int TAG_STRING = 4;

    private LuacConstants() {}
}
