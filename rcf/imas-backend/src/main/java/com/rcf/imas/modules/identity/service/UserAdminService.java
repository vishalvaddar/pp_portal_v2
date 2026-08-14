package com.rcf.imas.modules.identity.service;

import com.rcf.imas.platform.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UserAdminService {

    private final JdbcClient jdbc;
    private final BCryptPasswordEncoder bcrypt;

    public UserAdminService(JdbcClient jdbc, BCryptPasswordEncoder bcrypt) {
        this.jdbc = jdbc; this.bcrypt = bcrypt;
    }

    public List<Map<String, Object>> listUsersWithRoles() {
        return jdbc.sql("""
                SELECT u.user_id AS id, u.user_name AS username, u.locked_yn AS status,
                       ARRAY_AGG(r.role_name) FILTER (WHERE r.role_name IS NOT NULL) AS roles
                FROM pp.user u
                LEFT JOIN pp.user_role ur ON u.user_id = ur.user_id
                LEFT JOIN pp.role r ON ur.role_id = r.role_id
                GROUP BY u.user_id
                ORDER BY u.user_name ASC
                """)
                .query((rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getBigDecimal("id").toBigInteger().toString());
                    row.put("username", rs.getString("username"));
                    row.put("status", rs.getString("status"));
                    java.sql.Array arr = rs.getArray("roles");
                    row.put("roles", arr == null ? null : List.of((String[]) arr.getArray()));
                    return row;
                }).list();
    }

    public List<Map<String, Object>> listRoles() {
        return jdbc.sql("SELECT role_id AS id, role_name, active_yn AS status FROM pp.role ORDER BY role_name ASC")
                .query((rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getBigDecimal("id").toBigInteger().toString());
                    row.put("role_name", rs.getString("role_name"));
                    row.put("status", rs.getString("status"));
                    return row;
                }).list();
    }

    @Transactional
    public String createUserWithRoles(String username, String password, List<String> roles) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw ApiException.message(400, "Username and password are required.");
        }
        Integer dup = jdbc.sql("SELECT count(*) FROM pp.user WHERE user_name = :u")
                .param("u", username).query(Integer.class).single();
        if (dup > 0) throw ApiException.message(409, "Username already exists.");

        String userId = jdbc.sql("""
                INSERT INTO pp.user (user_name, enc_password, locked_yn)
                VALUES (:u, :p, 'N') RETURNING user_id
                """)
                .param("u", username).param("p", bcrypt.encode(password))
                .query(java.math.BigDecimal.class).single().toBigInteger().toString();

        syncRoles(userId, roles);
        return userId;
    }

    @Transactional
    public void updateUserWithRoles(String userId, String username, String password, List<String> roles) {
        if (username == null || username.isBlank()) {
            throw ApiException.message(400, "Username is required.");
        }
        Integer dup = jdbc.sql("SELECT count(*) FROM pp.user WHERE user_name = :u AND user_id != :id::numeric")
                .param("u", username).param("id", userId).query(Integer.class).single();
        if (dup > 0) throw ApiException.message(409, "Username already taken by another user.");

        String hash = (password == null || password.isBlank()) ? null : bcrypt.encode(password);
        // explicit VARCHAR type is required so a null hash binds as SQL NULL, not an inferred type
        jdbc.sql("UPDATE pp.user SET user_name = :u, enc_password = COALESCE(:p, enc_password) WHERE user_id = :id::numeric")
            .param("u", username)
            .param("p", hash, java.sql.Types.VARCHAR)
            .param("id", userId)
            .update();

        jdbc.sql("DELETE FROM pp.user_role WHERE user_id = :id::numeric").param("id", userId).update();
        syncRoles(userId, roles);
        if (roles == null || roles.stream().noneMatch(r -> r.trim().equalsIgnoreCase("TEACHER"))) {
            jdbc.sql("DELETE FROM pp.teacher WHERE user_id = :id::numeric").param("id", userId).update();
        }
    }

    /** Shared by create/update: resolve active roles, insert user_role, sync pp.teacher. */
    private void syncRoles(String userId, List<String> roles) {
        if (roles == null || roles.isEmpty()) return;
        Set<String> unique = new LinkedHashSet<>(roles.stream().map(r -> r.trim().toUpperCase()).toList());
        boolean isTeacher = false;
        for (String roleName : unique) {
            var roleId = jdbc.sql("SELECT role_id FROM pp.role WHERE role_name = :r AND active_yn = 'Y'")
                    .param("r", roleName).query(java.math.BigDecimal.class).optional();
            if (roleId.isPresent()) {
                jdbc.sql("""
                        INSERT INTO pp.user_role (user_id, role_id) VALUES (:u::numeric, :r)
                        ON CONFLICT DO NOTHING""")
                    .param("u", userId).param("r", roleId.get()).update();
                if ("TEACHER".equals(roleName)) isTeacher = true;
            }
        }
        if (isTeacher) {
            jdbc.sql("INSERT INTO pp.teacher (user_id) VALUES (:u::numeric) ON CONFLICT (user_id) DO NOTHING")
                .param("u", userId).update();
        }
    }

    @Transactional
    public void deleteUser(String userId) {
        jdbc.sql("DELETE FROM pp.user_role WHERE user_id = :id::numeric").param("id", userId).update();
        int n = jdbc.sql("DELETE FROM pp.user WHERE user_id = :id::numeric").param("id", userId).update();
        if (n == 0) throw ApiException.message(404, "User not found.");
    }

    public Map<String, Object> toggleUserStatus(String userId, String status) {
        if (!"Y".equals(status) && !"N".equals(status)) {
            throw ApiException.message(400, "Invalid status. Must be 'Y' or 'N'.");
        }
        // Node returned RETURNING * (including enc_password); we omit the password hash —
        // the client only reads the message and reloads the list, so this is safe and avoids
        // sending a bcrypt hash over the wire.
        var row = jdbc.sql("""
                UPDATE pp.user SET locked_yn = :s WHERE user_id = :id::numeric
                RETURNING user_id, user_name, locked_yn""")
                .param("s", status).param("id", userId)
                .query((rs, i) -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("user_id", rs.getBigDecimal("user_id").toBigInteger().toString());
                    u.put("user_name", rs.getString("user_name"));
                    u.put("locked_yn", rs.getString("locked_yn"));
                    return u;
                }).optional();
        return row.orElseThrow(() -> ApiException.message(404, "User not found."));
    }

    @Transactional
    public Map<String, Object> createRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            throw ApiException.message(400, "Role name is required.");
        }
        String formatted = roleName.trim().toUpperCase();
        Integer dup = jdbc.sql("SELECT count(*) FROM pp.role WHERE role_name = :r")
                .param("r", formatted).query(Integer.class).single();
        if (dup > 0) throw ApiException.message(409, "Role name already exists.");
        return jdbc.sql("INSERT INTO pp.role (role_name, active_yn) VALUES (:r, 'Y') RETURNING role_id, role_name, active_yn")
                .param("r", formatted)
                .query((rs, i) -> {
                    Map<String, Object> role = new LinkedHashMap<>();
                    role.put("role_id", rs.getBigDecimal("role_id").toBigInteger().toString());
                    role.put("role_name", rs.getString("role_name"));
                    role.put("active_yn", rs.getString("active_yn"));
                    return role;
                }).single();
    }

    @Transactional
    public void deleteRole(String roleId) {
        Integer inUse = jdbc.sql("SELECT count(*) FROM pp.user_role WHERE role_id = :id::numeric")
                .param("id", roleId).query(Integer.class).single();
        if (inUse > 0) throw ApiException.message(400, "Cannot delete role: It is currently assigned to one or more users.");
        int n = jdbc.sql("DELETE FROM pp.role WHERE role_id = :id::numeric").param("id", roleId).update();
        if (n == 0) throw ApiException.message(404, "Role not found.");
    }

    public Map<String, Object> toggleRoleStatus(String roleId, String status) {
        if (!"Y".equals(status) && !"N".equals(status)) {
            throw ApiException.message(400, "Invalid status. Must be 'Y' or 'N'.");
        }
        var row = jdbc.sql("""
                UPDATE pp.role SET active_yn = :s WHERE role_id = :id::numeric
                RETURNING role_id, role_name, active_yn""")
                .param("s", status).param("id", roleId)
                .query((rs, i) -> {
                    Map<String, Object> role = new LinkedHashMap<>();
                    role.put("role_id", rs.getBigDecimal("role_id").toBigInteger().toString());
                    role.put("role_name", rs.getString("role_name"));
                    role.put("active_yn", rs.getString("active_yn"));
                    return role;
                }).optional();
        return row.orElseThrow(() -> ApiException.message(404, "Role not found."));
    }

    @Transactional
    public void assignRole(String userId, String roleId) {
        Integer userExists = jdbc.sql("SELECT count(*) FROM pp.user WHERE user_id = :id::numeric")
                .param("id", userId).query(Integer.class).single();
        if (userExists == 0) throw ApiException.message(404, "User not found.");
        var roleName = jdbc.sql("SELECT role_name FROM pp.role WHERE role_id = :id::numeric")
                .param("id", roleId).query(String.class).optional();
        if (roleName.isEmpty()) throw ApiException.message(404, "Role not found.");

        jdbc.sql("""
                INSERT INTO pp.user_role (user_id, role_id) VALUES (:u::numeric, :r::numeric)
                ON CONFLICT (user_id, role_id) DO NOTHING""")
            .param("u", userId).param("r", roleId).update();
        if ("TEACHER".equals(roleName.get().trim().toUpperCase())) {
            jdbc.sql("INSERT INTO pp.teacher (user_id) VALUES (:u::numeric) ON CONFLICT (user_id) DO NOTHING")
                .param("u", userId).update();
        }
    }

    @Transactional
    public void removeRole(String userId, String roleId) {
        var roleName = jdbc.sql("SELECT role_name FROM pp.role WHERE role_id = :id::numeric")
                .param("id", roleId).query(String.class).optional();
        int n = jdbc.sql("DELETE FROM pp.user_role WHERE user_id = :u::numeric AND role_id = :r::numeric")
                .param("u", userId).param("r", roleId).update();
        if (n == 0) throw ApiException.message(404, "User-role assignment not found.");
        if (roleName.isPresent() && "TEACHER".equals(roleName.get().trim().toUpperCase())) {
            jdbc.sql("DELETE FROM pp.teacher WHERE user_id = :u::numeric").param("u", userId).update();
        }
    }

    @Transactional
    public void updateUsername(String userId, String username) {
        if (username == null || username.isBlank()) {
            throw ApiException.message(400, "Username is required.");
        }
        Integer dup = jdbc.sql("SELECT count(*) FROM pp.user WHERE user_name = :u AND user_id != :id::numeric")
                .param("u", username.trim()).param("id", userId).query(Integer.class).single();
        if (dup > 0) throw ApiException.message(409, "Username already taken.");
        int n = jdbc.sql("UPDATE pp.user SET user_name = :u WHERE user_id = :id::numeric")
                .param("u", username.trim()).param("id", userId).update();
        if (n == 0) throw ApiException.message(404, "User not found.");
    }

    // Not @Transactional: a verify-current-then-update sequence, matching Node's non-atomic flow.
    public void updatePassword(String userId, String currentPassword, String newPassword) {
        if (currentPassword == null || currentPassword.isBlank()
                || newPassword == null || newPassword.isBlank()) {
            throw ApiException.message(400, "Current and new passwords are required.");
        }
        var hash = jdbc.sql("SELECT enc_password FROM pp.user WHERE user_id = :id::numeric")
                .param("id", userId).query(String.class).optional();
        if (hash.isEmpty()) throw ApiException.message(404, "User not found.");
        if (!bcrypt.matches(currentPassword, hash.get())) {
            throw ApiException.message(401, "Current password is not correct.");
        }
        jdbc.sql("UPDATE pp.user SET enc_password = :p WHERE user_id = :id::numeric")
            .param("p", bcrypt.encode(newPassword)).param("id", userId).update();
    }
}
