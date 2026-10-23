package ee.paat.digidoc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContainerServiceTest {
    @TempDir Path temporary;
    Path root;
    Path fixture;
    ContainerService service;

    @BeforeEach void prepare() throws Exception {
        root = Files.createDirectory(temporary.resolve("allowed"));
        fixture = TestFixtures.copyValid(root);
        service = TestFixtures.service(root);
    }

    @Test void openReportsTypeFilesAndSignatureCount() {
        var result = service.openContainer(fixture.toString());
        assertEquals(Set.of("type", "data_files", "signature_count"), result.keySet());
        assertEquals("ASICE", result.get("type"));
        assertEquals(1, ((Number) result.get("signature_count")).intValue());
        var files = rows(result.get("data_files"));
        assertEquals(1, files.size());
        assertEquals(Map.of("name", "test.txt", "mime_type", "text/plain", "size_bytes", 15L), files.getFirst());
    }

    @Test void listSignaturesReportsCertificateAndTimes() {
        var signatures = rows(service.listSignatures(fixture.toString()).get("signatures"));
        assertEquals(1, signatures.size());
        var signature = signatures.getFirst();
        assertEquals(Set.of("id", "signer_common_name", "personal_identification_code", "claimed_signing_time",
                "trusted_signing_time", "profile", "signature_method", "certificate_issuer"), signature.keySet());
        assertFalse(((String) signature.get("id")).isBlank());
        assertFalse(((String) signature.get("signer_common_name")).isBlank());
        assertFalse(((String) signature.get("personal_identification_code")).isBlank());
        assertNotNull(signature.get("claimed_signing_time"));
        assertNotNull(signature.get("trusted_signing_time"));
        assertEquals("LT", signature.get("profile"));
        assertNotNull(signature.get("signature_method"));
        assertNotNull(signature.get("certificate_issuer"));
    }

    @Test void metadataInspectionDoesNotInitializeTslCache() throws Exception {
        service.openContainer(fixture.toString());
        service.listSignatures(fixture.toString());
        Path output = Files.createDirectory(root.resolve("output"));
        service.extractFile(fixture.toString(), "test.txt", output.toString(), false);
        assertFalse(Files.exists(root.resolve("tsl-cache")),
                "Opening, listing and extracting must not initialize the online TSL loader");
    }

    @Test void multipleFilesAndSignaturesAreAllListed() throws Exception {
        Path multi = TestFixtures.multiSigned(root);
        var opened = service.openContainer(multi.toString());
        assertEquals(2, rows(opened.get("data_files")).size());
        assertEquals(2, ((Number) opened.get("signature_count")).intValue());
        var signatures = rows(service.listSignatures(multi.toString()).get("signatures"));
        assertEquals(2, signatures.size());
        assertNotEquals(signatures.get(0).get("id"), signatures.get(1).get("id"));
        assertTrue(signatures.stream().allMatch(signature -> "B".equals(signature.get("profile"))));
    }

    @Test void extractionReturnsAbsolutePathAndActualSha256Only() throws Exception {
        var result = service.extractFile(fixture.toString(), "test.txt", root.toString(), false);
        Path target = root.resolve("test.txt");
        assertEquals(Set.of("path", "sha256"), result.keySet());
        assertEquals(target.toRealPath().toString(), result.get("path"));
        assertEquals("see on testfail", Files.readString(target));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target))),
                result.get("sha256"));
    }

    @Test void extractionRefusesExistingTargetAndPreservesBytes() throws Exception {
        Path target = Files.writeString(root.resolve("test.txt"), "keep me");
        assertThrows(ToolException.class,
                () -> service.extractFile(fixture.toString(), "test.txt", root.toString(), false));
        assertEquals("keep me", Files.readString(target));
    }

    @Test void explicitOverwriteReplacesExistingFile() throws Exception {
        Path target = Files.writeString(root.resolve("test.txt"), "replace me");
        service.extractFile(fixture.toString(), "test.txt", root.toString(), true);
        assertEquals("see on testfail", Files.readString(target));
    }

    @ParameterizedTest @ValueSource(strings = {"../test.txt", "a/test.txt", "a\\test.txt", "a..b", "/test.txt", "..", "bad\u0000name"})
    void extractionRejectsUnsafeNames(String name) {
        assertThrows(ToolException.class,
                () -> service.extractFile(fixture.toString(), name, root.toString(), false));
        assertFalse(Files.exists(temporary.resolve("test.txt")));
    }

    @Test void extractionRequiresExactCaseSensitiveMatch() {
        assertThrows(ToolException.class,
                () -> service.extractFile(fixture.toString(), "TEST.TXT", root.toString(), false));
        assertFalse(Files.exists(root.resolve("TEST.TXT")));
    }

    @Test void outsideRootIsRefusedByEveryTool() throws Exception {
        Path outside = Files.copy(fixture, temporary.resolve("outside.asice"));
        assertThrows(ToolException.class, () -> service.openContainer(outside.toString()));
        assertThrows(ToolException.class, () -> service.listSignatures(outside.toString()));
        assertThrows(ToolException.class, () -> service.validateContainer(outside.toString()));
        assertThrows(ToolException.class,
                () -> service.extractFile(outside.toString(), "test.txt", root.toString(), false));
    }

    @Test void symlinkInsideRootCannotOpenOutsideContainer() throws Exception {
        Path outside = Files.copy(fixture, temporary.resolve("outside.asice"));
        Path link = Files.createSymbolicLink(root.resolve("link.asice"), outside);
        assertThrows(ToolException.class, () -> service.openContainer(link.toString()));
    }

    @Test void dotDotEscapeIsRefused() throws Exception {
        Files.copy(fixture, temporary.resolve("outside.asice"));
        assertThrows(ToolException.class, () -> service.openContainer(root.resolve("../outside.asice").toString()));
    }

    @Test void commonPrefixSiblingIsNotAnAllowedRoot() throws Exception {
        Path sibling = Files.createDirectory(temporary.resolve("allowed-sibling"));
        Path outside = Files.copy(fixture, sibling.resolve("outside.asice"));
        assertThrows(ToolException.class, () -> service.openContainer(outside.toString()));
    }

    @Test void outputDirectoryMustBeInsideAllowedRoot() {
        assertThrows(ToolException.class,
                () -> service.extractFile(fixture.toString(), "test.txt", temporary.toString(), false));
    }

    @Test void outputDirectorySymlinkCannotEscape() throws Exception {
        Path link = Files.createSymbolicLink(root.resolve("output"), temporary);
        assertThrows(ToolException.class,
                () -> service.extractFile(fixture.toString(), "test.txt", link.toString(), false));
        assertFalse(Files.exists(temporary.resolve("test.txt")));
    }

    @Test void existingTargetSymlinkCannotWriteOutsideEvenWithOverwrite() throws Exception {
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "keep me");
        Files.createSymbolicLink(root.resolve("test.txt"), outside);
        assertThrows(ToolException.class,
                () -> service.extractFile(fixture.toString(), "test.txt", root.toString(), true));
        assertEquals("keep me", Files.readString(outside));
    }

    @Test void danglingTargetSymlinkCannotWriteOutside() throws Exception {
        Path outside = temporary.resolve("absent.txt");
        Files.createSymbolicLink(root.resolve("test.txt"), outside);
        assertThrows(ToolException.class,
                () -> service.extractFile(fixture.toString(), "test.txt", root.toString(), true));
        assertFalse(Files.exists(outside));
    }

    @Test void oversizedContainerIsRejectedBeforeParsing() throws Exception {
        Path oversized = Files.writeString(root.resolve("oversized.asice"), "this is deliberately not an archive".repeat(100));
        var limited = TestFixtures.service(root, 32);
        ToolException error = assertThrows(ToolException.class, () -> limited.openContainer(oversized.toString()));
        assertTrue(error.code().toUpperCase().contains("SIZE") || error.code().toUpperCase().contains("LARGE"), error.code());
    }

    @Test void highlyCompressedExtractionIsBoundedAndLeavesNoPartialFile() throws Exception {
        byte[] payload = new byte[128 * 1024];
        java.util.Arrays.fill(payload, (byte) 'A');
        Path bomb = TestFixtures.unsigned(root, "large.txt", payload);
        long limit = 8192;
        assertTrue(Files.size(bomb) < limit, "Fixture must pass the compressed container size limit");
        Path output = Files.createDirectory(root.resolve("output"));
        ToolException error = assertThrows(ToolException.class,
                () -> TestFixtures.service(root, limit).extractFile(bomb.toString(), "large.txt", output.toString(), false));
        assertEquals("SIZE_LIMIT", error.code(), "The application byte cap must reject the expansion");
        try (var entries = Files.list(output)) {
            assertEquals(0, entries.count(), "Failed extraction must remove partial and temporary files");
        }
    }

    @Test void malformedContainerHasSafeFailure() throws Exception {
        Path malformed = Files.writeString(root.resolve("malformed.asice"), "UNTRUSTED_SECRET_CONTENT");
        ToolException error = assertThrows(ToolException.class, () -> service.openContainer(malformed.toString()));
        assertFalse(error.getMessage().contains("UNTRUSTED_SECRET_CONTENT"));
        assertFalse(error.getMessage().contains(root.toString()));
        assertTrue(error.getMessage().length() <= 512);
    }

    @Test void streamingCopyNeverWritesBeyondLimitEvenWithoutDeclaredSize() throws Exception {
        byte[] data = new byte[20_000];
        var output = new java.io.ByteArrayOutputStream();
        ToolException error = assertThrows(ToolException.class, () -> ContainerService.copyBounded(
                new java.io.ByteArrayInputStream(data), output, 10_000, MessageDigest.getInstance("SHA-256")));
        assertEquals("SIZE_LIMIT", error.code());
        assertTrue(output.size() <= 10_000);
        assertTrue(output.size() > 0, "Exercise the guard after a partial write, not only an upfront size check");
    }

    @Test void legacyDdocCannotResolveEmptyDataFileBodyFromOutsideDisk() throws Exception {
        Path secret = Files.writeString(temporary.resolve("secret.txt"), "PRIVATE_OUTSIDE_BYTES");
        String xml = "<?xml version=\"1.0\"?><SignedDoc format=\"DIGIDOC-XML\" version=\"1.3\" "
                + "xmlns=\"http://www.sk.ee/DigiDoc/v1.3.0#\"><DataFile ContentType=\"EMBEDDED_BASE64\" "
                + "Filename=\"" + secret + "\" Id=\"D0\" MimeType=\"text/plain\" Size=\"21\"></DataFile></SignedDoc>";
        Path ddoc = Files.writeString(root.resolve("external.ddoc"), xml);
        ToolException error = assertThrows(ToolException.class, () -> service.openContainer(ddoc.toString()));
        assertEquals("UNSUPPORTED_CONTAINER", error.code());
        assertFalse(error.getMessage().contains("PRIVATE_OUTSIDE_BYTES"));
        assertFalse(error.getMessage().contains(secret.toString()));
    }

    @Test void legacyDdocRejectsExternalXmlEntities() throws Exception {
        Path secret = Files.writeString(temporary.resolve("secret.txt"), "PRIVATE_OUTSIDE_BYTES");
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE SignedDoc [<!ENTITY external SYSTEM \""
                + secret.toUri() + "\">]><SignedDoc format=\"DIGIDOC-XML\" version=\"1.3\" "
                + "xmlns=\"http://www.sk.ee/DigiDoc/v1.3.0#\"><DataFile ContentType=\"EMBEDDED\" "
                + "Filename=\"test.txt\" Id=\"D0\" MimeType=\"text/plain\" Size=\"21\">&external;</DataFile></SignedDoc>";
        Path ddoc = Files.writeString(root.resolve("entities.ddoc"), xml);
        ToolException error = assertThrows(ToolException.class, () -> service.openContainer(ddoc.toString()));
        assertEquals("UNSUPPORTED_CONTAINER", error.code());
        assertFalse(error.getMessage().contains("PRIVATE_OUTSIDE_BYTES"));
        assertFalse(error.getMessage().contains(secret.toString()));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
