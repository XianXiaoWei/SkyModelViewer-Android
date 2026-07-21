package com.sky.modelviewer.parsing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Little-endian binary reader for luac bytecode.
 * Ported from that-sky-project/that-luac-decompiler src/io/binary-reader.js
 */
public class LuacBinaryReader {

    private final byte[] buffer;
    private int offset;

    public LuacBinaryReader(byte[] buffer) {
        this.buffer = buffer;
        this.offset = 0;
    }

    public int getOffset() { return offset; }
    public int getLength() { return buffer.length; }
    public int remaining() { return buffer.length - offset; }

    private void assertReadable(int length) {
        if (offset + length > buffer.length) {
            throw new RuntimeException("EOF at offset " + offset);
        }
    }

    public int readByte() {
        assertReadable(1);
        return buffer[offset++] & 0xFF;
    }

    public int readInt32() {
        assertReadable(4);
        int v = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        offset += 4;
        return v;
    }

    public int readUint32Int() {
        assertReadable(4);
        int v = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        offset += 4;
        return v;
    }

    public double readLuaNumber() {
        assertReadable(8);
        double v = ByteBuffer.wrap(buffer, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
        offset += 8;
        return v;
    }

    /**
     * Read a string: uint32 size (0=null, else size includes trailing \0),
     * then (size-1) UTF-8 bytes + 1 byte \0.
     */
    public String readString() {
        long sizeLong = readUint32Int() & 0xFFFFFFFFL;
        if (sizeLong == 0) return null;
        int size = (int) sizeLong;
        int stringLength = size - 1;
        assertReadable(stringLength + 1);
        String value = new String(buffer, offset, stringLength, java.nio.charset.StandardCharsets.UTF_8);
        offset += stringLength;
        offset++; // skip trailing \0
        return value;
    }

    public byte[] readBytes(int length) {
        assertReadable(length);
        byte[] result = new byte[length];
        System.arraycopy(buffer, offset, result, 0, length);
        offset += length;
        return result;
    }
}
