package com.sky.modelviewer.parsing;

/**
 * Decodes FMOD FADPCM (FSB5 mode 16) audio to 16-bit PCM.
 *
 * <p>FADPCM is "basically XA/PSX ADPCM with a fancy header layout" (vgmstream).
 * Each frame is 0x8c (140) bytes: a 12-byte header followed by 128 bytes of
 * 4-bit ADPCM nibbles (256 samples per frame).</p>
 *
 * <p>For multichannel audio, frames are externally interleaved:
 * frame_ch0, frame_ch1, frame_ch0, frame_ch1, ...</p>
 *
 * <p>Algorithm debugged from FMOD's PC DLLs, byte-accurate with vgmstream's
 * implementation in {@code src/coding/fadpcm_decoder.c}.</p>
 */
public final class FadpcmDecoder {

    private static final int FRAME_SIZE = 0x8c;       // 140 bytes per frame
    private static final int HEADER_SIZE = 0x0c;      // 12-byte header
    private static final int SAMPLES_PER_FRAME = (FRAME_SIZE - HEADER_SIZE) * 2; // 256

    /** Tweaked XA/PSX coefficient table (coefs << 6 in vgmstream, applied with >> 6 at decode). */
    private static final int[][] COEFS = {
        {  0,  0 },
        { 60,  0 },
        {122, 60 },
        {115, 52 },
        { 98, 55 },
        {  0,  0 },
        {  0,  0 },
        {  0,  0 },
    };

    private static int readU32LE(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
             | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static int readS16LE(byte[] d, int off) {
        int v = (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
        return v >= 32768 ? v - 65536 : v;
    }

    /**
     * Decode FADPCM data to 16-bit PCM bytes (little-endian, interleaved).
     *
     * @param data     Raw FADPCM audio data
     * @param channels Number of channels (1=mono, 2=stereo)
     * @return 16-bit PCM byte array ready for WAV, or null on error
     */
    public static byte[] decode(byte[] data, int channels) {
        if (data == null || data.length < FRAME_SIZE || channels < 1) return null;

        int frameGroupSize = FRAME_SIZE * channels;
        int numFrameGroups = data.length / frameGroupSize;
        int totalSamplesPerChannel = numFrameGroups * SAMPLES_PER_FRAME;
        int totalSamples = totalSamplesPerChannel * channels;

        short[] pcm = new short[totalSamples];

        for (int fg = 0; fg < numFrameGroups; fg++) {
            for (int ch = 0; ch < channels; ch++) {
                int fo = fg * frameGroupSize + ch * FRAME_SIZE;
                if (fo + FRAME_SIZE > data.length) break;

                // Parse 12-byte header
                int coefs  = readU32LE(data, fo);
                int shifts = readU32LE(data, fo + 4);
                int hist1  = readS16LE(data, fo + 8);
                int hist2  = readS16LE(data, fo + 10);

                int sampleInFrame = 0;

                // 8 sets, each with 4 groups of 8 nibbles = 32 samples per set
                for (int set = 0; set < 8; set++) {
                    int index = ((coefs >> (set * 4)) & 0x0f) % 7;
                    int shift = 22 - ((shifts >> (set * 4)) & 0x0f);
                    int coef1 = COEFS[index][0];
                    int coef2 = COEFS[index][1];

                    for (int group = 0; group < 4; group++) {
                        int nibbles = readU32LE(data, fo + 0x0c + 0x10 * set + 0x04 * group);
                        for (int k = 0; k < 8; k++) {
                            int sample = (nibbles >> (k * 4)) & 0x0f;
                            sample = (sample << 28) >> shift;  // 32b sign-extend + scale
                            sample = (sample - hist2 * coef2 + hist1 * coef1) >> 6;
                            if (sample > 32767) sample = 32767;
                            else if (sample < -32768) sample = -32768;

                            int outIdx = (fg * SAMPLES_PER_FRAME + sampleInFrame) * channels + ch;
                            pcm[outIdx] = (short) sample;
                            sampleInFrame++;

                            hist2 = hist1;
                            hist1 = sample;
                        }
                    }
                }
            }
        }

        // Convert short[] → little-endian byte[]
        byte[] bytes = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            bytes[i * 2]     = (byte) pcm[i];
            bytes[i * 2 + 1] = (byte) (pcm[i] >> 8);
        }
        return bytes;
    }
}
