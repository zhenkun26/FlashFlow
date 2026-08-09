package dev.flashflow.admission;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "flashflow.admission", name = "mode", havingValue = "MYSQL_ONLY")
public final class MySqlOnlyAdmissionAdapter implements AdmissionPort, AdmissionAdministrationPort {
    @Override
    public AdmissionResult acquire(AdmissionCommand command) {
        return new AdmissionResult(AdmissionDecision.BYPASSED, command.admissionId(), "mysql-only");
    }

    @Override
    public AdmissionLifecycleResult confirm(AdmissionCommand command, String generation) {
        return new AdmissionLifecycleResult(AdmissionLifecycleDecision.CONFIRMED, generation);
    }

    @Override
    public AdmissionLifecycleResult release(AdmissionCommand command, String generation, boolean confirmedClosure) {
        return new AdmissionLifecycleResult(AdmissionLifecycleDecision.RELEASED, generation);
    }

    @Override
    public AdmissionLifecycleResult quarantine(AdmissionCommand command, String generation) {
        return new AdmissionLifecycleResult(AdmissionLifecycleDecision.QUARANTINED, generation);
    }

    @Override
    public boolean beginGeneration(String skuId, String generation, int capacity, String fenceToken) {
        return true;
    }

    @Override
    public boolean publishGeneration(String skuId, String generation, String fenceToken) {
        return true;
    }

    @Override
    public AdmissionGenerationSnapshot snapshot(String skuId) {
        return new AdmissionGenerationSnapshot("mysql-only", AdmissionGenerationState.READY, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public List<AdmissionRecordView> records(String skuId) {
        return List.of();
    }

    @Override
    public boolean seed(String skuId, String generation, String fenceToken,
                        AdmissionRecordView record, boolean consumeCapacity) {
        return true;
    }
}
