/*
 * Copyright 2026 Jellyfish Ng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jellyfish.timbre.audio;

import com.jellyfish.timbre.model.MemorySampleSource;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Reads RIFF/WAVE files into memory as planar float32.
 *
 * <p>Supported: format tags 1 (PCM), 3 (IEEE float) and 0xFFFE (EXTENSIBLE with a PCM or float
 * subformat; the channel mask is ignored); 8/16/24/32-bit PCM and 32-bit float; 1 to 64 channels.
 * Unknown chunks are skipped and metadata is not preserved. RF64, RIFX and every other format are
 * rejected with an {@link UnsupportedAudioFormatException}. A declared data size of 0, 0xFFFFFFFF or
 * larger than the file is replaced by the bytes actually present, and a trailing partial frame is
 * dropped.
 *
 * <p>Sample mapping: signed N-bit value / 2^(N-1) (so -32768 maps to -1.0f), 8-bit unsigned
 * (value - 128) / 128, float32 verbatim (NaN and Infinity are not sanitised). 32-bit PCM is
 * <b>not</b> lossless through float32 (24-bit mantissa).
 *
 * <p>Files whose decoded size would exceed half of {@link Runtime#maxMemory()} are rejected.
 *
 * <p><b>Threading:</b> stateless; every call is independent and may run on any thread.
 */
public final class WavReader {

    private static final int BLOCK_BYTES = 1 << 18;
    private static final int INITIAL_STREAM_FRAMES = 1 << 16;
    private static final int MAX_FRAMES = Integer.MAX_VALUE - 8;
    private static final int MAX_FMT_BYTES = 4096;
    private static final long UNKNOWN_SIZE = 0xFFFFFFFFL;

    private WavReader() {
    }

    /**
     * Reads a WAV file. The file size bounds allocation.
     *
     * @param path the file
     * @return the decoded audio and its original encoding
     * @throws UnsupportedAudioFormatException if the file is unsupported or malformed
     * @throws IOException                     on I/O failure
     */
    public static WavFile read(Path path) throws IOException {
        return read(path, defaultLimit());
    }

    /**
     * Reads a WAV file from a stream until the data chunk ends or the stream ends. The stream is
     * not closed.
     *
     * @param in the stream
     * @return the decoded audio and its original encoding
     * @throws UnsupportedAudioFormatException if the data is unsupported or malformed
     * @throws IOException                     on I/O failure
     */
    public static WavFile read(InputStream in) throws IOException {
        return read(in, defaultLimit());
    }

    static WavFile read(Path path, long maxDecodedBytes) throws IOException {
        Objects.requireNonNull(path, "path");
        long size = Files.size(path);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), 1 << 16)) {
            return new Parser(in, size, maxDecodedBytes).parse();
        }
    }

    static WavFile read(InputStream in, long maxDecodedBytes) throws IOException {
        Objects.requireNonNull(in, "in");
        return new Parser(in, -1L, maxDecodedBytes).parse();
    }

    private static long defaultLimit() {
        return Runtime.getRuntime().maxMemory() / 2;
    }

    private record Format(WavEncoding encoding, int channels, int sampleRate, int blockAlign) {
    }

    /** One parse of one stream. */
    private static final class Parser {
        private final InputStream in;
        private final long knownSize;
        private final long limit;
        private long pos;

        Parser(InputStream in, long knownSize, long limit) {
            this.in = in;
            this.knownSize = knownSize;
            this.limit = limit;
        }

        WavFile parse() throws IOException {
            byte[] h = new byte[12];
            int n = readFully(h, 0, 12);
            if (n < 12) {
                throw bad("file too short: found " + n + " bytes, a WAV header needs 12");
            }
            String riff = ascii(h, 0);
            if ("RF64".equals(riff)) {
                throw bad("RF64 files are not supported yet");
            }
            if ("RIFX".equals(riff)) {
                throw bad("RIFX (big-endian) files are not supported yet");
            }
            if (!"RIFF".equals(riff)) {
                throw bad("not a RIFF/WAVE file (found '" + riff + "'; supported: RIFF/WAVE)");
            }
            if (!"WAVE".equals(ascii(h, 8))) {
                throw bad("RIFF file is not WAVE (found form type '" + ascii(h, 8) + "')");
            }
            Format fmt = null;
            byte[] ch = new byte[8];
            while (true) {
                if (readFully(ch, 0, 8) < 8) {
                    throw bad(fmt == null ? "no fmt chunk found (file ends before any format information)"
                            : "no data chunk found (file ends after the fmt chunk)");
                }
                String id = ascii(ch, 0);
                long size = u32(ch, 4);
                if ("data".equals(id)) {
                    if (fmt == null) {
                        throw bad("data chunk found before the fmt chunk; fmt must come first");
                    }
                    return decodeData(fmt, size);
                }
                if ("fmt ".equals(id) && fmt == null) {
                    fmt = parseFmt(size);
                    skipPad(size);
                } else {
                    if (!skip(size + (size & 1))) {
                        throw bad("unexpected end of file inside chunk '" + id + "' (declared " + size + " bytes)");
                    }
                }
            }
        }

        private Format parseFmt(long size) throws IOException {
            if (size < 16) {
                throw bad("fmt chunk is too small: " + size + " bytes, need at least 16");
            }
            if (size > MAX_FMT_BYTES) {
                throw bad("fmt chunk is implausibly large: " + size + " bytes");
            }
            byte[] f = new byte[(int) size];
            if (readFully(f, 0, f.length) < f.length) {
                throw bad("file ends inside the fmt chunk");
            }
            int tag = u16(f, 0);
            int channels = u16(f, 2);
            long rate = u32(f, 4);
            int blockAlign = u16(f, 12);
            int bits = u16(f, 14);
            int effective = tag;
            if (tag == WavConstants.TAG_EXTENSIBLE) {
                if (size < 40 || u16(f, 16) < 22) {
                    throw bad("WAVE_FORMAT_EXTENSIBLE fmt chunk is too small: " + size + " bytes, need 40");
                }
                long data1 = u32(f, 24);
                byte[] suffix = Arrays.copyOfRange(f, 28, 40);
                if (!Arrays.equals(suffix, WavConstants.SUBFORMAT_SUFFIX)
                        || (data1 != WavConstants.TAG_PCM && data1 != WavConstants.TAG_FLOAT)) {
                    throw bad("unsupported EXTENSIBLE subformat (first GUID field 0x" + Long.toHexString(data1)
                            + "); supported: PCM and IEEE float");
                }
                effective = (int) data1;
            }
            if (effective != WavConstants.TAG_PCM && effective != WavConstants.TAG_FLOAT) {
                throw bad("unsupported format tag 0x" + Integer.toHexString(tag) + describeTag(tag)
                        + "; supported: PCM (1), IEEE float (3), EXTENSIBLE (0xFFFE)");
            }
            if (channels == 0) {
                throw bad("fmt declares zero channels");
            }
            if (channels > WavConstants.MAX_CHANNELS) {
                throw bad("fmt declares " + channels + " channels; supported: 1 to " + WavConstants.MAX_CHANNELS);
            }
            if (rate == 0) {
                throw bad("fmt declares a sample rate of zero");
            }
            if (rate > Integer.MAX_VALUE) {
                throw bad("sample rate " + rate + " Hz is too large");
            }
            WavEncoding enc;
            if (effective == WavConstants.TAG_FLOAT) {
                if (bits != 32) {
                    throw bad(bits + "-bit float is not supported; supported float: 32-bit");
                }
                enc = WavEncoding.FLOAT_32;
            } else {
                enc = switch (bits) {
                    case 8 -> WavEncoding.PCM_8;
                    case 16 -> WavEncoding.PCM_16;
                    case 24 -> WavEncoding.PCM_24;
                    case 32 -> WavEncoding.PCM_32;
                    default -> throw bad(bits + "-bit PCM is not supported; supported: 8, 16, 24, 32");
                };
            }
            int expected = channels * (bits / 8);
            if (blockAlign != expected) {
                throw bad("blockAlign " + blockAlign + " does not match channels * bytes per sample = " + expected);
            }
            return new Format(enc, channels, (int) rate, blockAlign);
        }

        private static String describeTag(int tag) {
            return switch (tag) {
                case 2 -> " (ADPCM)";
                case 6 -> " (A-law)";
                case 7 -> " (mu-law)";
                case 0x50, 0x55 -> " (MPEG)";
                default -> "";
            };
        }

        private WavFile decodeData(Format fmt, long declared) throws IOException {
            int channels = fmt.channels();
            long blockAlign = fmt.blockAlign();
            boolean declaredValid = declared != 0 && declared != UNKNOWN_SIZE;
            boolean known = knownSize >= 0;
            long bound;
            if (known) {
                long remaining = Math.max(0, knownSize - pos);
                bound = declaredValid ? Math.min(declared, remaining) : remaining;
            } else {
                bound = declaredValid ? declared : Long.MAX_VALUE;
            }
            long boundFrames = bound / blockAlign;
            long perFrame = channels * 4L;
            long initialFrames = known ? boundFrames : Math.min(boundFrames, INITIAL_STREAM_FRAMES);
            checkSize(initialFrames, perFrame);
            int capacity = (int) initialFrames;
            float[][] data = new float[channels][capacity];
            int framesPerBlock = (int) Math.max(1, BLOCK_BYTES / blockAlign);
            byte[] buf = new byte[(int) (framesPerBlock * blockAlign)];
            int frames = 0;
            while (frames < boundFrames) {
                int wantFrames = (int) Math.min(framesPerBlock, boundFrames - frames);
                int want = (int) (wantFrames * blockAlign);
                int got = readFully(buf, 0, want);
                int gotFrames = (int) (got / blockAlign);
                if (gotFrames > 0) {
                    if (frames + gotFrames > capacity) {
                        long newCap = Math.min(boundFrames, Math.max(capacity * 2L, (long) frames + gotFrames));
                        checkSize(newCap, perFrame);
                        capacity = (int) newCap;
                        for (int c = 0; c < channels; c++) {
                            data[c] = Arrays.copyOf(data[c], capacity);
                        }
                    }
                    SampleCodec.decode(buf, gotFrames, channels, fmt.encoding(), data, frames);
                    frames += gotFrames;
                }
                if (got < want) {
                    break;
                }
            }
            if (frames != capacity) {
                for (int c = 0; c < channels; c++) {
                    data[c] = Arrays.copyOf(data[c], frames);
                }
            }
            return new WavFile(MemorySampleSource.adopt(fmt.sampleRate(), data), fmt.encoding());
        }

        private void checkSize(long frames, long perFrame) throws UnsupportedAudioFormatException {
            if (frames > MAX_FRAMES || frames * perFrame > limit) {
                throw bad("file is too large for the in-memory source (" + frames + " frames need "
                        + (frames > MAX_FRAMES ? "more than the " + MAX_FRAMES + " frame maximum"
                                : frames * perFrame + " bytes, limit " + limit)
                        + "); a file-backed source is planned");
            }
        }

        private void skipPad(long size) throws IOException {
            if ((size & 1) != 0 && !skip(1)) {
                throw bad("file ends after the fmt chunk");
            }
        }

        private boolean skip(long n) throws IOException {
            try {
                in.skipNBytes(n);
            } catch (EOFException e) {
                return false;
            }
            pos += n;
            return true;
        }

        private int readFully(byte[] b, int off, int len) throws IOException {
            int n = in.readNBytes(b, off, len);
            pos += n;
            return n;
        }

        private static UnsupportedAudioFormatException bad(String message) {
            return new UnsupportedAudioFormatException(message);
        }
    }

    private static String ascii(byte[] b, int off) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) {
            int v = b[off + i] & 0xFF;
            c[i] = v >= 32 && v < 127 ? (char) v : '?';
        }
        return new String(c);
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }
}
