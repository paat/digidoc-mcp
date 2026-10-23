package ee.paat.digidoc;

import org.digidoc4j.Configuration;
import org.digidoc4j.Container;
import org.digidoc4j.ContainerBuilder;
import org.digidoc4j.SignatureBuilder;
import org.digidoc4j.SignatureProfile;
import org.digidoc4j.signers.PKCS12SignatureToken;
import org.digidoc4j.impl.asic.tsl.TSLCertificateSourceImpl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class TestFixtures {
    private TestFixtures() {}

    static Path copyValid(Path root) throws IOException {
        Path destination = root.resolve("valid.asice");
        try (var input = TestFixtures.class.getResourceAsStream("/fixtures/valid-asice.asice")) {
            if (input == null) throw new IOException("Missing fixture");
            Files.copy(input, destination);
        }
        return destination;
    }

    static Path copyComposite(Path root) throws IOException {
        return copyComposite(root, "composite-asics.asics");
    }

    static Path copyComposite(Path root, String name) throws IOException {
        Path destination = root.resolve(name);
        try (var input = TestFixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (input == null) throw new IOException("Missing composite fixture");
            Files.copy(input, destination);
        }
        return destination;
    }

    static ContainerService service(Path root) {
        return service(root, 100L * 1024 * 1024);
    }

    static ContainerService service(Path root, long limit) {
        return new ContainerService(new AppConfig(List.of(root), limit, Configuration.Mode.TEST,
                root.resolve("tsl-cache")));
    }

    static Path unsigned(Path root, String name, byte[] data) {
        Container container = ContainerBuilder.aContainer(Container.DocumentType.ASICE)
                .withConfiguration(offlineConfiguration()).build();
        container.addDataFile(new ByteArrayInputStream(data), name, "application/octet-stream");
        Path destination = root.resolve("unsigned.asice");
        container.saveAsFile(destination.toString());
        return destination;
    }

    static Path multiSigned(Path root) throws Exception {
        Container container = ContainerBuilder.aContainer(Container.DocumentType.ASICE)
                .withConfiguration(offlineConfiguration()).build();
        container.addDataFile(new ByteArrayInputStream("first document".getBytes(StandardCharsets.UTF_8)),
                "first.txt", "text/plain");
        container.addDataFile(new ByteArrayInputStream("second document".getBytes(StandardCharsets.UTF_8)),
                "second.txt", "text/plain");
        Path token = root.resolve("test-token.p12");
        try (var input = TestFixtures.class.getResourceAsStream("/fixtures/test-signing-token.p12")) {
            if (input == null) throw new IOException("Missing test token");
            Files.copy(input, token);
        }
        var signer = new PKCS12SignatureToken(token.toString(), "1234".toCharArray());
        try {
            for (int i = 0; i < 2; i++) {
                container.addSignature(SignatureBuilder.aSignature(container)
                        .withSignatureProfile(SignatureProfile.B_BES).withSignatureToken(signer).invokeSigning());
            }
        } finally { signer.close(); }
        Path destination = root.resolve("multi.asice");
        container.saveAsFile(destination.toString());
        return destination;
    }

    private static Configuration offlineConfiguration() {
        Configuration configuration = new Configuration(Configuration.Mode.TEST);
        // B_BES fixture generation needs no trusted list or online validation.
        configuration.setTSL(new TSLCertificateSourceImpl());
        return configuration;
    }

    static Path tampered(Path original, Path destination) throws IOException {
        boolean replaced = false;
        try (var input = new ZipInputStream(Files.newInputStream(original));
             var output = new ZipOutputStream(Files.newOutputStream(destination))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] bytes = input.readAllBytes();
                if (entry.getName().equals("test.txt")) {
                    bytes = "tampered after signing".getBytes(StandardCharsets.UTF_8);
                    replaced = true;
                }
                ZipEntry rewritten = new ZipEntry(entry.getName());
                rewritten.setMethod(entry.getMethod());
                if (entry.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    rewritten.setSize(bytes.length);
                    rewritten.setCrc(crc.getValue());
                }
                output.putNextEntry(rewritten);
                output.write(bytes);
                output.closeEntry();
            }
        }
        if (!replaced) throw new IOException("Fixture had no expected data file");
        return destination;
    }
}
