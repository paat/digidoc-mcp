package ee.paat.digidoc;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContainerNameTest {
    @TempDir Path root;

    @ParameterizedTest @ValueSource(strings = {"zip", "ddoc"})
    void rejectsMultiKilobyteNames(String format) throws Exception {
        assertNameRejected(format, "UNTRUSTED_NAME_" + "a".repeat(4096));
    }

    @ParameterizedTest @ValueSource(strings = {"zip", "ddoc"})
    void nameBoundCountsUtf8Bytes(String format) throws Exception {
        assertNameRejected(format, "õ".repeat(513));
    }

    @ParameterizedTest @ValueSource(strings = {"zip", "ddoc"})
    void acceptsExactUtf8NameBoundaryWithoutTruncating(String format) throws Exception {
        String name = "õ".repeat(512);
        Path container = container(format, name);
        var files = ContainerServiceTest.rows(TestFixtures.service(root)
                .openContainer(container.toString()).get("data_files"));
        assertEquals(name, files.getFirst().get("name"));
        assertEquals(1024, name.getBytes(StandardCharsets.UTF_8).length);
    }

    private void assertNameRejected(String format, String name) throws Exception {
        Path container = container(format, name);
        ToolException error = assertThrows(ToolException.class,
                () -> TestFixtures.service(root).openContainer(container.toString()));
        assertEquals("NAME_LIMIT", error.code());
        assertEquals("Container entry names must not exceed 1024 UTF-8 bytes.", error.getMessage());
        assertFalse(error.getMessage().contains(root.toString()));
        assertFalse(error.getMessage().contains("UNTRUSTED_NAME_"));
    }

    private Path container(String format, String name) throws Exception {
        if (format.equals("zip")) return TestFixtures.unsigned(root, name, new byte[] {'x'});
        return Files.writeString(root.resolve("names.ddoc"),
                "<?xml version=\"1.0\"?><SignedDoc format=\"DIGIDOC-XML\" version=\"1.3\" "
                        + "xmlns=\"http://www.sk.ee/DigiDoc/v1.3.0#\"><DataFile ContentType=\"EMBEDDED_BASE64\" "
                        + "Filename=\"" + name + "\" Id=\"D0\" MimeType=\"text/plain\" Size=\"1\">eA==</DataFile></SignedDoc>");
    }
}
