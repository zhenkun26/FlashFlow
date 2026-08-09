package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionCommand;
import dev.flashflow.admission.AdmissionDecision;
import dev.flashflow.admission.AdmissionGenerationSnapshot;
import dev.flashflow.admission.AdmissionLifecycleDecision;
import dev.flashflow.admission.AdmissionKeys;
import dev.flashflow.admission.RedisLuaAdmissionAdapter;
import dev.flashflow.support.RedisIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RedisLuaAdmissionIntegrationTest extends RedisIntegrationTest {
    @Autowired private RedisLuaAdmissionAdapter admission;

    @Test
    void generationIsInvisibleUntilPublishedAndRejectsStaleOperations() {
        AdmissionCommand command = command("s1", "a1", "u1", Instant.now().plusSeconds(10));
        assertThat(admission.acquire(command).decision()).isEqualTo(AdmissionDecision.NOT_READY);

        assertThat(admission.beginGeneration("s1", "g1", 1, "f1")).isTrue();
        assertThat(admission.acquire(command).decision()).isEqualTo(AdmissionDecision.NOT_READY);
        assertThat(admission.publishGeneration("s1", "g1", "f1")).isTrue();
        assertThat(admission.acquire(command).decision()).isEqualTo(AdmissionDecision.ADMITTED);

        assertThat(admission.beginGeneration("s1", "g2", 1, "f2")).isTrue();
        assertThat(admission.publishGeneration("s1", "g2", "f2")).isTrue();
        assertThat(admission.confirm(command, "g1").decision())
                .isEqualTo(AdmissionLifecycleDecision.STALE_GENERATION);
    }

    @Test
    void concurrentAcquireIsBoundedReplaySafeAndOnePerUser() throws Exception {
        ready("s1", "g1", 3);
        try (var executor = Executors.newFixedThreadPool(10)) {
            List<Future<AdmissionDecision>> futures = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                int current = index;
                futures.add(executor.submit(() -> admission.acquire(command(
                        "s1", "a" + current, "u" + current, Instant.now().plusSeconds(30))).decision()));
            }
            List<AdmissionDecision> decisions = new ArrayList<>();
            for (Future<AdmissionDecision> future : futures) decisions.add(future.get());
            assertThat(decisions).filteredOn(AdmissionDecision.ADMITTED::equals).hasSize(3);
            assertThat(decisions).filteredOn(AdmissionDecision.NO_TOKEN::equals).hasSize(7);
        }

        AdmissionCommand original = command("s2", "same", "user", Instant.now().plusSeconds(30));
        ready("s2", "g1", 2);
        assertThat(admission.acquire(original).decision()).isEqualTo(AdmissionDecision.ADMITTED);
        assertThat(admission.acquire(original).decision()).isEqualTo(AdmissionDecision.REPLAY);
        assertThat(admission.acquire(command("s2", "other", "user", Instant.now().plusSeconds(30))).decision())
                .isEqualTo(AdmissionDecision.USER_ACTIVE);
        assertThat(admission.snapshot("s2").remainingCapacity()).isEqualTo(1);
    }

    @Test
    void lifecycleIsIdempotentBoundedAndDeadlinesDoNotReturnCapacity() {
        ready("s1", "g1", 2);
        AdmissionCommand paid = command("s1", "paid", "u1", Instant.now().minusSeconds(1));
        AdmissionCommand closed = command("s1", "closed", "u2", Instant.now().minusSeconds(1));
        admission.acquire(paid);
        admission.acquire(closed);

        assertThat(admission.snapshot("s1").remainingCapacity()).isZero();
        assertThat(admission.confirm(paid, "g1").decision()).isEqualTo(AdmissionLifecycleDecision.CONFIRMED);
        assertThat(admission.confirm(paid, "g1").decision()).isEqualTo(AdmissionLifecycleDecision.ALREADY_CONFIRMED);
        assertThat(admission.confirm(closed, "g1").decision()).isEqualTo(AdmissionLifecycleDecision.CONFIRMED);
        assertThat(admission.release(closed, "g1", true).decision()).isEqualTo(AdmissionLifecycleDecision.RELEASED);
        assertThat(admission.release(closed, "g1", true).decision())
                .isEqualTo(AdmissionLifecycleDecision.ALREADY_RELEASED);

        AdmissionGenerationSnapshot snapshot = admission.snapshot("s1");
        assertThat(snapshot.remainingCapacity()).isEqualTo(1);
        assertThat(snapshot.confirmed()).isEqualTo(1);
        assertThat(snapshot.released()).isEqualTo(1);
    }

    @Test
    void replayAfterSimulatedLostAcquireReplyDoesNotDecrementAgain() {
        ready("s1", "g1", 1);
        AdmissionCommand command = command("s1", "lost-reply", "u1", Instant.now().plusSeconds(30));

        // The first result is deliberately ignored to model a reply lost after Lua committed.
        admission.acquire(command);
        assertThat(admission.acquire(command).decision()).isEqualTo(AdmissionDecision.REPLAY);
        assertThat(admission.snapshot("s1").remainingCapacity()).isZero();
        assertThat(admission.snapshot("s1").held()).isEqualTo(1);
    }

    @Test
    void missingOrVersionMismatchedStateFailsClosed() {
        ready("s1", "g1", 1);
        AdmissionKeys keys = new AdmissionKeys("s1");
        redis.delete(keys.meta("g1"));
        assertThat(admission.acquire(command("s1", "a1", "u1", Instant.now())).decision())
                .isEqualTo(AdmissionDecision.NOT_READY);

        redis.opsForHash().put(keys.current(), "version", "unknown");
        assertThat(admission.acquire(command("s1", "a2", "u2", Instant.now())).decision())
                .isEqualTo(AdmissionDecision.VERSION_MISMATCH);
    }

    @Test
    void scriptsHaveStableNonEmptyDigests() {
        assertThat(admission.scriptDigests()).hasSize(7);
        assertThat(admission.scriptDigests().values()).allSatisfy(value -> assertThat(value).hasSize(64));
    }

    private void ready(String skuId, String generation, int capacity) {
        String fence = UUID.randomUUID().toString();
        assertThat(admission.beginGeneration(skuId, generation, capacity, fence)).isTrue();
        assertThat(admission.publishGeneration(skuId, generation, fence)).isTrue();
    }

    private static AdmissionCommand command(
            String skuId, String admissionId, String userDigest, Instant deadline) {
        return new AdmissionCommand(skuId, admissionId, userDigest, deadline);
    }
}
