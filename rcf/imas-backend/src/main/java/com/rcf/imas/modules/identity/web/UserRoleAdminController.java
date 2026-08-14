package com.rcf.imas.modules.identity.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rcf.imas.modules.identity.service.UserAdminService;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
class UserRoleAdminController {

    record UserUpsertRequest(@JsonProperty("username") String username,
                             @JsonProperty("password") String password,
                             @JsonProperty("roles") List<String> roles) {}
    record StatusRequest(@JsonProperty("status") String status) {}
    record RoleCreateRequest(@JsonProperty("roleName") String roleName) {}
    record UsernameRequest(@JsonProperty("username") String username) {}
    record PasswordRequest(@JsonProperty("currentPassword") String currentPassword,
                           @JsonProperty("newPassword") String newPassword) {}

    private final UserAdminService svc;

    UserRoleAdminController(UserAdminService svc) { this.svc = svc; }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users")
    public List<Map<String, Object>> listUsers() { return svc.listUsersWithRoles(); }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody UserUpsertRequest req) {
        String userId = svc.createUserWithRoles(req.username(), req.password(), req.roles());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "User \"" + req.username() + "\" created successfully");
        body.put("userId", userId);
        return ResponseEntity.status(201).body(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/users/{userId}")
    public Map<String, Object> updateUser(@PathVariable String userId, @RequestBody UserUpsertRequest req) {
        svc.updateUserWithRoles(userId, req.username(), req.password(), req.roles());
        return Map.of("message", "User updated successfully");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable String userId) {
        svc.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/users/{userId}/status")
    public Map<String, Object> toggleUserStatus(@PathVariable String userId, @RequestBody StatusRequest req) {
        Map<String, Object> user = svc.toggleUserStatus(userId, req.status());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "User status updated successfully.");
        body.put("user", user);
        return body;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/roles")
    public List<Map<String, Object>> listRoles() { return svc.listRoles(); }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/roles")
    public ResponseEntity<Map<String, Object>> createRole(@RequestBody RoleCreateRequest req) {
        Map<String, Object> role = svc.createRole(req.roleName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Role \"" + role.get("role_name") + "\" created successfully");
        body.put("role", role);
        return ResponseEntity.status(201).body(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/roles/{roleId}")
    public ResponseEntity<Void> deleteRole(@PathVariable String roleId) {
        svc.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/roles/{roleId}/status")
    public Map<String, Object> toggleRoleStatus(@PathVariable String roleId, @RequestBody StatusRequest req) {
        Map<String, Object> role = svc.toggleRoleStatus(roleId, req.status());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Role status updated successfully.");
        body.put("role", role);
        return body;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users/{userId}/roles/{roleId}")
    public Map<String, Object> assignRole(@PathVariable String userId, @PathVariable String roleId) {
        svc.assignRole(userId, roleId);
        return Map.of("message", "Role assigned successfully.");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/users/{userId}/roles/{roleId}")
    public Map<String, Object> removeRole(@PathVariable String userId, @PathVariable String roleId) {
        svc.removeRole(userId, roleId);
        return Map.of("message", "Role removed successfully.");
    }

    // Self-service allowed for one's own id. The client always sends the canonical id string
    // from the login response (e.g. "5"), so string equality with principal.userId() is safe here.
    @PreAuthorize("hasRole('ADMIN') or #userId == principal.userId()")
    @PutMapping("/user/change-username/{userId}")
    public Map<String, Object> changeUsername(@PathVariable String userId, @RequestBody UsernameRequest req,
                                       @AuthenticationPrincipal JwtService.FinalToken principal) {
        svc.updateUsername(userId, req.username());
        return Map.of("message", "Username updated successfully.");
    }

    @PreAuthorize("hasRole('ADMIN') or #userId == principal.userId()")
    @PutMapping("/user/change-password/{userId}")
    public Map<String, Object> changePassword(@PathVariable String userId, @RequestBody PasswordRequest req,
                                       @AuthenticationPrincipal JwtService.FinalToken principal) {
        svc.updatePassword(userId, req.currentPassword(), req.newPassword());
        return Map.of("message", "Password updated successfully.");
    }
}
