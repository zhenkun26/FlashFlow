package dev.flashflow.admission;

import dev.flashflow.shared.config.FlashFlowProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class AdmissionIdentity {
    private final byte[] secret;

    public AdmissionIdentity(FlashFlowProperties properties) {
        String configured = properties.admission().identitySecret();
        this.secret = (configured == null || configured.isBlank()
                ? "mysql-only-development-secret" : configured).getBytes(StandardCharsets.UTF_8);
    }

    public String admissionId(String operation, String callerId, String idempotencyKey) {
        return digest("operation\u0000" + operation + "\u0000" + callerId + "\u0000" + idempotencyKey);
    }

    public String userDigest(String skuId, String userId) {
        return digest("user\u0000" + skuId + "\u0000" + userId);
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }
}
