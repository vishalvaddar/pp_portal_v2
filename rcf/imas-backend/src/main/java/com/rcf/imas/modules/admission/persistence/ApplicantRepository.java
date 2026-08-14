package com.rcf.imas.modules.admission.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ApplicantRepository {

    // node-pg timestamp serialization parity (UTC ISO with millis + Z), same as Phase-1 SystemConfigRepository
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public ApplicantRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---- generic row → ordered map, mirroring node-pg's typed JSON output ----
    // numeric → String (parity), date → java.sql.Date (ApplicantFormatter turns dob into YYYY-MM-DD),
    // timestamp → ISO-Z string, varchar/other → native.
    static Map<String, Object> genericRow(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String name = md.getColumnLabel(i);
            int type = md.getColumnType(i);
            Object val;
            switch (type) {
                case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> {
                    BigDecimal bd = rs.getBigDecimal(i);
                    val = bd == null ? null : bd.toBigInteger().toString();
                }
                case java.sql.Types.DATE -> {
                    // keep as java.sql.Date except for a column literally named dob → let formatter handle;
                    // for other date columns emit YYYY-MM-DD directly for parity.
                    java.sql.Date d = rs.getDate(i);
                    val = "dob".equals(name) ? d : (d == null ? null : d.toLocalDate().toString());
                }
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i);
                    val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    // ---- list summary (order + columns exactly as Node getAllApplicants) ----
    public List<Map<String, Object>> listSummary() {
        return jdbc.sql("""
                SELECT p.applicant_id, p.nmms_year, p.nmms_reg_number, p.student_name, p.father_name, p.gender,
                       dist.juris_name AS district_name, p.contact_no1, p.created_at
                FROM pp.applicant_primary_info p
                LEFT JOIN pp.jurisdiction dist ON p.district = dist.juris_code
                ORDER BY p.created_at DESC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    public Optional<Map<String, Object>> findById(String applicantId) {
        return jdbc.sql("""
                SELECT p.*, s.* FROM pp.applicant_primary_info p
                LEFT JOIN pp.applicant_secondary_info s ON p.applicant_id = s.applicant_id
                WHERE p.applicant_id = :id::numeric
                """).param("id", applicantId).query((rs, i) -> genericRow(rs)).optional();
    }

    public Optional<Map<String, Object>> findByRegNumber(String reg) {
        return jdbc.sql("""
                SELECT
                  p.*, s.*,
                  sm.photo_link, sm.enr_id, sm.active_yn,
                  state.juris_name AS state_name,
                  dist.juris_name AS district_name,
                  blk.juris_name AS block_name,
                  c.cohort_name, b.batch_name
                FROM pp.applicant_primary_info p
                LEFT JOIN pp.applicant_secondary_info s ON p.applicant_id = s.applicant_id
                LEFT JOIN pp.jurisdiction state ON p.app_state = state.juris_code
                LEFT JOIN pp.jurisdiction dist  ON p.district  = dist.juris_code
                LEFT JOIN pp.jurisdiction blk   ON p.nmms_block = blk.juris_code
                LEFT JOIN pp.student_master sm ON p.applicant_id = sm.applicant_id
                LEFT JOIN pp.batch b  ON sm.batch_id = b.batch_id
                LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                WHERE p.nmms_reg_number = :reg::numeric
                """).param("reg", reg).query((rs, i) -> genericRow(rs)).optional();
    }

    // ---- counts ----
    public long countByYear(String year) {
        return jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_year = :y::numeric")
                .param("y", year).query(Long.class).single();
    }

    public long shortlistedCount(String year) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM pp.applicant_primary_info ap
                JOIN pp.applicant_shortlist_info asi ON ap.applicant_id = asi.applicant_id
                WHERE ap.nmms_year = :y::numeric
                """).param("y", year).query(Long.class).single();
    }

    public long selectedCount(String year) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM pp.applicant_primary_info ap
                JOIN pp.student_master sm ON ap.applicant_id = sm.applicant_id
                WHERE ap.nmms_year = :y::numeric
                """).param("y", year).query(Long.class).single();
    }

    public Map<String, Object> cohortCounts(int currentYear, int previousYear) {
        return jdbc.sql("""
                SELECT
                  COUNT(CASE WHEN ap.nmms_year = :cur THEN 1 END)::INT AS current_count,
                  COUNT(CASE WHEN ap.nmms_year = :prev THEN 1 END)::INT AS previous_count
                FROM pp.student_master sm
                JOIN pp.applicant_primary_info ap ON sm.applicant_id = ap.applicant_id
                WHERE ap.nmms_year IN (:cur, :prev)
                """).param("cur", currentYear).param("prev", previousYear)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("current_count", rs.getInt("current_count"));
                    m.put("previous_count", rs.getInt("previous_count"));
                    return m;
                }).single();
    }

    /** Insert primary (RETURNING applicant_id) + secondary in ONE transaction. Returns applicant_id as String. */
    @org.springframework.transaction.annotation.Transactional
    public String insertApplicant(Map<String, Object> primary, Map<String, Object> secondary, String userId) {
        String applicantId = jdbc.sql("""
                INSERT INTO pp.applicant_primary_info (
                  nmms_year, nmms_reg_number, app_state, district, nmms_block,
                  student_name, father_name, mother_name, gmat_score, sat_score,
                  gender, medium, aadhaar, dob, home_address, family_income_total,
                  contact_no1, contact_no2, current_institute_dise_code, previous_institute_dise_code,
                  created_by, updated_by
                ) VALUES (
                  :nmms_year, :nmms_reg_number, :app_state, :district, :nmms_block,
                  :student_name, :father_name, :mother_name, :gmat_score, :sat_score,
                  :gender, :medium, :aadhaar, CAST(:dob AS DATE), :home_address, :family_income_total,
                  :contact_no1, :contact_no2, :current_institute_dise_code, :previous_institute_dise_code,
                  :uid::numeric, :uid::numeric
                )
                RETURNING applicant_id
                """)
                .param("nmms_year", primary.get("nmms_year"))
                .param("nmms_reg_number", primary.get("nmms_reg_number"))
                .param("app_state", primary.get("app_state"))
                .param("district", primary.get("district"))
                .param("nmms_block", primary.get("nmms_block"))
                .param("student_name", primary.get("student_name"))
                .param("father_name", primary.get("father_name"))
                .param("mother_name", primary.get("mother_name"))
                .param("gmat_score", primary.get("gmat_score"))
                .param("sat_score", primary.get("sat_score"))
                .param("gender", primary.get("gender"))
                .param("medium", primary.get("medium"))
                .param("aadhaar", primary.get("aadhaar"))
                .param("dob", primary.get("dob"))
                .param("home_address", primary.get("home_address"))
                .param("family_income_total", primary.get("family_income_total"))
                .param("contact_no1", primary.get("contact_no1"))
                .param("contact_no2", primary.get("contact_no2"))
                .param("current_institute_dise_code", primary.get("current_institute_dise_code"))
                .param("previous_institute_dise_code", primary.get("previous_institute_dise_code"))
                .param("uid", userId)
                .query((rs, i) -> rs.getBigDecimal("applicant_id").toBigInteger().toString())
                .single();

        jdbc.sql("""
                INSERT INTO pp.applicant_secondary_info (
                  applicant_id, village, father_occupation, mother_occupation,
                  father_education, mother_education, household_size, own_house,
                  smart_phone_home, internet_facility_home, career_goals, subjects_of_interest,
                  transportation_mode, distance_to_school, num_two_wheelers, num_four_wheelers,
                  irrigation_land, neighbor_name, neighbor_phone, favorite_teacher_name, favorite_teacher_phone,
                  created_by, updated_by
                ) VALUES (
                  :applicant_id::numeric, :village, :father_occupation, :mother_occupation,
                  :father_education, :mother_education, :household_size, :own_house,
                  :smart_phone_home, :internet_facility_home, :career_goals, :subjects_of_interest,
                  :transportation_mode, :distance_to_school, :num_two_wheelers, :num_four_wheelers,
                  :irrigation_land, :neighbor_name, :neighbor_phone, :favorite_teacher_name, :favorite_teacher_phone,
                  :uid::numeric, :uid::numeric
                )
                """)
                .param("applicant_id", applicantId)
                .param("village", secondary.get("village"))
                .param("father_occupation", secondary.get("father_occupation"))
                .param("mother_occupation", secondary.get("mother_occupation"))
                .param("father_education", secondary.get("father_education"))
                .param("mother_education", secondary.get("mother_education"))
                .param("household_size", secondary.get("household_size"))
                .param("own_house", secondary.get("own_house"))
                .param("smart_phone_home", secondary.get("smart_phone_home"))
                .param("internet_facility_home", secondary.get("internet_facility_home"))
                .param("career_goals", secondary.get("career_goals"))
                .param("subjects_of_interest", secondary.get("subjects_of_interest"))
                .param("transportation_mode", secondary.get("transportation_mode"))
                .param("distance_to_school", secondary.get("distance_to_school"))
                .param("num_two_wheelers", secondary.getOrDefault("num_two_wheelers", 0))
                .param("num_four_wheelers", secondary.getOrDefault("num_four_wheelers", 0))
                .param("irrigation_land", secondary.getOrDefault("irrigation_land", 0))
                .param("neighbor_name", secondary.get("neighbor_name"))
                .param("neighbor_phone", secondary.get("neighbor_phone"))
                .param("favorite_teacher_name", secondary.get("favorite_teacher_name"))
                .param("favorite_teacher_phone", secondary.get("favorite_teacher_phone"))
                .param("uid", userId)
                .update();

        return applicantId;
    }

    /** Update primary (never nmms_reg_number, sets updated_at) + UPSERT secondary in ONE transaction. No 404 (0-row is silent). */
    @org.springframework.transaction.annotation.Transactional
    public void updateApplicant(String applicantId, Map<String, Object> primary, Map<String, Object> secondary) {
        if (primary != null) {
            jdbc.sql("""
                    UPDATE pp.applicant_primary_info SET
                      nmms_year = :nmms_year, app_state = :app_state, district = :district, nmms_block = :nmms_block,
                      student_name = :student_name, father_name = :father_name, mother_name = :mother_name,
                      gmat_score = :gmat_score, sat_score = :sat_score, gender = :gender,
                      medium = :medium, aadhaar = :aadhaar, dob = CAST(:dob AS DATE),
                      home_address = :home_address, family_income_total = :family_income_total,
                      contact_no1 = :contact_no1, contact_no2 = :contact_no2,
                      current_institute_dise_code = :current_institute_dise_code,
                      previous_institute_dise_code = :previous_institute_dise_code,
                      updated_at = CURRENT_TIMESTAMP
                    WHERE applicant_id = :id::numeric
                    """)
                    .param("nmms_year", primary.get("nmms_year"))
                    .param("app_state", primary.get("app_state"))
                    .param("district", primary.get("district"))
                    .param("nmms_block", primary.get("nmms_block"))
                    .param("student_name", primary.get("student_name"))
                    .param("father_name", primary.get("father_name"))
                    .param("mother_name", primary.get("mother_name"))
                    .param("gmat_score", primary.get("gmat_score"))
                    .param("sat_score", primary.get("sat_score"))
                    .param("gender", primary.get("gender"))
                    .param("medium", primary.get("medium"))
                    .param("aadhaar", primary.get("aadhaar"))
                    .param("dob", primary.get("dob"))
                    .param("home_address", primary.get("home_address"))
                    .param("family_income_total", primary.get("family_income_total"))
                    .param("contact_no1", primary.get("contact_no1"))
                    .param("contact_no2", primary.get("contact_no2"))
                    .param("current_institute_dise_code", primary.get("current_institute_dise_code"))
                    .param("previous_institute_dise_code", primary.get("previous_institute_dise_code"))
                    .param("id", applicantId)
                    .update();
        }

        if (secondary != null) {
            jdbc.sql("""
                    INSERT INTO pp.applicant_secondary_info (
                      village, father_occupation, mother_occupation, father_education, mother_education,
                      household_size, own_house, smart_phone_home, internet_facility_home,
                      career_goals, subjects_of_interest, transportation_mode, distance_to_school,
                      num_two_wheelers, num_four_wheelers, irrigation_land,
                      neighbor_name, neighbor_phone, favorite_teacher_name, favorite_teacher_phone,
                      applicant_id, updated_at
                    ) VALUES (
                      :village, :father_occupation, :mother_occupation, :father_education, :mother_education,
                      :household_size, :own_house, :smart_phone_home, :internet_facility_home,
                      :career_goals, :subjects_of_interest, :transportation_mode, :distance_to_school,
                      :num_two_wheelers, :num_four_wheelers, :irrigation_land,
                      :neighbor_name, :neighbor_phone, :favorite_teacher_name, :favorite_teacher_phone,
                      :id::numeric, CURRENT_TIMESTAMP
                    )
                    ON CONFLICT (applicant_id) DO UPDATE SET
                      village = EXCLUDED.village, father_occupation = EXCLUDED.father_occupation,
                      mother_occupation = EXCLUDED.mother_occupation, father_education = EXCLUDED.father_education,
                      mother_education = EXCLUDED.mother_education, household_size = EXCLUDED.household_size,
                      own_house = EXCLUDED.own_house, smart_phone_home = EXCLUDED.smart_phone_home,
                      internet_facility_home = EXCLUDED.internet_facility_home, career_goals = EXCLUDED.career_goals,
                      subjects_of_interest = EXCLUDED.subjects_of_interest, transportation_mode = EXCLUDED.transportation_mode,
                      distance_to_school = EXCLUDED.distance_to_school, num_two_wheelers = EXCLUDED.num_two_wheelers,
                      num_four_wheelers = EXCLUDED.num_four_wheelers, irrigation_land = EXCLUDED.irrigation_land,
                      neighbor_name = EXCLUDED.neighbor_name, neighbor_phone = EXCLUDED.neighbor_phone,
                      favorite_teacher_name = EXCLUDED.favorite_teacher_name, favorite_teacher_phone = EXCLUDED.favorite_teacher_phone,
                      updated_at = CURRENT_TIMESTAMP
                    """)
                    .param("village", secondary.get("village"))
                    .param("father_occupation", secondary.get("father_occupation"))
                    .param("mother_occupation", secondary.get("mother_occupation"))
                    .param("father_education", secondary.get("father_education"))
                    .param("mother_education", secondary.get("mother_education"))
                    .param("household_size", secondary.get("household_size"))
                    .param("own_house", secondary.get("own_house"))
                    .param("smart_phone_home", secondary.get("smart_phone_home"))
                    .param("internet_facility_home", secondary.get("internet_facility_home"))
                    .param("career_goals", secondary.get("career_goals"))
                    .param("subjects_of_interest", secondary.get("subjects_of_interest"))
                    .param("transportation_mode", secondary.get("transportation_mode"))
                    .param("distance_to_school", secondary.get("distance_to_school"))
                    .param("num_two_wheelers", secondary.getOrDefault("num_two_wheelers", 0))
                    .param("num_four_wheelers", secondary.getOrDefault("num_four_wheelers", 0))
                    .param("irrigation_land", secondary.getOrDefault("irrigation_land", 0))
                    .param("neighbor_name", secondary.get("neighbor_name"))
                    .param("neighbor_phone", secondary.get("neighbor_phone"))
                    .param("favorite_teacher_name", secondary.get("favorite_teacher_name"))
                    .param("favorite_teacher_phone", secondary.get("favorite_teacher_phone"))
                    .param("id", applicantId)
                    .update();
        }
    }

    /** DELETE ... RETURNING applicant_id. Empty optional = not found. */
    public Optional<String> deleteById(String applicantId) {
        return jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = :id::numeric RETURNING applicant_id")
                .param("id", applicantId)
                .query((rs, i) -> rs.getBigDecimal("applicant_id").toBigInteger().toString())
                .optional();
    }

    public List<Map<String, Object>> todayClasses(int cohortNumber) {
        return jdbc.sql("""
                SELECT c.cohort_name, COUNT(DISTINCT t.timetable_id) AS classes_count
                FROM pp.timetable t
                JOIN pp.classroom cl ON t.classroom_id = cl.classroom_id
                JOIN pp.classroom_batch cb ON cl.classroom_id = cb.classroom_id
                JOIN pp.batch b ON cb.batch_id = b.batch_id
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                WHERE t.day_of_week = TRIM(UPPER(TO_CHAR(CURRENT_DATE, 'Day')))
                  AND b.cohort_number = :cohort
                GROUP BY c.cohort_name
                ORDER BY c.cohort_name
                """).param("cohort", cohortNumber)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cohort_name", rs.getString("cohort_name"));
                    m.put("classes_count", rs.getLong("classes_count"));
                    return m;
                }).list();
    }
}
