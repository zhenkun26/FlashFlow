package dev.flashflow.verification.experiment;

import dev.flashflow.FlashFlowApplication;
import dev.flashflow.admission.AdmissionGenerationSnapshot;
import dev.flashflow.admission.RedisLuaAdmissionAdapter;
import dev.flashflow.admission.reconciliation.AdmissionReconciliationReport;
import dev.flashflow.admission.reconciliation.AdmissionReconciliationService;
import dev.flashflow.admission.reconciliation.ReconciliationStatus;
import java.nio.file.Path;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public final class AdmissionExperimentCli {
    private AdmissionExperimentCli() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: <report-directory> <sku-count>");
        }
        Path reports = Path.of(args[0]);
        int skuCount = Integer.parseInt(args[1]);
        String requestedGeneration = System.getenv().getOrDefault(
                "FLASHFLOW_ADMISSION_GENERATION", "experiment-v2");
        if (skuCount < 1) throw new IllegalArgumentException("sku-count must be positive");

        try (var context = new SpringApplicationBuilder(FlashFlowApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=OFF",
                        "flashflow.expiration.scheduling-enabled=false")
                .run()) {
            AdmissionReconciliationService reconciliation = context.getBean(AdmissionReconciliationService.class);
            RedisLuaAdmissionAdapter admission = context.getBean(RedisLuaAdmissionAdapter.class);
            admission.scriptDigests().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> System.out.println("scriptDigest." + entry.getKey() + "=" + entry.getValue()));
            for (int index = 1; index <= skuCount; index++) {
                String skuId = "experiment-sku-" + index;
                String generation = skuCount == 1
                        ? requestedGeneration : requestedGeneration + "-" + index;
                AdmissionReconciliationReport report = reconciliation.reconcile(skuId, reports, generation);
                if (report.status() != ReconciliationStatus.PASS) {
                    throw new IllegalStateException("Reconciliation " + report.status() + " for " + skuId);
                }
                AdmissionGenerationSnapshot state = admission.snapshot(skuId);
                String prefix = "sku." + index + ".";
                System.out.println(prefix + "generation=" + state.generation());
                System.out.println(prefix + "state=" + state.state());
                System.out.println(prefix + "initial=" + state.initialCapacity());
                System.out.println(prefix + "remaining=" + state.remainingCapacity());
                System.out.println(prefix + "held=" + state.held());
                System.out.println(prefix + "confirmed=" + state.confirmed());
                System.out.println(prefix + "released=" + state.released());
                System.out.println(prefix + "quarantined=" + state.quarantined());
            }
        }
    }
}
