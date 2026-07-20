package com.sky.modelviewer.parsing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Rebuilds standard OGG/Vorbis files from FMOD Vorbis sample data.
 *
 * <p>FMOD Vorbis strips the three Vorbis header packets (identification, comment,
 * setup) from the OGG container and stores only the audio packets, each prefixed
 * with a uint16 length. The setup header is referenced by a CRC32 in the
 * VORBISDATA metadata chunk.</p>
 *
 * <p>This class uses a pre-extracted setup header (from vgmstream's codebook
 * table, CRC32 0xD7913109) that matches Sky: Children of the Light's FMOD
 * Vorbis encoding. The rebuilt OGG file can be played by Android MediaPlayer.</p>
 *
 * <h3>OGG page format</h3>
 * <pre>
 *  0x00(4): "OggS" capture pattern
 *  0x04(1): stream structure version (0)
 *  0x05(1): header type flag (0x02=BOS, 0x04=EOS)
 *  0x06(8): granule position (int64)
 *  0x0e(4): serial number (uint32)
 *  0x12(4): page sequence (uint32)
 *  0x16(4): checksum (uint32, OGG CRC32)
 *  0x1a(1): page segments count
 *  0x1b(n): segment table
 *  0x--(n): data
 * </pre>
 */
public final class FmodVorbisDecoder {

    /** OGG CRC32 lookup table (polynomial 0x04c11db7). */
    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                if ((r & 0x80000000) != 0) {
                    r = (r << 1) ^ 0x04c11db7;
                } else {
                    r = r << 1;
                }
            }
            CRC_TABLE[i] = r;
        }
    }

    /** Compute OGG CRC32 (different from standard zlib CRC32). */
    private static int oggCrc(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc = ((crc << 8) ^ CRC_TABLE[((crc >>> 24) & 0xFF) ^ (b & 0xFF)]);
        }
        return crc;
    }

    /**
     * Build an OGG page.
     *
     * @param headerType 0x02=BOS, 0x04=EOS, 0x00=continuation
     * @param granule    granule position
     * @param serial     stream serial number
     * @param pageSeq    page sequence number
     * @param packets    array of packet data to include in this page
     */
    private static byte[] buildOggPage(int headerType, long granule, int serial,
                                        int pageSeq, byte[]... packets) {
        // Build segment table
        ByteArrayOutputStream segTable = new ByteArrayOutputStream();
        for (byte[] pkt : packets) {
            int len = pkt.length;
            while (len >= 255) {
                segTable.write(255);
                len -= 255;
            }
            segTable.write(len);
        }
        byte[] segments = segTable.toByteArray();

        // Build body
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] pkt : packets) {
            body.write(pkt, 0, pkt.length);
        }
        byte[] bodyBytes = body.toByteArray();

        // Build header (without checksum)
        ByteBuffer hdr = ByteBuffer.allocate(27 + segments.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        hdr.put((byte) 'O').put((byte) 'g').put((byte) 'g').put((byte) 'S'); // capture
        hdr.put((byte) 0);                    // version
        hdr.put((byte) headerType);           // header type
        hdr.putLong(granule);                 // granule position
        hdr.putInt(serial);                   // serial
        hdr.putInt(pageSeq);                  // page sequence
        hdr.putInt(0);                        // checksum (placeholder)
        hdr.put((byte) segments.length);      // segment count
        hdr.put(segments);                    // segment table

        // Combine header + body
        byte[] page = new byte[hdr.capacity() + bodyBytes.length];
        System.arraycopy(hdr.array(), 0, page, 0, hdr.capacity());
        System.arraycopy(bodyBytes, 0, page, hdr.capacity(), bodyBytes.length);

        // Compute and insert CRC
        int crc = oggCrc(page);
        page[22] = (byte) crc;
        page[23] = (byte) (crc >>> 8);
        page[24] = (byte) (crc >>> 16);
        page[25] = (byte) (crc >>> 24);

        return page;
    }

    /**
     * Build the Vorbis identification header packet.
     *
     * <pre>
     * 0x01 + "vorbis" + version(u32) + channels(u8) + sample_rate(u32)
     *       + bitrate_max(i32) + bitrate_nominal(i32) + bitrate_min(i32)
     *       + blocksize(u8: high nibble=long, low nibble=short) + framing(u8=1)
     * </pre>
     */
    private static byte[] buildIdHeader(int channels, int sampleRate) {
        // FSB Vorbis uses blocksize_short=256 (2^8), blocksize_long=2048 (2^11)
        int blocksizeByte = (11 << 4) | 8;  // long=2^11, short=2^8

        ByteBuffer buf = ByteBuffer.allocate(30)
            .order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x01);                    // packet type: identification
        buf.put((byte) 'v').put((byte) 'o').put((byte) 'r')
           .put((byte) 'b').put((byte) 'i').put((byte) 's');
        buf.putInt(0);                           // version
        buf.put((byte) channels);                // channels
        buf.putInt(sampleRate);                  // sample rate
        buf.putInt(0);                           // bitrate max
        buf.putInt(0);                           // bitrate nominal
        buf.putInt(0);                           // bitrate min
        buf.put((byte) blocksizeByte);           // blocksize
        buf.put((byte) 1);                       // framing bit
        return buf.array();
    }

    /** Build an empty Vorbis comment header packet (with framing flag). */
    private static byte[] buildCommentHeader() {
        ByteBuffer buf = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x03);                    // packet type: comment
        buf.put((byte) 'v').put((byte) 'o').put((byte) 'r')
           .put((byte) 'b').put((byte) 'i').put((byte) 's');
        buf.putInt(0);                           // vendor string length
        buf.putInt(0);                           // comment count
        buf.put((byte) 0x01);                    // framing flag (required by Vorbis spec)
        return buf.array();
    }

    /**
     * Rebuild a complete OGG/Vorbis file from FMOD Vorbis sample data.
     *
     * @param audioData    The raw FMOD Vorbis data (uint16-prefixed packets)
     * @param channels     Number of audio channels
     * @param sampleRate   Sample rate in Hz
     * @param setupHeader  The Vorbis setup header packet (from codebook lookup)
     * @return Complete OGG/Vorbis file as byte array, or null on error
     */
    public static byte[] rebuildOgg(byte[] audioData, int channels, int sampleRate,
                                     byte[] setupHeader) {
        return rebuildOgg(audioData, channels, sampleRate, setupHeader, -1);
    }

    /**
     * Rebuild a complete OGG/Vorbis file from FMOD Vorbis sample data.
     *
     * @param audioData     The raw FMOD Vorbis data (uint16-prefixed packets)
     * @param channels      Number of audio channels
     * @param sampleRate    Sample rate in Hz
     * @param setupHeader   The Vorbis setup header packet (from codebook lookup)
     * @param totalSamples  Total PCM sample count (from FSB5 header), or -1 if unknown
     * @return Complete OGG/Vorbis file as byte array, or null on error
     */
    public static byte[] rebuildOgg(byte[] audioData, int channels, int sampleRate,
                                     byte[] setupHeader, long totalSamples) {
        if (audioData == null || audioData.length < 2 || setupHeader == null) {
            return null;
        }

        try {
            ByteArrayOutputStream ogg = new ByteArrayOutputStream();

            // Build header packets
            byte[] idPacket = buildIdHeader(channels, sampleRate);
            byte[] commentPacket = buildCommentHeader();

            int serial = 1;
            int pageSeq = 0;

            // Page 1: identification header (BOS)
            ogg.write(buildOggPage(0x02, 0, serial, pageSeq, idPacket));
            pageSeq++;

            // Page 2: comment + setup headers
            ogg.write(buildOggPage(0x00, 0, serial, pageSeq, commentPacket, setupHeader));
            pageSeq++;

            // Parse FMOD Vorbis audio packets (uint16 little-endian length prefix)
            int pos = 0;
            int packetNo = 0;
            long granule = 0;

            while (pos + 2 <= audioData.length) {
                int pktLen = (audioData[pos] & 0xFF) | ((audioData[pos + 1] & 0xFF) << 8);
                pos += 2;

                if (pktLen == 0) {
                    break;  // end of packets
                }
                if (pktLen > audioData.length - pos) {
                    break;  // truncated packet
                }

                byte[] pktData = new byte[pktLen];
                System.arraycopy(audioData, pos, pktData, 0, pktLen);
                pos += pktLen;

                // Check if this is the last packet
                boolean isLast = (pos + 2 > audioData.length) ||
                    (((audioData[pos] & 0xFF) | ((audioData[pos + 1] & 0xFF) << 8)) == 0);

                int headerType = isLast ? 0x04 : 0x00;  // EOS flag on last packet

                // Use exact total sample count for the last page's granule position
                long pageGranule = granule;
                if (isLast && totalSamples > 0) {
                    pageGranule = totalSamples;
                }

                // Each audio packet goes in its own OGG page
                ogg.write(buildOggPage(headerType, pageGranule, serial, pageSeq, pktData));
                pageSeq++;
                packetNo++;
                granule += 1024;  // samples per long Vorbis block (2048/2)
            }

            return ogg.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
