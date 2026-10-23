package ee.paat.digidoc;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
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

    private static void assertSafeError(McpSchema.CallToolResult result) {
        assertEquals(true, result.isError());
        var body = (Map<?, ?>) result.structuredContent();
        var error = (Map<?, ?>) body.get("error");
        assertEquals(Set.of("code", "message"), error.keySet());
        String message = (String) error.get("message");
        assertFalse(message.isBlank());
        assertTrue(message.length() <= 512);
        assertFalse(message.contains("secret"));
        assertFalse(message.contains("Exception"));
        var text = (McpSchema.TextContent) result.content().getFirst();
        var json = JsonMapper.shared().readTree(text.text());
        assertEquals(error.get("code"), json.path("error").path("code").asString());
    }
}
