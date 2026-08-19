package com.rcf.imas.modules.admission.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JurisdictionLookupRepository {

    private final JdbcClient jdbc;

    public JurisdictionLookupRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Port of getJurisdictionIdByName: clean = trim, strip trailing . or ,, upper-case.
     * Primary: juris_name ILIKE :name AND juris_type = :type [AND parent_juris = :parent].
     * Fallback: UPPER(juris_name) = :name (no type/parent). Returns juris_code as String, else empty.
     *
     * Convention 1: parent_juris is numeric(12,0); the parent param is a String, so cast the param
     * (:parent::numeric) to avoid "operator does not exist: numeric = varchar".
     */
    public Optional<String> findCodeByName(String jurisName, String jurisType, String parentCode) {
        if (jurisName == null) return Optional.empty();
        String clean = jurisName.trim().replaceAll("[.,]+$", "").toUpperCase();

        Optional<String> primary;
        if (parentCode != null) {
            primary = jdbc.sql("""
                    SELECT juris_code FROM pp.jurisdiction
                    WHERE juris_name ILIKE :name AND juris_type = :type AND parent_juris = :parent::numeric
                    """)
                    .param("name", clean).param("type", jurisType).param("parent", parentCode)
                    .query((rs, i) -> rs.getBigDecimal("juris_code").toBigInteger().toString()).optional();
        } else {
            primary = jdbc.sql("""
                    SELECT juris_code FROM pp.jurisdiction
                    WHERE juris_name ILIKE :name AND juris_type = :type
                    """)
                    .param("name", clean).param("type", jurisType)
                    .query((rs, i) -> rs.getBigDecimal("juris_code").toBigInteger().toString()).optional();
        }
        if (primary.isPresent()) return primary;

        return jdbc.sql("SELECT juris_code FROM pp.jurisdiction WHERE UPPER(juris_name) = :name")
                .param("name", clean)
                .query((rs, i) -> rs.getBigDecimal("juris_code").toBigInteger().toString()).optional();
    }
}
