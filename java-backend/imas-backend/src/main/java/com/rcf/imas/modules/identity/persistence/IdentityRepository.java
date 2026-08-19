package com.rcf.imas.modules.identity.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class IdentityRepository {

    /** Row of the login join — one per (user, active role). Mirrors loginController.js SQL. */
    public record UserRoleRow(String userId, String userName, String encPassword, String lockedYn, String roleName) {}

    private final JdbcClient jdbc;

    public IdentityRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<UserRoleRow> findUserWithActiveRoles(String userName) {
        return jdbc.sql("""
                SELECT u.user_id, u.user_name, u.enc_password, u.locked_yn, r.role_name
                FROM pp.user u
                JOIN pp.user_role ur ON u.user_id = ur.user_id
                JOIN pp.role r ON ur.role_id = r.role_id
                WHERE LOWER(u.user_name) = LOWER(:userName) AND r.active_yn = 'Y'
                """)
                .param("userName", userName)
                .query((rs, i) -> new UserRoleRow(
                        rs.getBigDecimal("user_id").toBigInteger().toString(),
                        rs.getString("user_name"),
                        rs.getString("enc_password"),
                        rs.getString("locked_yn"),
                        rs.getString("role_name")))
                .list();
    }
}
