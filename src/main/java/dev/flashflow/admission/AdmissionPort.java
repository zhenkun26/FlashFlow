package dev.flashflow.admission;

public interface AdmissionPort {
    AdmissionResult acquire(AdmissionCommand command);
    AdmissionLifecycleResult confirm(AdmissionCommand command, String generation);
    AdmissionLifecycleResult release(AdmissionCommand command, String generation, boolean confirmedClosure);
    AdmissionLifecycleResult quarantine(AdmissionCommand command, String generation);
}
