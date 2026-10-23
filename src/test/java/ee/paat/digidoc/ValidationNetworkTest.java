package ee.paat.digidoc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("network")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ValidationNetworkTest {
    @TempDir Path root;

    @Test void testSignedAsiceIsValidInTestMode() throws Exception {
        Path fixture = TestFixtures.copyValid(root);
        var result = TestFixtures.service(root).validateContainer(fixture.toString());
        assertEquals(Set.of("result", "signatures", "errors", "warnings"), result.keySet());
        assertEquals("VALID", result.get("result"), result.toString());
        var signatures = ContainerServiceTest.rows(result.get("signatures"));
        assertEquals(1, signatures.size());
        assertEquals(Set.of("id", "indication", "sub_indication", "errors", "warnings", "signer", "signing_time", "profile"),
                signatures.getFirst().keySet());
        assertNotNull(signatures.getFirst().get("indication"));
        assertNotNull(signatures.getFirst().get("signer"));
        assertNotNull(signatures.getFirst().get("signing_time"));
        assertEquals("LT", signatures.getFirst().get("profile"));
    }

    @Test void changingSignedPayloadMakesContainerInvalid() throws Exception {
        Path valid = TestFixtures.copyValid(root);
        Path tampered = TestFixtures.tampered(valid, root.resolve("tampered.asice"));
        var result = TestFixtures.service(root).validateContainer(tampered.toString());
        assertEquals("INVALID", result.get("result"), result.toString());
        assertEquals(1, ContainerServiceTest.rows(result.get("signatures")).size());
    }
}
