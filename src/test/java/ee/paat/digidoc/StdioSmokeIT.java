package ee.paat.digidoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StdioSmokeIT {
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @TempDir Path temporary;

    @Test @Timeout(90)
    void shadedJarSpeaksOnlyJsonAndExposesFourToolsAndSurvivesToolErrors() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("allowed"));
        Path fixture = TestFixtures.copyValid(root);
        Path outside = Files.copy(fixture, temporary.resolve("outside.asice"));
        Path stderr = temporary.resolve("stderr.log");
        Path jar = Path.of("target/digidoc-mcp.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "The packaged shaded jar must exist before integration tests");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString());
        builder.environment().put("DIGIDOC_MCP_ROOTS", root.toString());
        builder.environment().put("DIGIDOC_MCP_MODE", "test");
        builder.environment().put("DIGIDOC_MCP_TSL_CACHE", root.resolve("cache").toString());
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        var lines = new LinkedBlockingQueue<String>();
        var allLines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) { allLines.add(line); lines.add(line); }
            } catch (Exception exception) { lines.add("STDOUT_READ_FAILURE"); }
        });
        try (var input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            send(input, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                    "protocolVersion", "2025-06-18", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "stdio-regression", "version", "1.0"))));
            JsonNode initialized = response(lines, 1);
            assertTrue(initialized.has("result"), initialized.toString());
            send(input, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            send(input, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()));
            JsonNode listed = response(lines, 2).path("result").path("tools");
            assertEquals(4, listed.size());
            Set<String> names = new HashSet<>();
            for (JsonNode tool : listed) {
                names.add(tool.path("name").asString());
                assertEquals("object", tool.path("inputSchema").path("type").asString());
                assertEquals("string", tool.path("inputSchema").path("properties").path("path").path("type").asString());
            }
            assertEquals(Set.of("open_container", "list_signatures", "extract_file", "validate_container"), names);

            send(input, call(3, "open_container", Map.of("path", fixture.toString())));
            JsonNode opened = response(lines, 3).path("result");
            assertFalse(opened.path("isError").asBoolean(false), opened.toString());
            assertEquals(1, structured(opened).path("signature_count").asInt());
            assertEquals("test.txt", structured(opened).path("data_files").get(0).path("name").asString());

            send(input, call(4, "open_container", Map.of("path", outside.toString())));
            JsonNode error = response(lines, 4).path("result");
            assertTrue(error.path("isError").asBoolean(), error.toString());
            assertTrue(structured(error).path("error").path("code").isString());
            assertTrue(structured(error).path("error").path("message").isString());
            assertFalse(error.toString().contains(outside.toString()), "Tool failures must not leak local paths");
            assertFalse(error.toString().contains("Exception"), "Tool failures must not return stack traces");

            send(input, call(5, "open_container", Map.of("path", fixture.toString())));
            assertFalse(response(lines, 5).path("result").path("isError").asBoolean(false),
                    "Server must keep serving after a rejected path");
        } finally {
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
            reader.join(Duration.ofSeconds(5));
            for (String line : allLines) assertJsonFrame(line);
        }
    }

    private static Map<String, Object> call(int id, String tool, Map<String, Object> arguments) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments));
    }

    private static void send(BufferedWriter input, Object value) throws Exception {
        input.write(JSON.writeValueAsString(value));
        input.newLine();
        input.flush();
    }

    private static JsonNode response(LinkedBlockingQueue<String> lines, int id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(line, "Timed out waiting for MCP response " + id);
            JsonNode frame = assertJsonFrame(line);
            if (frame.path("id").asInt(-1) == id) return frame;
        }
        throw new AssertionError("Timed out waiting for MCP response " + id);
    }

    private static JsonNode assertJsonFrame(String line) {
        JsonNode parsed = assertDoesNotThrow(() -> JSON.readTree(line),
                "Non-JSON stdout line contaminates the MCP transport: " + line);
        assertNotNull(parsed, "Blank stdout lines are not MCP frames");
        assertTrue(parsed.isObject(), "stdout must contain JSON-RPC objects");
        assertEquals("2.0", parsed.path("jsonrpc").asString(), "stdout must contain JSON-RPC frames");
        return parsed;
    }

    private static JsonNode structured(JsonNode result) {
        if (result.has("structuredContent")) return result.get("structuredContent");
        return JSON.readTree(result.path("content").get(0).path("text").asString());
    }
}
