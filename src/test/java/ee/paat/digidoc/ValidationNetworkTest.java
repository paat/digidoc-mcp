package ee.paat.digidoc;

import org.digidoc4j.Configuration;
import org.digidoc4j.ContainerBuilder;
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
        var signatures = ContainerServiceTest.rows(result.get("signatures"));
        assertEquals(1, signatures.size());
        assertEquals("HASH_FAILURE", signatures.getFirst().get("sub_indication"), result.toString());
    }

    @Test void timestampedCompositeReportsNestedSignatureValidation() throws Exception {
        Path fixture = TestFixtures.copyComposite(root);
        var result = TestFixtures.service(root).validateContainer(fixture.toString());
        var signatures = ContainerServiceTest.rows(result.get("signatures"));
        assertEquals(1, signatures.size(), result.toString());
        assertEquals("TOTAL_PASSED", signatures.getFirst().get("indication"), result.toString());
        assertNotNull(signatures.getFirst().get("signer"));
        assertEquals("LT", signatures.getFirst().get("profile"));
        assertEquals("VALID", result.get("result"), result.toString());
    }

    @Test void timestampedBasicCompositePreservesInnerIndeterminateVerdict() throws Exception {
        Path fixture = TestFixtures.copyComposite(root, "composite-b-bes.asics");
        Configuration configuration = new Configuration(Configuration.Mode.TEST);
        configuration.setConnectionTimeout(15_000);
        configuration.setSocketTimeout(30_000);
        var report = ContainerBuilder.aContainer().withConfiguration(configuration)
                .fromExistingFile(fixture.toString()).build().validate();
        assertEquals(1, report.getSignatureIdList().size());
        String id = report.getSignatureIdList().getFirst();
        assertEquals("INDETERMINATE", report.getIndication(id).name());
        var result = TestFixtures.service(root).validateContainer(fixture.toString());
        var signatures = ContainerServiceTest.rows(result.get("signatures"));
        assertEquals(1, signatures.size(), result.toString());
        assertEquals(report.getIndication(id).name(), signatures.getFirst().get("indication"));
        assertEquals(report.getSubIndication(id).name(), signatures.getFirst().get("sub_indication"));
        assertEquals("INDETERMINATE", result.get("result"), result.toString());
    }
}
