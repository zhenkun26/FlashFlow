package dev.flashflow.ordering;

final class ClaimRaceException extends RuntimeException {
    ClaimRaceException() {
        super("Effective purchase claim committed after precheck");
    }
}
