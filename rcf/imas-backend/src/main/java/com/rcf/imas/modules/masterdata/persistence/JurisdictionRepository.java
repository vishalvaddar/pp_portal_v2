package com.rcf.imas.modules.masterdata.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JurisdictionRepository {

    private final JdbcClient jdbc;

    public JurisdictionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** id/name pair used by every cascade level. numeric ids -> String (node-pg parity). */
    private static Map<String, Object> idName(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getBigDecimal("id").toBigInteger().toString());
        m.put("name", rs.getString("name"));
        return m;
    }

    public List<Map<String, Object>> states() {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'STATE' AND parent_juris IS NULL""")
                .query((rs, i) -> idName(rs)).list();
    }

    public List<Map<String, Object>> childrenOf(String jurisType, String parentId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = :t AND parent_juris = :p::numeric""")
                .param("t", jurisType).param("p", parentId)
                .query((rs, i) -> idName(rs)).list();
    }

    public List<Map<String, Object>> institutesByCluster(String clusterId) {
        return jdbc.sql("""
                SELECT institute_id, institute_name, dise_code FROM pp.institute
                WHERE juris_code = :c::numeric""")
                .param("c", clusterId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("institute_id", rs.getBigDecimal("institute_id").toBigInteger().toString());
                    m.put("institute_name", rs.getString("institute_name"));
                    m.put("dise_code", rs.getString("dise_code"));
                    return m;
                }).list();
    }

    public Optional<Map<String, Object>> jurisName(String jurisCode) {
        return jdbc.sql("SELECT juris_name AS name FROM pp.jurisdiction WHERE juris_code = :c::numeric")
                .param("c", jurisCode)
                .query((rs, i) -> Map.<String, Object>of("name", rs.getString("name")))
                .optional();
    }

    public List<Map<String, Object>> allDistricts() {
        return jdbc.sql("""
                SELECT juris_name AS district, juris_code AS district_code FROM pp.jurisdiction
                WHERE juris_type = 'EDUCATION DISTRICT' ORDER BY juris_name""")
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("district", rs.getString("district"));
                    m.put("district_code", rs.getBigDecimal("district_code").toBigInteger().toString());
                    return m;
                }).list();
    }

    public List<Map<String, Object>> allInstituteNames() {
        return jdbc.sql("SELECT institute_name FROM pp.institute ORDER BY institute_name")
                .query((rs, i) -> Map.<String, Object>of("institute_name", rs.getString("institute_name")))
                .list();
    }

    public List<Map<String, Object>> searchInstitutes(String query) {
        return jdbc.sql("""
                SELECT dise_code, institute_name FROM pp.institute
                WHERE institute_name ILIKE :q LIMIT 10""")
                .param("q", "%" + query + "%")
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("dise_code", rs.getString("dise_code"));
                    m.put("institute_name", rs.getString("institute_name"));
                    return m;
                }).list();
    }

    /** juris-names bulk resolve: {id -> name} maps, keyed exactly like Node's reduce(). */
    public Map<String, String> namesByType(String jurisType, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = :t AND juris_code = ANY(:ids)""")
                .param("t", jurisType)
                .param("ids", ids.toArray(new Long[0]))
                .query((rs, i) -> Map.entry(
                        rs.getBigDecimal("id").toBigInteger().toString(), rs.getString("name")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    public Map<String, String> instituteNamesByDise(List<String> diseCodes) {
        if (diseCodes == null || diseCodes.isEmpty()) return Map.of();
        return jdbc.sql("""
                SELECT dise_code AS id, institute_name AS name FROM pp.institute
                WHERE dise_code = ANY(:ids)""")
                .param("ids", diseCodes.toArray(new String[0]))
                .query((rs, i) -> Map.entry(
                        rs.getString("id"), rs.getString("name")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }
}
