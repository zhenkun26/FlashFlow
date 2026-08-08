package dev.flashflow.verification;

import dev.flashflow.verification.persistence.InvariantMapper;
import dev.flashflow.verification.persistence.InvariantSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvariantService {
    private final InvariantMapper invariantMapper;

    public InvariantService(InvariantMapper invariantMapper) {
        this.invariantMapper = invariantMapper;
    }

    @Transactional(readOnly = true)
    public InvariantSnapshot inspectCommittedState() {
        return invariantMapper.snapshot();
    }

    public void requireValid() {
        InvariantSnapshot snapshot = inspectCommittedState();
        if (!snapshot.valid()) {
            throw new AssertionError("FlashFlow invariants violated: " + snapshot);
        }
    }
}
