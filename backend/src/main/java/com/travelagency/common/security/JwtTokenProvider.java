package com.travelagency.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Small dependency-free JWT HS256 provider for the starter project.
 * Replace the secret through JWT_SECRET in every deployed environment.
 */
@Component
public class JwtTokenProvider {

    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    /** HS256 下过短的密钥很容易被暴力枚举，低于 32 字节直接拒绝启动。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final byte[] secret;
    private final long expireHours;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expire-hours:24}") long expireHours) {
        // fail-fast：密钥缺失或过短时在启动阶段就失败，避免使用公开默认值或弱密钥签发令牌。
        // JWT_SECRET 必须由部署环境注入（CONTRIBUTING §14）。
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET 未配置：必须通过环境变量注入至少 32 字节的签名密钥");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET 过短：签名密钥至少需要 " + MIN_SECRET_BYTES + " 字节");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expireHours = expireHours;
    }

    public String createToken(Long userId, String username, Set<String> roles) {
        long expiresAt = Instant.now().plusSeconds(expireHours * 3600).getEpochSecond();
        String payload = "{\"sub\":\"" + escape(String.valueOf(userId))
                + "\",\"username\":\"" + escape(username)
                + "\",\"roles\":\"" + escape(roles.stream().sorted().collect(Collectors.joining("|")))
                + "\",\"exp\":" + expiresAt + "}";
        String encodedHeader = encode(HEADER);
        String encodedPayload = encode(payload);
        String content = encodedHeader + "." + encodedPayload;
        return content + "." + encodeBytes(sign(content));
    }

    /**
     * 令牌有效期秒数，用于契约 AuthSession.expiresIn。
     */
    public long expireSeconds() {
        return expireHours * 3600;
    }

    public Claims parse(String token) {
        try {
            String[] pieces = token.split("\\.");
            if (pieces.length != 3) {
                throw new IllegalArgumentException("Malformed token");
            }
            String content = pieces[0] + "." + pieces[1];
            byte[] expected = sign(content);
            byte[] actual = Base64.getUrlDecoder().decode(pieces[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException("Invalid signature");
            }
            String payload = new String(Base64.getUrlDecoder().decode(pieces[1]), StandardCharsets.UTF_8);
            long exp = readLong(payload, "exp");
            if (exp <= Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("Token expired");
            }
            Long userId = Long.valueOf(readString(payload, "sub"));
            String username = readString(payload, "username");
            String roleValue = readString(payload, "roles");
            List<String> roles = roleValue.isBlank() ? List.of() : List.of(roleValue.split("\\|"));
            return new Claims(userId, username, roles, exp);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid token", ex);
        }
    }

    private byte[] sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT signing is unavailable", ex);
        }
    }

    private static String encode(String value) {
        return encodeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeBytes(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String readString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Missing claim: " + key);
        }
        start += marker.length();
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char current = json.charAt(i);
            if (escaped) {
                result.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return result.toString();
            } else {
                result.append(current);
            }
        }
        throw new IllegalArgumentException("Unterminated claim: " + key);
    }

    private static long readLong(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Missing claim: " + key);
        }
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    public record Claims(Long userId, String username, List<String> roles, long expiresAt) {
    }
}
