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
        assertNull(config.signing());
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
        assertNull(config.signing());
    }

    @Test void emptySigningFlagStaysDisabledEvenWhenACodeIsPresent() {
        assertNull(AppConfig.fromEnvironment(Map.of(), "Linux").signing());
        assertNull(AppConfig.fromEnvironment(Map.of("DIGIDOC_MCP_SIGNING", "",
                "DIGIDOC_MCP_SMARTID_CODE", "11404176865"), "Linux").signing());
    }

    @Test void smartIdSigningEnablesOnWindows() {
        AppConfig config = AppConfig.fromEnvironment(Map.of(
                "DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "60001013739"), "Windows 11");
        assertEquals("60001013739", config.signing().personalCode());
        assertNull(config.signing().digidoc4());
        assertEquals(180, config.signing().timeoutSeconds());
        AppConfig custom = AppConfig.fromEnvironment(Map.of(
                "DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "60001013739",
                "DIGIDOC_MCP_SIGN_TIMEOUT_SECONDS", "9",
                "DIGIDOC_MCP_DIGIDOC4", "C:/Program Files/RIA/DigiDoc4/qdigidoc4.exe"), "Windows 10");
        assertEquals(9, custom.signing().timeoutSeconds());
        assertEquals(Path.of("C:/Program Files/RIA/DigiDoc4/qdigidoc4.exe"), custom.signing().digidoc4());
    }

    @Test void signingConfigurationErrorsNeverEchoThePersonalCode() {
        String fixtureCode = "11404176865";
        for (Map<String, String> env : java.util.List.of(
                Map.of("DIGIDOC_MCP_SIGNING", "idcard", "DIGIDOC_MCP_SMARTID_CODE", fixtureCode),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", fixtureCode),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "60001013730"),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "60001313739"),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "123456789012"),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "not-a-code"),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid"),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "60001013739",
                        "DIGIDOC_MCP_SIGN_TIMEOUT_SECONDS", "0"),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "60001013739",
                        "DIGIDOC_MCP_SIGN_TIMEOUT_SECONDS", "-1"),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "60001013739",
                        "DIGIDOC_MCP_SIGN_TIMEOUT_SECONDS", "1.5"),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "60001013739",
                        "DIGIDOC_MCP_SIGN_TIMEOUT_SECONDS", "9223372036854775807"),
                Map.of("DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", "60001013739",
                        "DIGIDOC_MCP_DIGIDOC4", " "))) {
            ToolException error = assertThrows(ToolException.class, () -> AppConfig.fromEnvironment(env, "Windows 10"));
            assertEquals("INVALID_CONFIG", error.code());
            assertFalse(error.getMessage().chars().anyMatch(Character::isDigit), error.getMessage());
            assertFalse(error.getMessage().contains(fixtureCode));
        }
    }

    @Test void signingOnNonWindowsIsRejectedWithoutEchoingThePersonalCode() {
        String code = "60001013739";
        for (String os : java.util.List.of("Linux", "Mac OS X")) {
            ToolException error = assertThrows(ToolException.class, () -> AppConfig.fromEnvironment(Map.of(
                    "DIGIDOC_MCP_SIGNING", "smartid", "DIGIDOC_MCP_SMARTID_CODE", code), os));
            assertEquals("INVALID_CONFIG", error.code());
            assertFalse(error.getMessage().contains(code));
            assertFalse(error.getMessage().chars().anyMatch(Character::isDigit), error.getMessage());
        }
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
