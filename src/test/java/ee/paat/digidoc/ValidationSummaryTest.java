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

    @Test void timestampWithoutIndicationUsesValidResult() {
        assertEquals("VALID", timestampVerdict(null, true));
    }

    @Test void timestampWithoutIndicationUsesFailedResult() {
        assertEquals("INVALID", timestampVerdict(null, false));
    }

    @Test void timestampWithoutIndicationOrResultIsIndeterminate() {
        assertEquals("INDETERMINATE", timestampVerdict(null, null));
    }

    @Test void explicitTimestampIndicationTakesPrecedence() {
        assertEquals("VALID", timestampVerdict(Indication.PASSED, false));
        assertEquals("VALID", timestampVerdict(Indication.TOTAL_PASSED, null));
        assertEquals("INVALID", timestampVerdict(Indication.FAILED, true));
        assertEquals("INVALID", timestampVerdict(Indication.TOTAL_FAILED, null));
        assertEquals("INDETERMINATE", timestampVerdict(Indication.INDETERMINATE, true));
    }

    @Test void failureDominatesMissingTimestampResultRegardlessOfOrder() {
        for (var ids : List.of(List.of("missing", "failed"), List.of("failed", "missing"))) {
            var report = timestampReport(Map.of(), Map.of("failed", false), ids);
            assertEquals("INVALID", ContainerService.validationSummary(List.of(), report).get("result"));
        }
    }

    @Test void timestampDoesNotOverrideExistingSignatureOrReportFailure() {
        assertEquals("INVALID", ContainerService.validationVerdict(true, true, List.of("PASSED")));
        assertEquals("INDETERMINATE", ContainerService.validationVerdict(false, true, List.of("INDETERMINATE", "PASSED")));
        assertEquals("INVALID", ContainerService.validationVerdict(false, true, List.of("FAILED", "PASSED")));
        assertEquals("INVALID", ContainerService.validationVerdict(false, false, List.of("PASSED")));
    }

    private static Object timestampVerdict(Indication indication, Boolean valid) {
        var report = timestampReport(indication == null ? Map.of() : Map.of("timestamp", indication),
                valid == null ? Map.of() : Map.of("timestamp", valid), List.of("timestamp"));
        return ContainerService.validationSummary(List.of(), report).get("result");
    }

    private static Report timestampReport(Map<String, Indication> indications, Map<String, Boolean> results,
                                          List<String> timestamps) {
        return new Report(indications, List.of(), timestamps) {
            @Override public ValidationResult getValidationResult(String id) {
                return !results.containsKey(id) ? null : new AsicContainerValidationResult() {
                    @Override public boolean isValid() { return results.get(id); }
                };
            }
            @Override public boolean isValid() { return true; }
        };
    }

    /** Minimal offline report: only the report projection's inputs are overridden. */
    private static class Report extends AsicContainerValidationResult {
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
