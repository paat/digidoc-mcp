package ee.paat.digidoc;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.digidoc4j.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class SmartIdSigningTest {
    /** Signer serialNumber on valid-asice.asice. It fails the Estonian check digit (expected 7). */
    private static final String FIXTURE_CODE = "11404176865";
    /** Valid test personal code (MARY ÄNN). Used when the configured code must not match the fixture. */
    private static final String OTHER_CODE = "60001013739";

    @TempDir Path temporary;

    @Test void containerNameUsesDigiDoc4CompleteBaseName() {
        assertEquals(Path.of("/data/contract.asice"), SmartIdSigning.containerTarget(Path.of("/data/contract.pdf")));
        assertEquals(Path.of("/data/archive.tar.asice"), SmartIdSigning.containerTarget(Path.of("/data/archive.tar.gz")));
        assertEquals(Path.of("/data/README.asice"), SmartIdSigning.containerTarget(Path.of("/data/README")));
        assertEquals(Path.of("/data/.gitignore.asice"), SmartIdSigning.containerTarget(Path.of("/data/.gitignore")));
        assertEquals(Path.of("/data/foo.asice"), SmartIdSigning.containerTarget(Path.of("/data/foo.")));
        assertEquals(Path.of("/data/notes.asice"), SmartIdSigning.containerTarget(Path.of("/data/notes.asice")));
    }

    @Test void existingTargetIsRefusedAndNotOverwritten() throws Exception {
        Path root = directory("allowed");
        Path source = Files.writeString(root.resolve("contract.pdf"), "pdf");
        Path target = Files.writeString(root.resolve("contract.asice"), "keep");
        AtomicInteger calls = new AtomicInteger();
        SmartIdSigning signing = signing(root, FIXTURE_CODE, executable(), (exe, file, code) -> calls.incrementAndGet(),
                new ManualClock(), Map.of(), summary("VALID"), 30);
        ToolException error = assertThrows(ToolException.class, () -> signing.sign(source.toString()));
        assertEquals("TARGET_EXISTS", error.code());
        assertNoCode(error.getMessage());
        assertEquals("keep", Files.readString(target));
        assertEquals(0, calls.get());

        Path container = Files.writeString(root.resolve("notes.asice"), "original");
        ToolException same = assertThrows(ToolException.class, () -> signing.sign(container.toString()));
        assertEquals("TARGET_EXISTS", same.code());
        assertEquals("original", Files.readString(container));
    }

    @Test void pathOutsideRootAndSymlinkEscapeAreRefused() throws Exception {
        Path root = directory("allowed");
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "secret");
        Path link = Files.createSymbolicLink(root.resolve("link.txt"), outside);
        AtomicInteger calls = new AtomicInteger();
        SmartIdSigning signing = signing(root, FIXTURE_CODE, executable(), (exe, file, code) -> calls.incrementAndGet(),
                new ManualClock(), Map.of(), summary("VALID"), 30);
        ToolException escaped = assertThrows(ToolException.class, () -> signing.sign(link.toString()));
        ToolException external = assertThrows(ToolException.class, () -> signing.sign(outside.toString()));
        assertEquals("PATH_NOT_ALLOWED", escaped.code());
        assertEquals("PATH_NOT_ALLOWED", external.code());
        assertNoCode(escaped.getMessage());
        assertNoCode(external.getMessage());
        assertEquals(0, calls.get());
        assertFalse(Files.exists(root.resolve("link.asice")));
        assertFalse(Files.exists(temporary.resolve("outside.asice")));
    }

    @Test void signingReadsAByteSnapshotThatMatchesPathListing() throws Exception {
        Path root = directory("allowed");
        Path fixture = TestFixtures.copyValid(root);
        byte[] bytes = Files.readAllBytes(fixture);
        ContainerService service = TestFixtures.service(root);
        assertEquals(service.listSignatures(fixture.toString()), service.listSignatures(bytes));
        Path source = Files.writeString(root.resolve("contract.pdf"), "sign me");
        Map<String, Object> result = signing(root, FIXTURE_CODE, executable(), (exe, file, code) -> {
            try { Files.copy(fixture, SmartIdSigning.containerTarget(file)); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }, new ManualClock(), Map.of(), summary("VALID"), 30).sign(source.toString());
        assertEquals("VALID", result.get("result"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), result.get("sha256"));
    }

    @Test void validFixtureMatchesConfiguredCodeAndReportsValidationSummary() throws Exception {
        Path root = directory("allowed");
        Path fixture = TestFixtures.copyValid(root);
        String carried = ((Map<?, ?>) ((List<?>) TestFixtures.service(root).listSignatures(fixture.toString())
                .get("signatures")).getFirst()).get("personal_identification_code").toString();
        assertEquals(FIXTURE_CODE, carried);
        Path source = Files.writeString(root.resolve("contract.pdf"), "sign me");
        Path exe = executable();
        ManualClock clock = new ManualClock();
        SmartIdSigning signing = signing(root, FIXTURE_CODE, exe, (executable, file, code) -> {
            assertEquals(exe, executable);
            assertEquals(FIXTURE_CODE, code);
            try {
                Files.copy(fixture, SmartIdSigning.containerTarget(file));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, clock, Map.of(), summary("VALID"), 30);
        var tools = new Tools(containers(root, summary("VALID"), signing), mapper(), signing);
        var listed = tools.specifications();
        assertEquals(5, listed.size());
        var signTool = listed.get(4).tool();
        assertEquals("sign_with_smartid", signTool.name());
        assertEquals(List.of("path"), signTool.inputSchema().get("required"));
        assertEquals(Set.of("path"), ((Map<?, ?>) signTool.inputSchema().get("properties")).keySet());
        assertEquals(false, signTool.inputSchema().get("additionalProperties"));
        assertEquals(false, signTool.annotations().readOnlyHint());

        McpSchema.CallToolResult result = tools.call("sign_with_smartid", Map.of("path", source.toString()));
        assertEquals(false, result.isError(), String.valueOf(result));
        ToolsTest.assertConforms(signTool.outputSchema(), result.structuredContent());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) result.structuredContent();
        Path target = SmartIdSigning.containerTarget(source.toRealPath());
        assertEquals(target.toRealPath().toString(), body.get("path"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture))),
                body.get("sha256"));
        assertEquals("VALID", body.get("result"));
        assertFalse(result.content().getFirst().toString().contains(FIXTURE_CODE));
        assertFalse(Files.exists(root.resolve("tsl")));
        var rejected = tools.call("sign_with_smartid", Map.of("path", source.toString(), "code", FIXTURE_CODE));
        assertEquals("INVALID_ARGUMENT", error(rejected).path("code").asString());
        assertNoCode(error(rejected).path("message").asString());
    }

    @Test void personalCodeMismatchAndInvalidIndicationLeaveTheContainer() throws Exception {
        Path root = directory("allowed");
        Path fixture = TestFixtures.copyValid(root);
        Path source = Files.writeString(root.resolve("contract.pdf"), "sign me");
        byte[] bytes = Files.readAllBytes(fixture);
        SigningDriver copy = (exe, file, code) -> {
            try { Files.copy(fixture, SmartIdSigning.containerTarget(file)); }
            catch (Exception e) { throw new IllegalStateException(e); }
        };
        Path target = root.resolve("contract.asice");
        SmartIdSigning mismatch = signing(root, OTHER_CODE, executable(), copy, new ManualClock(), Map.of(),
                summary("VALID"), 30);
        ToolException wrongSigner = assertThrows(ToolException.class, () -> mismatch.sign(source.toString()));
        assertEquals("SIGNATURE_NOT_VALID", wrongSigner.code());
        assertNoCode(wrongSigner.getMessage());
        assertFalse(wrongSigner.getMessage().contains(OTHER_CODE));
        assertArrayEquals(bytes, Files.readAllBytes(target));

        Files.delete(target);
        SmartIdSigning invalid = signing(root, FIXTURE_CODE, executable(), copy, new ManualClock(), Map.of(),
                summary("INVALID"), 30);
        ToolException notValid = assertThrows(ToolException.class, () -> invalid.sign(source.toString()));
        assertEquals("SIGNATURE_NOT_VALID", notValid.code());
        assertNoCode(notValid.getMessage());
        assertArrayEquals(bytes, Files.readAllBytes(target));
    }

    @Test void timeoutWithoutAFileLeavesNothingBehind() throws Exception {
        Path root = directory("allowed");
        Path source = Files.writeString(root.resolve("contract.pdf"), "sign me");
        Path kept = Files.writeString(root.resolve("keep.txt"), "stay");
        SmartIdSigning signing = signing(root, FIXTURE_CODE, executable(), (exe, file, code) -> { },
                new ManualClock(), Map.of(), () -> { throw new AssertionError("validated"); }, 2);
        ToolException error = assertThrows(ToolException.class, () -> signing.sign(source.toString()));
        assertEquals("SIGN_TIMEOUT", error.code());
        assertNoCode(error.getMessage());
        assertFalse(Files.exists(root.resolve("contract.asice")));
        assertEquals("stay", Files.readString(kept));
    }

    @Test void timeoutDeletesAnIncompleteContainerCreatedDuringTheCall() throws Exception {
        Path root = directory("allowed");
        Path source = Files.writeString(root.resolve("contract.pdf"), "sign me");
        Path kept = Files.writeString(root.resolve("keep.txt"), "stay");
        Path target = root.resolve("contract.asice");
        ManualClock clock = new ManualClock();
        clock.onSleep = () -> {
            try {
                Files.writeString(target, "x", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        SmartIdSigning signing = signing(root, FIXTURE_CODE, executable(), (exe, file, code) -> {
            try { Files.writeString(SmartIdSigning.containerTarget(file), "partial"); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }, clock, Map.of(), () -> { throw new AssertionError("validated"); }, 2);
        ToolException error = assertThrows(ToolException.class, () -> signing.sign(source.toString()));
        assertEquals("SIGN_TIMEOUT", error.code());
        assertNoCode(error.getMessage());
        assertFalse(Files.exists(target));
        assertEquals("stay", Files.readString(kept));
        assertEquals("sign me", Files.readString(source));
    }

    @Test void uiAutomationFailureIsPropagatedAndDoesNotEchoTheCode() throws Exception {
        Path root = directory("allowed");
        Path source = Files.writeString(root.resolve("contract.pdf"), "sign me");
        SmartIdSigning signing = signing(root, FIXTURE_CODE, executable(),
                (exe, file, code) -> { throw new ToolException("UI_AUTOMATION_FAILED", "DigiDoc4 signing controls could not be used."); },
                new ManualClock(), Map.of(), summary("VALID"), 30);
        var tools = new Tools(containers(root, summary("VALID"), signing), mapper(), signing);
        JsonNode error = error(tools.call("sign_with_smartid", Map.of("path", source.toString())));
        assertEquals("UI_AUTOMATION_FAILED", error.path("code").asString());
        assertNoCode(error.path("message").asString());
        assertFalse(Files.exists(root.resolve("contract.asice")));
    }

    @Test void unexpectedDriverFailureDoesNotEchoThePersonalCode() throws Exception {
        Path root = directory("allowed");
        Path source = Files.writeString(root.resolve("contract.pdf"), "sign me");
        SmartIdSigning signing = signing(root, FIXTURE_CODE, executable(),
                (exe, file, code) -> { throw new IllegalStateException(code); },
                new ManualClock(), Map.of(), summary("VALID"), 30);
        var tools = new Tools(containers(root, summary("VALID"), signing), mapper(), signing);
        JsonNode error = error(tools.call("sign_with_smartid", Map.of("path", source.toString())));
        assertEquals("OPERATION_FAILED", error.path("code").asString());
        assertNoCode(error.path("message").asString());
        assertEquals("The container operation could not be completed.", error.path("message").asString());
    }

    @Test void missingExecutableDoesNotFallBackOrLaunch() throws Exception {
        Path root = directory("allowed");
        Path source = Files.writeString(root.resolve("contract.pdf"), "sign me");
        Path programFiles = directory("Program Files");
        Path decoy = programFiles.resolve("RIA").resolve("DigiDoc4").resolve("qdigidoc4.exe");
        Files.createDirectories(decoy.getParent());
        Files.createFile(decoy);
        AtomicInteger calls = new AtomicInteger();
        Path missing = temporary.resolve("missing.exe");
        AppConfig config = new AppConfig(List.of(root), AppConfig.DEFAULT_MAX_BYTES, Configuration.Mode.TEST,
                root.resolve("tsl"), 30, new AppConfig.Signing(FIXTURE_CODE, missing, 30));
        SmartIdSigning signing = new SmartIdSigning(config, new ContainerService(config, summary("VALID")),
                (exe, file, code) -> calls.incrementAndGet(), new ManualClock(),
                Map.of("ProgramFiles", programFiles.toString()), 500);
        ToolException error = assertThrows(ToolException.class, () -> signing.sign(source.toString()));
        assertEquals("DIGIDOC4_NOT_FOUND", error.code());
        assertNoCode(error.getMessage());
        assertEquals(0, calls.get());
        assertTrue(Files.isRegularFile(decoy));
    }

    @Test void discoveryPrefersProgramFilesThenProgramFilesX86() throws Exception {
        Path programFiles = directory("pf");
        Path programFilesX86 = directory("pf86");
        Path primary = programFiles.resolve("RIA").resolve("DigiDoc4").resolve("qdigidoc4.exe");
        Path secondary = programFilesX86.resolve("RIA").resolve("DigiDoc4").resolve("qdigidoc4.exe");
        Files.createDirectories(primary.getParent());
        Files.createDirectories(secondary.getParent());
        Files.createFile(secondary);
        assertNull(SmartIdSigning.installedExecutable(Map.of()));
        assertEquals(secondary, SmartIdSigning.installedExecutable(Map.of("ProgramFiles(x86)", programFilesX86.toString())));
        Files.createFile(primary);
        assertEquals(primary, SmartIdSigning.installedExecutable(Map.of(
                "ProgramFiles", programFiles.toString(), "ProgramFiles(x86)", programFilesX86.toString())));
    }

    @Test void signingToolIsHiddenUntilEnabled() throws Exception {
        Path root = directory("allowed");
        var mapper = mapper();
        Tools disabled = new Tools(TestFixtures.service(root), mapper);
        assertEquals(Set.of("open_container", "list_signatures", "extract_file", "validate_container"),
                disabled.specifications().stream().map(spec -> spec.tool().name()).collect(java.util.stream.Collectors.toSet()));
        JsonNode hidden = error(disabled.call("sign_with_smartid", Map.of("path", "unused")));
        assertEquals("SIGNING_DISABLED", hidden.path("code").asString());
        assertNoCode(hidden.path("message").asString());

        SmartIdSigning signing = signing(root, FIXTURE_CODE, executable(), (exe, file, code) -> { },
                new ManualClock(), Map.of(), summary("VALID"), 30);
        Tools enabled = new Tools(TestFixtures.service(root), mapper, signing);
        assertTrue(enabled.specifications().stream().anyMatch(spec -> spec.tool().name().equals("sign_with_smartid")));
        assertEquals(5, enabled.specifications().size());
    }

    @Test void processCommandsNeverCarryThePersonalCode() throws Exception {
        Path exe = Path.of("C:/Program Files/RIA/DigiDoc4/qdigidoc4.exe");
        Path source = Path.of("C:/docs/contract.pdf");
        List<String> launch = WindowsSmartIdDriver.launchCommand(exe, source);
        assertEquals(List.of(exe.toString(), "-sign", source.toString()), launch);
        assertFalse(String.join("\n", launch).contains(FIXTURE_CODE));
        List<String> settings = WindowsSmartIdDriver.powershell(Path.of("C:/tmp/smartid-settings.ps1"), false);
        List<String> ui = WindowsSmartIdDriver.powershell(Path.of("C:/tmp/smartid-ui.ps1"), true);
        assertFalse(String.join("\n", settings).contains(FIXTURE_CODE));
        assertFalse(settings.contains("-Command"));
        assertTrue(settings.contains("-File"));
        assertFalse(ui.contains("-Command"));
        assertTrue(ui.contains("-STA"));
        String settingsScript = resource("/ee/paat/digidoc/smartid-settings.ps1");
        String uiScript = resource("/ee/paat/digidoc/smartid-ui.ps1");
        assertTrue(settingsScript.contains("ReadToEnd"));
        assertTrue(settingsScript.contains("SmartIDCountry"));
        assertTrue(settingsScript.contains("SmartIDSettings"));
        assertTrue(settingsScript.toLowerCase(java.util.Locale.ROOT).contains("unverified until the windows spike"));
        assertFalse(settingsScript.contains(FIXTURE_CODE));
        assertTrue(uiScript.contains("AutomationId"));
        assertTrue(uiScript.contains("MainAction"));
        assertTrue(uiScript.contains("'tabs'"));
        assertTrue(uiScript.contains("'sign'"));
        assertTrue(uiScript.contains("SelectionItemPattern"));
        assertTrue(uiScript.toLowerCase(java.util.Locale.ROOT).contains("unverified until the windows spike"));
        assertFalse(uiScript.contains("ReadToEnd"));
        assertFalse(uiScript.contains(FIXTURE_CODE));
    }

    @Test void oneSigningRunsAtATime() throws Exception {
        Path root = directory("allowed");
        Path fixture = TestFixtures.copyValid(root);
        Path source = Files.writeString(root.resolve("contract.pdf"), "sign me");
        CountDownLatch inDriver = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        SmartIdSigning signing = signing(root, FIXTURE_CODE, executable(), (exe, file, code) -> {
            int running = current.incrementAndGet();
            max.accumulateAndGet(running, Math::max);
            try {
                if (running == 1) {
                    inDriver.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                Files.copy(fixture, SmartIdSigning.containerTarget(file));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                current.decrementAndGet();
            }
        }, new ManualClock(), Map.of(), summary("VALID"), 30);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = pool.submit(() -> signing.sign(source.toString()));
            assertTrue(inDriver.await(5, TimeUnit.SECONDS));
            Future<ToolException> second = pool.submit(() -> {
                try {
                    signing.sign(source.toString());
                    return null;
                } catch (ToolException error) {
                    return error;
                }
            });
            Thread.sleep(100);
            assertEquals(1, max.get());
            release.countDown();
            assertEquals("VALID", first.get(5, TimeUnit.SECONDS).get("result"));
            ToolException error = second.get(5, TimeUnit.SECONDS);
            assertNotNull(error);
            assertEquals("TARGET_EXISTS", error.code());
            assertEquals(1, max.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Path directory(String name) throws Exception {
        return Files.createDirectory(temporary.resolve(name));
    }

    private Path executable() throws Exception {
        return Files.createFile(temporary.resolve("qdigidoc4-" + System.nanoTime() + ".exe"));
    }

    private SmartIdSigning signing(Path root, String code, Path exe, SigningDriver driver, ManualClock clock,
                                   Map<String, String> env, Supplier<Map<String, Object>> validation, long timeout) {
        AppConfig config = new AppConfig(List.of(root), AppConfig.DEFAULT_MAX_BYTES, Configuration.Mode.TEST,
                root.resolve("tsl"), 30, new AppConfig.Signing(code, exe, timeout));
        return new SmartIdSigning(config, new ContainerService(config, validation), driver, clock, env, 500);
    }

    private ContainerService containers(Path root, Supplier<Map<String, Object>> validation, SmartIdSigning signing) {
        return new ContainerService(new AppConfig(List.of(root), AppConfig.DEFAULT_MAX_BYTES, Configuration.Mode.TEST,
                root.resolve("tsl"), 30, signingConfig(signing)), validation);
    }

    private static AppConfig.Signing signingConfig(SmartIdSigning ignored) {
        return new AppConfig.Signing(FIXTURE_CODE, null, 30);
    }

    private static JacksonMcpJsonMapper mapper() {
        return new JacksonMcpJsonMapper(JsonMapper.shared());
    }

    private static Supplier<Map<String, Object>> summary(String result) {
        return () -> {
            Map<String, Object> signature = new LinkedHashMap<>();
            signature.put("id", "S0");
            signature.put("indication", "VALID".equals(result) ? "TOTAL_PASSED" : "TOTAL_FAILED");
            signature.put("sub_indication", null);
            signature.put("errors", List.of());
            signature.put("warnings", List.of());
            signature.put("signer", "Test Signer");
            signature.put("signing_time", "2020-01-01T00:00:00Z");
            signature.put("profile", "LT");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("result", result);
            body.put("signatures", List.of(signature));
            body.put("errors", List.of());
            body.put("warnings", List.of());
            return body;
        };
    }

    private static JsonNode error(McpSchema.CallToolResult result) {
        assertEquals(true, result.isError());
        var text = (McpSchema.TextContent) result.content().getFirst();
        return JsonMapper.shared().readTree(text.text()).path("error");
    }

    private static void assertNoCode(String message) {
        assertFalse(message.contains(FIXTURE_CODE), message);
        assertFalse(message.contains(OTHER_CODE), message);
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = SmartIdSigningTest.class.getResourceAsStream(name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class ManualClock implements SmartIdSigning.WaitClock {
        long now;
        Runnable onSleep = () -> { };
        @Override public long nanoTime() { return now; }
        @Override public void sleep(long millis) {
            now += TimeUnit.MILLISECONDS.toNanos(millis);
            onSleep.run();
        }
    }
}
