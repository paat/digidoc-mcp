package ee.paat.digidoc;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class InspectionProfileTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"<xades:ArchiveTimeStamp/>",
            "<xades:SignatureTimeStamp/><xades:CertificateValues/><xades:RevocationValues/>"})
    void counterSignatureEvidenceDoesNotUpgradeOuterBProfile(String evidence) throws Exception {
        Path original = TestFixtures.multiSigned(root);
        Path injected = root.resolve("counter-signature.asice");
        boolean changed = false;
        try (var input = new ZipInputStream(Files.newInputStream(original));
             var output = new ZipOutputStream(Files.newOutputStream(injected))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] bytes = input.readAllBytes();
                if (entry.getName().equals("META-INF/signatures0.xml")) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    assertFalse(xml.contains("<xades:UnsignedSignatureProperties>"));
                    String nested = xml.substring(xml.indexOf("<ds:Signature "),
                            xml.indexOf("</ds:Signature>") + "</ds:Signature>".length());
                    nested = nested.replace("id-", "counter-id-");
                    nested = nested.replace("</xades:QualifyingProperties>",
                            unsigned(evidence) + "</xades:QualifyingProperties>");
                    xml = xml.replace("</xades:QualifyingProperties>",
                            unsigned("<xades:CounterSignature>" + nested + "</xades:CounterSignature>")
                                    + "</xades:QualifyingProperties>");
                    bytes = xml.getBytes(StandardCharsets.UTF_8);
                    changed = true;
                }
                ZipEntry replacement = new ZipEntry(entry.getName());
                replacement.setMethod(entry.getMethod());
                if (entry.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    replacement.setSize(bytes.length);
                    replacement.setCrc(crc.getValue());
                }
                output.putNextEntry(replacement);
                output.write(bytes);
                output.closeEntry();
            }
        }
        assertTrue(changed);
        var signatures = ContainerServiceTest.rows(TestFixtures.service(root)
                .listSignatures(injected.toString()).get("signatures"));
        assertEquals(2, signatures.size());
        assertTrue(signatures.stream().allMatch(signature -> "B".equals(signature.get("profile"))),
                signatures.toString());
    }

    private static String unsigned(String contents) {
        return "<xades:UnsignedProperties><xades:UnsignedSignatureProperties>" + contents
                + "</xades:UnsignedSignatureProperties></xades:UnsignedProperties>";
    }
}
