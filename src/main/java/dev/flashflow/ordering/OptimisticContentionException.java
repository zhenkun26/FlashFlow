package dev.flashflow.ordering;

final class OptimisticContentionException extends RuntimeException {
    OptimisticContentionException() {
        super("Optimistic inventory conflict");
    }
}

