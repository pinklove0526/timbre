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
package com.jellyfish.timbre.audio.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Independent, minimal RIFF/WAVE byte builder for tests. It deliberately shares no code with the
 * production reader or writer.
 */
public final class RiffBuilder {

    private final ByteArrayOutputStream chunks = new ByteArrayOutputStream();
    private String magic = "RIFF";
    private String form = "WAVE";

    /** @return a new builder */
    public static RiffBuilder wave() {
        return new RiffBuilder();
    }

    /** Sets the first four bytes (for RF64 / RIFX tests). */
    public RiffBuilder magic(String m) {
        this.magic = m;
        return this;
    }

    /** Sets the form type (bytes 8..11). */
    public RiffBuilder form(String f) {
        this.form = f;
        return this;
    }

    /** Appends raw bytes. */
    public RiffBuilder raw(byte[] b) {
        chunks.writeBytes(b);
        return this;
    }

    /** Appends a chunk with a correct size and a pad byte after odd sizes. */
    public RiffBuilder chunk(String id, byte[] payload) {
        chunks.writeBytes(ascii(id));
        chunks.writeBytes(le32(payload.length));
        chunks.writeBytes(payload);
        if ((payload.length & 1) != 0) {
            chunks.write(0);
        }
        return this;
    }

    /** Appends a chunk header declaring {@code declared} bytes, followed by {@code payload}, no padding. */
    public RiffBuilder chunkDeclaring(String id, long declared, byte[] payload) {
        chunks.writeBytes(ascii(id));
        chunks.writeBytes(le32((int) declared));
        chunks.writeBytes(payload);
        return this;
    }

    /** Appends a plain 16-byte fmt chunk with consistent derived fields. */
    public RiffBuilder fmt(int tag, int channels, int rate, int bits) {
        int blockAlign = channels * (bits / 8);
        return fmtRaw(tag, channels, rate, (long) rate * blockAlign, blockAlign, bits);
    }

    /** Appends a 16-byte fmt chunk with every field given explicitly. */
    public RiffBuilder fmtRaw(int tag, int channels, long rate, long byteRate, int blockAlign, int bits) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.writeBytes(le16(tag));
        f.writeBytes(le16(channels));
        f.writeBytes(le32((int) rate));
        f.writeBytes(le32((int) byteRate));
        f.writeBytes(le16(blockAlign));
        f.writeBytes(le16(bits));
        return chunk("fmt ", f.toByteArray());
    }

    /** Appends a 40-byte WAVE_FORMAT_EXTENSIBLE fmt chunk. */
    public RiffBuilder fmtExtensible(int subTag, int channels, int rate, int containerBits, int validBits,
                                     int channelMask) {
        int blockAlign = channels * (containerBits / 8);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.writeBytes(le16(0xFFFE));
        f.writeBytes(le16(channels));
        f.writeBytes(le32(rate));
        f.writeBytes(le32(rate * blockAlign));
        f.writeBytes(le16(blockAlign));
        f.writeBytes(le16(containerBits));
        f.writeBytes(le16(22));
        f.writeBytes(le16(validBits));
        f.writeBytes(le32(channelMask));
        f.writeBytes(guid(subTag));
        return chunk("fmt ", f.toByteArray());
    }

    /** Appends a data chunk. */
    public RiffBuilder data(byte[] payload) {
        return chunk("data", payload);
    }

    /** Appends a data chunk declaring a (possibly wrong) size. */
    public RiffBuilder dataDeclaring(long declared, byte[] payload) {
        return chunkDeclaring("data", declared, payload);
    }

    /** @return the file bytes */
    public byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii(magic));
        out.writeBytes(le32(4 + chunks.size()));
        out.writeBytes(ascii(form));
        out.writeBytes(chunks.toByteArray());
        return out.toByteArray();
    }

    /** @return the standard GUID {tag-0000-0010-8000-00AA00389B71} in file byte order */
    public static byte[] guid(int tag) {
        byte[] g = new byte[16];
        g[0] = (byte) tag;
        g[1] = (byte) (tag >> 8);
        byte[] rest = {0x00, 0x00, 0x10, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71};
        System.arraycopy(rest, 0, g, 4, 12);
        return g;
    }

    /** @return the ASCII bytes of {@code s} */
    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** @return {@code v} as 2 little-endian bytes */
    public static byte[] le16(int v) {
        return new byte[] {(byte) v, (byte) (v >> 8)};
    }

    /** @return {@code v} as 4 little-endian bytes */
    public static byte[] le32(int v) {
        return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
