package ee.paat.digidoc;

import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.enumerations.SubIndication;
import org.digidoc4j.ValidationResult;
import org.digidoc4j.exceptions.DigiDoc4JException;
import org.digidoc4j.impl.asic.AsicCompositeContainerValidationResult;
import org.digidoc4j.impl.asic.AsicContainerValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidationSummaryTest {
    @ParameterizedTest
    @CsvSource({"TOTAL_PASSED,VALID", "INDETERMINATE,INDETERMINATE", "TOTAL_FAILED,INVALID"})
    void compositeReportIncludesNestedSignatureAndItsVerdict(Indication indication, String verdict) {
        var outer = new Report(Map.of("timestamp", Indication.PASSED), List.of(), List.of("timestamp"));
        var inner = new Report(Map.of("nested-signature", indication), List.of("nested-signature"), List.of());
        var report = new AsicCompositeContainerValidationResult(outer, inner);

        // Exercise the actual 6.2.0 composite report API, without a TSA or trust download.
        assertEquals(List.of("nested-signature"), report.getSignatureIdList());
        var summary = ContainerService.validationSummary(List.of(), report);
        var signatures = ContainerServiceTest.rows(summary.get("signatures"));
        assertEquals(1, signatures.size());
        assertEquals("nested-signature", signatures.getFirst().get("id"));
        assertEquals(indication.name(), signatures.getFirst().get("indication"));
        assertEquals(verdict, summary.get("result"));
        if (indication == Indication.TOTAL_FAILED) {
            assertEquals("HASH_FAILURE", signatures.getFirst().get("sub_indication"));
        }
    }

    @Test void unsignedContainerWithErrorsIsInvalid() {
        var report = new Report(Map.of(), List.of(), List.of());
        report.setContainerErrors(List.of(new DigiDoc4JException("UNTRUSTED_ERROR_TEXT")));
        var summary = ContainerService.validationSummary(List.of(), report);
        assertEquals("INVALID", summary.get("result"));
        assertFalse(summary.toString().contains("UNTRUSTED_ERROR_TEXT"));
    }

    @Test void unsignedCleanContainerIsIndeterminate() {
        var report = new Report(Map.of(), List.of(), List.of());
        assertEquals("INDETERMINATE", ContainerService.validationSummary(List.of(), report).get("result"));
    }

    @Test void containerFailureTakesPrecedenceOverIndeterminateSignature() {
        var report = new Report(Map.of("signature", Indication.INDETERMINATE), List.of("signature"), List.of());
        report.setContainerErrors(List.of(new DigiDoc4JException("invalid container")));
        assertEquals("INVALID", ContainerService.validationSummary(List.of(), report).get("result"));
    }

    @Test void timestampFailureTakesPrecedenceOverIndeterminateSignature() {
        var report = new Report(Map.of("signature", Indication.INDETERMINATE, "timestamp", Indication.FAILED),
                List.of("signature"), List.of("timestamp"));
        assertEquals("INVALID", ContainerService.validationSummary(List.of(), report).get("result"));
    }

    /** Minimal offline report: only the report projection's inputs are overridden. */
    private static final class Report extends AsicContainerValidationResult {
        private final Map<String, Indication> indications;
        private final List<String> signatures;
        private final List<String> timestamps;

        private Report(Map<String, Indication> indications, List<String> signatures, List<String> timestamps) {
            this.indications = indications;
            this.signatures = signatures;
            this.timestamps = timestamps;
        }

        @Override public List<String> getSignatureIdList() { return signatures; }
        @Override public List<String> getTimestampIdList() { return timestamps; }
        @Override public Indication getIndication(String id) { return indications.get(id); }
        @Override public SubIndication getSubIndication(String id) {
            return indications.get(id) == Indication.TOTAL_FAILED ? SubIndication.HASH_FAILURE : null;
        }
        @Override public ValidationResult getValidationResult(String id) { return null; }
        @Override public boolean isValid() {
            return getContainerErrors().isEmpty() && !indications.isEmpty() && indications.values().stream()
                    .allMatch(value -> value == Indication.PASSED || value == Indication.TOTAL_PASSED);
        }
    }
}
