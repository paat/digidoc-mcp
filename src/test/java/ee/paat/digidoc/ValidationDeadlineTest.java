package ee.paat.digidoc;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.digidoc4j.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ValidationDeadlineTest {
    @TempDir Path root;

    @Test void deadlineReturnsTextOnlySafeErrorAndInterruptsWorker() throws Exception {
        BlockingValidation operation = new BlockingValidation();
        Tools tools = tools(operation);
        var callers = Executors.newSingleThreadExecutor();
        try {
            var validation = callers.submit(() -> tools.call("validate_container", Map.of("path", "PRIVATE_PATH")));
            assertTrue(operation.started.await(2, TimeUnit.SECONDS));
            assertTimeoutError(validation.get(3, TimeUnit.SECONDS));
            assertTrue(operation.interrupted.await(1, TimeUnit.SECONDS), "Deadline must cancel with interruption");
        } finally {
            operation.release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void allInspectionToolsCompleteWhileSameToolsInstanceIsValidating() throws Exception {
        BlockingValidation operation = new BlockingValidation();
        Tools tools = tools(operation);
        Path fixture = TestFixtures.copyValid(root);
        assertFalse(tools.call("open_container", Map.of("path", fixture.toString())).isError());
        var callers = Executors.newFixedThreadPool(2);
        try {
            var validation = callers.submit(() -> tools.call("validate_container", Map.of("path", fixture.toString())));
            assertTrue(operation.started.await(2, TimeUnit.SECONDS));
            var inspection = callers.submit(() -> {
                assertFalse(tools.call("open_container", Map.of("path", fixture.toString())).isError());
                assertFalse(tools.call("list_signatures", Map.of("path", fixture.toString())).isError());
                assertFalse(tools.call("extract_file", Map.of("path", fixture.toString(), "file_name", "test.txt",
                        "output_dir", root.toString())).isError());
            });
            inspection.get(3, TimeUnit.SECONDS);
            assertEquals(1, operation.release.getCount(), "Validation must still be blocked during inspection");
            assertTimeoutError(validation.get(3, TimeUnit.SECONDS));
        } finally {
            operation.release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void secondValidationHasDeadlineEvenWhenFirstWorkerIgnoresCancellation() throws Exception {
        BlockingValidation operation = new BlockingValidation();
        Tools tools = tools(operation);
        var callers = Executors.newSingleThreadExecutor();
        try {
            var first = callers.submit(() -> tools.call("validate_container", Map.of("path", "first")));
            assertTrue(operation.started.await(2, TimeUnit.SECONDS));
            assertTimeoutError(first.get(3, TimeUnit.SECONDS));
            var second = callers.submit(() -> tools.call("validate_container", Map.of("path", "second")));
            assertTimeoutError(second.get(3, TimeUnit.SECONDS));
            assertEquals(1, operation.calls.get(), "A cancelled worker must retain exclusive validation ownership");
        } finally {
            operation.release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Tools tools(Supplier<Map<String, Object>> operation) {
        AppConfig config = new AppConfig(List.of(root), AppConfig.DEFAULT_MAX_BYTES, Configuration.Mode.TEST,
                root.resolve("tsl-cache"), 1);
        return new Tools(new ContainerService(config, operation), new JacksonMcpJsonMapper(JsonMapper.shared()));
    }

    private static void assertTimeoutError(McpSchema.CallToolResult result) {
        assertTrue(result.isError());
        assertNull(result.structuredContent());
        assertEquals(1, result.content().size());
        var text = (McpSchema.TextContent) result.content().getFirst();
        var error = JsonMapper.shared().readTree(text.text()).path("error");
        assertEquals(Set.of("code", "message"), Set.copyOf(error.propertyNames()));
        assertEquals("VALIDATION_TIMEOUT", error.path("code").asString());
        assertEquals("Validation exceeded its deadline.", error.path("message").asString());
    }

    private static final class BlockingValidation implements Supplier<Map<String, Object>> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        @Override public Map<String, Object> get() {
            calls.incrementAndGet();
            started.countDown();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; }
                catch (InterruptedException ignored) { interrupted.countDown(); }
            }
            return Map.of("result", "VALID");
        }
    }
}
