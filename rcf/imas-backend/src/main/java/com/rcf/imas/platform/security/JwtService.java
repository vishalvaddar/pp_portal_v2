package com.rcf.imas.platform.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    public static final String PRE_AUTH_TYPE = "PRE_AUTH_ROLE_SELECT";

    public record FinalToken(String userId, String userName, String roleName) {}
    public record PreAuthToken(String userId, String userName, List<String> allowedRoles) {}

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String m) { super(m); }
    }
    public static class ExpiredTokenException extends RuntimeException {
        public ExpiredTokenException(String m) { super(m); }
    }

    private final Algorithm algorithm;
    private final Duration finalTtl;
    private final Duration preAuthTtl;

    public JwtService(JwtProperties props) {
        if (props.secret() == null || props.secret().isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set");
        }
        this.algorithm = Algorithm.HMAC256(props.secret());
        this.finalTtl = parseDuration(props.expiresIn());
        this.preAuthTtl = parseDuration(props.preAuthExpiresIn());
    }

    public String issueFinalToken(String userId, String userName, String roleName) {
        return JWT.create()
                .withClaim("user_id", userId)
                .withClaim("user_name", userName)
                .withClaim("role_name", roleName)
                .withIssuedAt(new Date())
                .withExpiresAt(Date.from(Instant.now().plus(finalTtl)))
                .sign(algorithm);
    }

    public String issuePreAuthToken(String userId, String userName, List<String> allowedRoles) {
        return JWT.create()
                .withClaim("user_id", userId)
                .withClaim("user_name", userName)
                .withClaim("type", PRE_AUTH_TYPE)
                .withClaim("allowed_roles", allowedRoles)
                .withIssuedAt(new Date())
                .withExpiresAt(Date.from(Instant.now().plus(preAuthTtl)))
                .sign(algorithm);
    }

    public FinalToken parseFinalToken(String token) {
        DecodedJWT jwt = verify(token);
        if (!jwt.getClaim("type").isMissing()) {
            throw new InvalidTokenException("Invalid token type");
        }
        String role = jwt.getClaim("role_name").asString();
        if (role == null) throw new InvalidTokenException("Missing role_name");
        return new FinalToken(claimAsString(jwt, "user_id"),
                jwt.getClaim("user_name").asString(), role);
    }

    public PreAuthToken parsePreAuthToken(String token) {
        DecodedJWT jwt = verify(token);
        if (!PRE_AUTH_TYPE.equals(jwt.getClaim("type").asString())) {
            throw new InvalidTokenException("Invalid token type");
        }
        List<String> roles = jwt.getClaim("allowed_roles").asList(String.class);
        return new PreAuthToken(claimAsString(jwt, "user_id"),
                jwt.getClaim("user_name").asString(),
                roles == null ? List.of() : roles);
    }

    private DecodedJWT verify(String token) {
        try {
            return JWT.require(algorithm).build().verify(token);
        } catch (TokenExpiredException e) {
            throw new ExpiredTokenException(e.getMessage());
        } catch (JWTVerificationException e) {
            throw new InvalidTokenException(e.getMessage());
        }
    }

    private static String claimAsString(DecodedJWT jwt, String name) {
        var c = jwt.getClaim(name);
        String s = c.asString();
        if (s != null) return s;
        Integer i = c.asInt();
        return i == null ? null : String.valueOf(i);
    }

    static Duration parseDuration(String v) {
        String s = v.trim().toLowerCase();
        if (s.matches("\\d+")) return Duration.ofSeconds(Long.parseLong(s));
        long n = Long.parseLong(s.substring(0, s.length() - 1));
        return switch (s.charAt(s.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("Unsupported duration: " + v);
        };
    }
}
