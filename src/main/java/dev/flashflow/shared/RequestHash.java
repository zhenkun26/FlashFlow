package dev.flashflow.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RequestHash {
    private RequestHash() {
    }

    public static String order(String userId, String activitySkuId) {
        return sha256("order\n" + userId + "\n" + activitySkuId + "\nquantity=1");
    }

    public static String payment(String providerEventId, String providerTransactionId, String orderId,
                                 String amount, String currency) {
        return sha256("payment\n" + providerEventId + "\n" + providerTransactionId + "\n"
                + orderId + "\n" + amount + "\n" + currency);
    }

    public static String sha256(String canonical) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}

