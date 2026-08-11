package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flashflow.shared.config.FlashFlowProperties;
import dev.flashflow.verification.experiment.ExperimentManifest;
import dev.flashflow.verification.experiment.ExperimentManifestValidator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExperimentManifestValidatorTest {
    @Test
    void acceptsCheckedInIndependentCasesAndOneFactorGroups() throws Exception {
        ExperimentManifest manifest = new ObjectMapper().readValue(
                Path.of("experiments/matrix.json").toFile(), ExperimentManifest.class);

        assertThat(ExperimentManifestValidator.validate(manifest)).isEmpty();
        assertThat(manifest.cases()).hasSize(13);
        assertThat(manifest.comparisons()).hasSize(9);
    }

    @Test
    void rejectsComparisonThatChangesSeveralFactors() {
        ExperimentManifest.Case baseline = validCase("baseline");
        ExperimentManifest.Case changed = new ExperimentManifest.Case(
                "changed", "local", FlashFlowProperties.Strategy.PESSIMISTIC,
                20, 5, 100, ExperimentManifest.SkuDistribution.SINGLE_HOT,
                1, 10, 3000, 3, 3, FlashFlowProperties.TransactionSequence.STOCK_FIRST);
        ExperimentManifest manifest = new ExperimentManifest(1, List.of(baseline, changed),
                List.of(new ExperimentManifest.Comparison("bad", ExperimentManifest.Factor.STRATEGY,
                        List.of("baseline", "changed"))));

        assertThatThrownBy(() -> ExperimentManifestValidator.requireValid(manifest))
                .hasMessageContaining("must change only STRATEGY")
                .hasMessageContaining("VUS");
    }

    @Test
    void rejectsUnsafeStrategyOutsideLab() {
        ExperimentManifest.Case unsafe = new ExperimentManifest.Case(
                "unsafe", "local", FlashFlowProperties.Strategy.UNSAFE_READ_THEN_WRITE,
                10, 5, 100, ExperimentManifest.SkuDistribution.SINGLE_HOT,
                1, 10, 3000, 3, 3, FlashFlowProperties.TransactionSequence.STOCK_FIRST);

        assertThat(ExperimentManifestValidator.validate(
                new ExperimentManifest(1, List.of(unsafe), List.of())))
                .anyMatch(error -> error.contains("unsafe strategy requires the lab profile"));
    }

    @Test
    void rejectsMissingAndUnknownCaseReferences() {
        ExperimentManifest manifest = new ExperimentManifest(1, List.of(validCase("baseline")),
                List.of(new ExperimentManifest.Comparison("unknown", ExperimentManifest.Factor.VUS,
                        List.of("baseline", "missing"))));

        assertThat(ExperimentManifestValidator.validate(manifest))
                .anyMatch(error -> error.contains("references an unknown case"));
    }

    private static ExperimentManifest.Case validCase(String id) {
        return new ExperimentManifest.Case(
                id, "local", FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC,
                10, 5, 100, ExperimentManifest.SkuDistribution.SINGLE_HOT,
                1, 10, 3000, 3, 3, FlashFlowProperties.TransactionSequence.STOCK_FIRST);
    }
}
