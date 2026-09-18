package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenProviderTest {

    private final JwtTokenProvider provider = new JwtTokenProvider(
            "unit-test-secret-with-at-least-256-bits-of-entropy", 1);

    @Test
    void createsAndParsesToken() {
        String token = provider.createToken(7L, "alice", Set.of("USER", "STAFF"));
        JwtTokenProvider.Claims claims = provider.parse(token);

        assertEquals(7L, claims.userId());
        assertEquals("alice", claims.username());
        assertEquals(Set.of("USER", "STAFF"), Set.copyOf(claims.roles()));
    }

    @Test
    void rejectsTamperedToken() {
        String token = provider.createToken(7L, "alice", Set.of("USER"));
        // 不能用「改末位字符」的写法：HS256 签名是 32 字节，base64url 编码成 43 个字符，
        // 末位字符只有高 4 bit 参与解码，'a'(011010) 与 'b'(011011) 的高 4 bit 相同 ——
        // 换掉末位后签名可能一个字节都没变，令牌依然合法，用例会随机失败。
        // 改成首字符：它携带 6 个有效 bit，'A'/'B' 互换必然改变签名。
        int signatureStart = token.lastIndexOf('.') + 1;
        char original = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart)
                + (original == 'A' ? 'B' : 'A')
                + token.substring(signatureStart + 1);

        assertThrows(IllegalArgumentException.class, () -> provider.parse(tampered));
    }

    @Test
    void refusesMissingSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("", 1));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(null, 1));
    }

    @Test
    void refusesShortSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("too-short", 1));
    }
}
