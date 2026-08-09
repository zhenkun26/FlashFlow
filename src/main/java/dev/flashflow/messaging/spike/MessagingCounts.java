package dev.flashflow.messaging.spike;

public record MessagingCounts(
        long produced,
        long acknowledged,
        long ambiguous,
        long delivered,
        long redelivered,
        long consumed,
        long rejected,
        long unresolved,
        long stableCommands,
        long committedEffects) {

    public boolean reconciles() {
        return produced == acknowledged + ambiguous + rejected
                && delivered + redelivered >= consumed + unresolved
                && committedEffects <= stableCommands;
    }
}
