package com.rcf.imas.modules.identity.service;

import com.rcf.imas.modules.identity.persistence.IdentityRepository;
import com.rcf.imas.modules.identity.persistence.IdentityRepository.UserRoleRow;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {

    private final IdentityRepository repo;
    private final BCryptPasswordEncoder bcrypt;
    private final JwtService jwt;
    private final LoginAuditLogger audit;

    public AuthService(IdentityRepository repo, BCryptPasswordEncoder bcrypt,
                       JwtService jwt, LoginAuditLogger audit) {
        this.repo = repo; this.bcrypt = bcrypt; this.jwt = jwt; this.audit = audit;
    }

    /** Port of loginController.js — response keys and status codes are contract. */
    public Map<String, Object> login(String userName, String password, String clientIp) {
        if (userName == null || userName.isBlank() || password == null || password.isBlank()) {
            throw ApiException.error(400, "Username and password are required");
        }
        List<UserRoleRow> rows = repo.findUserWithActiveRoles(userName);
        if (rows.isEmpty()) { // anti-enumeration: same message as bad password
            audit.log(userName, "failed", "user_not_found", clientIp);
            throw ApiException.error(401, "Invalid credentials");
        }
        UserRoleRow user = rows.get(0);
        if ("Y".equals(user.lockedYn())) {
            throw ApiException.error(403, "Account is locked. Contact support.");
        }

        System.out.println("TEST HASH = " + bcrypt.encode("root@123"));
        System.out.println("========== BCRYPT DEBUG ==========");
        System.out.println("Username: " + userName);
        System.out.println("Password received: [" + password + "]");
        System.out.println("Stored hash: [" + user.encPassword() + "]");
        System.out.println("Hash length: " + user.encPassword().length());
        System.out.println("BCrypt match: " + bcrypt.matches(password, user.encPassword()));
        System.out.println("==================================");

        if (!bcrypt.matches(password, user.encPassword())) {
            audit.log(userName, "failed", "bad_password", clientIp);
            throw ApiException.error(401, "Invalid credentials");
        }
        List<String> roles = rows.stream().map(UserRoleRow::roleName).toList();
        String preAuthToken = jwt.issuePreAuthToken(user.userId(), user.userName(), roles);
        audit.log(userName, "success_pre_auth", null, clientIp);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Credentials verified");
        body.put("user_name", user.userName());
        body.put("roles", roles);
        body.put("preAuthToken", preAuthToken);
        return body;
    }

    /** Port of authorizeRoleController.js */
    public Map<String, Object> authorizeRole(String preAuthToken, String selectedRole, String clientIp) {
        if (preAuthToken == null || preAuthToken.isBlank() || selectedRole == null || selectedRole.isBlank()) {
            throw ApiException.error(400, "Missing session token or role selection");
        }
        JwtService.PreAuthToken decoded;
        try {
            decoded = jwt.parsePreAuthToken(preAuthToken);
        } catch (JwtService.ExpiredTokenException e) {
            throw ApiException.error(401, "Session expired. Please login again.")
                    .with("code", "PRE_AUTH_TOKEN_EXPIRED");
        } catch (JwtService.InvalidTokenException e) {
            throw ApiException.error(401, "Invalid session. Please login again.")
                    .with("code", "PRE_AUTH_TOKEN_INVALID");
        }
        boolean allowed = decoded.allowedRoles().stream()
                .anyMatch(r -> r.equalsIgnoreCase(selectedRole));
        if (!allowed) {
            audit.log(decoded.userName(), "failed_unauthorized_role", "role_auth", clientIp);
            throw ApiException.error(403, "You are not authorized for this role");
        }
        String token = jwt.issueFinalToken(decoded.userId(), decoded.userName(), selectedRole);
        audit.log(decoded.userName(), "success", "login_complete", clientIp);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("user_id", decoded.userId());
        user.put("user_name", decoded.userName());
        user.put("role_name", selectedRole);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Login complete");
        body.put("token", token);
        body.put("user", user);
        return body;
    }
}
