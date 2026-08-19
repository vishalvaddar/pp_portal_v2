package com.rcf.imas.modules.shortlist.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
public class ShortlistWriteRepository {

    /** Thrown when a non-frozen batch already covers one of the requested blocks for the year → controller maps to 409. */
    public static class DuplicateShortlistException extends RuntimeException {
        public DuplicateShortlistException(String message) { super(message); }
    }

    private final JdbcClient jdbc;

    public ShortlistWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record BatchResult(String shortlistBatchId, int shortlistedCount) {}

    /**
     * createShortlistBatch parity. thresholdLiteral is one of "0.04"/"0.06"/"0.08" (validated whitelist) — safe to
     * interpolate. blockNamesLower are already lowercased/trimmed.
     */
    @Transactional
    public BatchResult createBatch(String name, String description, String criteriaId,
                                   List<String> blockNamesLower, String state, String district,
                                   String year, String userId, String thresholdLiteral) {
        // 1. duplicate check (non-frozen batch already covering any of these blocks this year)
        Integer dup = jdbc.sql("""
                SELECT 1 FROM pp.shortlist_batch_jurisdiction AS sbj
                JOIN pp.jurisdiction AS block ON sbj.juris_code = block.juris_code
                JOIN pp.shortlist_batch AS sb ON sbj.shortlist_batch_id = sb.shortlist_batch_id
                WHERE LOWER(TRIM(block.juris_name)) = ANY(:blocks) AND sb.shortlisted_year = :year::numeric AND sb.frozen_yn = 'N'
                LIMIT 1
                """).param("blocks", blockNamesLower.toArray(new String[0])).param("year", year)
                .query(Integer.class).optional().orElse(null);
        if (dup != null) {
            throw new DuplicateShortlistException("Shortlists already exist for these blocks in " + year + ". Please delete them first.");
        }

        // 2. insert batch
        String batchId = jdbc.sql("""
                INSERT INTO pp.shortlist_batch (shortlist_batch_name, description, criteria_id, shortlisted_year)
                VALUES (:name, :desc, :crit::numeric, :year::numeric) RETURNING shortlist_batch_id
                """).param("name", name).param("desc", description).param("crit", criteriaId).param("year", year)
                .query((rs, i) -> rs.getBigDecimal("shortlist_batch_id").toBigInteger().toString()).single();

        // 3. link jurisdictions (blocks by name)
        jdbc.sql("""
                INSERT INTO pp.shortlist_batch_jurisdiction (shortlist_batch_id, juris_code)
                SELECT :batch::numeric, juris_code FROM pp.jurisdiction
                WHERE LOWER(TRIM(juris_name)) = ANY(:blocks) AND LOWER(juris_type) = 'block'
                """).param("batch", batchId).param("blocks", blockNamesLower.toArray(new String[0])).update();

        // 4. rank per block, collect applicant ids (block-loop order, weighted_score DESC within block)
        String rankSql = """
                WITH ApplicantRanked AS (
                    SELECT applicant_id, app_state, district, nmms_block AS block,
                           (gmat_score * 0.7 + sat_score * 0.3) AS weighted_score,
                           PERCENT_RANK() OVER (PARTITION BY nmms_block
                               ORDER BY (gmat_score * 0.7 + sat_score * 0.3) DESC, applicant_id ASC) AS percentile_rank
                    FROM pp.applicant_primary_info WHERE nmms_year = :year::numeric)
                SELECT ar.applicant_id FROM ApplicantRanked ar
                JOIN pp.jurisdiction sj ON ar.app_state = sj.juris_code
                JOIN pp.jurisdiction dj ON ar.district = dj.juris_code
                JOIN pp.jurisdiction bj ON ar.block = bj.juris_code
                WHERE LOWER(TRIM(sj.juris_name)) = LOWER(TRIM(:state))
                  AND LOWER(TRIM(dj.juris_name)) = LOWER(TRIM(:district))
                  AND LOWER(TRIM(bj.juris_name)) = LOWER(TRIM(:block))
                  AND ar.percentile_rank <= """ + thresholdLiteral + """

                ORDER BY ar.weighted_score DESC
                """;
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String block : blockNamesLower) {
            ids.addAll(jdbc.sql(rankSql).param("year", year).param("state", state)
                    .param("district", district).param("block", block)
                    .query((rs, i) -> rs.getBigDecimal("applicant_id").toBigInteger().toString()).list());
        }

        // 5. bulk insert results
        for (String id : ids) {
            jdbc.sql("""
                    INSERT INTO pp.applicant_shortlist_info (applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by)
                    VALUES (:aid::numeric, 'Y', :batch::numeric, :uid::numeric, :uid::numeric)
                    """).param("aid", id).param("batch", batchId).param("uid", userId).update();
        }
        return new BatchResult(batchId, ids.size());
    }

    /** autoUpdateSingleMediumStudents(batchId) — NOTE the Node controller passes filterMediums but the model ignores it. */
    @Transactional
    public void autoUpdateSingleMediumStudents(String batchId) {
        // 1. set medium from schools that have exactly one distinct medium, where the student's medium is null/empty
        jdbc.sql("""
                UPDATE pp.applicant_primary_info api
                SET medium = im.single_med, updated_at = CURRENT_TIMESTAMP
                FROM (SELECT dise_code, MAX(medium) AS single_med FROM pp.institute_medium
                      GROUP BY dise_code HAVING COUNT(DISTINCT medium) = 1) im
                WHERE api.current_institute_dise_code = im.dise_code
                  AND api.applicant_id IN (SELECT applicant_id FROM pp.applicant_shortlist_info WHERE shortlist_batch_id = :batch::numeric)
                  AND (api.medium IS NULL OR api.medium = '')
                """).param("batch", batchId).update();

        // 2. auto-reject by management-type rules (hard-coded, NOT driven by filterMediums)
        jdbc.sql("""
                UPDATE pp.applicant_shortlist_info asi SET shortlisted_yn = 'N'
                FROM pp.applicant_primary_info api
                JOIN pp.institute i ON TRIM(CAST(api.current_institute_dise_code AS TEXT)) = TRIM(CAST(i.dise_code AS TEXT))
                WHERE asi.applicant_id = api.applicant_id AND asi.shortlist_batch_id = :batch::numeric
                  AND ( (TRIM(UPPER(api.medium)) = 'ENGLISH' AND TRIM(UPPER(i.management_type)) <> 'GOVERNMENT')
                     OR (TRIM(UPPER(api.medium)) = 'KANNADA' AND TRIM(UPPER(i.management_type)) NOT IN ('GOVERNMENT','PRIVATE AIDED'))
                     OR (TRIM(UPPER(api.medium)) = 'MARATHI' AND TRIM(UPPER(i.management_type)) NOT IN ('GOVERNMENT','PRIVATE AIDED')) )
                """).param("batch", batchId).update();
    }

    /** freezeShortlist(batchId) → true if a row was updated. */
    public boolean freezeShortlist(String batchId) {
        return jdbc.sql("UPDATE pp.shortlist_batch SET frozen_yn = 'Y' WHERE shortlist_batch_id = :id::numeric")
                .param("id", batchId).update() > 0;
    }

    /**
     * bulkUpdateMediumsAndStatus RUNTIME parity: the Node controller drops `allowedMediums`, so the model's Step-2
     * validation never runs. Port only Step 1 (per-student medium + status) and Step 3 (set flags).
     */
    @Transactional
    public void bulkUpdateMediumsAndStatus(List<Map<String, Object>> updates, String batchId) {
        for (Map<String, Object> s : updates) {
            String applicantId = String.valueOf(s.get("applicant_id"));
            jdbc.sql("UPDATE pp.applicant_primary_info SET medium = :med WHERE applicant_id = :aid::numeric")
                .param("med", s.get("selected_medium")).param("aid", applicantId).update();
            jdbc.sql("UPDATE pp.applicant_shortlist_info SET shortlisted_yn = :st WHERE applicant_id = :aid::numeric AND shortlist_batch_id = :batch::numeric")
                .param("st", s.get("status")).param("aid", applicantId).param("batch", batchId).update();
        }
        jdbc.sql("UPDATE pp.shortlist_batch SET frozen_yn = 'Y', medium_filtered_yn = 'Y' WHERE shortlist_batch_id = :batch::numeric")
            .param("batch", batchId).update();
    }

    /** resetMediumFiltering: null medium for the batch's applicants only when the batch is NOT medium_filtered. */
    public boolean resetMediumFiltering(String batchId) {
        return jdbc.sql("""
                UPDATE pp.applicant_primary_info SET medium = NULL
                WHERE applicant_id IN (
                    SELECT asi.applicant_id FROM pp.applicant_shortlist_info asi, pp.shortlist_batch sb
                    WHERE asi.shortlist_batch_id = :id::numeric AND asi.shortlist_batch_id = sb.shortlist_batch_id
                      AND sb.medium_filtered_yn = 'N')
                """).param("id", batchId).update() > 0;
    }

    /** deleteShortlist: three sequential deletes (info → jurisdiction → batch) wrapped transactionally. batch rowCount>0 → true. */
    @Transactional
    public boolean deleteShortlist(String batchId) {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE shortlist_batch_id = :id::numeric").param("id", batchId).update();
        jdbc.sql("DELETE FROM pp.shortlist_batch_jurisdiction WHERE shortlist_batch_id = :id::numeric").param("id", batchId).update();
        return jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_id = :id::numeric").param("id", batchId).update() > 0;
    }
}
