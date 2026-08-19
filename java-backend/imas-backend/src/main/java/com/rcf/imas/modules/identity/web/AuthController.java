package com.rcf.imas.modules.identity.web;

import com.rcf.imas.modules.identity.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    record LoginRequest(@com.fasterxml.jackson.annotation.JsonProperty("user_name") String userName,
                        @com.fasterxml.jackson.annotation.JsonProperty("password") String password) {}
    record AuthorizeRoleRequest(@com.fasterxml.jackson.annotation.JsonProperty("preAuthToken") String preAuthToken,
                                @com.fasterxml.jackson.annotation.JsonProperty("selectedRole") String selectedRole) {}

    private final AuthService authService;

    AuthController(AuthService authService) { this.authService = authService; }

    @PostMapping("/login")
    Map<String, Object> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        return authService.login(req.userName(), req.password(), clientIp(http));
    }

    @PostMapping("/authorize-role")
    Map<String, Object> authorizeRole(@RequestBody AuthorizeRoleRequest req, HttpServletRequest http) {
        return authService.authorizeRole(req.preAuthToken(), req.selectedRole(), clientIp(http));
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : req.getRemoteAddr();
    }
}
