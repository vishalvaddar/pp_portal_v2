package com.rcf.imas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayBaselineIT extends PgIntegrationTest {

    @Autowired JdbcClient jdbc;

    @Test
    void baselineCreatesCoreTables() {
        Integer n = jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='pp'
                AND table_name IN ('user','role','user_role','jurisdiction','institute','system_config','teacher')
                """).query(Integer.class).single();
        assertThat(n).isEqualTo(7);
    }

    @Test
    void systemConfigHasLiveColumnsAndNoUniqueOnAcademicYear() {
        Integer cols = jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='pp' AND table_name='system_config'
                AND column_name IN ('system_config_id','academic_year','phase','is_active','created_at','updated_at')
                """).query(Integer.class).single();
        assertThat(cols).isEqualTo(6);

        Integer uniq = jdbc.sql("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_schema='pp' AND tc.table_name='system_config'
                AND tc.constraint_type='UNIQUE' AND kcu.column_name='academic_year'
                """).query(Integer.class).single();
        assertThat(uniq).isZero();
    }

    @Test
    void diseCodeIsVarcharAndTeacherUserIdUnique() {
        String diseType = jdbc.sql("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema='pp' AND table_name='institute' AND column_name='dise_code'
                """).query(String.class).single();
        assertThat(diseType).isEqualTo("character varying");

        Integer teacherUserUnique = jdbc.sql("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_schema='pp' AND tc.table_name='teacher'
                AND tc.constraint_type='UNIQUE' AND kcu.column_name='user_id'
                """).query(Integer.class).single();
        assertThat(teacherUserUnique).isEqualTo(1);
    }
}
