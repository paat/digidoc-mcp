package ee.paat.digidoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipPreflightTest {
    @TempDir Path root;

    @Test void nestedExpansionExceedsOneSharedBudget() throws Exception {
        byte[] inner = zip("large.txt", new byte[32_000]);
        byte[] outer = zip("inner.asice", inner);
        assertTrue(outer.length < 4096);
        rejected(outer, 4096, "SIZE_LIMIT");
    }

    @Test void nestedLevelsCannotResetBudget() throws Exception {
        byte[] inner = zip("small.txt", new byte[1000]);
        byte[] outer = zip("inner.asice", inner);
        rejected(outer, inner.length + 999L, "SIZE_LIMIT");
    }

    @Test void legitimateNestedContainerPasses() throws Exception {
        preflight(zip("inner.asice", zip("small.txt", new byte[1000])), 4096);
    }

    @Test void storedNestedZipEocdIsNotAnOuterEocd() throws Exception {
        preflight(zip("inner.asice", zip("small.txt", new byte[1000]), true), 4096);
    }

    @Test void ordinaryXmlWithDtdRemainsAnOpaqueDataFile() throws Exception {
        byte[] xml = "<!DOCTYPE note [<!ENTITY text 'ordinary'>]><note>&text;</note>".getBytes(StandardCharsets.UTF_8);
        preflight(zip("note.xml", xml), 4096);
    }

    @Test void asicsRejectsDtdBeforeLegacyReaderCanExpandIt() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            // Put mimetype last: classification cannot depend on entry order.
            output.putNextEntry(new ZipEntry("payload.xml"));
            output.write("<!DOCTYPE ordinary [<!ENTITY text 'data'>]><ordinary>&text;</ordinary>"
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("mimetype"));
            output.write("application/vnd.etsi.asic-s+zip".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        rejected(bytes.toByteArray(), 4096, "UNSUPPORTED_CONTAINER");
    }

    @Test void nestingDepthBombIsRejected() throws Exception {
        byte[] bytes = zip("file.txt", new byte[1]);
        for (int i = 0; i < 3; i++) bytes = zip("inner.asice", bytes);
        rejected(bytes, 100_000, "UNSUPPORTED_CONTAINER");
    }

    @Test void concatenatedArchivesAreRejected() throws Exception {
        byte[] first = zip("first.txt", new byte[10]);
        byte[] second = zip("second.txt", new byte[10]);
        var bytes = new ByteArrayOutputStream();
        bytes.write(first);
        bytes.write(second);
        rejected(bytes.toByteArray(), 4096, "UNSUPPORTED_CONTAINER");
    }

    @Test void concatenatedArchiveHiddenInCommentWithOversizedLengthIsRejected() throws Exception {
        byte[] first = zip("first.txt", new byte[10]);
        byte[] second = zip("second.txt", new byte[10]);
        byte[] bytes = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, bytes, first.length, second.length);
        // The first EOCD treats the concatenated archive as its comment. The second
        // EOCD's impossible comment length must not hide its signature from preflight.
        bytes[first.length - 2] = (byte) second.length;
        bytes[first.length - 1] = (byte) (second.length >>> 8);
        bytes[bytes.length - 2] = (byte) 0xff;
        bytes[bytes.length - 1] = (byte) 0xff;
        rejected(bytes, 4096, "UNSUPPORTED_CONTAINER");
    }

    @Test void truncatedEocdSignatureInCommentIsRejected() throws Exception {
        byte[] original = zip("file.txt", new byte[10]);
        byte[] bytes = Arrays.copyOf(original, original.length + 4);
        bytes[original.length - 2] = 4;
        put32(bytes, original.length, 0x06054b50);
        rejected(bytes, 4096, "UNSUPPORTED_CONTAINER");
    }

    @Test void ordinaryArchiveCommentStillPasses() throws Exception {
        byte[] original = zip("file.txt", new byte[10]);
        byte[] comment = "ordinary comment".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = Arrays.copyOf(original, original.length + comment.length);
        System.arraycopy(comment, 0, bytes, original.length, comment.length);
        bytes[original.length - 2] = (byte) comment.length;
        preflight(bytes, 4096);
    }

    @Test void trailingBytesAreRejected() throws Exception {
        byte[] original = zip("file.txt", new byte[10]);
        rejected(Arrays.copyOf(original, original.length + 1), 4096, "UNSUPPORTED_CONTAINER");
    }

    @Test void centralDirectoryCannotRenameLocalEntry() throws Exception {
        byte[] bytes = zip("file.txt", new byte[10]);
        bytes[centralOffset(bytes) + 46] = 'X';
        rejected(bytes, 4096, "UNSUPPORTED_CONTAINER");
    }

    @Test void centralDirectoryOnlyLongNameIsRejected() throws Exception {
        byte[] original = zip("file.txt", new byte[1]);
        int central = centralOffset(original);
        byte[] name = "a".repeat(1025).getBytes(StandardCharsets.UTF_8);
        int growth = name.length - "file.txt".length();
        byte[] bytes = new byte[original.length + growth];
        System.arraycopy(original, 0, bytes, 0, central + 46);
        System.arraycopy(name, 0, bytes, central + 46, name.length);
        System.arraycopy(original, central + 46 + 8, bytes, central + 46 + name.length,
                original.length - central - 46 - 8);
        bytes[central + 28] = (byte) name.length;
        bytes[central + 29] = (byte) (name.length >>> 8);
        put32(bytes, bytes.length - 22 + 12, 46 + name.length);
        rejected(bytes, 4096, "NAME_LIMIT");
    }

    @Test void nestedZipAndDdocNamesAreBounded() throws Exception {
        String name = "a".repeat(1025);
        rejected(zip("inner.asice", zip(name, new byte[1])), 4096, "NAME_LIMIT");
        String ddoc = "<SignedDoc><DataFile ContentType=\"EMBEDDED_BASE64\" Filename=\""
                + name + "\">QQ==</DataFile></SignedDoc>";
        rejected(zip("inner.ddoc", ddoc.getBytes(StandardCharsets.UTF_8)), 4096, "NAME_LIMIT");
    }

    @Test void centralDirectoryCannotPointOutsideLocalChain() throws Exception {
        byte[] bytes = zip("file.txt", new byte[10]);
        put32(bytes, centralOffset(bytes) + 42, 1);
        rejected(bytes, 4096, "UNSUPPORTED_CONTAINER");
    }

    @Test void inflatedBytesAreCountedEvenWhenDeclaredSizeIsTiny() throws Exception {
        byte[] bytes = zip("file.txt", new byte[32_000]);
        int central = centralOffset(bytes);
        put32(bytes, central + 24, 1);
        put32(bytes, central - 4, 1); // matching data descriptor's uncompressed size
        rejected(bytes, 4096, "SIZE_LIMIT");
    }

    @Test void entryCountIsSharedAcrossNestedArchives() throws Exception {
        var inner = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(inner)) {
            for (int i = 0; i < 10_000; i++) {
                out.putNextEntry(new ZipEntry("f" + i));
                out.closeEntry();
            }
        }
        rejected(zip("inner.asice", inner.toByteArray()), 10_000_000, "SIZE_LIMIT");
    }

    @Test void nestedDdocCannotResolveExternalFile() throws Exception {
        String ddoc = "<SignedDoc format=\"DIGIDOC-XML\" version=\"1.3\"><DataFile ContentType=\"EMBEDDED_BASE64\" Filename=\"secret.txt\" Id=\"D0\" MimeType=\"text/plain\" Size=\"1\"></DataFile></SignedDoc>";
        rejected(zip("inner.ddoc", ddoc.getBytes(StandardCharsets.UTF_8)), 4096, "UNSUPPORTED_CONTAINER");
    }

    @Test void nestedDdocDecodedBytesShareBudget() throws Exception {
        String ddoc = "<SignedDoc format=\"DIGIDOC-XML\" version=\"1.3\"><DataFile ContentType=\"EMBEDDED_BASE64\" Filename=\"file.txt\" Id=\"D0\" MimeType=\"text/plain\" Size=\"300\">"
                + java.util.Base64.getEncoder().encodeToString(new byte[300]) + "</DataFile></SignedDoc>";
        rejected(zip("inner.ddoc", ddoc.getBytes(StandardCharsets.UTF_8)), ddoc.length() + 299L, "SIZE_LIMIT");
    }

    @Test void junkPrefixCannotHideNestedDdocFromPreflight() throws Exception {
        String ddoc = "JUNK<?xml version=\"1.0\"?><SignedDoc format=\"DIGIDOC-XML\" version=\"1.3\">"
                + "<DataFile ContentType=\"EMBEDDED_BASE64\" Filename=\"file.txt\" Id=\"D0\" "
                + "MimeType=\"text/plain\" Size=\"1\"></DataFile></SignedDoc>";
        rejected(zip("payload.bin", ddoc.getBytes(StandardCharsets.UTF_8)), 4096, "UNSUPPORTED_CONTAINER");
    }

    @Test void junkPrefixedNestedDdocSharesDecodedBudget() throws Exception {
        String ddoc = "JUNK<?xml version=\"1.0\"?><SignedDoc format=\"DIGIDOC-XML\" version=\"1.3\">"
                + "<DataFile ContentType=\"EMBEDDED_BASE64\" Filename=\"file.txt\" Id=\"D0\" "
                + "MimeType=\"text/plain\" Size=\"300\">"
                + java.util.Base64.getEncoder().encodeToString(new byte[300]) + "</DataFile></SignedDoc>";
        rejected(zip("payload.bin", ddoc.getBytes(StandardCharsets.UTF_8)), ddoc.length() + 299L, "SIZE_LIMIT");
        preflight(zip("payload.bin", ddoc.getBytes(StandardCharsets.UTF_8)), ddoc.length() + 300L);
    }

    private void rejected(byte[] bytes, long limit, String code) {
        ToolException error = assertThrows(ToolException.class, () -> preflight(bytes, limit));
        assertEquals(code, error.code());
    }

    private void preflight(byte[] bytes, long limit) throws Exception {
        // Exercise the production pre-build boundary, including against the original implementation.
        var method = ContainerService.class.getDeclaredMethod("preflightZip", byte[].class);
        method.setAccessible(true);
        try { method.invoke(TestFixtures.service(root, limit), bytes); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static byte[] zip(String name, byte[] payload) throws Exception { return zip(name, payload, false); }
    private static byte[] zip(String name, byte[] payload, boolean stored) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry(name);
            if (stored) {
                CRC32 crc = new CRC32(); crc.update(payload);
                entry.setMethod(ZipEntry.STORED); entry.setSize(payload.length); entry.setCrc(crc.getValue());
            }
            output.putNextEntry(entry); output.write(payload); output.closeEntry();
        }
        return bytes.toByteArray();
    }
    private static int centralOffset(byte[] bytes) {
        int eocd = bytes.length - 22;
        return (bytes[eocd + 16] & 255) | (bytes[eocd + 17] & 255) << 8
                | (bytes[eocd + 18] & 255) << 16 | (bytes[eocd + 19] & 255) << 24;
    }
    private static void put32(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (8 * i));
    }
}
