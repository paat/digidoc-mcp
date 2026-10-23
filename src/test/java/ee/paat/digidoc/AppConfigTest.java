package ee.paat.digidoc;

import org.digidoc4j.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @TempDir Path temporary;

    @Test void defaultsUseWorkingDirectoryProductionAndHundredMiB() throws Exception {
        AppConfig config = AppConfig.fromEnvironment(Map.of());
        assertEquals(java.util.List.of(Path.of("").toRealPath()), config.roots());
        assertEquals(Configuration.Mode.PROD, config.mode());
        assertEquals(100L * 1024 * 1024, config.maxBytes());
        assertEquals(120, config.validationTimeoutSeconds());
        assertEquals(Path.of(System.getProperty("user.home"), ".cache", "digidoc-mcp", "tsl").toAbsolutePath(), config.tslCache());
    }

    @Test void environmentMapsModeRootsByteLimitAndCache() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));
        Path cache = temporary.resolve("cache");
        AppConfig config = AppConfig.fromEnvironment(Map.of(
                "DIGIDOC_MCP_ROOTS", first + File.pathSeparator + second,
                "DIGIDOC_MCP_MODE", "test", "DIGIDOC_MCP_MAX_BYTES", "4096",
                "DIGIDOC_MCP_TSL_CACHE", cache.toString(), "DIGIDOC_MCP_VALIDATION_TIMEOUT_SECONDS", "7"));
        assertEquals(java.util.List.of(first.toRealPath(), second.toRealPath()), config.roots());
        assertEquals(Configuration.Mode.TEST, config.mode());
        assertEquals(4096, config.maxBytes());
        assertEquals(cache.toAbsolutePath(), config.tslCache());
        assertEquals(7, config.validationTimeoutSeconds());
    }

    @Test void invalidModeDoesNotSilentlyUseProduction() {
        assertThrows(RuntimeException.class, () -> AppConfig.fromEnvironment(Map.of("DIGIDOC_MCP_MODE", "typo")));
    }

    @Test void invalidValidationDeadlinesAreRejectedWithoutDisclosingTheirValue() {
        for (String value : java.util.List.of("0", "-1", "1.5", "PRIVATE_CONFIG", "9223372036854775807")) {
            ToolException error = assertThrows(ToolException.class, () -> AppConfig.fromEnvironment(
                    Map.of("DIGIDOC_MCP_VALIDATION_TIMEOUT_SECONDS", value)));
            assertEquals("INVALID_CONFIG", error.code());
            assertFalse(error.getMessage().contains(value));
        }
    }

    @Test void nonpositiveSizeDoesNotDisableLimit() {
        assertThrows(RuntimeException.class, () -> AppConfig.fromEnvironment(Map.of("DIGIDOC_MCP_MAX_BYTES", "0")));
        assertThrows(RuntimeException.class, () -> AppConfig.fromEnvironment(Map.of("DIGIDOC_MCP_MAX_BYTES", "-1")));
    }
}
