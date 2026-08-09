package dev.flashflow.admission;

import dev.flashflow.shared.config.FlashFlowProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "flashflow.admission", name = "mode", havingValue = "REDIS_LUA")
public final class RedisLuaAdmissionAdapter implements AdmissionPort, AdmissionAdministrationPort {
    private static final String MAINTENANCE_LEASE_MILLIS = "30000";
    private enum Script { BEGIN, PUBLISH, SEED, ACQUIRE, CONFIRM, RELEASE, QUARANTINE }

    private final StringRedisTemplate redis;
    private final String version;
    private final Map<Script, DefaultRedisScript<List>> scripts = new EnumMap<>(Script.class);
    private final Map<Script, String> digests = new EnumMap<>(Script.class);

    public RedisLuaAdmissionAdapter(StringRedisTemplate redis, FlashFlowProperties properties) {
        this.redis = redis;
        this.version = properties.admission().scriptVersion();
        load(Script.BEGIN, "redis/admission-begin.lua");
        load(Script.PUBLISH, "redis/admission-publish.lua");
        load(Script.SEED, "redis/admission-seed.lua");
        load(Script.ACQUIRE, "redis/admission-acquire.lua");
        load(Script.CONFIRM, "redis/admission-confirm.lua");
        load(Script.RELEASE, "redis/admission-release.lua");
        load(Script.QUARANTINE, "redis/admission-quarantine.lua");
    }

    @Override
    public AdmissionResult acquire(AdmissionCommand command) {
        AdmissionKeys keys = new AdmissionKeys(command.skuId());
        try {
            List<?> reply = execute(Script.ACQUIRE, List.of(keys.current(), keys.generationBase()),
                    version, command.admissionId(), command.userDigest(),
                    Long.toString(command.resolutionDeadline().toEpochMilli()));
            return new AdmissionResult(decision(reply), string(reply, 1), string(reply, 2));
        } catch (RedisConnectionFailureException exception) {
            return failure(AdmissionDecision.UNAVAILABLE, command);
        } catch (QueryTimeoutException exception) {
            return failure(AdmissionDecision.AMBIGUOUS, command);
        } catch (RedisSystemException | IllegalArgumentException exception) {
            return failure(AdmissionDecision.MALFORMED_REPLY, command);
        }
    }

    @Override
    public AdmissionLifecycleResult confirm(AdmissionCommand command, String generation) {
        return lifecycle(Script.CONFIRM, command, generation, "0");
    }

    @Override
    public AdmissionLifecycleResult release(AdmissionCommand command, String generation, boolean confirmedClosure) {
        return lifecycle(Script.RELEASE, command, generation, confirmedClosure ? "1" : "0");
    }

    @Override
    public AdmissionLifecycleResult quarantine(AdmissionCommand command, String generation) {
        return lifecycle(Script.QUARANTINE, command, generation, "0");
    }

    @Override
    public boolean beginGeneration(String skuId, String generation, int capacity, String fenceToken) {
        AdmissionKeys keys = new AdmissionKeys(skuId);
        List<?> result = execute(Script.BEGIN,
                List.of(keys.current(), keys.meta(generation), keys.admissions(generation),
                        keys.users(generation), keys.deadlines(generation)),
                version, generation, Integer.toString(capacity), fenceToken, MAINTENANCE_LEASE_MILLIS);
        return "INITIALIZING".equals(string(result, 0));
    }

    @Override
    public boolean publishGeneration(String skuId, String generation, String fenceToken) {
        AdmissionKeys keys = new AdmissionKeys(skuId);
        List<?> result = execute(Script.PUBLISH, List.of(keys.current(), keys.meta(generation)),
                version, generation, fenceToken);
        return "READY".equals(string(result, 0));
    }

    @Override
    public AdmissionGenerationSnapshot snapshot(String skuId) {
        AdmissionKeys keys = new AdmissionKeys(skuId);
        Map<Object, Object> current = redis.opsForHash().entries(keys.current());
        if (current.isEmpty()) {
            return new AdmissionGenerationSnapshot(null, AdmissionGenerationState.MISSING, 0, 0, 0, 0, 0, 0);
        }
        String generation = String.valueOf(current.get("generation"));
        AdmissionGenerationState state = AdmissionGenerationState.valueOf(String.valueOf(current.get("state")));
        if (generation == null || "null".equals(generation)) {
            return new AdmissionGenerationSnapshot(null, state, 0, 0, 0, 0, 0, 0);
        }
        Map<Object, Object> meta = redis.opsForHash().entries(keys.meta(generation));
        Map<Object, Object> records = redis.opsForHash().entries(keys.admissions(generation));
        long held = count(records, AdmissionState.HELD);
        long confirmed = count(records, AdmissionState.CONFIRMED);
        long released = count(records, AdmissionState.RELEASED);
        long quarantined = count(records, AdmissionState.QUARANTINED);
        return new AdmissionGenerationSnapshot(generation, state,
                number(meta.get("initial")), number(meta.get("remaining")),
                held, confirmed, released, quarantined);
    }

    @Override
    public List<AdmissionRecordView> records(String skuId) {
        AdmissionGenerationSnapshot snapshot = snapshot(skuId);
        if (snapshot.generation() == null) return List.of();
        AdmissionKeys keys = new AdmissionKeys(skuId);
        Map<Object, Object> stored = redis.opsForHash().entries(keys.admissions(snapshot.generation()));
        List<AdmissionRecordView> result = new java.util.ArrayList<>();
        stored.forEach((id, raw) -> {
            String[] fields = raw.toString().split("\\|", -1);
            if (fields.length == 3) {
                result.add(new AdmissionRecordView(id.toString(), fields[1], AdmissionState.valueOf(fields[0]),
                        java.time.Instant.ofEpochMilli(Long.parseLong(fields[2]))));
            }
        });
        return List.copyOf(result);
    }

    @Override
    public boolean seed(String skuId, String generation, String fenceToken,
                        AdmissionRecordView record, boolean consumeCapacity) {
        AdmissionKeys keys = new AdmissionKeys(skuId);
        List<?> result = execute(Script.SEED,
                List.of(keys.meta(generation), keys.admissions(generation),
                        keys.users(generation), keys.deadlines(generation)),
                version, generation, fenceToken, record.admissionId(), record.userDigest(),
                record.state().name(), Long.toString(record.resolutionDeadline().toEpochMilli()),
                consumeCapacity ? "1" : "0");
        return "SEEDED".equals(string(result, 0));
    }

    public Map<String, String> scriptDigests() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        scripts.keySet().forEach(key -> result.put(key.name(), digests.get(key)));
        return Map.copyOf(result);
    }

    private AdmissionLifecycleResult lifecycle(
            Script script, AdmissionCommand command, String generation, String closure) {
        AdmissionKeys keys = new AdmissionKeys(command.skuId());
        try {
            List<?> reply = execute(script, List.of(keys.current(), keys.generationBase()),
                    version, generation, command.admissionId(), closure);
            return new AdmissionLifecycleResult(lifecycleDecision(reply), string(reply, 1));
        } catch (RedisConnectionFailureException exception) {
            return new AdmissionLifecycleResult(AdmissionLifecycleDecision.UNAVAILABLE, generation);
        } catch (QueryTimeoutException exception) {
            return new AdmissionLifecycleResult(AdmissionLifecycleDecision.AMBIGUOUS, generation);
        } catch (RedisSystemException | IllegalArgumentException exception) {
            return new AdmissionLifecycleResult(AdmissionLifecycleDecision.AMBIGUOUS, generation);
        }
    }

    @SuppressWarnings("unchecked")
    private List<?> execute(Script script, List<String> keys, String... arguments) {
        List<?> result = redis.execute(scripts.get(script), keys, (Object[]) arguments);
        if (result == null || result.isEmpty()) {
            throw new IllegalArgumentException("Empty Redis Lua reply");
        }
        return result;
    }

    private void load(Script key, String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try {
            byte[] bytes = resource.getInputStream().readAllBytes();
            if (bytes.length == 0) {
                throw new IllegalStateException("Redis Lua script is empty: " + path);
            }
            DefaultRedisScript<List> script = new DefaultRedisScript<>();
            script.setScriptSource(new ResourceScriptSource(resource));
            script.setResultType(List.class);
            scripts.put(key, script);
            digests.put(key, sha256(bytes));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot load Redis Lua script: " + path, exception);
        }
    }

    private static AdmissionResult failure(AdmissionDecision decision, AdmissionCommand command) {
        return new AdmissionResult(decision, command.admissionId(), null);
    }

    private static AdmissionDecision decision(List<?> reply) {
        try {
            return AdmissionDecision.valueOf(string(reply, 0));
        } catch (RuntimeException exception) {
            return AdmissionDecision.MALFORMED_REPLY;
        }
    }

    private static AdmissionLifecycleDecision lifecycleDecision(List<?> reply) {
        try {
            return AdmissionLifecycleDecision.valueOf(string(reply, 0));
        } catch (RuntimeException exception) {
            return AdmissionLifecycleDecision.AMBIGUOUS;
        }
    }

    private static String string(List<?> values, int index) {
        if (index >= values.size() || values.get(index) == null) return null;
        Object value = values.get(index);
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
    }

    private static int number(Object value) {
        return value == null ? 0 : Integer.parseInt(value.toString());
    }

    private static long count(Map<Object, Object> records, AdmissionState state) {
        return records.values().stream().map(Object::toString)
                .filter(value -> value.startsWith(state.name() + "|")).count();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
