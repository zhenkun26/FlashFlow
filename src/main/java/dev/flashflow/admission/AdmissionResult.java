package dev.flashflow.admission;

public record AdmissionResult(AdmissionDecision decision, String admissionId, String generation) {
    public boolean permitsMySqlAttempt() {
        return decision == AdmissionDecision.ADMITTED
                || decision == AdmissionDecision.REPLAY
                || decision == AdmissionDecision.BYPASSED;
    }
}
