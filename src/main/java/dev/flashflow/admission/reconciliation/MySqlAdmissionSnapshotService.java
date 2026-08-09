package dev.flashflow.admission.reconciliation;

import dev.flashflow.admission.persistence.AdmissionReconciliationMapper;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MySqlAdmissionSnapshotService {
    private final AdmissionReconciliationMapper mapper;
    private final Clock clock;

    public MySqlAdmissionSnapshotService(AdmissionReconciliationMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MySqlAdmissionFacts capture(String skuId) {
        var stock = mapper.findStock(skuId);
        if (stock == null) throw new IllegalArgumentException("Unknown activity SKU: " + skuId);
        return new MySqlAdmissionFacts(clock.instant(), stock,
                mapper.findEffectiveOrders(skuId), mapper.findAllOrderIdempotency());
    }
}
