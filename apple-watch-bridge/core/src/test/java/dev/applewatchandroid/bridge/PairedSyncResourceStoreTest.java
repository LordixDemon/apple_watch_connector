package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import org.junit.Test;

public final class PairedSyncResourceStoreTest {
    private static final String WRAPPED =
            "011f8b0800e81dc26a02ff8d8ebb4a04311885ff99f5b2de20e013a8205884ccde675b3b41b050"
                    + "b00cc9e41f0dcc8d3fd9c13c840f64e53358580ab6828d8860e9652b9bc1af39c5f9381cdd14"
                    + "d6f924798ce2decaeada7a7f6b5bb27d6b1c2fd13975859cd0d50bca907b5295cb91b8515e49"
                    + "76d42de14d6329fcb828d961b75ba257ff1af5b55705d7c1a3936cafdb5d50717c6d0fee3fe19"
                    + "7878dcd0b1f1a643d164b76295a45a2acb52d509c5a4d8a8238315879ebc339526b337422ff6e"
                    + "9d4892c160381c8df8783c99f0e97436e3693a9f73a5b4ce326310f35cb448ced615f46117523"
                    + "0700b77f004cff002aff006eff0b17c1147cb841df84374f6054a9eac3e89010000";

    @Test
    public void wrappedPlistIsSavedUnderTheResourceFileName() throws Exception {
        java.io.File dir = Files.createTempDirectory("resource-unwrap").toFile();
        PairedSyncResourceStore.setDirectoryForTesting(dir);
        try {
            byte[] wrapped = hex(WRAPPED);
            IdsSocketPairCodec.DataMessage message = new IdsSocketPairCodec.DataMessage(
                    IdsSocketPairCodec.COMMAND_RESOURCE_TRANSFER,
                    1,
                    1,
                    0,
                    null,
                    "00112233-4455-6677-8899-aabbccddeeff",
                    null,
                    wrapped,
                    null);
            try {
                PairedSyncResourceStore.Result stored = PairedSyncResourceStore.append(message);
                assertEquals("native/00112233-4455-6677-8899-aabbccddeeff-version", stored.savedName);
                assertArrayEquals("hi".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        Files.readAllBytes(dir.toPath().resolve(stored.savedName)));
            } finally {
                message.destroy();
            }
        } finally {
            PairedSyncResourceStore.setDirectoryForTesting(null);
        }
    }

    private static byte[] hex(String value) {
        byte[] output = new byte[value.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return output;
    }
}
