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
        int signatureStart = token.lastIndexOf('.') + 1;
        char original = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart) + (original == 'a' ? 'b' : 'a')
                + token.substring(signatureStart + 1);

        assertThrows(IllegalArgumentException.class, () -> provider.parse(tampered));
    }
}
