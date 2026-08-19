package com.rcf.imas.platform.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtService svc = new JwtService(new JwtProperties("shortsecret", "1d", "15m"));

    @Test
    void issuesAndParsesFinalToken() {
        String token = svc.issueFinalToken("123", "admin", "ADMIN");
        JwtService.FinalToken parsed = svc.parseFinalToken(token);
        assertThat(parsed.userId()).isEqualTo("123");
        assertThat(parsed.userName()).isEqualTo("admin");
        assertThat(parsed.roleName()).isEqualTo("ADMIN");
    }

    @Test
    void acceptsTokenSignedTheWayNodeSignsIt() {
        String nodeToken = JWT.create()
                .withClaim("user_id", "77")
                .withClaim("user_name", "coord1")
                .withClaim("role_name", "BATCH COORDINATOR")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
                .sign(Algorithm.HMAC256("shortsecret"));
        JwtService.FinalToken parsed = svc.parseFinalToken(nodeToken);
        assertThat(parsed.roleName()).isEqualTo("BATCH COORDINATOR");
    }

    @Test
    void issuesAndParsesPreAuthToken() {
        String token = svc.issuePreAuthToken("9", "multi", List.of("ADMIN", "TEACHER"));
        JwtService.PreAuthToken parsed = svc.parsePreAuthToken(token);
        assertThat(parsed.allowedRoles()).containsExactly("ADMIN", "TEACHER");
    }

    @Test
    void rejectsPreAuthTokenAsFinalToken() {
        String pre = svc.issuePreAuthToken("9", "multi", List.of("ADMIN"));
        assertThatThrownBy(() -> svc.parseFinalToken(pre))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void expiredTokenThrowsExpired() {
        String expired = JWT.create()
                .withClaim("user_id", "1").withClaim("user_name", "x").withClaim("role_name", "ADMIN")
                .withExpiresAt(Date.from(Instant.now().minusSeconds(5)))
                .sign(Algorithm.HMAC256("shortsecret"));
        assertThatThrownBy(() -> svc.parseFinalToken(expired))
                .isInstanceOf(JwtService.ExpiredTokenException.class);
    }

    @Test
    void parsesDurationStringsLikeNode() {
        assertThat(JwtService.parseDuration("15m").toMinutes()).isEqualTo(15);
        assertThat(JwtService.parseDuration("1d").toHours()).isEqualTo(24);
        assertThat(JwtService.parseDuration("12h").toHours()).isEqualTo(12);
        assertThat(JwtService.parseDuration("3600").toSeconds()).isEqualTo(3600);
    }
}
