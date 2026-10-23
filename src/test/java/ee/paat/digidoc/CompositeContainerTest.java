package ee.paat.digidoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CompositeContainerTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"composite-asics.asics", "composite-b-bes.asics"})
    void openingCompositeCountsNestedSignaturesOffline(String name) throws Exception {
        Path composite = TestFixtures.copyComposite(root, name);
        var result = TestFixtures.service(root).openContainer(composite.toString());
        assertEquals("ASICS", result.get("type"));
        assertEquals(1, result.get("signature_count"));
        assertFalse(Files.exists(root.resolve("tsl-cache")));
    }

    @Test void listingBasicCompositeIncludesNestedSignatureMetadataOffline() throws Exception {
        Path composite = TestFixtures.copyComposite(root, "composite-b-bes.asics");
        var signatures = ContainerServiceTest.rows(TestFixtures.service(root)
                .listSignatures(composite.toString()).get("signatures"));
        assertEquals(1, signatures.size());
        assertEquals("B", signatures.getFirst().get("profile"));
        assertNotNull(signatures.getFirst().get("signer_common_name"));
        assertFalse(Files.exists(root.resolve("tsl-cache")));
    }

    @Test void listingCompositeIncludesNestedSignatureMetadataOffline() throws Exception {
        Path composite = TestFixtures.copyComposite(root);
        Path inner = TestFixtures.copyValid(root);
        var service = TestFixtures.service(root);
        var expected = ContainerServiceTest.rows(service.listSignatures(inner.toString()).get("signatures"));
        var actual = ContainerServiceTest.rows(service.listSignatures(composite.toString()).get("signatures"));
        assertEquals(1, expected.size());
        assertEquals(expected, actual);
        assertFalse(Files.exists(root.resolve("tsl-cache")));
    }
}
