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

import com.jellyfish.timbre.model.SampleSource;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Writes a {@link SampleSource} as a RIFF/WAVE file.
 *
 * <p>Float to integer conversion scales by 2^(N-1) in double precision, rounds half-even, clamps to
 * the N-bit range and maps NaN to 0; there is no dither. FLOAT_32 is written bit-exactly. 8, 16 and
 * 24-bit PCM and float32 round-trip exactly through {@link WavReader}; 32-bit PCM does <b>not</b>
 * (float32 has a 24-bit mantissa).
 *
 * <p>Mono and stereo use a plain fmt chunk; more than two channels (up to 64) use
 * WAVE_FORMAT_EXTENSIBLE with an empty channel mask. Float files carry a fact chunk. Output whose
 * RIFF size would exceed 4 GiB - 1 is rejected before anything is written (RF64 is not supported yet).
 *
 * <p>The file is written to a temporary file in the target's directory and then moved onto the
 * target (atomically when the file system allows it). On any failure the target is left as it was
 * and no temporary file remains.
 *
 * <p><b>Threading:</b> stateless; calls are independent. The source is read from the calling thread
 * only, so it must not be modified concurrently.
 */
public final class WavWriter {

    private static final int BLOCK_FRAMES = 1 << 14;
    private static final long MAX_RIFF_SIZE = 0xFFFFFFFFL;

    private WavWriter() {
    }

    /**
     * Writes {@code source} to {@code target} in the given encoding.
     *
     * @param target   the file to create or replace
     * @param source   the audio to write
     * @param encoding the sample encoding
     * @throws IllegalArgumentException        if the source has more than 64 channels
     * @throws UnsupportedAudioFormatException if the result would exceed the 4 GiB WAV limit
     * @throws IOException                     on I/O failure
     */
    public static void write(Path target, SampleSource source, WavEncoding encoding) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(encoding, "encoding");
        int channels = source.channelCount();
        long frames = source.frameCount();
        if (channels > WavConstants.MAX_CHANNELS) {
            throw new IllegalArgumentException("at most " + WavConstants.MAX_CHANNELS + " channels are supported: "
                    + channels);
        }
        int bytesPerSample = SampleCodec.bytesPerSample(encoding);
        long blockAlign = (long) channels * bytesPerSample;
        boolean extensible = channels > 2;
        boolean fact = encoding.isFloat();
        if (frames > MAX_RIFF_SIZE / blockAlign) {
            throw tooLarge(frames);
        }
        long dataBytes = frames * blockAlign;
        long pad = dataBytes & 1;
        long fmtSize = extensible ? 40 : 16;
        long riffSize = 4 + 8 + fmtSize + (fact ? 12 : 0) + 8 + dataBytes + pad;
        if (riffSize > MAX_RIFF_SIZE) {
            throw tooLarge(frames);
        }

        Path dir = target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, "timbre-", ".tmp");
        boolean moved = false;
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)) {
                out.write(header(source, encoding, extensible, fact, riffSize, dataBytes, frames));
                float[][] block = new float[channels][(int) Math.min(BLOCK_FRAMES, Math.max(1, frames))];
                byte[] bytes = new byte[(int) (block[0].length * blockAlign)];
                for (long done = 0; done < frames; ) {
                    int n = (int) Math.min(BLOCK_FRAMES, frames - done);
                    source.read(done, n, block, 0);
                    SampleCodec.encode(block, 0, n, channels, encoding, bytes);
                    out.write(bytes, 0, (int) (n * blockAlign));
                    done += n;
                }
                if (pad != 0) {
                    out.write(0);
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private static UnsupportedAudioFormatException tooLarge(long frames) {
        return new UnsupportedAudioFormatException("output of " + frames + " frames would exceed the WAV limit of "
                + "4 GiB - 1 bytes; RF64 / files over 4 GiB are not supported yet");
    }

    private static byte[] header(SampleSource source, WavEncoding encoding, boolean extensible, boolean fact,
                                 long riffSize, long dataBytes, long frames) {
        int channels = source.channelCount();
        int bits = encoding.bitsPerSample();
        int blockAlign = channels * (bits / 8);
        int tag = encoding.isFloat() ? WavConstants.TAG_FLOAT : WavConstants.TAG_PCM;
        int fmtSize = extensible ? 40 : 16;
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + fmtSize + (fact ? 12 : 0) + 8).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R', 'I', 'F', 'F'}).putInt((int) riffSize).put(new byte[] {'W', 'A', 'V', 'E'});
        b.put(new byte[] {'f', 'm', 't', ' '}).putInt(fmtSize);
        b.putShort((short) (extensible ? WavConstants.TAG_EXTENSIBLE : tag));
        b.putShort((short) channels);
        b.putInt(source.sampleRate());
        b.putInt((int) ((long) source.sampleRate() * blockAlign));
        b.putShort((short) blockAlign);
        b.putShort((short) bits);
        if (extensible) {
            b.putShort((short) 22);
            b.putShort((short) bits);
            b.putInt(0);
            b.putInt(tag);
            b.put(WavConstants.SUBFORMAT_SUFFIX);
        }
        if (fact) {
            b.put(new byte[] {'f', 'a', 'c', 't'}).putInt(4).putInt((int) frames);
        }
        b.put(new byte[] {'d', 'a', 't', 'a'}).putInt((int) dataBytes);
        return b.array();
    }
}
