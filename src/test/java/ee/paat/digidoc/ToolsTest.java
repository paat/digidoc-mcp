package ee.paat.digidoc;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolsTest {
    @TempDir Path root;
    Tools tools;
    Path fixture;

    @BeforeEach void prepare() throws Exception {
        fixture = TestFixtures.copyValid(root);
        tools = new Tools(TestFixtures.service(root), new JacksonMcpJsonMapper(JsonMapper.shared()));
    }

    @Test void missingWrongTypeUnknownAndOversizedArgumentsReturnToolErrors() {
        for (Map<String, Object> args : List.<Map<String, Object>>of(Map.of(), Map.of("path", 12),
                Map.of("path", ""), Map.of("path", "x".repeat(8193)),
                Map.of("path", fixture.toString(), "unknown", "value"))) {
            assertSafeError(tools.call("open_container", args));
        }
        assertSafeError(tools.call("open_container", null));
        assertSafeError(tools.call("unknown_tool", Map.of("path", fixture.toString())));
    }

    @Test void overwriteMustBeBoolean() {
        assertSafeError(tools.call("extract_file", Map.of("path", fixture.toString(), "file_name", "test.txt",
                "output_dir", root.toString(), "overwrite", "true")));
        assertFalse(java.nio.file.Files.exists(root.resolve("test.txt")));
    }

    @Test void extractionCannotOverwriteItsSourceContainer() throws Exception {
        Path source = TestFixtures.unsigned(root, "unsigned.asice", new byte[] {1, 2, 3});
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] before = digest.digest(Files.readAllBytes(source));

        var result = tools.call("extract_file", Map.of("path", source.toString(),
                "file_name", "unsigned.asice", "output_dir", root.toString(), "overwrite", true));

        assertAll(
                () -> {
                    assertSafeError(result);
                    assertEquals("INVALID_TARGET", errorBody(result).path("code").asString());
                },
                () -> assertArrayEquals(before, digest.digest(Files.readAllBytes(source)),
                        "Extraction must preserve the source container's SHA-256"));
    }

    @Test void malformedPathIsToolErrorAndServerCanContinue() {
        assertSafeError(tools.call("open_container", Map.of("path", "secret\u0000path")));
        McpSchema.CallToolResult success = tools.call("open_container", Map.of("path", fixture.toString()));
        assertEquals(false, success.isError());
        assertEquals(1, ((Number) ((Map<?, ?>) success.structuredContent()).get("signature_count")).intValue());
    }

    @Test void everyToolHasInputOutputSchemasAndAppropriateAnnotations() {
        var specifications = tools.specifications();
        assertEquals(4, specifications.size());
        assertEquals(Set.of("open_container", "list_signatures", "extract_file", "validate_container"),
                specifications.stream().map(spec -> spec.tool().name()).collect(java.util.stream.Collectors.toSet()));
        for (var specification : specifications) {
            var tool = specification.tool();
            assertEquals("object", tool.inputSchema().get("type"));
            assertTrue(((Map<?, ?>) tool.inputSchema().get("properties")).containsKey("path"));
            assertTrue(((List<?>) tool.inputSchema().get("required")).contains("path"));
            assertEquals(false, tool.inputSchema().get("additionalProperties"));
            assertNotNull(tool.outputSchema());
            assertEquals(!tool.name().equals("extract_file"), tool.annotations().readOnlyHint());
        }
    }

    @Test void everyToolReturnsTextOnlyErrorsWithVisibleCodeAndMessage() {
        for (var specification : tools.specifications()) {
            var result = tools.call(specification.tool().name(), Map.of());
            assertSafeError(result);
            assertEquals("INVALID_ARGUMENT", errorBody(result).path("code").asString());
            assertEquals("Tool arguments do not match the required schema.", errorBody(result).path("message").asString());
        }
    }

    @Test void successfulResultsConformToEveryDeclaredOutputSchema() {
        Path unsigned = TestFixtures.unsigned(root, "unsigned.txt", new byte[] {1});
        for (var specification : tools.specifications()) {
            String name = specification.tool().name();
            Map<String, Object> arguments = name.equals("extract_file")
                    ? Map.of("path", fixture.toString(), "file_name", "test.txt", "output_dir", root.toString())
                    : Map.of("path", (name.equals("validate_container") ? unsigned : fixture).toString());
            var result = tools.call(name, arguments);
            assertEquals(false, result.isError(), name + ": " + result);
            assertNotNull(result.structuredContent(), name);
            assertConforms(specification.tool().outputSchema(), result.structuredContent());
        }
    }

    static void assertConforms(Map<String, Object> schema, Object value) {
        var validation = new DefaultJsonSchemaValidator().validate(schema, value);
        assertTrue(validation.valid(), validation.errorMessage());
    }

    private static JsonNode errorBody(McpSchema.CallToolResult result) {
        var text = (McpSchema.TextContent) result.content().getFirst();
        return JsonMapper.shared().readTree(text.text()).path("error");
    }

    private static void assertSafeError(McpSchema.CallToolResult result) {
        assertEquals(true, result.isError());
        assertNull(result.structuredContent(), "Error bodies must not violate the success output schema");
        assertEquals(1, result.content().size());
        JsonNode error = errorBody(result);
        assertEquals(Set.of("code", "message"), Set.copyOf(error.propertyNames()));
        assertTrue(error.path("code").isString());
        assertFalse(error.path("code").asString().isBlank());
        String message = error.path("message").asString();
        assertFalse(message.isBlank());
        assertTrue(message.length() <= 512);
        assertFalse(message.contains("secret"));
        assertFalse(message.contains("Exception"));
    }
}
