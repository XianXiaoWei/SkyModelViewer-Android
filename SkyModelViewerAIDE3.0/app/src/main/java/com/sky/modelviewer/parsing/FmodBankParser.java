package com.sky.modelviewer.parsing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parser for FMOD {@code .bank} files (RIFF/FEV format) shipped with
 * <i>Sky: Children of the Light</i>.
 *
 * <h3>File layout</h3>
 * <pre>
 *   RIFF &lt;size&gt; FEV                  // top-level RIFF container, format = "FEV "
 *     FMT  &lt;8&gt;  version(u32) reserved(u32)   // typically version 142
 *     LIST &lt;size&gt; PROJ               // main project list
 *       BNKI  &lt;32&gt;  guid(16) flags(16)        // bank info / bank GUID
 *       LIST &lt;size&gt; IBSS               // sound-bank / bus list
 *         LCNT &lt;4&gt;  count(u32)             // (some banks use "SLNT")
 *         LIST &lt;size&gt; IBUS (repeated)       // one entry per bus
 *           IBSB &lt;42&gt;  guid(16) props        // bus GUID + properties
 *           BUS  &lt;44&gt;  ...                   // bus parameters
 *           LIST &lt;4&gt;  PRPS                   // properties list
 *       LIST &lt;size&gt; EVTS               // event list
 *         LCNT &lt;4&gt;  count(u32)
 *         LIST &lt;size&gt; EVNT (repeated)       // one entry per event
 *           EVTB &lt;147&gt; guid(16) props        // event GUID + properties
 *           LIST &lt;4&gt;  PRPS
 *       RMBD ...                            // reverb / 3D definitions
 *       STBL &lt;count + strings&gt;             // string table (may be empty)
 *       HASH &lt;entries&gt;                     // hash table for name lookup
 * </pre>
 *
 * <p>Bus and event <em>path</em> names (e.g. {@code event:/Ambience/Cave}) are
 * normally stored in the companion {@code .strings.bank}; this parser extracts
 * every GUID it can find plus all readable strings embedded throughout the file
 * (markers, parameters, music sections, etc.). When a name is not present in the
 * binary it is left as an empty string.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   FmodBankParser p = new FmodBankParser();
 *   p.setBankName("Common");
 *   p.parse(data);
 *   BankInfo bank = p.getBankInfo();
 *   List&lt;BusEntry&gt; buses = p.getBuses();
 *   List&lt;EventEntry&gt; events = p.getEvents();
 *   List&lt;StringEntry&gt; strings = p.getStrings();
 * </pre>
 *
 * <p>The parser is robust against malformed data: every read is bounds-checked
 * and the recursive walk stops as soon as a chunk identifier or size looks
 * invalid.</p>
 */
public final class FmodBankParser {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final int MAX_DEPTH = 32;
    private static final int MIN_STRING_LEN = 5;

    /** Known RIFF/FMOD chunk type IDs that should not be treated as strings. */
    private static final java.util.Set<String> RIFF_CHUNK_IDS = new java.util.HashSet<String>(java.util.Arrays.asList(
        "RIFF","LIST","FEV ","PROJ","BNKI","MODS","MODU","MODB","SNDH","SNDB",
        "STDT","STBL","GBSS","MBSS","WAIS","WAIT","WAIB","INST","EVTB","EVNT",
        "BUS ","IBUS","IBSB","GBSB","MBUS","PRPS","HASH","FMT ","LCNT","SLNT",
        "NAME","REFI","PLAT","RMBD","DEL ","MUTE","SEND","CGRP","PROP","BIN ",
        "WAVS","WAV ","SNAM","TAGS","USER","MARK","LOOP","SRCH","HEAD","DATA",
        "FACT","CUE ","LIST","SMPL","FLLR","ID3 ","INFO","DATA"
    ));
    private static final int STRING_HARD_CAP = 200_000;

    // ═══════════════════════════════════════════════════════════════
    //  Inner data classes
    // ═══════════════════════════════════════════════════════════════

    /** Bank-level information: the bank GUID (from {@code BNKI}) and its name. */
    public static final class BankInfo {
        /** 16-byte bank GUID formatted as {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}, or "". */
        public final String guid;
        /** Bank name (typically derived from the file name). */
        public String name;

        public BankInfo(String guid, String name) {
            this.guid = guid == null ? "" : guid;
            this.name = name == null ? "" : name;
        }

        @Override
        public String toString() {
            return name + " {" + guid + "}";
        }
    }

    /** A single bus entry: GUID (from {@code IBSB}) and name. */
    public static final class BusEntry {
        /** Bus GUID (filled in when the {@code IBSB} sub-chunk is found). */
        public String guid;
        public String name;

        public BusEntry(String guid, String name) {
            this.guid = guid == null ? "" : guid;
            this.name = name == null ? "" : name;
        }

        @Override
        public String toString() {
            return (name == null || name.isEmpty() ? "(unnamed)" : name) + " {" + guid + "}";
        }
    }

    /** A single event entry: GUID (from {@code EVTB}) and name. */
    public static final class EventEntry {
        /** Event GUID (filled in when the {@code EVTB} sub-chunk is found). */
        public String guid;
        public String name;

        public EventEntry(String guid, String name) {
            this.guid = guid == null ? "" : guid;
            this.name = name == null ? "" : name;
        }

        @Override
        public String toString() {
            return (name == null || name.isEmpty() ? "(unnamed)" : name) + " {" + guid + "}";
        }
    }

    /** A readable string discovered in the binary, with its byte offset. */
    public static final class StringEntry {
        public final int offset;
        public final String text;
        public StringEntry(int offset, String text) {
            this.offset = offset;
            this.text = text == null ? "" : text;
        }
        @Override
        public String toString() { return "0x" + Integer.toHexString(offset) + ": " + text; }
    }

    /** An audio sample/module found in the bank (MODU or WAIT entry). */
    public static final class AudioSampleEntry {
        public final int index;
        public final String guid;
        public final String type; // "MODU" or "WAIT"
        public final int offset;
        public final int size;
        public AudioSampleEntry(int index, String guid, String type, int offset, int size) {
            this.index = index;
            this.guid = guid == null ? "" : guid;
            this.type = type;
            this.offset = offset;
            this.size = size;
        }
        @Override
        public String toString() { return type + " #" + index + " {" + guid + "}"; }
    }

    // ═══════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════

    private byte[] data;
    private ByteBuffer buf;

    private String bankName = "";
    private int version;
    private int fileSize;
    private boolean validFev;

    private BankInfo bankInfo;
    private final List<BusEntry> buses = new ArrayList<BusEntry>();
    private final List<EventEntry> events = new ArrayList<EventEntry>();
    private final List<StringEntry> strings = new ArrayList<StringEntry>();
    private final List<AudioSampleEntry> samples = new ArrayList<AudioSampleEntry>();
    private final Set<String> seenStrings = new LinkedHashSet<String>();
    private int moduCount = 0;
    private int waitCount = 0;

    private final Map<String, Integer> chunkCounts = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> listTypeCounts = new LinkedHashMap<String, Integer>();

    // Context stacks for the recursive walk (IBUS / EVNT do not nest, so a
    // single slot is enough, but we save/restore to be safe).
    private BusEntry currentBus;
    private EventEntry currentEvent;

    public FmodBankParser() {
        bankInfo = new BankInfo("", "");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Configuration
    // ═══════════════════════════════════════════════════════════════

    /** Sets the bank name (normally derived from the file name). */
    public void setBankName(String name) {
        this.bankName = name == null ? "" : name;
        if (bankInfo != null) bankInfo.name = this.bankName;
    }

    /**
     * Derives a bank name from a file path by stripping the directory and the
     * {@code .bank} extension (e.g. {@code "/x/y/Common.bank"} -&gt; {@code "Common"}).
     */
    public static String deriveBankName(String filename) {
        if (filename == null) return "";
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Main entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parses the supplied {@code .bank} byte array. Results are available via
     * the getters after this returns. Calling this again resets all state.
     */
    public void parse(byte[] data) {
        reset();
        this.data = data == null ? new byte[0] : data;
        this.fileSize = this.data.length;
        this.buf = ByteBuffer.wrap(this.data).order(ByteOrder.LITTLE_ENDIAN);

        if (this.data.length < 12) {
            bankInfo = new BankInfo("", bankName);
            return;
        }

        // Validate the RIFF/FEV header.
        if (eq(data, 0, "RIFF") && eq(data, 8, "FEV ")) {
            validFev = true;
            try {
                walkChunks(0, this.data.length, 0);
            } catch (Throwable t) {
                // Best effort: keep whatever was parsed so far.
            }
        }

        if (bankInfo == null) {
            bankInfo = new BankInfo("", bankName);
        } else {
            bankInfo.name = bankName;
        }

        // Whole-file string scan (independent of the chunk walk).
        try {
            scanStrings(this.data);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Convenience: parse and set the bank name in one call. */
    public void parse(byte[] data, String bankName) {
        setBankName(bankName);
        parse(data);
    }

    /** Convenience static helper: parse a byte array and return the parser. */
    public static FmodBankParser parseBank(byte[] data) {
        FmodBankParser p = new FmodBankParser();
        p.parse(data);
        return p;
    }

    private void reset() {
        data = null;
        buf = null;
        version = 0;
        fileSize = 0;
        validFev = false;
        bankInfo = null;
        buses.clear();
        events.clear();
        strings.clear();
        seenStrings.clear();
        chunkCounts.clear();
        listTypeCounts.clear();
        currentBus = null;
        currentEvent = null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Recursive RIFF / LIST walker
    // ═══════════════════════════════════════════════════════════════

    /**
     * Walks RIFF chunks in the range [start, end). For {@code RIFF} and
     * {@code LIST} chunks the body (minus the 4-byte type) is walked
     * recursively. Chunks whose FourCC is not made of printable identifier
     * bytes stop the walk at the current level (guards against misalignment).
     */
    private void walkChunks(int start, int end, int depth) {
        if (depth > MAX_DEPTH) return;
        int off = start;
        final int n = data.length;
        while (off + 8 <= end && off + 8 <= n) {
            if (!isChunkId(off)) break;            // not a real chunk boundary
            String id = readFourCC(off);
            int size = readInt(off + 4);
            if (size < 0) break;                   // sanity
            int body = off + 8;
            int bodyEnd = body + size;
            if (bodyEnd > end || bodyEnd > n) bodyEnd = Math.min(end, n);
            if (body > bodyEnd) break;

            incChunk(id);

            if (id.equals("RIFF")) {
                // body[0..4) = format ("FEV "); recurse over the rest.
                if (body + 4 <= bodyEnd) {
                    walkChunks(body + 4, bodyEnd, depth + 1);
                }
            } else if (id.equals("LIST")) {
                if (body + 4 <= bodyEnd) {
                    String ltype = readFourCC(body);
                    incListType(ltype);
                    handleList(ltype, body + 4, bodyEnd, depth);
                }
            } else if (id.equals("FMT ")) {
                if (size >= 4) version = readInt(body);
            } else if (id.equals("BNKI")) {
                if (size >= 16) {
                    bankInfo = new BankInfo(readGuid(body), bankName);
                }
            } else if (id.equals("IBSB") || id.equals("GBSB")) {
                if (size >= 16 && currentBus != null && currentBus.guid.isEmpty()) {
                    currentBus.guid = readGuid(body);
                }
            } else if (id.equals("EVTB")) {
                if (size >= 16 && currentEvent != null && currentEvent.guid.isEmpty()) {
                    currentEvent.guid = readGuid(body);
                }
            } else if (id.equals("STBL")) {
                parseStbl(body, bodyEnd);
            } else if (id.equals("HASH")) {
                // Hash table for name lookup. Only its presence is recorded
                // (via chunkCounts); fully decoding it is not required.
            } else if (id.equals("MODB")) {
                // Module definition - extract GUID
                if (size >= 16) {
                    String guid = readGuid(body);
                    samples.add(new AudioSampleEntry(moduCount, guid, "MODU", off, size));
                    moduCount++;
                }
            } else if (id.equals("WAIB")) {
                // Waveform Audio Instrument Body - extract GUID
                if (size >= 16) {
                    String guid = readGuid(body);
                    samples.add(new AudioSampleEntry(waitCount, guid, "WAIT", off, size));
                    waitCount++;
                }
            }
            // RMBD (reverb/3D), LCNT/SLNT (counts), BUS, PRPS, etc. are
            // counted automatically and otherwise skipped.

            // Advance past this chunk, honouring RIFF word-alignment padding.
            int adv = 8 + size;
            if ((size & 1) != 0) adv++;
            if (adv < 8) break;                    // sanity
            off += adv;
        }
    }

    /** Dispatches a {@code LIST} body based on its type identifier. */
    private void handleList(String ltype, int start, int end, int depth) {
        if (ltype == null) {
            walkChunks(start, end, depth + 1);
            return;
        }
        if (ltype.equals("IBUS")) {
            BusEntry bus = new BusEntry("", "");
            buses.add(bus);
            BusEntry prev = currentBus;
            currentBus = bus;
            walkChunks(start, end, depth + 1);
            currentBus = prev;
        } else if (ltype.equals("EVNT")) {
            EventEntry ev = new EventEntry("", "");
            events.add(ev);
            EventEntry prev = currentEvent;
            currentEvent = ev;
            walkChunks(start, end, depth + 1);
            currentEvent = prev;
        } else {
            walkChunks(start, end, depth + 1);
        }
    }

    /**
     * Reads the {@code STBL} string table: a u32 count followed by that many
     * null-terminated strings. STBL strings are authoritative names (often
     * containing {@code /} or {@code :}) so they are accepted regardless of the
     * strict identifier filter used by {@link #scanStrings(byte[])}.
     */
    private void parseStbl(int start, int end) {
        int p = start;
        if (p + 4 > end) return;
        int count = readInt(p);
        p += 4;
        if (count < 0 || count > 1_000_000) return;   // sanity
        for (int i = 0; i < count && p < end; i++) {
            int s = p;
            int e = indexOfNull(p, end);
            if (e < 0) e = end;
            if (e > s) {
                String str = new String(data, s, e - s, ASCII);
                addString(s, str);
            }
            p = e + 1;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  String extraction
    // ═══════════════════════════════════════════════════════════════

    /**
     * Scans the whole file for null-terminated ASCII strings consisting solely
     * of alphanumeric characters, spaces and underscores, with length &gt;= 4.
     * Duplicates are collapsed (the first offset is kept).
     */
    private void scanStrings(byte[] src) {
        int i = 0;
        int n = src.length;
        while (i < n) {
            if (strings.size() >= STRING_HARD_CAP) break;
            byte b = src[i];
            if (isStringChar(b)) {
                int start = i;
                int j = i;
                while (j < n && isStringChar(src[j])) j++;
                int len = j - start;
                // Require a null terminator (FMOD strings are null-terminated).
                if (len >= MIN_STRING_LEN && j < n && src[j] == 0) {
                    String s = new String(src, start, len, ASCII);
                    addString(start, s);
                }
                i = j + 1;
            } else {
                i++;
            }
        }
    }

    /** Adds a string entry, collapsing duplicates by text (first offset wins). */
    private void addString(int offset, String text) {
        if (text == null) return;
        if (text.isEmpty()) return;
        // Filter out RIFF chunk type IDs (4-8 chars, all uppercase)
        if (text.length() <= 8 && text.equals(text.toUpperCase()) && !text.contains(" ") && !text.contains("/")) {
            // Check if it's a known chunk ID or a concatenation of two 4-char IDs
            if (RIFF_CHUNK_IDS.contains(text)) return;
            if (text.length() == 8) {
                String first = text.substring(0, 4);
                String second = text.substring(4, 8);
                if (RIFF_CHUNK_IDS.contains(first) || RIFF_CHUNK_IDS.contains(second)) return;
            }
            // Skip pure uppercase strings with no meaning
            if (text.length() <= 5 && text.matches("[A-Z]{4,5}")) return;
        }
        if (seenStrings.contains(text)) return;
        seenStrings.add(text);
        strings.add(new StringEntry(offset, text));
    }

    private static boolean isStringChar(byte b) {
        return (b >= 'A' && b <= 'Z')
            || (b >= 'a' && b <= 'z')
            || (b >= '0' && b <= '9')
            || b == ' '
            || b == '_'
            || b == '/'
            || b == ':'
            || b == '.'
            || b == '-';
    }

    // ═══════════════════════════════════════════════════════════════
    //  Low-level readers
    // ═══════════════════════════════════════════════════════════════

    private String readFourCC(int off) {
        return new String(data, off, 4, ASCII);
    }

    /** A valid RIFF chunk id is 4 bytes of [A-Za-z0-9 ] (space allowed). */
    private boolean isChunkId(int off) {
        if (off + 4 > data.length) return false;
        for (int i = 0; i < 4; i++) {
            byte b = data[off + i];
            if (!((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9') || b == ' ')) {
                return false;
            }
        }
        return true;
    }

    private int readInt(int off) {
        if (off + 4 > data.length) return 0;
        return buf.getInt(off);
    }

    private int indexOfNull(int from, int to) {
        for (int i = from; i < to; i++) {
            if (data[i] == 0) return i;
        }
        return -1;
    }

    /**
     * Reads a 16-byte FMOD GUID and formats it in the canonical
     * {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx} form (lower-case).
     * The first three fields are little-endian (Data1=u32, Data2=u16,
     * Data3=u16); the final 8 bytes are taken in order.
     */
    private String readGuid(int off) {
        if (off + 16 > data.length) return "";
        int d1 = buf.getInt(off);
        int d2 = buf.getShort(off + 4) & 0xFFFF;
        int d3 = buf.getShort(off + 6) & 0xFFFF;
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = data[off + 8 + i];
        return String.format("%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                d1, d2, d3,
                b[0] & 0xFF, b[1] & 0xFF,
                b[2] & 0xFF, b[3] & 0xFF, b[4] & 0xFF,
                b[5] & 0xFF, b[6] & 0xFF, b[7] & 0xFF);
    }

    private void incChunk(String id) {
        Integer c = chunkCounts.get(id);
        chunkCounts.put(id, c == null ? 1 : c + 1);
    }

    private void incListType(String ltype) {
        Integer c = listTypeCounts.get(ltype);
        listTypeCounts.put(ltype, c == null ? 1 : c + 1);
    }

    private static boolean eq(byte[] d, int off, String s) {
        if (off + s.length() > d.length) return false;
        for (int i = 0; i < s.length(); i++) {
            if (d[off + i] != (byte) s.charAt(i)) return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Getters
    // ═══════════════════════════════════════════════════════════════

    /** Bank information (GUID + name). Never {@code null}. */
    public BankInfo getBankInfo() {
        return bankInfo;
    }

    /** The bank GUID string, or "" if no {@code BNKI} chunk was found. */
    public String getBankGuid() {
        return bankInfo != null ? bankInfo.guid : "";
    }

    /** The bank name. */
    public String getBankName() {
        return bankName;
    }

    /** FEV format version (typically 142), or 0 if unknown. */
    public int getVersion() {
        return version;
    }

    /** Size of the parsed byte array. */
    public int getFileSize() {
        return fileSize;
    }

    /** Whether the input started with a valid {@code RIFF/FEV } header. */
    public boolean isValidFev() {
        return validFev;
    }

    /** All bus entries (GUID + name) extracted from {@code LIST/IBUS} chunks. */
    public List<BusEntry> getBuses() {
        return buses;
    }

    public int getBusCount() {
        return buses.size();
    }

    /** All event entries (GUID + name) extracted from {@code LIST/EVNT} chunks. */
    public List<EventEntry> getEvents() {
        return events;
    }

    public int getEventCount() {
        return events.size();
    }

    /** All readable strings (offset + text) found throughout the file. */
    public List<StringEntry> getStrings() {
        return strings;
    }

    public int getStringCount() {
        return strings.size();
    }

    public List<AudioSampleEntry> getSamples() {
        return samples;
    }

    public int getModuCount() { return moduCount; }
    public int getWaitCount() { return waitCount; }

    /**
     * Merges strings from another parser (e.g. from a .strings.bank file).
     * Only strings not already present (by text) are added.
     */
    public void mergeStrings(List<StringEntry> other) {
        if (other == null) return;
        for (StringEntry se : other) {
            if (!seenStrings.contains(se.text)) {
                seenStrings.add(se.text);
                strings.add(new StringEntry(se.offset, se.text));
            }
        }
    }

    /** Counts of each chunk FourCC encountered during the walk. */
    public Map<String, Integer> getChunkCounts() {
        return chunkCounts;
    }

    /** Counts of each {@code LIST} type encountered (e.g. IBUS, EVNT, PROJ). */
    public Map<String, Integer> getListTypeCounts() {
        return listTypeCounts;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Summary
    // ═══════════════════════════════════════════════════════════════

    /** Returns a human-readable structural summary of the bank. */
    public String getStructureSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("FMOD Bank: ").append(bankName.isEmpty() ? "(unnamed)" : bankName);
        sb.append("  (version ").append(version).append(", ");
        sb.append(fileSize).append(" bytes, ");
        sb.append(validFev ? "valid FEV" : "NOT FEV").append(")\n");
        sb.append("Bank GUID: ").append(getBankGuid()).append('\n');
        sb.append("Buses:   ").append(buses.size()).append('\n');
        sb.append("Events:  ").append(events.size()).append('\n');
        sb.append("Strings: ").append(strings.size()).append('\n');

        sb.append("---- LIST types ----\n");
        for (Map.Entry<String, Integer> e : listTypeCounts.entrySet()) {
            sb.append("  ").append(pad(e.getKey())).append(e.getValue()).append('\n');
        }
        sb.append("---- Chunk counts ----\n");
        for (Map.Entry<String, Integer> e : chunkCounts.entrySet()) {
            sb.append("  ").append(pad(e.getKey())).append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('\'').append(s).append('\'');
        while (sb.length() < 12) sb.append(' ');
        return sb.toString();
    }

    @Override
    public String toString() {
        return getStructureSummary();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Standalone debug entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Reads a {@code .bank} file given as the first argument and prints a
     * structural summary plus the first few buses, events and strings.
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: FmodBankParser <file.bank>");
            return;
        }
        try {
            java.io.File f = new java.io.File(args[0]);
            byte[] bytes = new byte[(int) f.length()];
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.FileInputStream(f));
            try {
                in.readFully(bytes);
            } finally {
                in.close();
            }
            FmodBankParser p = new FmodBankParser();
            p.setBankName(deriveBankName(args[0]));
            p.parse(bytes);

            System.out.println(p.getStructureSummary());

            int show = Math.min(8, p.getBusCount());
            System.out.println("---- First " + show + " buses ----");
            for (int i = 0; i < show; i++) System.out.println("  " + p.getBuses().get(i));

            show = Math.min(8, p.getEventCount());
            System.out.println("---- First " + show + " events ----");
            for (int i = 0; i < show; i++) System.out.println("  " + p.getEvents().get(i));

            show = Math.min(40, p.getStringCount());
            System.out.println("---- First " + show + " strings ----");
            for (int i = 0; i < show; i++) System.out.println("  " + p.getStrings().get(i));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
