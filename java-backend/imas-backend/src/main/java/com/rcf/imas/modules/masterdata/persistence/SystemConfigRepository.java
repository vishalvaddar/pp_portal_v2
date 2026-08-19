package com.rcf.imas.modules.masterdata.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SystemConfigRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public SystemConfigRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("system_config_id", rs.getLong("system_config_id"));
        m.put("academic_year", rs.getString("academic_year"));
        m.put("phase", rs.getString("phase"));
        m.put("is_active", rs.getBoolean("is_active"));
        Timestamp c = rs.getTimestamp("created_at");
        Timestamp u = rs.getTimestamp("updated_at");
        m.put("created_at", c == null ? null : TS.format(c.toInstant()));
        m.put("updated_at", u == null ? null : TS.format(u.toInstant()));
        return m;
    }

    private static final String COLS = "system_config_id, academic_year, phase, is_active, created_at, updated_at";

    public List<Map<String, Object>> findAll() {
        return jdbc.sql("SELECT " + COLS + " FROM pp.system_config ORDER BY created_at DESC")
                .query((rs, i) -> mapRow(rs)).list();
    }

    public List<Map<String, Object>> findActive() {
        return jdbc.sql("SELECT " + COLS + " FROM pp.system_config WHERE is_active = true ORDER BY academic_year DESC")
                .query((rs, i) -> mapRow(rs)).list();
    }

    public Map<String, Object> insert(String academicYear, String phase, Boolean isActive) {
        return jdbc.sql("""
                INSERT INTO pp.system_config (academic_year, phase, is_active)
                VALUES (:y, :p, COALESCE(:a, false)) RETURNING """ + " " + COLS)
                .param("y", academicYear).param("p", phase)
                .param("a", isActive, java.sql.Types.BOOLEAN)
                .query((rs, i) -> mapRow(rs)).single();
    }

    public Optional<Map<String, Object>> update(long id, String academicYear, String phase, Boolean isActive) {
        return jdbc.sql("""
                UPDATE pp.system_config SET academic_year = :y, phase = :p, is_active = :a
                WHERE system_config_id = :id RETURNING """ + " " + COLS)
                .param("y", academicYear).param("p", phase)
                .param("a", isActive, java.sql.Types.BOOLEAN).param("id", id)
                .query((rs, i) -> mapRow(rs)).optional();
    }

    public Optional<Map<String, Object>> delete(long id) {
        return jdbc.sql("DELETE FROM pp.system_config WHERE system_config_id = :id RETURNING " + COLS)
                .param("id", id).query((rs, i) -> mapRow(rs)).optional();
    }

    public Optional<Map<String, Object>> activate(long id) {
        return jdbc.sql("""
                UPDATE pp.system_config SET is_active = true
                WHERE system_config_id = :id RETURNING """ + " " + COLS)
                .param("id", id).query((rs, i) -> mapRow(rs)).optional();
    }
}
