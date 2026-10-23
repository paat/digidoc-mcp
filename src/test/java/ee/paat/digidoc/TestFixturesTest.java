package ee.paat.digidoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class TestFixturesTest {
    @TempDir Path root;

    @Test void tamperingPreservesEntryCompressionMethodsAndStoredChecksums() throws Exception {
        Path original = TestFixtures.copyValid(root);
        Path tampered = TestFixtures.tampered(original, root.resolve("tampered.asice"));
        try (var before = new ZipFile(original.toFile()); var after = new ZipFile(tampered.toFile())) {
            assertEquals(before.size(), after.size());
            assertEquals(ZipEntry.STORED, before.getEntry("mimetype").getMethod());
            for (var entries = before.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                var rewritten = after.getEntry(entry.getName());
                assertNotNull(rewritten);
                assertEquals(entry.getMethod(), rewritten.getMethod(), entry.getName());
                if (rewritten.getMethod() == ZipEntry.STORED) {
                    byte[] bytes;
                    try (var input = after.getInputStream(rewritten)) { bytes = input.readAllBytes(); }
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    assertEquals(bytes.length, rewritten.getSize());
                    assertEquals(crc.getValue(), rewritten.getCrc());
                }
            }
        }
    }
}
