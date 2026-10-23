package ee.paat.digidoc;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DdocSizeTest {
    @TempDir Path root;

    @ParameterizedTest @ValueSource(strings = {"1073741823", "40000000", "999999999999999999999999999"})
    void oversizedDeclarationIsRejectedBeforeBuildAtEveryLevel(String size) throws Exception {
        byte[] bytes = ddoc(size, new byte[] {'A'});
        long limit = size.equals("1073741823") ? AppConfig.DEFAULT_MAX_BYTES : 4096;
        assertRejected(bytes, limit, "SIZE_LIMIT");
        assertRejected(zip(bytes), limit, "SIZE_LIMIT");
        assertRejected(ddoc(Integer.toString(bytes.length), bytes), limit, "SIZE_LIMIT");
    }

    @ParameterizedTest @ValueSource(strings = {"", "-1", "+1", " 1", "1 ", "1.0", "1e3", "١", "999999999999999999999X"})
    void malformedDeclarationIsRejectedAtEveryLevel(String size) throws Exception {
        byte[] bytes = ddoc(size, new byte[] {'A'});
        assertRejected(bytes, 4096, "UNSUPPORTED_CONTAINER");
        assertRejected(zip(bytes), 4096, "UNSUPPORTED_CONTAINER");
        assertRejected(ddoc(Integer.toString(bytes.length), bytes), 4096, "UNSUPPORTED_CONTAINER");
    }

    @ParameterizedTest @ValueSource(strings = {"40000000", "malformed"})
    void namespacedAttributeCannotHideTheLegacySize(String size) throws Exception {
        byte[] bytes = new String(ddoc(size, new byte[] {'A'}), StandardCharsets.UTF_8)
                .replace(" Size=", " xmlns:shadow=\"urn:test\" shadow:Size=\"1\" Size=")
                .getBytes(StandardCharsets.UTF_8);
        String code = size.equals("malformed") ? "UNSUPPORTED_CONTAINER" : "SIZE_LIMIT";
        assertRejected(bytes, 4096, code);
        assertRejected(zip(bytes), 4096, code);
        assertRejected(ddoc(Integer.toString(bytes.length), bytes), 4096, code);
    }

    @Test void namespacedSizeDoesNotChangeTheLegacyDeclaration() throws Exception {
        byte[] bytes = new String(ddoc("1", new byte[] {'A'}), StandardCharsets.UTF_8)
                .replace(" Size=", " xmlns:shadow=\"urn:test\" shadow:Size=\"40000000\" Size=")
                .getBytes(StandardCharsets.UTF_8);
        ContainerPreflight.check(bytes, 1);
        byte[] prefixedOnly = new String(bytes, StandardCharsets.UTF_8).replace(" Size=\"1\"", "")
                .getBytes(StandardCharsets.UTF_8);
        ContainerPreflight.check(prefixedOnly, 1);
    }

    @Test void siblingDeclarationsShareTheRemainingBudget() throws Exception {
        String first = new String(ddoc("1000", new byte[] {'A'}), StandardCharsets.UTF_8);
        String dataFile = first.substring(first.indexOf("<DataFile"), first.indexOf("</SignedDoc>"));
        byte[] bytes = first.replace("</SignedDoc>", dataFile.replace("D0", "D1") + "</SignedDoc>")
                .getBytes(StandardCharsets.UTF_8);
        assertRejected(bytes, 1999, "SIZE_LIMIT");
        ContainerPreflight.check(bytes, 2000);
    }

    @Test void nestedDeclarationUsesRemainingRatherThanOriginalBudget() throws Exception {
        byte[] inner = ddoc("1000", new byte[] {'A'});
        byte[] outer = zip(inner);
        assertRejected(outer, inner.length + 999L, "SIZE_LIMIT");
        ContainerPreflight.check(outer, inner.length + 1000L);
    }

    @Test void chargeIsMaximumOfDeclaredAndDecodedSize() throws Exception {
        byte[] underdeclared = ddoc("1", new byte[20]);
        assertRejected(underdeclared, 19, "SIZE_LIMIT");
        ContainerPreflight.check(underdeclared, 20);
        ContainerPreflight.check(ddoc("20", new byte[] {'A'}), 20);
    }

    @ParameterizedTest @ValueSource(strings = {"1", "0001"})
    void honestDdocStillOpens(String size) throws Exception {
        Path file = Files.write(root.resolve("honest.ddoc"), ddoc(size, new byte[] {'A'}));
        var result = TestFixtures.service(root).openContainer(file.toString());
        assertEquals("DDOC", result.get("type"));
        var data = ContainerServiceTest.rows(result.get("data_files")).getFirst();
        assertEquals("payload.txt", data.get("name"));
        assertEquals(1L, ((Number) data.get("size_bytes")).longValue());
    }

    @Test void absentAndZeroDeclarationsStillChargeDecodedBytes() throws Exception {
        for (String size : new String[] {null, "0"}) {
            byte[] bytes = ddoc(size, new byte[20]);
            assertRejected(bytes, 19, "SIZE_LIMIT");
            ContainerPreflight.check(bytes, 20);
        }
    }

    @Test void toolErrorRemainsTextOnlyAndDoesNotDiscloseInput() throws Exception {
        byte[] bytes = ddoc("1073741823", new byte[] {'A'});
        // This assertion also keeps the vulnerable test run away from the allocating builder.
        assertRejected(bytes, 4096, "SIZE_LIMIT");
        Path file = Files.write(root.resolve("UNTRUSTED_PATH.ddoc"), bytes);
        var tools = new Tools(TestFixtures.service(root, 4096), new JacksonMcpJsonMapper(JsonMapper.shared()));
        var result = tools.call("open_container", Map.of("path", file.toString()));
        assertEquals(true, result.isError());
        assertNull(result.structuredContent());
        var text = ((McpSchema.TextContent) result.content().getFirst()).text();
        var error = JsonMapper.shared().readTree(text).path("error");
        assertEquals("SIZE_LIMIT", error.path("code").asString());
        assertEquals("Container or extracted data exceeds the configured byte limit.", error.path("message").asString());
        assertFalse(text.contains(root.toString()));
        assertFalse(text.contains("1073741823"));
        assertFalse(text.contains("UNTRUSTED_PATH"));
    }

    private static void assertRejected(byte[] bytes, long limit, String code) {
        ToolException error = assertThrows(ToolException.class, () -> ContainerPreflight.check(bytes, limit));
        assertEquals(code, error.code());
    }

    private static byte[] ddoc(String size, byte[] payload) {
        return ("<?xml version=\"1.0\"?><SignedDoc format=\"DIGIDOC-XML\" version=\"1.3\" "
                + "xmlns=\"http://www.sk.ee/DigiDoc/v1.3.0#\"><DataFile ContentType=\"EMBEDDED_BASE64\" "
                + "Filename=\"payload.txt\" Id=\"D0\" MimeType=\"text/plain\""
                + (size == null ? "" : " Size=\"" + size + "\"") + ">"
                + Base64.getEncoder().encodeToString(payload) + "</DataFile></SignedDoc>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(byte[] payload) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry("inner.ddoc"));
            output.write(payload);
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
