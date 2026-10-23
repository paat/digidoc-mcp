package ee.paat.digidoc;

import org.digidoc4j.Configuration;
import org.digidoc4j.ContainerBuilder;
import org.digidoc4j.impl.asic.tsl.TSLCertificateSourceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("network")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class TimestampValidationNetworkTest {
    @TempDir Path root;

    @Test void timestampOnlyAsicsHasAValidCryptographicVerdict() throws Exception {
        Path fixture = root.resolve("timestamp-only.asics");
        try (var input = getClass().getResourceAsStream("/fixtures/timestamp-only.asics")) {
            assertNotNull(input);
            Files.copy(input, fixture);
        }
        Configuration inspection = new Configuration(Configuration.Mode.TEST);
        inspection.setTSL(new TSLCertificateSourceImpl());
        var container = ContainerBuilder.aContainer().withConfiguration(inspection)
                .fromExistingFile(fixture.toString()).build();
        assertEquals("ASICS", container.getType());
        assertTrue(container.getSignatures().isEmpty());
        assertEquals(1, container.getTimestamps().size());

        var result = TestFixtures.service(root).validateContainer(fixture.toString());
        assertEquals("VALID", result.get("result"), result.toString());
        assertEquals(List.of(), result.get("signatures"));
    }
}
