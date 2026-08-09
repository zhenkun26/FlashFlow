package dev.flashflow.admission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AdmissionKeys {
    private final String prefix;

    public AdmissionKeys(String skuId) {
        this.prefix = "flashflow:v2:{" + sha256(skuId) + "}";
    }

    public String current() { return prefix + ":current"; }
    public String generationBase() { return prefix + ":g:"; }
    public String meta(String generation) { return prefix + ":g:" + generation + ":meta"; }
    public String admissions(String generation) { return prefix + ":g:" + generation + ":admissions"; }
    public String users(String generation) { return prefix + ":g:" + generation + ":users"; }
    public String deadlines(String generation) { return prefix + ":g:" + generation + ":deadlines"; }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
