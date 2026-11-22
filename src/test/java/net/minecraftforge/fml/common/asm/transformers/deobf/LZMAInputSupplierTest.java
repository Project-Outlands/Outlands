package net.minecraftforge.fml.common.asm.transformers.deobf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The pack writer is commons-compress (see {@code binpatchesPack} in {@code projects/outlands/build.gradle}), the
 * reader is org.tukaani.xz: both sides have to agree on the LZMA_ALONE layout. The reader used to be the LZMA SDK's
 * {@code LZMA.LzmaInputStream}, which shares the format but allocates whatever dictionary size the header claims, so
 * this covers the round trip and the refusal.
 */
class LZMAInputSupplierTest {

    @Test
    void readsWhatTheBuildWrites() throws IOException {
        final StringBuilder srg = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            srg.append("MD: net/minecraft/Block func_").append(1000 + i)
                    .append(" ()V net/minecraft/Block func_").append(1000 + i).append("_srg\n");
        }
        final byte[] plain = srg.toString().getBytes(StandardCharsets.UTF_8);

        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (LZMACompressorOutputStream out = new LZMACompressorOutputStream(compressed)) {
            out.write(plain);
        }

        final String read = new LZMAInputSupplier(new ByteArrayInputStream(compressed.toByteArray()))
                .asCharSource(StandardCharsets.UTF_8)
                .read();

        assertEquals(srg.toString(), read, "the loader has to read the stream the build writes, byte for byte");
    }

    @Test
    void refusesAStreamThatIsNotLzma() {
        assertThrows(IOException.class, () -> new LZMAInputSupplier(new ByteArrayInputStream(new byte[] {1, 2, 3}))
                .asCharSource(StandardCharsets.UTF_8)
                .read());
    }
}
