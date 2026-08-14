--
-- PostgreSQL database dump
--

\restrict t42AVZcBh8nizPOzWW5bolnmPVyCr39YzpsqSPZjNUqLb1L6QBXaIPqVNUoqguY

-- Dumped from database version 18.3 (Ubuntu 18.3-1.pgdg24.04+1)
-- Dumped by pg_dump version 18.3 (Ubuntu 18.3-1.pgdg24.04+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pp; Type: SCHEMA; Schema: -; Owner: pp_db_user1
--

CREATE SCHEMA pp;


ALTER SCHEMA pp OWNER TO pp_db_user1;

--
-- Name: check_timetable_overlap(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.check_timetable_overlap() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    conflict_count int;
BEGIN
    SELECT COUNT(*) INTO conflict_count
    FROM pp.timetable
    WHERE day_of_week = NEW.day_of_week
      AND classroom_id = NEW.classroom_id
      AND (NEW.start_time < end_time AND NEW.end_time > start_time)
      AND timetable_id <> COALESCE(NEW.timetable_id, 0);

    IF conflict_count > 0 THEN
        RAISE EXCEPTION 'Classroom time conflict detected!';
    END IF;

    -- Optional: check teacher overlap if classroom has teacher assigned
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.check_timetable_overlap() OWNER TO postgres;

--
-- Name: pp_update_timestamp(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.pp_update_timestamp() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.pp_update_timestamp() OWNER TO postgres;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: applicant_exam; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_exam (
    applicant_id numeric(14,0) NOT NULL,
    exam_id numeric(14,0) NOT NULL,
    pp_hall_ticket_no character varying(20)
);


ALTER TABLE pp.applicant_exam OWNER TO pp_db_user1;

--
-- Name: applicant_exam_attendance; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_exam_attendance (
    applicant_id numeric(14,0),
    pp_exam_appeared_yn character(1),
    CONSTRAINT applicant_exam_attendance_pp_exam_appeared_yn_check CHECK ((pp_exam_appeared_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.applicant_exam_attendance OWNER TO pp_db_user1;

--
-- Name: applicant_exam_attendance_csv; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_exam_attendance_csv (
    nmms_reg_number numeric(11,0) NOT NULL,
    pp_exam_appeared_yn character(1),
    CONSTRAINT applicant_exam_attendance_csv_pp_exam_appeared_yn_check CHECK ((pp_exam_appeared_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.applicant_exam_attendance_csv OWNER TO pp_db_user1;

--
-- Name: applicant_exam_bak1; Type: TABLE; Schema: pp; Owner: postgres
--

CREATE TABLE pp.applicant_exam_bak1 (
    id integer,
    exam_id integer,
    pp_hall_ticket_no character varying(20)
);


ALTER TABLE pp.applicant_exam_bak1 OWNER TO postgres;

--
-- Name: applicant_exam_results_csv; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_exam_results_csv (
    nmms_reg_number numeric(11,0),
    pp_exam_score numeric(5,2),
    pp_exam_cleared character(1),
    interview_required_yn character(1),
    CONSTRAINT applicant_exam_results_csv_interview_required_yn_check CHECK ((interview_required_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT applicant_exam_results_csv_pp_exam_cleared_check CHECK ((pp_exam_cleared = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.applicant_exam_results_csv OWNER TO pp_db_user1;

--
-- Name: applicant_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.applicant_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.applicant_id_seq OWNER TO postgres;

--
-- Name: applicant_primary_info; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_primary_info (
    applicant_id numeric(14,0) DEFAULT nextval('pp.applicant_id_seq'::regclass) NOT NULL,
    nmms_year numeric(4,0),
    nmms_reg_number numeric(11,0) NOT NULL,
    app_state numeric(12,0) DEFAULT NULL::numeric,
    district numeric(12,0) DEFAULT NULL::numeric,
    nmms_block numeric(12,0) DEFAULT NULL::numeric,
    student_name character varying(100),
    father_name character varying(100),
    mother_name character varying(100),
    gmat_score numeric(2,0),
    sat_score numeric(2,0),
    gender character(1),
    medium character varying(50),
    aadhaar character varying(12),
    dob date,
    home_address character varying(200),
    family_income_total numeric(7,0),
    contact_no1 character varying(12),
    contact_no2 character varying(12),
    current_institute_dise_code character varying(15),
    previous_institute_dise_code character varying(15),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    students_sats_id numeric(11,0),
    CONSTRAINT applicant_primary_info_gender_check CHECK ((gender = ANY (ARRAY['M'::bpchar, 'F'::bpchar, 'O'::bpchar])))
);


ALTER TABLE pp.applicant_primary_info OWNER TO pp_db_user1;

--
-- Name: applicant_primary_info_csv; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_primary_info_csv (
    nmms_reg_number numeric(11,0) NOT NULL,
    father_name character varying(100),
    mother_name character varying(100),
    gender character(1),
    medium character varying(50),
    aadhaar character varying(12),
    dob_text character varying(20),
    home_address character varying(200),
    family_income_total numeric(7,0),
    contact_no1 character varying(12),
    contact_no2 character varying(12),
    current_institute_dise_code character varying(15),
    previous_institute_dise_code character varying(15)
);


ALTER TABLE pp.applicant_primary_info_csv OWNER TO pp_db_user1;

--
-- Name: applicant_result; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_result (
    applicant_id numeric(14,0),
    status character varying(20),
    remarks character varying(200),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT applicant_result_status_check CHECK (((status)::text = ANY (ARRAY[('SELECTED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


ALTER TABLE pp.applicant_result OWNER TO pp_db_user1;

--
-- Name: applicant_result_csv; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_result_csv (
    nmms_reg_number numeric(11,0),
    status character varying(20),
    remarks character varying(500)
);


ALTER TABLE pp.applicant_result_csv OWNER TO pp_db_user1;

--
-- Name: applicant_secondary_info; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_secondary_info (
    applicant_id numeric(14,0) NOT NULL,
    village character varying(100),
    father_occupation character varying(100),
    mother_occupation character varying(100),
    father_education character varying(100),
    mother_education character varying(100),
    household_size numeric(3,0),
    own_house character(1),
    smart_phone_home character(1),
    internet_facility_home character(1),
    career_goals text,
    subjects_of_interest text,
    transportation_mode character varying(100),
    distance_to_school numeric(5,2),
    num_two_wheelers numeric(2,0) DEFAULT 0 NOT NULL,
    num_four_wheelers numeric(2,0) DEFAULT 0 NOT NULL,
    irrigation_land numeric(6,2) DEFAULT 0 NOT NULL,
    neighbor_name character varying(100),
    neighbor_phone character varying(12),
    favorite_teacher_name character varying(100),
    favorite_teacher_phone character varying(12),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    spl_health_cond character(1) DEFAULT 'N'::bpchar,
    spl_health_cond_dtls text,
    spl_family_cond character(1) DEFAULT 'N'::bpchar,
    spl_family_cond_dtls text,
    CONSTRAINT applicant_secondary_info_internet_facility_home_check CHECK ((internet_facility_home = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT applicant_secondary_info_own_house_check CHECK ((own_house = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT applicant_secondary_info_smart_phone_home_check CHECK ((smart_phone_home = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT applicant_secondary_info_spl_family_cond_check CHECK ((spl_family_cond = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT applicant_secondary_info_spl_health_cond_check CHECK ((spl_health_cond = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.applicant_secondary_info OWNER TO pp_db_user1;

--
-- Name: applicant_shortlist_csv; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_shortlist_csv (
    nmms_reg_number numeric(11,0) NOT NULL,
    shortlisted_yn character(1),
    CONSTRAINT applicant_shortlist_csv_shortlisted_yn_check CHECK ((shortlisted_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.applicant_shortlist_csv OWNER TO pp_db_user1;

--
-- Name: shortlist_info_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.shortlist_info_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.shortlist_info_seq OWNER TO postgres;

--
-- Name: applicant_shortlist_info; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.applicant_shortlist_info (
    shortlist_info_id numeric(14,0) DEFAULT nextval('pp.shortlist_info_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0),
    shortlisted_yn character(1),
    shortlist_batch_id numeric(6,0),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT applicant_shortlist_info_shortlisted_yn_check CHECK ((shortlisted_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.applicant_shortlist_info OWNER TO pp_db_user1;

--
-- Name: attendance_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.attendance_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.attendance_id_seq OWNER TO postgres;

--
-- Name: batch_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.batch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.batch_id_seq OWNER TO postgres;

--
-- Name: batch; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.batch (
    batch_id integer DEFAULT nextval('pp.batch_id_seq'::regclass) NOT NULL,
    batch_name character varying(100),
    cohort_number integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    medium character varying(20) DEFAULT 'KANNADA'::character varying,
    house_name character varying(100),
    CONSTRAINT batch_medium_check CHECK (((medium)::text = ANY (ARRAY[('ENGLISH'::character varying)::text, ('KANNADA'::character varying)::text, ('HINDI'::character varying)::text, ('MARATHI'::character varying)::text])))
);


ALTER TABLE pp.batch OWNER TO pp_db_user1;

--
-- Name: batch_coordinator_batches; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.batch_coordinator_batches (
    user_id numeric(8,0) NOT NULL,
    batch_id integer NOT NULL
);


ALTER TABLE pp.batch_coordinator_batches OWNER TO pp_db_user1;

--
-- Name: class_session_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.class_session_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.class_session_seq OWNER TO postgres;

--
-- Name: class_session; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.class_session (
    session_id integer DEFAULT nextval('pp.class_session_seq'::regclass) NOT NULL,
    classroom_id integer NOT NULL,
    session_date date NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    timetable_id integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    duration_minutes integer,
    teacher_id integer
);


ALTER TABLE pp.class_session OWNER TO pp_db_user1;

--
-- Name: classroom_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.classroom_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.classroom_id_seq OWNER TO postgres;

--
-- Name: classroom; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.classroom (
    classroom_id integer DEFAULT nextval('pp.classroom_id_seq'::regclass) NOT NULL,
    classroom_name character varying(100) NOT NULL,
    subject_id integer,
    teacher_id integer,
    platform_id integer,
    description character varying(200),
    active_yn character(1) DEFAULT 'Y'::bpchar,
    class_link character varying(150),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT classroom_active_yn_check CHECK ((active_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.classroom OWNER TO pp_db_user1;

--
-- Name: classroom_batch; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.classroom_batch (
    classroom_id integer NOT NULL,
    batch_id integer NOT NULL
);


ALTER TABLE pp.classroom_batch OWNER TO pp_db_user1;

--
-- Name: cohort_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.cohort_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.cohort_seq OWNER TO postgres;

--
-- Name: cohort; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.cohort (
    cohort_number integer DEFAULT nextval('pp.cohort_seq'::regclass) NOT NULL,
    cohort_name character varying(100),
    start_date date,
    end_date date,
    description text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    status character varying(20),
    current_grade integer,
    CONSTRAINT cohort_current_grade_check CHECK ((current_grade = ANY (ARRAY[9, 10, 11, 12]))),
    CONSTRAINT cohort_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('COMPLETED'::character varying)::text])))
);


ALTER TABLE pp.cohort OWNER TO pp_db_user1;

--
-- Name: criteria_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.criteria_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.criteria_id_seq OWNER TO postgres;

--
-- Name: custom_list_id_seq; Type: SEQUENCE; Schema: pp; Owner: pp_db_user1
--

CREATE SEQUENCE pp.custom_list_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.custom_list_id_seq OWNER TO pp_db_user1;

--
-- Name: custom_list; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.custom_list (
    list_id numeric(10,0) DEFAULT nextval('pp.custom_list_id_seq'::regclass) NOT NULL,
    list_name character varying(200) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE pp.custom_list OWNER TO pp_db_user1;

--
-- Name: custom_list_fields; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.custom_list_fields (
    list_id numeric(10,0) NOT NULL,
    field_id numeric(6,0) NOT NULL
);


ALTER TABLE pp.custom_list_fields OWNER TO pp_db_user1;

--
-- Name: custom_list_master; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.custom_list_master (
    id integer NOT NULL,
    list_id integer NOT NULL,
    student_id integer NOT NULL
);


ALTER TABLE pp.custom_list_master OWNER TO pp_db_user1;

--
-- Name: custom_list_master_id_seq; Type: SEQUENCE; Schema: pp; Owner: pp_db_user1
--

CREATE SEQUENCE pp.custom_list_master_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.custom_list_master_id_seq OWNER TO pp_db_user1;

--
-- Name: custom_list_master_id_seq; Type: SEQUENCE OWNED BY; Schema: pp; Owner: pp_db_user1
--

ALTER SEQUENCE pp.custom_list_master_id_seq OWNED BY pp.custom_list_master.id;


--
-- Name: custom_list_names; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.custom_list_names (
    id integer NOT NULL,
    name character varying(100) NOT NULL
);


ALTER TABLE pp.custom_list_names OWNER TO pp_db_user1;

--
-- Name: custom_list_names_id_seq; Type: SEQUENCE; Schema: pp; Owner: pp_db_user1
--

CREATE SEQUENCE pp.custom_list_names_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.custom_list_names_id_seq OWNER TO pp_db_user1;

--
-- Name: custom_list_names_id_seq; Type: SEQUENCE OWNED BY; Schema: pp; Owner: pp_db_user1
--

ALTER SEQUENCE pp.custom_list_names_id_seq OWNED BY pp.custom_list_names.id;


--
-- Name: custom_list_students; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.custom_list_students (
    list_id numeric(10,0) NOT NULL,
    student_id numeric(14,0) NOT NULL
);


ALTER TABLE pp.custom_list_students OWNER TO pp_db_user1;

--
-- Name: desig_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.desig_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.desig_id_seq OWNER TO postgres;

--
-- Name: edudesig; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.edudesig (
    desig_id numeric(14,0) DEFAULT nextval('pp.desig_id_seq'::regclass) NOT NULL,
    desig_name character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);


ALTER TABLE pp.edudesig OWNER TO pp_db_user1;

--
-- Name: officer_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.officer_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.officer_id_seq OWNER TO postgres;

--
-- Name: eduofficer; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.eduofficer (
    officer_id numeric(14,0) DEFAULT nextval('pp.officer_id_seq'::regclass) NOT NULL,
    officer_name character varying(100),
    phone1 character varying(15),
    phone2 character varying(15),
    email character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);


ALTER TABLE pp.eduofficer OWNER TO pp_db_user1;

--
-- Name: evaluation_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.evaluation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.evaluation_id_seq OWNER TO postgres;

--
-- Name: event_master_event_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.event_master_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.event_master_event_id_seq OWNER TO postgres;

--
-- Name: event_master; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.event_master (
    event_id integer DEFAULT nextval('pp.event_master_event_id_seq'::regclass) NOT NULL,
    event_type_id integer,
    event_title character varying(150),
    event_start_date date NOT NULL,
    event_end_date date,
    event_district numeric(12,0),
    event_block numeric(12,0),
    event_location character varying(150),
    pincode character varying(12),
    cohort_number integer,
    boys_attended integer DEFAULT 0,
    girls_attended integer DEFAULT 0,
    parents_attended integer DEFAULT 0,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    event_description character varying(255)
);


ALTER TABLE pp.event_master OWNER TO pp_db_user1;

--
-- Name: event_photos_photo_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.event_photos_photo_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.event_photos_photo_id_seq OWNER TO postgres;

--
-- Name: event_photos; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.event_photos (
    photo_id integer DEFAULT nextval('pp.event_photos_photo_id_seq'::regclass) NOT NULL,
    event_id integer,
    file_path text NOT NULL,
    file_name character varying(100),
    uploaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    uploaded_by numeric(8,0)
);


ALTER TABLE pp.event_photos OWNER TO pp_db_user1;

--
-- Name: event_reports_report_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.event_reports_report_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.event_reports_report_id_seq OWNER TO postgres;

--
-- Name: event_reports; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.event_reports (
    report_id integer DEFAULT nextval('pp.event_reports_report_id_seq'::regclass) NOT NULL,
    event_id integer,
    report_type character varying(50),
    file_path text NOT NULL,
    file_name character varying(150),
    generated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    generated_by numeric(8,0)
);


ALTER TABLE pp.event_reports OWNER TO pp_db_user1;

--
-- Name: event_type; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.event_type (
    event_type_id integer NOT NULL,
    event_type_name character varying(100) NOT NULL
);


ALTER TABLE pp.event_type OWNER TO pp_db_user1;

--
-- Name: event_type_event_type_id_seq; Type: SEQUENCE; Schema: pp; Owner: pp_db_user1
--

CREATE SEQUENCE pp.event_type_event_type_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.event_type_event_type_id_seq OWNER TO pp_db_user1;

--
-- Name: event_type_event_type_id_seq; Type: SEQUENCE OWNED BY; Schema: pp; Owner: pp_db_user1
--

ALTER SEQUENCE pp.event_type_event_type_id_seq OWNED BY pp.event_type.event_type_id;


--
-- Name: exam_results; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.exam_results (
    applicant_id numeric(14,0),
    pp_exam_score numeric(3,0),
    pp_exam_cleared character(1),
    interview_required_yn character(1),
    CONSTRAINT exam_results_interview_required_yn_check CHECK ((interview_required_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT exam_results_pp_exam_cleared_check CHECK ((pp_exam_cleared = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.exam_results OWNER TO pp_db_user1;

--
-- Name: examination_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.examination_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.examination_seq OWNER TO postgres;

--
-- Name: examination; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.examination (
    exam_id numeric(14,0) DEFAULT nextval('pp.examination_seq'::regclass) NOT NULL,
    exam_name character varying(100) NOT NULL,
    exam_date date NOT NULL,
    exam_start_time time without time zone NOT NULL,
    exam_end_time time without time zone NOT NULL,
    pp_exam_centre_id numeric(10,0),
    frozen_yn character(1) DEFAULT 'N'::bpchar,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    exam_year character varying(10),
    CONSTRAINT examination_frozen_yn_check CHECK ((frozen_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.examination OWNER TO pp_db_user1;

--
-- Name: field_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.field_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.field_id_seq OWNER TO postgres;

--
-- Name: field_master; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.field_master (
    field_id numeric(6,0) DEFAULT nextval('pp.field_id_seq'::regclass) NOT NULL,
    tab_name character varying(100) DEFAULT 'pp.student_master'::character varying NOT NULL,
    col_name character varying(100) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE pp.field_master OWNER TO pp_db_user1;

--
-- Name: hall_ticket_sequence; Type: TABLE; Schema: pp; Owner: postgres
--

CREATE TABLE pp.hall_ticket_sequence (
    id integer NOT NULL,
    academic_year character varying(9) NOT NULL,
    juris_code character varying(20) NOT NULL,
    last_sequence integer DEFAULT 0 NOT NULL
);


ALTER TABLE pp.hall_ticket_sequence OWNER TO postgres;

--
-- Name: hall_ticket_sequence_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.hall_ticket_sequence_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.hall_ticket_sequence_id_seq OWNER TO postgres;

--
-- Name: hall_ticket_sequence_id_seq; Type: SEQUENCE OWNED BY; Schema: pp; Owner: postgres
--

ALTER SEQUENCE pp.hall_ticket_sequence_id_seq OWNED BY pp.hall_ticket_sequence.id;


--
-- Name: verification_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.verification_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.verification_id_seq OWNER TO postgres;

--
-- Name: home_verification; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.home_verification (
    verification_id numeric(12,0) DEFAULT nextval('pp.verification_id_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0),
    date_of_verification date,
    remarks character varying(200),
    status character varying(10),
    verified_by character varying(100),
    rejection_reason_id numeric(4,0),
    verification_type character varying(20),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    doc_name character varying(100),
    doc_type character varying(50),
    CONSTRAINT home_verification_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('SCHEDULED'::character varying)::text, ('REJECTED'::character varying)::text, ('ACCEPTED'::character varying)::text]))),
    CONSTRAINT home_verification_verification_type_check CHECK (((verification_type)::text = ANY (ARRAY[('PHYSICAL'::character varying)::text, ('VIRTUAL'::character varying)::text])))
);


ALTER TABLE pp.home_verification OWNER TO pp_db_user1;

--
-- Name: inactive_students; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.inactive_students (
    student_id numeric(14,0),
    inactive_reason character varying(200),
    inactive_date date,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);


ALTER TABLE pp.inactive_students OWNER TO pp_db_user1;

--
-- Name: institute_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.institute_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.institute_id_seq OWNER TO postgres;

--
-- Name: institute; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.institute (
    institute_id numeric(14,0) DEFAULT nextval('pp.institute_id_seq'::regclass) NOT NULL,
    dise_code character varying(15),
    institute_name character varying(200),
    institute_board character varying(20),
    address character varying(200),
    pin_code character varying(10),
    email character varying(200),
    contact_no character varying(12),
    juris_code numeric(12,0),
    management_type character varying(50),
    institute_management_type character varying(150),
    institute_category_name character varying(100),
    institute_urban_or_rural character varying(50),
    class_from numeric(2,0),
    class_to numeric(2,0),
    institute_type character varying(50),
    latitude numeric(15,2),
    longitude numeric(15,2),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT institute_institute_board_check CHECK (((institute_board)::text = ANY (ARRAY[('STATE'::character varying)::text, ('ICSE'::character varying)::text, ('CBSE'::character varying)::text, ('OTHER BOARD'::character varying)::text, ('INTERNATIONAL BOARD'::character varying)::text]))),
    CONSTRAINT institute_institute_category_name_check CHECK (((institute_category_name)::text = ANY (ARRAY[('SECONDARY ONLY'::character varying)::text, ('UPPER PRIMARY ONLY'::character varying)::text, ('UP. PR. SECONDARY AND HIGHER SEC'::character varying)::text, ('HIGHER SECONDARY ONLY'::character varying)::text, ('UPPER PR. AND SECONDARY'::character varying)::text, ('PR. WITH UP.PR. SEC. AND H.SEC.'::character varying)::text, ('SECONDARY WITH HIGHER SECONDARY'::character varying)::text, ('PRIMARY WITH UPPER PRIMARY'::character varying)::text, ('PR. UP PR. AND SECONDARY ONLY'::character varying)::text, ('PRIMARY'::character varying)::text]))),
    CONSTRAINT institute_institute_management_type_check CHECK (((institute_management_type)::text = ANY (ARRAY[('UNRECOGNIZED'::character varying)::text, ('TRIBAL WELFARE DEPARTMENT'::character varying)::text, ('CENTRAL TIBETAN SCHOOLS'::character varying)::text, ('MINISTRY OF LABOR'::character varying)::text, ('SOCIAL WELFARE DEPARTMENT'::character varying)::text, ('DEPARTMENT OF EDUCATION'::character varying)::text, ('MADARSA RECOGNIZED'::character varying)::text, ('OTHER GOVT. MANAGED SCHOOLS'::character varying)::text, ('OTHER CENTRAL GOVT. SCHOOLS'::character varying)::text, ('SAINIK SCHOOL'::character varying)::text, ('LOCAL BODY'::character varying)::text, ('GOVERNMENT AIDED'::character varying)::text, ('RAILWAY SCHOOL'::character varying)::text, ('PRIVATE UNAIDED (RECOGNIZED)'::character varying)::text]))),
    CONSTRAINT institute_institute_type_check CHECK (((institute_type)::text = ANY (ARRAY[('CO EDUCATION'::character varying)::text, ('BOYS'::character varying)::text, ('GIRLS'::character varying)::text]))),
    CONSTRAINT institute_institute_urban_or_rural_check CHECK (((institute_urban_or_rural)::text = ANY (ARRAY[('URBAN'::character varying)::text, ('RURAL'::character varying)::text]))),
    CONSTRAINT institute_management_type_check CHECK (((management_type)::text = ANY (ARRAY[('GOVERNMENT'::character varying)::text, ('PRIVATE AIDED'::character varying)::text, ('PRIVATE UNAIDED'::character varying)::text, ('OTHERS'::character varying)::text, ('NULL'::character varying)::text])))
);


ALTER TABLE pp.institute OWNER TO pp_db_user1;

--
-- Name: institute_medium; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.institute_medium (
    dise_code character varying(15),
    medium character varying(10)
);


ALTER TABLE pp.institute_medium OWNER TO pp_db_user1;

--
-- Name: institute_type_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.institute_type_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.institute_type_id_seq OWNER TO postgres;

--
-- Name: interview_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.interview_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.interview_id_seq OWNER TO postgres;

--
-- Name: interviewer_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.interviewer_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.interviewer_id_seq OWNER TO postgres;

--
-- Name: interviewer; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.interviewer (
    interviewer_id numeric(10,0) DEFAULT nextval('pp.interviewer_id_seq'::regclass) NOT NULL,
    interviewer_name character varying(100),
    email character varying(100),
    mobile1 character varying(12),
    mobile2 character varying(12),
    active_status character(1),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT interviewer_active_status_check CHECK ((active_status = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.interviewer OWNER TO pp_db_user1;

--
-- Name: jurisdiction; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.jurisdiction (
    juris_code numeric(12,0) NOT NULL,
    juris_name character varying(100),
    juris_type character varying(100),
    parent_juris numeric(12,0),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);


ALTER TABLE pp.jurisdiction OWNER TO pp_db_user1;

--
-- Name: jurisdiction_code_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.jurisdiction_code_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.jurisdiction_code_seq OWNER TO postgres;

--
-- Name: jurisdiction_type; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.jurisdiction_type (
    juris_type character varying(100) NOT NULL
);


ALTER TABLE pp.jurisdiction_type OWNER TO pp_db_user1;

--
-- Name: officer_desig; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.officer_desig (
    officer_id numeric(14,0) NOT NULL,
    juris_code numeric(12,0) NOT NULL,
    desig_id numeric(14,0) NOT NULL,
    other_desig character varying(100)
);


ALTER TABLE pp.officer_desig OWNER TO pp_db_user1;

--
-- Name: official_issue; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.official_issue (
    tab_id integer NOT NULL,
    user_id numeric(8,0) NOT NULL,
    assignment_date date NOT NULL,
    return_date date,
    remark text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);


ALTER TABLE pp.official_issue OWNER TO pp_db_user1;

--
-- Name: platform_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.platform_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.platform_id_seq OWNER TO postgres;

--
-- Name: platform_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.platform_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.platform_seq OWNER TO postgres;

--
-- Name: pp_exam_centre_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.pp_exam_centre_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.pp_exam_centre_seq OWNER TO postgres;

--
-- Name: pp_exam_centre; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.pp_exam_centre (
    pp_exam_centre_id numeric(10,0) DEFAULT nextval('pp.pp_exam_centre_seq'::regclass) NOT NULL,
    pp_exam_centre_code character varying(20),
    pp_exam_centre_name character varying(200) NOT NULL,
    address character varying(200),
    village character varying(100),
    pincode character varying(12),
    contact_person character varying(100),
    contact_phone character varying(12),
    contact_email character varying(200),
    sitting_capacity integer,
    active_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    latitude numeric(15,2),
    longitude numeric(15,2),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    google_map_link text GENERATED ALWAYS AS (
CASE
    WHEN ((latitude IS NOT NULL) AND (longitude IS NOT NULL)) THEN ((((('https://www.google.com/maps/search/?api=1&query='::text || replace((pp_exam_centre_name)::text, ' '::text, '%20'::text)) || '%20'::text) || latitude) || ','::text) || longitude)
    ELSE NULL::text
END) STORED,
    CONSTRAINT pp_exam_centre_active_yn_check CHECK ((active_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT pp_exam_centre_sitting_capacity_check CHECK ((sitting_capacity >= 0))
);


ALTER TABLE pp.pp_exam_centre OWNER TO pp_db_user1;

--
-- Name: rejection_reasons; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.rejection_reasons (
    rej_reason_id numeric(4,0) NOT NULL,
    rejection_reason character varying(200) NOT NULL
);


ALTER TABLE pp.rejection_reasons OWNER TO pp_db_user1;

--
-- Name: role_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.role_id_seq OWNER TO postgres;

--
-- Name: role; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.role (
    role_id numeric(4,0) DEFAULT nextval('pp.role_id_seq'::regclass) NOT NULL,
    role_name character varying(100) NOT NULL,
    active_yn character(1),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT role_active_yn_check CHECK ((active_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.role OWNER TO pp_db_user1;

--
-- Name: shortlist_batch_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.shortlist_batch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.shortlist_batch_id_seq OWNER TO postgres;

--
-- Name: shortlist_batch; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.shortlist_batch (
    shortlist_batch_id numeric(6,0) DEFAULT nextval('pp.shortlist_batch_id_seq'::regclass) NOT NULL,
    shortlist_batch_name character varying(100) NOT NULL,
    description character varying(200),
    created_on timestamp without time zone DEFAULT now(),
    criteria_id numeric(3,0),
    frozen_yn character(1) DEFAULT 'N'::bpchar,
    shortlisted_year numeric(4,0) NOT NULL,
    medium_filtered_yn character(1) DEFAULT 'N'::bpchar,
    CONSTRAINT shortlist_batch_frozen_yn_check CHECK ((frozen_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT shortlist_batch_medium_filtered_yn_check CHECK ((medium_filtered_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp.shortlist_batch OWNER TO pp_db_user1;

--
-- Name: shortlist_batch_jurisdiction; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.shortlist_batch_jurisdiction (
    shortlist_batch_id numeric(6,0) NOT NULL,
    juris_code numeric(12,0) NOT NULL
);


ALTER TABLE pp.shortlist_batch_jurisdiction OWNER TO pp_db_user1;

--
-- Name: shortlist_criteria; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.shortlist_criteria (
    criteria_id numeric(3,0) DEFAULT nextval('pp.criteria_id_seq'::regclass) NOT NULL,
    criteria character varying(500),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);


ALTER TABLE pp.shortlist_criteria OWNER TO pp_db_user1;

--
-- Name: sibling_education_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.sibling_education_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.sibling_education_seq OWNER TO postgres;

--
-- Name: sibling_education; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.sibling_education (
    sibling_id numeric(14,0) DEFAULT nextval('pp.sibling_education_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0),
    sibling_name character varying(100),
    sibling_type character(1) NOT NULL,
    education character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT sibling_education_education_check CHECK (((education)::text = ANY (ARRAY[('SSLC'::character varying)::text, ('PUC'::character varying)::text, ('DIPLOMA'::character varying)::text, ('DEGREE'::character varying)::text, ('POSTGRADUATE'::character varying)::text, ('OTHERS'::character varying)::text]))),
    CONSTRAINT sibling_education_sibling_type_check CHECK ((sibling_type = ANY (ARRAY['B'::bpchar, 'S'::bpchar])))
);


ALTER TABLE pp.sibling_education OWNER TO pp_db_user1;

--
-- Name: std_applicant_primary_info; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.std_applicant_primary_info (
    applicant_id numeric(14,0) DEFAULT nextval('pp.applicant_id_seq'::regclass) NOT NULL,
    nmms_year numeric(4,0),
    nmms_reg_number numeric(11,0) NOT NULL,
    app_state numeric(12,0) DEFAULT NULL::numeric,
    district numeric(12,0) DEFAULT NULL::numeric,
    nmms_block numeric(12,0) DEFAULT NULL::numeric,
    student_name character varying(100),
    father_name character varying(100),
    mother_name character varying(100),
    gmat_score numeric(2,0),
    sat_score numeric(2,0),
    gender character(1),
    medium character varying(50),
    aadhaar character varying(12),
    dob date,
    home_address character varying(200),
    family_income_total numeric(7,0),
    contact_no1 character varying(12),
    contact_no2 character varying(12),
    current_institute_dise_code character varying(15),
    previous_institute_dise_code character varying(15),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    students_sats_id numeric(11,0),
    CONSTRAINT std_applicant_primary_info_gender_check CHECK ((gender = ANY (ARRAY['M'::bpchar, 'F'::bpchar, 'O'::bpchar])))
);


ALTER TABLE pp.std_applicant_primary_info OWNER TO pp_db_user1;

--
-- Name: stg_nmms_phase1_applications; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.stg_nmms_phase1_applications (
    id bigint NOT NULL,
    nmms_year text,
    exam text,
    district numeric(12,0),
    app_state numeric(12,0),
    nmms_block numeric(12,0),
    current_institute_dise_code text,
    students_sats_id text,
    student_name text,
    father_name text,
    institute_name text,
    institute_type text,
    category_name text,
    disability_status text,
    contact_no1 text,
    contact_no2 text,
    date_of_application text,
    created_at text DEFAULT (CURRENT_TIMESTAMP)::text,
    student_name_key text
);


ALTER TABLE pp.stg_nmms_phase1_applications OWNER TO pp_db_user1;

--
-- Name: stg_nmms_phase1_applications_id_seq; Type: SEQUENCE; Schema: pp; Owner: pp_db_user1
--

CREATE SEQUENCE pp.stg_nmms_phase1_applications_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.stg_nmms_phase1_applications_id_seq OWNER TO pp_db_user1;

--
-- Name: stg_nmms_phase1_applications_id_seq; Type: SEQUENCE OWNED BY; Schema: pp; Owner: pp_db_user1
--

ALTER SEQUENCE pp.stg_nmms_phase1_applications_id_seq OWNED BY pp.stg_nmms_phase1_applications.id;


--
-- Name: stg_nmms_phase2_results; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.stg_nmms_phase2_results (
    result_stg_id bigint NOT NULL,
    nmms_year text,
    district numeric(12,0),
    nmms_block numeric(12,0),
    nmms_reg_number text,
    student_name text,
    gmat_score text,
    sat_score text,
    match_status character varying(30) DEFAULT 'PENDING'::character varying,
    remarks text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    student_name_key text
);


ALTER TABLE pp.stg_nmms_phase2_results OWNER TO pp_db_user1;

--
-- Name: stg_nmms_phase2_results_result_stg_id_seq; Type: SEQUENCE; Schema: pp; Owner: pp_db_user1
--

CREATE SEQUENCE pp.stg_nmms_phase2_results_result_stg_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.stg_nmms_phase2_results_result_stg_id_seq OWNER TO pp_db_user1;

--
-- Name: stg_nmms_phase2_results_result_stg_id_seq; Type: SEQUENCE OWNED BY; Schema: pp; Owner: pp_db_user1
--

ALTER SEQUENCE pp.stg_nmms_phase2_results_result_stg_id_seq OWNED BY pp.stg_nmms_phase2_results.result_stg_id;


--
-- Name: student_attendance; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.student_attendance (
    attendance_id integer DEFAULT nextval('pp.attendance_id_seq'::regclass) NOT NULL,
    session_id integer,
    student_id numeric(14,0),
    status character varying(20) NOT NULL,
    time_joined time without time zone,
    time_exited time without time zone,
    attendance_percent numeric(5,2),
    remarks character varying(200),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    duration_minutes integer,
    CONSTRAINT student_attendance_status_check CHECK (((status)::text = ANY (ARRAY[('PRESENT'::character varying)::text, ('ABSENT'::character varying)::text, ('LATE JOINED'::character varying)::text, ('LEAVE'::character varying)::text])))
);


ALTER TABLE pp.student_attendance OWNER TO pp_db_user1;

--
-- Name: student_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.student_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.student_id_seq OWNER TO postgres;

--
-- Name: student_interview; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.student_interview (
    interview_id numeric(12,0) DEFAULT nextval('pp.interview_id_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0),
    interviewer_id numeric(10,0),
    interview_date date,
    interview_time time without time zone,
    interview_mode character varying(20),
    interview_round integer,
    status character varying(15),
    life_goals_and_zeal numeric(3,1),
    commitment_to_learning numeric(3,1),
    integrity numeric(3,1),
    communication_skills numeric(3,1),
    interview_result character varying(50),
    home_verification_req_yn character(1) DEFAULT 'N'::bpchar,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    remarks character varying(500),
    doc_name character varying(100),
    doc_type character varying(50),
    CONSTRAINT chk_interview_result CHECK (((interview_result)::text = ANY (ARRAY[('SELECTED'::character varying)::text, ('REJECTED'::character varying)::text, ('ANOTHER INTERVIEW REQUIRED'::character varying)::text]))),
    CONSTRAINT student_interview_home_verification_req_yn_check CHECK ((home_verification_req_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT student_interview_interview_mode_check CHECK (((interview_mode)::text = ANY (ARRAY[('ONLINE'::character varying)::text, ('OFFLINE'::character varying)::text]))),
    CONSTRAINT student_interview_status_check CHECK (((status)::text = ANY (ARRAY[('SCHEDULED'::character varying)::text, ('COMPLETED'::character varying)::text, ('CANCELLED'::character varying)::text, ('RESCHEDULED'::character varying)::text])))
);


ALTER TABLE pp.student_interview OWNER TO pp_db_user1;

--
-- Name: student_interview_csv; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.student_interview_csv (
    nmms_reg_number character varying(20),
    interviewer_id integer,
    interview_date character varying(20),
    interview_time character varying(20),
    interview_mode character varying(20),
    interview_round integer,
    status character varying(20),
    life_goals_and_zeal integer,
    commitment_to_learning integer,
    integrity integer,
    communication_skills integer,
    interview_result character varying(50),
    home_verification_req_yn character(1),
    remarks text
);


ALTER TABLE pp.student_interview_csv OWNER TO pp_db_user1;

--
-- Name: student_issue; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.student_issue (
    tab_id integer NOT NULL,
    student_id numeric(14,0) NOT NULL,
    assignment_date date NOT NULL,
    return_date date,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);


ALTER TABLE pp.student_issue OWNER TO pp_db_user1;

--
-- Name: student_list_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.student_list_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.student_list_id_seq OWNER TO postgres;

--
-- Name: student_list; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.student_list (
    list_id numeric(10,0) DEFAULT nextval('pp.student_list_id_seq'::regclass) NOT NULL,
    list_name character varying(200) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE pp.student_list OWNER TO pp_db_user1;

--
-- Name: student_list_fields; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.student_list_fields (
    list_id numeric(10,0) NOT NULL,
    field_id numeric(6,0) NOT NULL
);


ALTER TABLE pp.student_list_fields OWNER TO pp_db_user1;

--
-- Name: student_lists; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.student_lists (
    list_id numeric(10,0) NOT NULL,
    student_id numeric(14,0) NOT NULL
);


ALTER TABLE pp.student_lists OWNER TO pp_db_user1;

--
-- Name: student_master; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.student_master (
    student_id numeric(14,0) DEFAULT nextval('pp.student_id_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0),
    enr_id numeric(11,0),
    student_name character varying(100),
    father_name character varying(100),
    father_occupation character varying(100),
    mother_name character varying(100),
    mother_occupation character varying(100),
    gender character(1),
    batch_id integer,
    sim_name character varying(10),
    student_email character varying(150),
    student_email_password character varying(100),
    parent_email character varying(150),
    photo_link text,
    home_address character varying(200),
    contact_no1 character varying(12),
    contact_no2 character varying(12),
    current_institute_dise_code character varying(15),
    previous_institute_dise_code character varying(15),
    active_yn character varying(10) DEFAULT 'ACTIVE'::character varying,
    recharge_status character varying(20),
    sponsor character varying(100),
    teacher_name character varying(100),
    teacher_mobile_number character varying(12),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    user_id numeric,
    CONSTRAINT student_master_active_yn_check CHECK (((active_yn)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text]))),
    CONSTRAINT student_master_gender_check CHECK ((gender = ANY (ARRAY['M'::bpchar, 'F'::bpchar, 'O'::bpchar]))),
    CONSTRAINT student_master_recharge_status_check CHECK (((recharge_status)::text = ANY (ARRAY[('GRANTED'::character varying)::text, ('NOT GRANTED'::character varying)::text])))
);


ALTER TABLE pp.student_master OWNER TO pp_db_user1;

--
-- Name: student_master_staging; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.student_master_staging (
    nmms_reg_number numeric(11,0),
    enr_id numeric(11,0),
    student_name character varying(100),
    father_name character varying(100),
    father_occupation character varying(100),
    mother_name character varying(100),
    mother_occupation character varying(100),
    gender character(1),
    batch_id integer,
    sim_name character varying(10),
    student_email character varying(150),
    student_email_password character varying(100),
    parent_email character varying(150),
    photo_link text,
    home_address character varying(200),
    residential_address character varying(200),
    contact_no1 character varying(12),
    contact_no2 character varying(12),
    current_institute_dise_code character varying(15),
    previous_institute_dise_code character varying(15),
    active_yn character varying(10),
    recharge_status character varying(20),
    sponsor character varying(100),
    teacher_name character varying(100),
    teacher_mobile_number character varying(12)
);


ALTER TABLE pp.student_master_staging OWNER TO pp_db_user1;

--
-- Name: subject_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.subject_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.subject_id_seq OWNER TO postgres;

--
-- Name: subject; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.subject (
    subject_id integer DEFAULT nextval('pp.subject_id_seq'::regclass) NOT NULL,
    subject_code character varying(5) NOT NULL,
    subject_name character varying(100) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);


ALTER TABLE pp.subject OWNER TO pp_db_user1;

--
-- Name: system_config_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.system_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.system_config_id_seq OWNER TO postgres;

--
-- Name: system_config; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.system_config (
    system_config_id integer DEFAULT nextval('pp.system_config_id_seq'::regclass) NOT NULL,
    academic_year character varying(9) NOT NULL,
    phase character varying(50) NOT NULL,
    is_active boolean DEFAULT true,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_academic_year_format CHECK (((academic_year)::text ~ '^[0-9]{4}-[0-9]{2,4}$'::text))
);


ALTER TABLE pp.system_config OWNER TO pp_db_user1;

--
-- Name: tab_brand; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.tab_brand (
    brand_id integer NOT NULL,
    brand_name character varying(15) NOT NULL,
    model_name character varying(15) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);


ALTER TABLE pp.tab_brand OWNER TO pp_db_user1;

--
-- Name: tab_brand_brand_id_seq; Type: SEQUENCE; Schema: pp; Owner: pp_db_user1
--

CREATE SEQUENCE pp.tab_brand_brand_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.tab_brand_brand_id_seq OWNER TO pp_db_user1;

--
-- Name: tab_brand_brand_id_seq; Type: SEQUENCE OWNED BY; Schema: pp; Owner: pp_db_user1
--

ALTER SEQUENCE pp.tab_brand_brand_id_seq OWNED BY pp.tab_brand.brand_id;


--
-- Name: tab_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.tab_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.tab_id_seq OWNER TO postgres;

--
-- Name: tab_inventory; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.tab_inventory (
    tab_id numeric(20,0) DEFAULT nextval('pp.tab_id_seq'::regclass) NOT NULL,
    serial_number character varying(50) NOT NULL,
    brand_id integer NOT NULL,
    inventory_id character varying(40),
    imei character varying(40),
    tab_purchase_date date,
    status character varying(10) DEFAULT 'IN_OFFICE'::character varying,
    remarks text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT tab_inventory_status_check CHECK (((status)::text = ANY ((ARRAY['IN_OFFICE'::character varying, 'ASSIGNED'::character varying, 'RETURNED'::character varying, 'DAMAGED'::character varying, 'LOST'::character varying])::text[])))
);


ALTER TABLE pp.tab_inventory OWNER TO pp_db_user1;

--
-- Name: teacher_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.teacher_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.teacher_id_seq OWNER TO postgres;

--
-- Name: teacher; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.teacher (
    teacher_id integer DEFAULT nextval('pp.teacher_id_seq'::regclass) NOT NULL,
    user_id numeric(8,0),
    teacher_name character varying(150),
    qualification character varying(150),
    experience_yrs integer,
    doj date,
    contact_no character varying(12),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT teacher_experience_yrs_check CHECK ((experience_yrs >= 0))
);


ALTER TABLE pp.teacher OWNER TO pp_db_user1;

--
-- Name: teacher_subject; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.teacher_subject (
    teacher_id integer NOT NULL,
    subject_id integer NOT NULL,
    medium character varying(20) DEFAULT 'KANNADA'::character varying NOT NULL,
    CONSTRAINT teacher_subject_medium_check CHECK (((medium)::text = ANY (ARRAY[('ENGLISH'::character varying)::text, ('KANNADA'::character varying)::text, ('HINDI'::character varying)::text, ('MARATHI'::character varying)::text])))
);


ALTER TABLE pp.teacher_subject OWNER TO pp_db_user1;

--
-- Name: teaching_platform; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.teaching_platform (
    platform_id integer DEFAULT nextval('pp.platform_id_seq'::regclass) NOT NULL,
    platform_name character varying(100) NOT NULL
);


ALTER TABLE pp.teaching_platform OWNER TO pp_db_user1;

--
-- Name: temp1; Type: TABLE; Schema: pp; Owner: postgres
--

CREATE TABLE pp.temp1 (
    nmms_reg_no numeric(14,0),
    pp_hall_ticket_no character varying(50)
);


ALTER TABLE pp.temp1 OWNER TO postgres;

--
-- Name: temp2; Type: TABLE; Schema: pp; Owner: postgres
--

CREATE TABLE pp.temp2 (
    nmms_reg_no character varying(50),
    applicant_id character varying(50)
);


ALTER TABLE pp.temp2 OWNER TO postgres;

--
-- Name: time_table_solution; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.time_table_solution (
    solution_id bigint NOT NULL,
    solution_file_name character varying(255) NOT NULL,
    solution_file_ins_user_name character varying(255) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);


ALTER TABLE pp.time_table_solution OWNER TO pp_db_user1;

--
-- Name: time_table_solution_seq; Type: SEQUENCE; Schema: pp; Owner: pp_db_user1
--

CREATE SEQUENCE pp.time_table_solution_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.time_table_solution_seq OWNER TO pp_db_user1;

--
-- Name: time_table_solution_solution_id_seq; Type: SEQUENCE; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE pp.time_table_solution ALTER COLUMN solution_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME pp.time_table_solution_solution_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: timetable_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.timetable_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.timetable_id_seq OWNER TO postgres;

--
-- Name: timetable; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.timetable (
    timetable_id integer DEFAULT nextval('pp.timetable_id_seq'::regclass) NOT NULL,
    classroom_id integer,
    day_of_week character varying(10),
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT timetable_day_of_week_check CHECK (((day_of_week)::text = ANY (ARRAY[('SUNDAY'::character varying)::text, ('MONDAY'::character varying)::text, ('TUESDAY'::character varying)::text, ('WEDNESDAY'::character varying)::text, ('THURSDAY'::character varying)::text, ('FRIDAY'::character varying)::text, ('SATURDAY'::character varying)::text])))
);


ALTER TABLE pp.timetable OWNER TO pp_db_user1;

--
-- Name: user_id_seq; Type: SEQUENCE; Schema: pp; Owner: postgres
--

CREATE SEQUENCE pp.user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE pp.user_id_seq OWNER TO postgres;

--
-- Name: user; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp."user" (
    user_id numeric(8,0) DEFAULT nextval('pp.user_id_seq'::regclass) NOT NULL,
    user_name character varying(100) NOT NULL,
    enc_password character varying(300),
    locked_yn character(1),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    full_name character varying(150),
    user_email character varying(150),
    contact_no character varying(15),
    active_yn character(1) DEFAULT 'Y'::bpchar,
    last_login_at timestamp without time zone,
    password_changed_at timestamp without time zone,
    CONSTRAINT user_active_yn_check CHECK ((active_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT user_locked_yn_check CHECK ((locked_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


ALTER TABLE pp."user" OWNER TO pp_db_user1;

--
-- Name: user_role; Type: TABLE; Schema: pp; Owner: pp_db_user1
--

CREATE TABLE pp.user_role (
    user_id numeric(8,0) NOT NULL,
    role_id numeric(4,0) NOT NULL
);


ALTER TABLE pp.user_role OWNER TO pp_db_user1;

--
-- Name: account; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.account (
    accnum character varying(14) NOT NULL,
    acc_type character(4),
    opendt date,
    status character(4),
    balance numeric(12,0)
);


ALTER TABLE public.account OWNER TO postgres;

--
-- Name: customer; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.customer (
    cust_id integer NOT NULL,
    custname character varying(200),
    dob date,
    pan character varying(12)
);


ALTER TABLE public.customer OWNER TO postgres;

--
-- Name: customeracc; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.customeracc (
    customeracc_id integer NOT NULL,
    accnum character varying(14),
    cust_id integer
);


ALTER TABLE public.customeracc OWNER TO postgres;

--
-- Name: deposittx; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.deposittx (
    txid character varying(20) NOT NULL,
    accnum character varying(14),
    amt numeric(12,0),
    txmode character(4)
);


ALTER TABLE public.deposittx OWNER TO postgres;

--
-- Name: staging_excel_data; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.staging_excel_data (
    nmms_reg_number numeric(14,0),
    village character varying(100),
    father_occupation character varying(100),
    mother_occupation character varying(100),
    father_education character varying(100),
    mother_education character varying(100),
    household_size numeric(3,0),
    own_house character(1),
    smart_phone_home character(1),
    internet_facility_home character(1),
    career_goals text,
    subjects_of_interest text,
    transportation_mode character varying(100),
    distance_to_school numeric(15,2),
    num_two_wheelers numeric(5,0),
    num_four_wheelers numeric(5,0),
    irrigation_land numeric(15,2),
    neighbor_name character varying(100),
    neighbor_phone character varying(12),
    favorite_teacher_name character varying(100),
    favorite_teacher_phone character varying(12)
);


ALTER TABLE public.staging_excel_data OWNER TO postgres;

--
-- Name: transact; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.transact (
    txid character varying(20) NOT NULL,
    txtime timestamp without time zone,
    txtype character(4)
);


ALTER TABLE public.transact OWNER TO postgres;

--
-- Name: transfertx; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.transfertx (
    txid character varying(20) NOT NULL,
    from_acc character varying(14),
    to_acc character varying(14),
    amt numeric(12,0),
    txmode character(4)
);


ALTER TABLE public.transfertx OWNER TO postgres;

--
-- Name: withdrawaltx; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.withdrawaltx (
    txid character varying(20) NOT NULL,
    accnum character varying(14),
    amt numeric(12,0),
    txmode character(4)
);


ALTER TABLE public.withdrawaltx OWNER TO postgres;

--
-- Name: custom_list_master id; Type: DEFAULT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_master ALTER COLUMN id SET DEFAULT nextval('pp.custom_list_master_id_seq'::regclass);


--
-- Name: custom_list_names id; Type: DEFAULT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_names ALTER COLUMN id SET DEFAULT nextval('pp.custom_list_names_id_seq'::regclass);


--
-- Name: event_type event_type_id; Type: DEFAULT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_type ALTER COLUMN event_type_id SET DEFAULT nextval('pp.event_type_event_type_id_seq'::regclass);


--
-- Name: hall_ticket_sequence id; Type: DEFAULT; Schema: pp; Owner: postgres
--

ALTER TABLE ONLY pp.hall_ticket_sequence ALTER COLUMN id SET DEFAULT nextval('pp.hall_ticket_sequence_id_seq'::regclass);


--
-- Name: stg_nmms_phase1_applications id; Type: DEFAULT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.stg_nmms_phase1_applications ALTER COLUMN id SET DEFAULT nextval('pp.stg_nmms_phase1_applications_id_seq'::regclass);


--
-- Name: stg_nmms_phase2_results result_stg_id; Type: DEFAULT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.stg_nmms_phase2_results ALTER COLUMN result_stg_id SET DEFAULT nextval('pp.stg_nmms_phase2_results_result_stg_id_seq'::regclass);


--
-- Name: tab_brand brand_id; Type: DEFAULT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_brand ALTER COLUMN brand_id SET DEFAULT nextval('pp.tab_brand_brand_id_seq'::regclass);


--
-- Name: applicant_exam_attendance_csv applicant_exam_attendance_csv_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_exam_attendance_csv
    ADD CONSTRAINT applicant_exam_attendance_csv_pkey PRIMARY KEY (nmms_reg_number);


--
-- Name: applicant_primary_info_csv applicant_primary_info_csv_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_primary_info_csv
    ADD CONSTRAINT applicant_primary_info_csv_pkey PRIMARY KEY (nmms_reg_number);


--
-- Name: applicant_primary_info applicant_primary_info_nmms_reg_number_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_nmms_reg_number_key UNIQUE (nmms_reg_number);


--
-- Name: applicant_primary_info applicant_primary_info_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_pkey PRIMARY KEY (applicant_id);


--
-- Name: applicant_secondary_info applicant_secondary_info_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_secondary_info
    ADD CONSTRAINT applicant_secondary_info_pkey PRIMARY KEY (applicant_id);


--
-- Name: applicant_shortlist_csv applicant_shortlist_csv_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_shortlist_csv
    ADD CONSTRAINT applicant_shortlist_csv_pkey PRIMARY KEY (nmms_reg_number);


--
-- Name: applicant_shortlist_info applicant_shortlist_info_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_shortlist_info
    ADD CONSTRAINT applicant_shortlist_info_pkey PRIMARY KEY (shortlist_info_id);


--
-- Name: batch batch_cohort_number_batch_name_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.batch
    ADD CONSTRAINT batch_cohort_number_batch_name_key UNIQUE (cohort_number, batch_name);


--
-- Name: batch_coordinator_batches batch_coordinator_batches_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.batch_coordinator_batches
    ADD CONSTRAINT batch_coordinator_batches_pkey PRIMARY KEY (user_id, batch_id);


--
-- Name: batch batch_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.batch
    ADD CONSTRAINT batch_pkey PRIMARY KEY (batch_id);


--
-- Name: tab_brand brand_model_unique; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_brand
    ADD CONSTRAINT brand_model_unique UNIQUE (brand_name, model_name);


--
-- Name: class_session class_session_classroom_id_session_date_start_time_end_time_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_classroom_id_session_date_start_time_end_time_key UNIQUE (classroom_id, session_date, start_time, end_time);


--
-- Name: class_session class_session_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_pkey PRIMARY KEY (session_id);


--
-- Name: classroom_batch classroom_batch_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.classroom_batch
    ADD CONSTRAINT classroom_batch_pkey PRIMARY KEY (classroom_id, batch_id);


--
-- Name: classroom classroom_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_pkey PRIMARY KEY (classroom_id);


--
-- Name: cohort cohort_cohort_name_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.cohort
    ADD CONSTRAINT cohort_cohort_name_key UNIQUE (cohort_name);


--
-- Name: cohort cohort_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.cohort
    ADD CONSTRAINT cohort_pkey PRIMARY KEY (cohort_number);


--
-- Name: custom_list_fields custom_list_fields_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_fields
    ADD CONSTRAINT custom_list_fields_pkey PRIMARY KEY (list_id, field_id);


--
-- Name: custom_list_master custom_list_master_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_master
    ADD CONSTRAINT custom_list_master_pkey PRIMARY KEY (id);


--
-- Name: custom_list_names custom_list_names_name_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_names
    ADD CONSTRAINT custom_list_names_name_key UNIQUE (name);


--
-- Name: custom_list_names custom_list_names_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_names
    ADD CONSTRAINT custom_list_names_pkey PRIMARY KEY (id);


--
-- Name: custom_list custom_list_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list
    ADD CONSTRAINT custom_list_pkey PRIMARY KEY (list_id);


--
-- Name: custom_list_students custom_list_students_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_students
    ADD CONSTRAINT custom_list_students_pkey PRIMARY KEY (list_id, student_id);


--
-- Name: edudesig edudesig_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.edudesig
    ADD CONSTRAINT edudesig_pkey PRIMARY KEY (desig_id);


--
-- Name: eduofficer eduofficer_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.eduofficer
    ADD CONSTRAINT eduofficer_pkey PRIMARY KEY (officer_id);


--
-- Name: event_master event_master_event_title_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_event_title_key UNIQUE (event_title);


--
-- Name: event_master event_master_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_pkey PRIMARY KEY (event_id);


--
-- Name: event_photos event_photos_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_photos
    ADD CONSTRAINT event_photos_pkey PRIMARY KEY (photo_id);


--
-- Name: event_reports event_reports_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_reports
    ADD CONSTRAINT event_reports_pkey PRIMARY KEY (report_id);


--
-- Name: event_type event_type_event_type_name_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_type
    ADD CONSTRAINT event_type_event_type_name_key UNIQUE (event_type_name);


--
-- Name: event_type event_type_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_type
    ADD CONSTRAINT event_type_pkey PRIMARY KEY (event_type_id);


--
-- Name: examination examination_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.examination
    ADD CONSTRAINT examination_pkey PRIMARY KEY (exam_id);


--
-- Name: field_master field_master_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.field_master
    ADD CONSTRAINT field_master_pkey PRIMARY KEY (field_id);


--
-- Name: hall_ticket_sequence hall_ticket_sequence_academic_year_juris_code_key; Type: CONSTRAINT; Schema: pp; Owner: postgres
--

ALTER TABLE ONLY pp.hall_ticket_sequence
    ADD CONSTRAINT hall_ticket_sequence_academic_year_juris_code_key UNIQUE (academic_year, juris_code);


--
-- Name: hall_ticket_sequence hall_ticket_sequence_pkey; Type: CONSTRAINT; Schema: pp; Owner: postgres
--

ALTER TABLE ONLY pp.hall_ticket_sequence
    ADD CONSTRAINT hall_ticket_sequence_pkey PRIMARY KEY (id);


--
-- Name: home_verification home_verification_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.home_verification
    ADD CONSTRAINT home_verification_pkey PRIMARY KEY (verification_id);


--
-- Name: institute institute_dise_code_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.institute
    ADD CONSTRAINT institute_dise_code_key UNIQUE (dise_code);


--
-- Name: institute institute_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.institute
    ADD CONSTRAINT institute_pkey PRIMARY KEY (institute_id);


--
-- Name: interviewer interviewer_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.interviewer
    ADD CONSTRAINT interviewer_pkey PRIMARY KEY (interviewer_id);


--
-- Name: jurisdiction jurisdiction_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.jurisdiction
    ADD CONSTRAINT jurisdiction_pkey PRIMARY KEY (juris_code);


--
-- Name: jurisdiction_type jurisdiction_type_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.jurisdiction_type
    ADD CONSTRAINT jurisdiction_type_pkey PRIMARY KEY (juris_type);


--
-- Name: officer_desig officer_desig_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.officer_desig
    ADD CONSTRAINT officer_desig_pkey PRIMARY KEY (officer_id, desig_id, juris_code);


--
-- Name: official_issue official_issue_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.official_issue
    ADD CONSTRAINT official_issue_pkey PRIMARY KEY (tab_id, user_id);


--
-- Name: applicant_exam pk_applicant_exam; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_exam
    ADD CONSTRAINT pk_applicant_exam PRIMARY KEY (applicant_id, exam_id);


--
-- Name: pp_exam_centre pp_exam_centre_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.pp_exam_centre
    ADD CONSTRAINT pp_exam_centre_pkey PRIMARY KEY (pp_exam_centre_id);


--
-- Name: pp_exam_centre pp_exam_centre_pp_exam_centre_code_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.pp_exam_centre
    ADD CONSTRAINT pp_exam_centre_pp_exam_centre_code_key UNIQUE (pp_exam_centre_code);


--
-- Name: rejection_reasons rejection_reasons_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.rejection_reasons
    ADD CONSTRAINT rejection_reasons_pkey PRIMARY KEY (rej_reason_id);


--
-- Name: role role_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.role
    ADD CONSTRAINT role_pkey PRIMARY KEY (role_id);


--
-- Name: role role_role_name_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.role
    ADD CONSTRAINT role_role_name_key UNIQUE (role_name);


--
-- Name: shortlist_batch_jurisdiction shortlist_batch_jurisdiction_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.shortlist_batch_jurisdiction
    ADD CONSTRAINT shortlist_batch_jurisdiction_pkey PRIMARY KEY (shortlist_batch_id, juris_code);


--
-- Name: shortlist_batch shortlist_batch_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.shortlist_batch
    ADD CONSTRAINT shortlist_batch_pkey PRIMARY KEY (shortlist_batch_id);


--
-- Name: shortlist_batch shortlist_batch_shortlist_batch_name_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.shortlist_batch
    ADD CONSTRAINT shortlist_batch_shortlist_batch_name_key UNIQUE (shortlist_batch_name);


--
-- Name: shortlist_criteria shortlist_criteria_criteria_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.shortlist_criteria
    ADD CONSTRAINT shortlist_criteria_criteria_key UNIQUE (criteria);


--
-- Name: shortlist_criteria shortlist_criteria_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.shortlist_criteria
    ADD CONSTRAINT shortlist_criteria_pkey PRIMARY KEY (criteria_id);


--
-- Name: sibling_education sibling_education_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.sibling_education
    ADD CONSTRAINT sibling_education_pkey PRIMARY KEY (sibling_id);


--
-- Name: std_applicant_primary_info std_applicant_primary_info_nmms_reg_number_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_nmms_reg_number_key UNIQUE (nmms_reg_number);


--
-- Name: std_applicant_primary_info std_applicant_primary_info_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_pkey PRIMARY KEY (applicant_id);


--
-- Name: stg_nmms_phase1_applications stg_nmms_phase1_applications_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.stg_nmms_phase1_applications
    ADD CONSTRAINT stg_nmms_phase1_applications_pkey PRIMARY KEY (id);


--
-- Name: stg_nmms_phase2_results stg_nmms_phase2_results_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.stg_nmms_phase2_results
    ADD CONSTRAINT stg_nmms_phase2_results_pkey PRIMARY KEY (result_stg_id);


--
-- Name: student_attendance student_attendance_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_pkey PRIMARY KEY (attendance_id);


--
-- Name: student_attendance student_attendance_session_id_student_id_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_session_id_student_id_key UNIQUE (session_id, student_id);


--
-- Name: student_interview student_interview_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_interview
    ADD CONSTRAINT student_interview_pkey PRIMARY KEY (interview_id);


--
-- Name: student_issue student_issue_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_issue
    ADD CONSTRAINT student_issue_pkey PRIMARY KEY (tab_id, student_id);


--
-- Name: student_list_fields student_list_fields_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_list_fields
    ADD CONSTRAINT student_list_fields_pkey PRIMARY KEY (list_id, field_id);


--
-- Name: student_list student_list_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_list
    ADD CONSTRAINT student_list_pkey PRIMARY KEY (list_id);


--
-- Name: student_lists student_lists_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_lists
    ADD CONSTRAINT student_lists_pkey PRIMARY KEY (list_id, student_id);


--
-- Name: student_master student_master_applicant_id_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_applicant_id_key UNIQUE (applicant_id);


--
-- Name: student_master student_master_enr_id_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_enr_id_key UNIQUE (enr_id);


--
-- Name: student_master student_master_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_pkey PRIMARY KEY (student_id);


--
-- Name: subject subject_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.subject
    ADD CONSTRAINT subject_pkey PRIMARY KEY (subject_id);


--
-- Name: subject subject_subject_name_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.subject
    ADD CONSTRAINT subject_subject_name_key UNIQUE (subject_name);


--
-- Name: system_config system_config_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.system_config
    ADD CONSTRAINT system_config_pkey PRIMARY KEY (system_config_id);


--
-- Name: tab_brand tab_brand_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_brand
    ADD CONSTRAINT tab_brand_pkey PRIMARY KEY (brand_id);


--
-- Name: tab_inventory tab_inventory_imei_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_imei_key UNIQUE (imei);


--
-- Name: tab_inventory tab_inventory_inventory_id_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_inventory_id_key UNIQUE (inventory_id);


--
-- Name: tab_inventory tab_inventory_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_pkey PRIMARY KEY (tab_id);


--
-- Name: tab_inventory tab_inventory_serial_number_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_serial_number_key UNIQUE (serial_number);


--
-- Name: teacher teacher_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.teacher
    ADD CONSTRAINT teacher_pkey PRIMARY KEY (teacher_id);


--
-- Name: teacher_subject teacher_subject_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.teacher_subject
    ADD CONSTRAINT teacher_subject_pkey PRIMARY KEY (teacher_id, subject_id, medium);


--
-- Name: teacher teacher_user_id_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.teacher
    ADD CONSTRAINT teacher_user_id_key UNIQUE (user_id);


--
-- Name: teaching_platform teaching_platform_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.teaching_platform
    ADD CONSTRAINT teaching_platform_pkey PRIMARY KEY (platform_id);


--
-- Name: teaching_platform teaching_platform_platform_name_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.teaching_platform
    ADD CONSTRAINT teaching_platform_platform_name_key UNIQUE (platform_name);


--
-- Name: time_table_solution time_table_solution_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.time_table_solution
    ADD CONSTRAINT time_table_solution_pkey PRIMARY KEY (solution_id);


--
-- Name: timetable timetable_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.timetable
    ADD CONSTRAINT timetable_pkey PRIMARY KEY (timetable_id);


--
-- Name: applicant_exam unique_hall_ticket; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_exam
    ADD CONSTRAINT unique_hall_ticket UNIQUE (pp_hall_ticket_no);


--
-- Name: user user_email_uk; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp."user"
    ADD CONSTRAINT user_email_uk UNIQUE (user_email);


--
-- Name: user user_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp."user"
    ADD CONSTRAINT user_pkey PRIMARY KEY (user_id);


--
-- Name: user_role user_role_pkey; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.user_role
    ADD CONSTRAINT user_role_pkey PRIMARY KEY (user_id, role_id);


--
-- Name: user user_user_name_key; Type: CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp."user"
    ADD CONSTRAINT user_user_name_key UNIQUE (user_name);


--
-- Name: account account_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.account
    ADD CONSTRAINT account_pkey PRIMARY KEY (accnum);


--
-- Name: customer customer_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.customer
    ADD CONSTRAINT customer_pkey PRIMARY KEY (cust_id);


--
-- Name: customeracc customeracc_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.customeracc
    ADD CONSTRAINT customeracc_pkey PRIMARY KEY (customeracc_id);


--
-- Name: deposittx deposittx_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.deposittx
    ADD CONSTRAINT deposittx_pkey PRIMARY KEY (txid);


--
-- Name: transact transact_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.transact
    ADD CONSTRAINT transact_pkey PRIMARY KEY (txid);


--
-- Name: transfertx transfertx_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.transfertx
    ADD CONSTRAINT transfertx_pkey PRIMARY KEY (txid);


--
-- Name: withdrawaltx withdrawaltx_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.withdrawaltx
    ADD CONSTRAINT withdrawaltx_pkey PRIMARY KEY (txid);


--
-- Name: idx_phase1_block_name; Type: INDEX; Schema: pp; Owner: pp_db_user1
--

CREATE INDEX idx_phase1_block_name ON pp.stg_nmms_phase1_applications USING btree (nmms_block, student_name);


--
-- Name: idx_phase1_fast_match; Type: INDEX; Schema: pp; Owner: pp_db_user1
--

CREATE INDEX idx_phase1_fast_match ON pp.stg_nmms_phase1_applications USING btree (nmms_year, district, nmms_block, student_name_key);


--
-- Name: idx_phase1_merge_lookup; Type: INDEX; Schema: pp; Owner: pp_db_user1
--

CREATE INDEX idx_phase1_merge_lookup ON pp.stg_nmms_phase1_applications USING btree (nmms_year, district, nmms_block, student_name_key);


--
-- Name: idx_phase2_block_name; Type: INDEX; Schema: pp; Owner: pp_db_user1
--

CREATE INDEX idx_phase2_block_name ON pp.stg_nmms_phase2_results USING btree (nmms_block, student_name);


--
-- Name: idx_phase2_fast_match; Type: INDEX; Schema: pp; Owner: pp_db_user1
--

CREATE INDEX idx_phase2_fast_match ON pp.stg_nmms_phase2_results USING btree (nmms_year, district, nmms_block, student_name_key, match_status);


--
-- Name: idx_phase2_match_status; Type: INDEX; Schema: pp; Owner: pp_db_user1
--

CREATE INDEX idx_phase2_match_status ON pp.stg_nmms_phase2_results USING btree (match_status);


--
-- Name: idx_phase2_merge_lookup; Type: INDEX; Schema: pp; Owner: pp_db_user1
--

CREATE INDEX idx_phase2_merge_lookup ON pp.stg_nmms_phase2_results USING btree (nmms_year, district, nmms_block, student_name_key, match_status);


--
-- Name: timetable trg_check_timetable_overlap; Type: TRIGGER; Schema: pp; Owner: pp_db_user1
--

CREATE TRIGGER trg_check_timetable_overlap BEFORE INSERT OR UPDATE ON pp.timetable FOR EACH ROW EXECUTE FUNCTION public.check_timetable_overlap();


--
-- Name: system_config trg_update_system_config_timestamp; Type: TRIGGER; Schema: pp; Owner: pp_db_user1
--

CREATE TRIGGER trg_update_system_config_timestamp BEFORE UPDATE ON pp.system_config FOR EACH ROW EXECUTE FUNCTION public.pp_update_timestamp();


--
-- Name: applicant_exam applicant_exam_applicant_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_exam
    ADD CONSTRAINT applicant_exam_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);


--
-- Name: applicant_exam_attendance applicant_exam_attendance_applicant_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_exam_attendance
    ADD CONSTRAINT applicant_exam_attendance_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);


--
-- Name: applicant_exam applicant_exam_exam_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_exam
    ADD CONSTRAINT applicant_exam_exam_id_fkey FOREIGN KEY (exam_id) REFERENCES pp.examination(exam_id);


--
-- Name: applicant_primary_info applicant_primary_info_app_state_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_app_state_fkey FOREIGN KEY (app_state) REFERENCES pp.jurisdiction(juris_code);


--
-- Name: applicant_primary_info applicant_primary_info_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: applicant_primary_info applicant_primary_info_current_institute_dise_code_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_current_institute_dise_code_fkey FOREIGN KEY (current_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;


--
-- Name: applicant_primary_info applicant_primary_info_district_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_district_fkey FOREIGN KEY (district) REFERENCES pp.jurisdiction(juris_code);


--
-- Name: applicant_primary_info applicant_primary_info_nmms_block_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_nmms_block_fkey FOREIGN KEY (nmms_block) REFERENCES pp.jurisdiction(juris_code);


--
-- Name: applicant_primary_info applicant_primary_info_previous_institute_dise_code_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_previous_institute_dise_code_fkey FOREIGN KEY (previous_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;


--
-- Name: applicant_primary_info applicant_primary_info_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: applicant_result applicant_result_applicant_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_result
    ADD CONSTRAINT applicant_result_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);


--
-- Name: applicant_result applicant_result_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_result
    ADD CONSTRAINT applicant_result_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: applicant_result applicant_result_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_result
    ADD CONSTRAINT applicant_result_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: applicant_secondary_info applicant_secondary_info_applicant_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_secondary_info
    ADD CONSTRAINT applicant_secondary_info_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id) ON DELETE CASCADE;


--
-- Name: applicant_secondary_info applicant_secondary_info_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_secondary_info
    ADD CONSTRAINT applicant_secondary_info_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: applicant_secondary_info applicant_secondary_info_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_secondary_info
    ADD CONSTRAINT applicant_secondary_info_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: applicant_shortlist_info applicant_shortlist_info_applicant_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_shortlist_info
    ADD CONSTRAINT applicant_shortlist_info_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);


--
-- Name: applicant_shortlist_info applicant_shortlist_info_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_shortlist_info
    ADD CONSTRAINT applicant_shortlist_info_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: applicant_shortlist_info applicant_shortlist_info_shortlist_batch_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_shortlist_info
    ADD CONSTRAINT applicant_shortlist_info_shortlist_batch_id_fkey FOREIGN KEY (shortlist_batch_id) REFERENCES pp.shortlist_batch(shortlist_batch_id) ON DELETE CASCADE;


--
-- Name: applicant_shortlist_info applicant_shortlist_info_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.applicant_shortlist_info
    ADD CONSTRAINT applicant_shortlist_info_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: batch batch_cohort_number_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.batch
    ADD CONSTRAINT batch_cohort_number_fkey FOREIGN KEY (cohort_number) REFERENCES pp.cohort(cohort_number) ON DELETE CASCADE;


--
-- Name: batch_coordinator_batches batch_coordinator_batches_batch_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.batch_coordinator_batches
    ADD CONSTRAINT batch_coordinator_batches_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES pp.batch(batch_id);


--
-- Name: batch_coordinator_batches batch_coordinator_batches_user_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.batch_coordinator_batches
    ADD CONSTRAINT batch_coordinator_batches_user_id_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id);


--
-- Name: batch batch_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.batch
    ADD CONSTRAINT batch_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: batch batch_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.batch
    ADD CONSTRAINT batch_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: tab_brand brand_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_brand
    ADD CONSTRAINT brand_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: tab_brand brand_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_brand
    ADD CONSTRAINT brand_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: class_session class_session_classroom_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_classroom_id_fkey FOREIGN KEY (classroom_id) REFERENCES pp.classroom(classroom_id);


--
-- Name: class_session class_session_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: class_session class_session_teacher_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES pp.teacher(teacher_id) ON DELETE SET NULL;


--
-- Name: class_session class_session_timetable_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_timetable_id_fkey FOREIGN KEY (timetable_id) REFERENCES pp.timetable(timetable_id) ON DELETE SET NULL;


--
-- Name: class_session class_session_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: classroom_batch classroom_batch_batch_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.classroom_batch
    ADD CONSTRAINT classroom_batch_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES pp.batch(batch_id) ON DELETE CASCADE;


--
-- Name: classroom_batch classroom_batch_classroom_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.classroom_batch
    ADD CONSTRAINT classroom_batch_classroom_id_fkey FOREIGN KEY (classroom_id) REFERENCES pp.classroom(classroom_id) ON DELETE CASCADE;


--
-- Name: classroom classroom_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: classroom classroom_platform_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_platform_id_fkey FOREIGN KEY (platform_id) REFERENCES pp.teaching_platform(platform_id) ON DELETE SET NULL;


--
-- Name: classroom classroom_subject_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_subject_id_fkey FOREIGN KEY (subject_id) REFERENCES pp.subject(subject_id) ON DELETE SET NULL;


--
-- Name: classroom classroom_teacher_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES pp.teacher(teacher_id) ON DELETE SET NULL;


--
-- Name: classroom classroom_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: cohort cohort_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.cohort
    ADD CONSTRAINT cohort_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: cohort cohort_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.cohort
    ADD CONSTRAINT cohort_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: custom_list_fields custom_list_fields_field_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_fields
    ADD CONSTRAINT custom_list_fields_field_id_fkey FOREIGN KEY (field_id) REFERENCES pp.field_master(field_id) ON DELETE RESTRICT;


--
-- Name: custom_list_fields custom_list_fields_list_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_fields
    ADD CONSTRAINT custom_list_fields_list_id_fkey FOREIGN KEY (list_id) REFERENCES pp.custom_list(list_id) ON DELETE CASCADE;


--
-- Name: custom_list_master custom_list_master_list_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_master
    ADD CONSTRAINT custom_list_master_list_id_fkey FOREIGN KEY (list_id) REFERENCES pp.custom_list_names(id) ON DELETE CASCADE;


--
-- Name: custom_list_master custom_list_master_student_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_master
    ADD CONSTRAINT custom_list_master_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id) ON DELETE CASCADE;


--
-- Name: custom_list_students custom_list_students_list_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_students
    ADD CONSTRAINT custom_list_students_list_id_fkey FOREIGN KEY (list_id) REFERENCES pp.custom_list(list_id) ON DELETE CASCADE;


--
-- Name: custom_list_students custom_list_students_student_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.custom_list_students
    ADD CONSTRAINT custom_list_students_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id) ON DELETE CASCADE;


--
-- Name: edudesig edudesig_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.edudesig
    ADD CONSTRAINT edudesig_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: edudesig edudesig_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.edudesig
    ADD CONSTRAINT edudesig_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: eduofficer eduofficer_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.eduofficer
    ADD CONSTRAINT eduofficer_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: eduofficer eduofficer_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.eduofficer
    ADD CONSTRAINT eduofficer_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: event_master event_master_block_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_block_fkey FOREIGN KEY (event_block) REFERENCES pp.jurisdiction(juris_code);


--
-- Name: event_master event_master_cohort_number_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_cohort_number_fkey FOREIGN KEY (cohort_number) REFERENCES pp.cohort(cohort_number);


--
-- Name: event_master event_master_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: event_master event_master_district_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_district_fkey FOREIGN KEY (event_district) REFERENCES pp.jurisdiction(juris_code);


--
-- Name: event_master event_master_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: event_photos event_photos_event_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_photos
    ADD CONSTRAINT event_photos_event_id_fkey FOREIGN KEY (event_id) REFERENCES pp.event_master(event_id) ON DELETE CASCADE;


--
-- Name: event_photos event_photos_uploaded_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_photos
    ADD CONSTRAINT event_photos_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES pp."user"(user_id);


--
-- Name: event_reports event_reports_event_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_reports
    ADD CONSTRAINT event_reports_event_id_fkey FOREIGN KEY (event_id) REFERENCES pp.event_master(event_id) ON DELETE CASCADE;


--
-- Name: event_reports event_reports_generated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_reports
    ADD CONSTRAINT event_reports_generated_by_fkey FOREIGN KEY (generated_by) REFERENCES pp."user"(user_id);


--
-- Name: exam_results exam_results_applicant_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.exam_results
    ADD CONSTRAINT exam_results_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);


--
-- Name: examination examination_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.examination
    ADD CONSTRAINT examination_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: examination examination_pp_exam_centre_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.examination
    ADD CONSTRAINT examination_pp_exam_centre_id_fkey FOREIGN KEY (pp_exam_centre_id) REFERENCES pp.pp_exam_centre(pp_exam_centre_id);


--
-- Name: examination examination_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.examination
    ADD CONSTRAINT examination_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: event_master fk_event_type; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT fk_event_type FOREIGN KEY (event_type_id) REFERENCES pp.event_type(event_type_id);


--
-- Name: home_verification home_verification_applicant_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.home_verification
    ADD CONSTRAINT home_verification_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);


--
-- Name: home_verification home_verification_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.home_verification
    ADD CONSTRAINT home_verification_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: home_verification home_verification_rejection_reason_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.home_verification
    ADD CONSTRAINT home_verification_rejection_reason_id_fkey FOREIGN KEY (rejection_reason_id) REFERENCES pp.rejection_reasons(rej_reason_id);


--
-- Name: home_verification home_verification_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.home_verification
    ADD CONSTRAINT home_verification_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: inactive_students inactive_students_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.inactive_students
    ADD CONSTRAINT inactive_students_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: inactive_students inactive_students_student_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.inactive_students
    ADD CONSTRAINT inactive_students_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id);


--
-- Name: inactive_students inactive_students_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.inactive_students
    ADD CONSTRAINT inactive_students_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: institute institute_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.institute
    ADD CONSTRAINT institute_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: institute institute_juris_code_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.institute
    ADD CONSTRAINT institute_juris_code_fkey FOREIGN KEY (juris_code) REFERENCES pp.jurisdiction(juris_code);


--
-- Name: institute_medium institute_medium_dise_code_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.institute_medium
    ADD CONSTRAINT institute_medium_dise_code_fkey FOREIGN KEY (dise_code) REFERENCES pp.institute(dise_code) ON DELETE CASCADE;


--
-- Name: institute institute_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.institute
    ADD CONSTRAINT institute_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: interviewer interviewer_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.interviewer
    ADD CONSTRAINT interviewer_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: interviewer interviewer_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.interviewer
    ADD CONSTRAINT interviewer_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: jurisdiction jurisdiction_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.jurisdiction
    ADD CONSTRAINT jurisdiction_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: jurisdiction jurisdiction_juris_type_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.jurisdiction
    ADD CONSTRAINT jurisdiction_juris_type_fkey FOREIGN KEY (juris_type) REFERENCES pp.jurisdiction_type(juris_type);


--
-- Name: jurisdiction jurisdiction_parent_juris_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.jurisdiction
    ADD CONSTRAINT jurisdiction_parent_juris_fkey FOREIGN KEY (parent_juris) REFERENCES pp.jurisdiction(juris_code);


--
-- Name: jurisdiction jurisdiction_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.jurisdiction
    ADD CONSTRAINT jurisdiction_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: officer_desig officer_desig_desig_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.officer_desig
    ADD CONSTRAINT officer_desig_desig_id_fkey FOREIGN KEY (desig_id) REFERENCES pp.edudesig(desig_id) ON DELETE CASCADE;


--
-- Name: officer_desig officer_desig_juris_code_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.officer_desig
    ADD CONSTRAINT officer_desig_juris_code_fkey FOREIGN KEY (juris_code) REFERENCES pp.jurisdiction(juris_code) ON DELETE CASCADE;


--
-- Name: officer_desig officer_desig_officer_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.officer_desig
    ADD CONSTRAINT officer_desig_officer_id_fkey FOREIGN KEY (officer_id) REFERENCES pp.eduofficer(officer_id) ON DELETE CASCADE;


--
-- Name: official_issue official_issue_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.official_issue
    ADD CONSTRAINT official_issue_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: official_issue official_issue_tab_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.official_issue
    ADD CONSTRAINT official_issue_tab_fkey FOREIGN KEY (tab_id) REFERENCES pp.tab_inventory(tab_id);


--
-- Name: official_issue official_issue_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.official_issue
    ADD CONSTRAINT official_issue_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: official_issue official_issue_user_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.official_issue
    ADD CONSTRAINT official_issue_user_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id);


--
-- Name: pp_exam_centre pp_exam_centre_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.pp_exam_centre
    ADD CONSTRAINT pp_exam_centre_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: pp_exam_centre pp_exam_centre_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.pp_exam_centre
    ADD CONSTRAINT pp_exam_centre_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: role role_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.role
    ADD CONSTRAINT role_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: role role_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.role
    ADD CONSTRAINT role_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: shortlist_batch shortlist_batch_criteria_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.shortlist_batch
    ADD CONSTRAINT shortlist_batch_criteria_id_fkey FOREIGN KEY (criteria_id) REFERENCES pp.shortlist_criteria(criteria_id) ON DELETE SET NULL;


--
-- Name: shortlist_batch_jurisdiction shortlist_batch_jurisdiction_juris_code_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.shortlist_batch_jurisdiction
    ADD CONSTRAINT shortlist_batch_jurisdiction_juris_code_fkey FOREIGN KEY (juris_code) REFERENCES pp.jurisdiction(juris_code) ON DELETE CASCADE;


--
-- Name: shortlist_batch_jurisdiction shortlist_batch_jurisdiction_shortlist_batch_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.shortlist_batch_jurisdiction
    ADD CONSTRAINT shortlist_batch_jurisdiction_shortlist_batch_id_fkey FOREIGN KEY (shortlist_batch_id) REFERENCES pp.shortlist_batch(shortlist_batch_id) ON DELETE CASCADE;


--
-- Name: shortlist_criteria shortlist_criteria_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.shortlist_criteria
    ADD CONSTRAINT shortlist_criteria_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: shortlist_criteria shortlist_criteria_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.shortlist_criteria
    ADD CONSTRAINT shortlist_criteria_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: sibling_education sibling_education_applicant_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.sibling_education
    ADD CONSTRAINT sibling_education_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id) ON DELETE CASCADE;


--
-- Name: sibling_education sibling_education_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.sibling_education
    ADD CONSTRAINT sibling_education_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: sibling_education sibling_education_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.sibling_education
    ADD CONSTRAINT sibling_education_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: std_applicant_primary_info std_applicant_primary_info_app_state_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_app_state_fkey FOREIGN KEY (app_state) REFERENCES pp.jurisdiction(juris_code);


--
-- Name: std_applicant_primary_info std_applicant_primary_info_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: std_applicant_primary_info std_applicant_primary_info_current_institute_dise_code_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_current_institute_dise_code_fkey FOREIGN KEY (current_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;


--
-- Name: std_applicant_primary_info std_applicant_primary_info_district_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_district_fkey FOREIGN KEY (district) REFERENCES pp.jurisdiction(juris_code);


--
-- Name: std_applicant_primary_info std_applicant_primary_info_nmms_block_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_nmms_block_fkey FOREIGN KEY (nmms_block) REFERENCES pp.jurisdiction(juris_code);


--
-- Name: std_applicant_primary_info std_applicant_primary_info_previous_institute_dise_code_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_previous_institute_dise_code_fkey FOREIGN KEY (previous_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;


--
-- Name: std_applicant_primary_info std_applicant_primary_info_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: student_attendance student_attendance_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: student_attendance student_attendance_session_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_session_id_fkey FOREIGN KEY (session_id) REFERENCES pp.class_session(session_id) ON DELETE CASCADE;


--
-- Name: student_attendance student_attendance_student_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id) ON DELETE CASCADE;


--
-- Name: student_attendance student_attendance_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: student_interview student_interview_applicant_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_interview
    ADD CONSTRAINT student_interview_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);


--
-- Name: student_interview student_interview_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_interview
    ADD CONSTRAINT student_interview_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: student_interview student_interview_interviewer_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_interview
    ADD CONSTRAINT student_interview_interviewer_id_fkey FOREIGN KEY (interviewer_id) REFERENCES pp.interviewer(interviewer_id);


--
-- Name: student_interview student_interview_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_interview
    ADD CONSTRAINT student_interview_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: student_issue student_issue_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_issue
    ADD CONSTRAINT student_issue_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: student_issue student_issue_student_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_issue
    ADD CONSTRAINT student_issue_student_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id);


--
-- Name: student_issue student_issue_tab_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_issue
    ADD CONSTRAINT student_issue_tab_fkey FOREIGN KEY (tab_id) REFERENCES pp.tab_inventory(tab_id);


--
-- Name: student_issue student_issue_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_issue
    ADD CONSTRAINT student_issue_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: student_list_fields student_list_fields_field_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_list_fields
    ADD CONSTRAINT student_list_fields_field_id_fkey FOREIGN KEY (field_id) REFERENCES pp.field_master(field_id) ON DELETE RESTRICT;


--
-- Name: student_list_fields student_list_fields_list_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_list_fields
    ADD CONSTRAINT student_list_fields_list_id_fkey FOREIGN KEY (list_id) REFERENCES pp.student_list(list_id) ON DELETE CASCADE;


--
-- Name: student_lists student_lists_list_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_lists
    ADD CONSTRAINT student_lists_list_id_fkey FOREIGN KEY (list_id) REFERENCES pp.student_list(list_id) ON DELETE CASCADE;


--
-- Name: student_lists student_lists_student_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_lists
    ADD CONSTRAINT student_lists_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id) ON DELETE CASCADE;


--
-- Name: student_master student_master_applicant_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);


--
-- Name: student_master student_master_batch_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES pp.batch(batch_id);


--
-- Name: student_master student_master_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: student_master student_master_current_institute_dise_code_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_current_institute_dise_code_fkey FOREIGN KEY (current_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;


--
-- Name: student_master student_master_previous_institute_dise_code_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_previous_institute_dise_code_fkey FOREIGN KEY (previous_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;


--
-- Name: student_master student_master_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: student_master student_master_user_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_user_id_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id);


--
-- Name: subject subject_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.subject
    ADD CONSTRAINT subject_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: subject subject_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.subject
    ADD CONSTRAINT subject_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: tab_inventory tab_inventory_brand_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_brand_fkey FOREIGN KEY (brand_id) REFERENCES pp.tab_brand(brand_id);


--
-- Name: tab_inventory tab_inventory_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: tab_inventory tab_inventory_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: teacher teacher_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.teacher
    ADD CONSTRAINT teacher_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: teacher_subject teacher_subject_subject_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.teacher_subject
    ADD CONSTRAINT teacher_subject_subject_id_fkey FOREIGN KEY (subject_id) REFERENCES pp.subject(subject_id) ON DELETE CASCADE;


--
-- Name: teacher_subject teacher_subject_teacher_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.teacher_subject
    ADD CONSTRAINT teacher_subject_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES pp.teacher(teacher_id) ON DELETE CASCADE;


--
-- Name: teacher teacher_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.teacher
    ADD CONSTRAINT teacher_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: teacher teacher_user_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.teacher
    ADD CONSTRAINT teacher_user_id_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id) ON DELETE CASCADE;


--
-- Name: time_table_solution time_table_solution_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.time_table_solution
    ADD CONSTRAINT time_table_solution_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: time_table_solution time_table_solution_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.time_table_solution
    ADD CONSTRAINT time_table_solution_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: timetable timetable_classroom_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.timetable
    ADD CONSTRAINT timetable_classroom_id_fkey FOREIGN KEY (classroom_id) REFERENCES pp.classroom(classroom_id);


--
-- Name: timetable timetable_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.timetable
    ADD CONSTRAINT timetable_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: timetable timetable_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.timetable
    ADD CONSTRAINT timetable_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: user user_created_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp."user"
    ADD CONSTRAINT user_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);


--
-- Name: user_role user_role_role_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.user_role
    ADD CONSTRAINT user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES pp.role(role_id) ON DELETE CASCADE;


--
-- Name: user_role user_role_user_id_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp.user_role
    ADD CONSTRAINT user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id) ON DELETE CASCADE;


--
-- Name: user user_updated_by_fkey; Type: FK CONSTRAINT; Schema: pp; Owner: pp_db_user1
--

ALTER TABLE ONLY pp."user"
    ADD CONSTRAINT user_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);


--
-- Name: customeracc customeracc_accnum_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.customeracc
    ADD CONSTRAINT customeracc_accnum_fkey FOREIGN KEY (accnum) REFERENCES public.account(accnum);


--
-- Name: customeracc customeracc_cust_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.customeracc
    ADD CONSTRAINT customeracc_cust_id_fkey FOREIGN KEY (cust_id) REFERENCES public.customer(cust_id);


--
-- Name: deposittx deposittx_accnum_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.deposittx
    ADD CONSTRAINT deposittx_accnum_fkey FOREIGN KEY (accnum) REFERENCES public.account(accnum);


--
-- Name: deposittx deposittx_txid_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.deposittx
    ADD CONSTRAINT deposittx_txid_fkey FOREIGN KEY (txid) REFERENCES public.transact(txid);


--
-- Name: transfertx transfertx_from_acc_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.transfertx
    ADD CONSTRAINT transfertx_from_acc_fkey FOREIGN KEY (from_acc) REFERENCES public.account(accnum);


--
-- Name: transfertx transfertx_to_acc_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.transfertx
    ADD CONSTRAINT transfertx_to_acc_fkey FOREIGN KEY (to_acc) REFERENCES public.account(accnum);


--
-- Name: transfertx transfertx_txid_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.transfertx
    ADD CONSTRAINT transfertx_txid_fkey FOREIGN KEY (txid) REFERENCES public.transact(txid);


--
-- Name: withdrawaltx withdrawaltx_accnum_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.withdrawaltx
    ADD CONSTRAINT withdrawaltx_accnum_fkey FOREIGN KEY (accnum) REFERENCES public.account(accnum);


--
-- Name: withdrawaltx withdrawaltx_txid_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.withdrawaltx
    ADD CONSTRAINT withdrawaltx_txid_fkey FOREIGN KEY (txid) REFERENCES public.transact(txid);


--
-- Name: SEQUENCE applicant_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.applicant_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE shortlist_info_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.shortlist_info_seq TO pp_db_user1;


--
-- Name: SEQUENCE attendance_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.attendance_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE batch_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.batch_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE class_session_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.class_session_seq TO pp_db_user1;


--
-- Name: SEQUENCE classroom_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.classroom_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE cohort_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.cohort_seq TO pp_db_user1;


--
-- Name: SEQUENCE criteria_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.criteria_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE desig_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.desig_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE officer_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.officer_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE evaluation_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.evaluation_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE event_master_event_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.event_master_event_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE event_photos_photo_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.event_photos_photo_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE event_reports_report_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.event_reports_report_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE examination_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.examination_seq TO pp_db_user1;


--
-- Name: SEQUENCE field_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.field_id_seq TO pp_db_user1;


--
-- Name: TABLE hall_ticket_sequence; Type: ACL; Schema: pp; Owner: postgres
--

GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,UPDATE ON TABLE pp.hall_ticket_sequence TO pp_db_user1;


--
-- Name: SEQUENCE hall_ticket_sequence_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.hall_ticket_sequence_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE verification_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.verification_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE institute_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.institute_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE institute_type_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.institute_type_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE interview_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.interview_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE interviewer_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.interviewer_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE jurisdiction_code_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.jurisdiction_code_seq TO pp_db_user1;


--
-- Name: SEQUENCE platform_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.platform_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE platform_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.platform_seq TO pp_db_user1;


--
-- Name: SEQUENCE pp_exam_centre_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.pp_exam_centre_seq TO pp_db_user1;


--
-- Name: SEQUENCE role_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.role_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE shortlist_batch_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.shortlist_batch_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE sibling_education_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.sibling_education_seq TO pp_db_user1;


--
-- Name: SEQUENCE student_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.student_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE student_list_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.student_list_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE subject_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.subject_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE system_config_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.system_config_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE tab_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.tab_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE teacher_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.teacher_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE timetable_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.timetable_id_seq TO pp_db_user1;


--
-- Name: SEQUENCE user_id_seq; Type: ACL; Schema: pp; Owner: postgres
--

GRANT ALL ON SEQUENCE pp.user_id_seq TO pp_db_user1;


--
-- Name: TABLE account; Type: ACL; Schema: public; Owner: postgres
--

GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,UPDATE ON TABLE public.account TO pp_db_user1;


--
-- Name: TABLE customer; Type: ACL; Schema: public; Owner: postgres
--

GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,UPDATE ON TABLE public.customer TO pp_db_user1;


--
-- Name: TABLE customeracc; Type: ACL; Schema: public; Owner: postgres
--

GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,UPDATE ON TABLE public.customeracc TO pp_db_user1;


--
-- Name: TABLE deposittx; Type: ACL; Schema: public; Owner: postgres
--

GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,UPDATE ON TABLE public.deposittx TO pp_db_user1;


--
-- Name: TABLE staging_excel_data; Type: ACL; Schema: public; Owner: postgres
--

GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,UPDATE ON TABLE public.staging_excel_data TO pp_db_user1;


--
-- Name: TABLE transact; Type: ACL; Schema: public; Owner: postgres
--

GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,UPDATE ON TABLE public.transact TO pp_db_user1;


--
-- Name: TABLE transfertx; Type: ACL; Schema: public; Owner: postgres
--

GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,UPDATE ON TABLE public.transfertx TO pp_db_user1;


--
-- Name: TABLE withdrawaltx; Type: ACL; Schema: public; Owner: postgres
--

GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,UPDATE ON TABLE public.withdrawaltx TO pp_db_user1;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,UPDATE ON TABLES TO pp_db_user1;


--
-- PostgreSQL database dump complete
--

\unrestrict t42AVZcBh8nizPOzWW5bolnmPVyCr39YzpsqSPZjNUqLb1L6QBXaIPqVNUoqguY

