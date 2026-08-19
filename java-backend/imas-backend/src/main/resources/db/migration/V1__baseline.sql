CREATE SCHEMA IF NOT EXISTS pp;

CREATE TABLE pp.applicant_exam (
    applicant_id numeric(14,0) NOT NULL,
    exam_id numeric(14,0) NOT NULL,
    pp_hall_ticket_no character varying(20)
);

CREATE TABLE pp.applicant_exam_attendance (
    applicant_id numeric(14,0),
    pp_exam_appeared_yn character(1),
    CONSTRAINT applicant_exam_attendance_pp_exam_appeared_yn_check CHECK ((pp_exam_appeared_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);

CREATE TABLE pp.applicant_exam_attendance_csv (
    nmms_reg_number numeric(11,0) NOT NULL,
    pp_exam_appeared_yn character(1),
    CONSTRAINT applicant_exam_attendance_csv_pp_exam_appeared_yn_check CHECK ((pp_exam_appeared_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);

CREATE TABLE pp.applicant_exam_bak1 (
    id integer,
    exam_id integer,
    pp_hall_ticket_no character varying(20)
);

CREATE TABLE pp.applicant_exam_results_csv (
    nmms_reg_number numeric(11,0),
    pp_exam_score numeric(5,2),
    pp_exam_cleared character(1),
    interview_required_yn character(1),
    CONSTRAINT applicant_exam_results_csv_interview_required_yn_check CHECK ((interview_required_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT applicant_exam_results_csv_pp_exam_cleared_check CHECK ((pp_exam_cleared = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);

CREATE SEQUENCE pp.applicant_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE TABLE pp.applicant_result_csv (
    nmms_reg_number numeric(11,0),
    status character varying(20),
    remarks character varying(500)
);

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

CREATE TABLE pp.applicant_shortlist_csv (
    nmms_reg_number numeric(11,0) NOT NULL,
    shortlisted_yn character(1),
    CONSTRAINT applicant_shortlist_csv_shortlisted_yn_check CHECK ((shortlisted_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);

CREATE SEQUENCE pp.shortlist_info_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.attendance_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE pp.batch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE TABLE pp.batch_coordinator_batches (
    user_id numeric(8,0) NOT NULL,
    batch_id integer NOT NULL
);

CREATE SEQUENCE pp.class_session_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.classroom_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE TABLE pp.classroom_batch (
    classroom_id integer NOT NULL,
    batch_id integer NOT NULL
);

CREATE SEQUENCE pp.cohort_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.criteria_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE pp.custom_list_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE pp.custom_list (
    list_id numeric(10,0) DEFAULT nextval('pp.custom_list_id_seq'::regclass) NOT NULL,
    list_name character varying(200) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pp.custom_list_fields (
    list_id numeric(10,0) NOT NULL,
    field_id numeric(6,0) NOT NULL
);

CREATE TABLE pp.custom_list_master (
    id integer NOT NULL,
    list_id integer NOT NULL,
    student_id integer NOT NULL
);

CREATE SEQUENCE pp.custom_list_master_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pp.custom_list_master_id_seq OWNED BY pp.custom_list_master.id;

CREATE TABLE pp.custom_list_names (
    id integer NOT NULL,
    name character varying(100) NOT NULL
);

CREATE SEQUENCE pp.custom_list_names_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pp.custom_list_names_id_seq OWNED BY pp.custom_list_names.id;

CREATE TABLE pp.custom_list_students (
    list_id numeric(10,0) NOT NULL,
    student_id numeric(14,0) NOT NULL
);

CREATE SEQUENCE pp.desig_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE pp.edudesig (
    desig_id numeric(14,0) DEFAULT nextval('pp.desig_id_seq'::regclass) NOT NULL,
    desig_name character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);

CREATE SEQUENCE pp.officer_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.evaluation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE pp.event_master_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.event_photos_photo_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE pp.event_photos (
    photo_id integer DEFAULT nextval('pp.event_photos_photo_id_seq'::regclass) NOT NULL,
    event_id integer,
    file_path text NOT NULL,
    file_name character varying(100),
    uploaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    uploaded_by numeric(8,0)
);

CREATE SEQUENCE pp.event_reports_report_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE pp.event_reports (
    report_id integer DEFAULT nextval('pp.event_reports_report_id_seq'::regclass) NOT NULL,
    event_id integer,
    report_type character varying(50),
    file_path text NOT NULL,
    file_name character varying(150),
    generated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    generated_by numeric(8,0)
);

CREATE TABLE pp.event_type (
    event_type_id integer NOT NULL,
    event_type_name character varying(100) NOT NULL
);

CREATE SEQUENCE pp.event_type_event_type_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pp.event_type_event_type_id_seq OWNED BY pp.event_type.event_type_id;

CREATE TABLE pp.exam_results (
    applicant_id numeric(14,0),
    pp_exam_score numeric(3,0),
    pp_exam_cleared character(1),
    interview_required_yn character(1),
    CONSTRAINT exam_results_interview_required_yn_check CHECK ((interview_required_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT exam_results_pp_exam_cleared_check CHECK ((pp_exam_cleared = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);

CREATE SEQUENCE pp.examination_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.field_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE pp.field_master (
    field_id numeric(6,0) DEFAULT nextval('pp.field_id_seq'::regclass) NOT NULL,
    tab_name character varying(100) DEFAULT 'pp.student_master'::character varying NOT NULL,
    col_name character varying(100) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pp.hall_ticket_sequence (
    id integer NOT NULL,
    academic_year character varying(9) NOT NULL,
    juris_code character varying(20) NOT NULL,
    last_sequence integer DEFAULT 0 NOT NULL
);

CREATE SEQUENCE pp.hall_ticket_sequence_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pp.hall_ticket_sequence_id_seq OWNED BY pp.hall_ticket_sequence.id;

CREATE SEQUENCE pp.verification_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE TABLE pp.inactive_students (
    student_id numeric(14,0),
    inactive_reason character varying(200),
    inactive_date date,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);

CREATE SEQUENCE pp.institute_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE TABLE pp.institute_medium (
    dise_code character varying(15),
    medium character varying(10)
);

CREATE SEQUENCE pp.institute_type_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE pp.interview_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE pp.interviewer_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.jurisdiction_code_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE pp.jurisdiction_type (
    juris_type character varying(100) NOT NULL
);

CREATE TABLE pp.officer_desig (
    officer_id numeric(14,0) NOT NULL,
    juris_code numeric(12,0) NOT NULL,
    desig_id numeric(14,0) NOT NULL,
    other_desig character varying(100)
);

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

CREATE SEQUENCE pp.platform_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE pp.platform_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE pp.pp_exam_centre_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE TABLE pp.rejection_reasons (
    rej_reason_id numeric(4,0) NOT NULL,
    rejection_reason character varying(200) NOT NULL
);

CREATE SEQUENCE pp.role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.shortlist_batch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE TABLE pp.shortlist_batch_jurisdiction (
    shortlist_batch_id numeric(6,0) NOT NULL,
    juris_code numeric(12,0) NOT NULL
);

CREATE TABLE pp.shortlist_criteria (
    criteria_id numeric(3,0) DEFAULT nextval('pp.criteria_id_seq'::regclass) NOT NULL,
    criteria character varying(500),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);

CREATE SEQUENCE pp.sibling_education_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.stg_nmms_phase1_applications_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pp.stg_nmms_phase1_applications_id_seq OWNED BY pp.stg_nmms_phase1_applications.id;

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

CREATE SEQUENCE pp.stg_nmms_phase2_results_result_stg_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pp.stg_nmms_phase2_results_result_stg_id_seq OWNED BY pp.stg_nmms_phase2_results.result_stg_id;

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

CREATE SEQUENCE pp.student_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.student_list_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE pp.student_list (
    list_id numeric(10,0) DEFAULT nextval('pp.student_list_id_seq'::regclass) NOT NULL,
    list_name character varying(200) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pp.student_list_fields (
    list_id numeric(10,0) NOT NULL,
    field_id numeric(6,0) NOT NULL
);

CREATE TABLE pp.student_lists (
    list_id numeric(10,0) NOT NULL,
    student_id numeric(14,0) NOT NULL
);

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

CREATE SEQUENCE pp.subject_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE pp.subject (
    subject_id integer DEFAULT nextval('pp.subject_id_seq'::regclass) NOT NULL,
    subject_code character varying(5) NOT NULL,
    subject_name character varying(100) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);

CREATE SEQUENCE pp.system_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE pp.system_config (
    system_config_id integer DEFAULT nextval('pp.system_config_id_seq'::regclass) NOT NULL,
    academic_year character varying(9) NOT NULL,
    phase character varying(50) NOT NULL,
    is_active boolean DEFAULT true,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_academic_year_format CHECK (((academic_year)::text ~ '^[0-9]{4}-[0-9]{2,4}$'::text))
);

CREATE TABLE pp.tab_brand (
    brand_id integer NOT NULL,
    brand_name character varying(15) NOT NULL,
    model_name character varying(15) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);

CREATE SEQUENCE pp.tab_brand_brand_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pp.tab_brand_brand_id_seq OWNED BY pp.tab_brand.brand_id;

CREATE SEQUENCE pp.tab_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.teacher_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE TABLE pp.teacher_subject (
    teacher_id integer NOT NULL,
    subject_id integer NOT NULL,
    medium character varying(20) DEFAULT 'KANNADA'::character varying NOT NULL,
    CONSTRAINT teacher_subject_medium_check CHECK (((medium)::text = ANY (ARRAY[('ENGLISH'::character varying)::text, ('KANNADA'::character varying)::text, ('HINDI'::character varying)::text, ('MARATHI'::character varying)::text])))
);

CREATE TABLE pp.teaching_platform (
    platform_id integer DEFAULT nextval('pp.platform_id_seq'::regclass) NOT NULL,
    platform_name character varying(100) NOT NULL
);

CREATE TABLE pp.temp1 (
    nmms_reg_no numeric(14,0),
    pp_hall_ticket_no character varying(50)
);

CREATE TABLE pp.temp2 (
    nmms_reg_no character varying(50),
    applicant_id character varying(50)
);

CREATE TABLE pp.time_table_solution (
    solution_id bigint NOT NULL,
    solution_file_name character varying(255) NOT NULL,
    solution_file_ins_user_name character varying(255) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);

CREATE SEQUENCE pp.time_table_solution_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER TABLE pp.time_table_solution ALTER COLUMN solution_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME pp.time_table_solution_solution_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE SEQUENCE pp.timetable_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE SEQUENCE pp.user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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

CREATE TABLE pp.user_role (
    user_id numeric(8,0) NOT NULL,
    role_id numeric(4,0) NOT NULL
);

ALTER TABLE ONLY pp.custom_list_master ALTER COLUMN id SET DEFAULT nextval('pp.custom_list_master_id_seq'::regclass);

ALTER TABLE ONLY pp.custom_list_names ALTER COLUMN id SET DEFAULT nextval('pp.custom_list_names_id_seq'::regclass);

ALTER TABLE ONLY pp.event_type ALTER COLUMN event_type_id SET DEFAULT nextval('pp.event_type_event_type_id_seq'::regclass);

ALTER TABLE ONLY pp.hall_ticket_sequence ALTER COLUMN id SET DEFAULT nextval('pp.hall_ticket_sequence_id_seq'::regclass);

ALTER TABLE ONLY pp.stg_nmms_phase1_applications ALTER COLUMN id SET DEFAULT nextval('pp.stg_nmms_phase1_applications_id_seq'::regclass);

ALTER TABLE ONLY pp.stg_nmms_phase2_results ALTER COLUMN result_stg_id SET DEFAULT nextval('pp.stg_nmms_phase2_results_result_stg_id_seq'::regclass);

ALTER TABLE ONLY pp.tab_brand ALTER COLUMN brand_id SET DEFAULT nextval('pp.tab_brand_brand_id_seq'::regclass);

ALTER TABLE ONLY pp.applicant_exam_attendance_csv
    ADD CONSTRAINT applicant_exam_attendance_csv_pkey PRIMARY KEY (nmms_reg_number);

ALTER TABLE ONLY pp.applicant_primary_info_csv
    ADD CONSTRAINT applicant_primary_info_csv_pkey PRIMARY KEY (nmms_reg_number);

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_nmms_reg_number_key UNIQUE (nmms_reg_number);

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_pkey PRIMARY KEY (applicant_id);

ALTER TABLE ONLY pp.applicant_secondary_info
    ADD CONSTRAINT applicant_secondary_info_pkey PRIMARY KEY (applicant_id);

ALTER TABLE ONLY pp.applicant_shortlist_csv
    ADD CONSTRAINT applicant_shortlist_csv_pkey PRIMARY KEY (nmms_reg_number);

ALTER TABLE ONLY pp.applicant_shortlist_info
    ADD CONSTRAINT applicant_shortlist_info_pkey PRIMARY KEY (shortlist_info_id);

ALTER TABLE ONLY pp.batch
    ADD CONSTRAINT batch_cohort_number_batch_name_key UNIQUE (cohort_number, batch_name);

ALTER TABLE ONLY pp.batch_coordinator_batches
    ADD CONSTRAINT batch_coordinator_batches_pkey PRIMARY KEY (user_id, batch_id);

ALTER TABLE ONLY pp.batch
    ADD CONSTRAINT batch_pkey PRIMARY KEY (batch_id);

ALTER TABLE ONLY pp.tab_brand
    ADD CONSTRAINT brand_model_unique UNIQUE (brand_name, model_name);

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_classroom_id_session_date_start_time_end_time_key UNIQUE (classroom_id, session_date, start_time, end_time);

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_pkey PRIMARY KEY (session_id);

ALTER TABLE ONLY pp.classroom_batch
    ADD CONSTRAINT classroom_batch_pkey PRIMARY KEY (classroom_id, batch_id);

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_pkey PRIMARY KEY (classroom_id);

ALTER TABLE ONLY pp.cohort
    ADD CONSTRAINT cohort_cohort_name_key UNIQUE (cohort_name);

ALTER TABLE ONLY pp.cohort
    ADD CONSTRAINT cohort_pkey PRIMARY KEY (cohort_number);

ALTER TABLE ONLY pp.custom_list_fields
    ADD CONSTRAINT custom_list_fields_pkey PRIMARY KEY (list_id, field_id);

ALTER TABLE ONLY pp.custom_list_master
    ADD CONSTRAINT custom_list_master_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pp.custom_list_names
    ADD CONSTRAINT custom_list_names_name_key UNIQUE (name);

ALTER TABLE ONLY pp.custom_list_names
    ADD CONSTRAINT custom_list_names_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pp.custom_list
    ADD CONSTRAINT custom_list_pkey PRIMARY KEY (list_id);

ALTER TABLE ONLY pp.custom_list_students
    ADD CONSTRAINT custom_list_students_pkey PRIMARY KEY (list_id, student_id);

ALTER TABLE ONLY pp.edudesig
    ADD CONSTRAINT edudesig_pkey PRIMARY KEY (desig_id);

ALTER TABLE ONLY pp.eduofficer
    ADD CONSTRAINT eduofficer_pkey PRIMARY KEY (officer_id);

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_event_title_key UNIQUE (event_title);

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_pkey PRIMARY KEY (event_id);

ALTER TABLE ONLY pp.event_photos
    ADD CONSTRAINT event_photos_pkey PRIMARY KEY (photo_id);

ALTER TABLE ONLY pp.event_reports
    ADD CONSTRAINT event_reports_pkey PRIMARY KEY (report_id);

ALTER TABLE ONLY pp.event_type
    ADD CONSTRAINT event_type_event_type_name_key UNIQUE (event_type_name);

ALTER TABLE ONLY pp.event_type
    ADD CONSTRAINT event_type_pkey PRIMARY KEY (event_type_id);

ALTER TABLE ONLY pp.examination
    ADD CONSTRAINT examination_pkey PRIMARY KEY (exam_id);

ALTER TABLE ONLY pp.field_master
    ADD CONSTRAINT field_master_pkey PRIMARY KEY (field_id);

ALTER TABLE ONLY pp.hall_ticket_sequence
    ADD CONSTRAINT hall_ticket_sequence_academic_year_juris_code_key UNIQUE (academic_year, juris_code);

ALTER TABLE ONLY pp.hall_ticket_sequence
    ADD CONSTRAINT hall_ticket_sequence_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pp.home_verification
    ADD CONSTRAINT home_verification_pkey PRIMARY KEY (verification_id);

ALTER TABLE ONLY pp.institute
    ADD CONSTRAINT institute_dise_code_key UNIQUE (dise_code);

ALTER TABLE ONLY pp.institute
    ADD CONSTRAINT institute_pkey PRIMARY KEY (institute_id);

ALTER TABLE ONLY pp.interviewer
    ADD CONSTRAINT interviewer_pkey PRIMARY KEY (interviewer_id);

ALTER TABLE ONLY pp.jurisdiction
    ADD CONSTRAINT jurisdiction_pkey PRIMARY KEY (juris_code);

ALTER TABLE ONLY pp.jurisdiction_type
    ADD CONSTRAINT jurisdiction_type_pkey PRIMARY KEY (juris_type);

ALTER TABLE ONLY pp.officer_desig
    ADD CONSTRAINT officer_desig_pkey PRIMARY KEY (officer_id, desig_id, juris_code);

ALTER TABLE ONLY pp.official_issue
    ADD CONSTRAINT official_issue_pkey PRIMARY KEY (tab_id, user_id);

ALTER TABLE ONLY pp.applicant_exam
    ADD CONSTRAINT pk_applicant_exam PRIMARY KEY (applicant_id, exam_id);

ALTER TABLE ONLY pp.pp_exam_centre
    ADD CONSTRAINT pp_exam_centre_pkey PRIMARY KEY (pp_exam_centre_id);

ALTER TABLE ONLY pp.pp_exam_centre
    ADD CONSTRAINT pp_exam_centre_pp_exam_centre_code_key UNIQUE (pp_exam_centre_code);

ALTER TABLE ONLY pp.rejection_reasons
    ADD CONSTRAINT rejection_reasons_pkey PRIMARY KEY (rej_reason_id);

ALTER TABLE ONLY pp.role
    ADD CONSTRAINT role_pkey PRIMARY KEY (role_id);

ALTER TABLE ONLY pp.role
    ADD CONSTRAINT role_role_name_key UNIQUE (role_name);

ALTER TABLE ONLY pp.shortlist_batch_jurisdiction
    ADD CONSTRAINT shortlist_batch_jurisdiction_pkey PRIMARY KEY (shortlist_batch_id, juris_code);

ALTER TABLE ONLY pp.shortlist_batch
    ADD CONSTRAINT shortlist_batch_pkey PRIMARY KEY (shortlist_batch_id);

ALTER TABLE ONLY pp.shortlist_batch
    ADD CONSTRAINT shortlist_batch_shortlist_batch_name_key UNIQUE (shortlist_batch_name);

ALTER TABLE ONLY pp.shortlist_criteria
    ADD CONSTRAINT shortlist_criteria_criteria_key UNIQUE (criteria);

ALTER TABLE ONLY pp.shortlist_criteria
    ADD CONSTRAINT shortlist_criteria_pkey PRIMARY KEY (criteria_id);

ALTER TABLE ONLY pp.sibling_education
    ADD CONSTRAINT sibling_education_pkey PRIMARY KEY (sibling_id);

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_nmms_reg_number_key UNIQUE (nmms_reg_number);

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_pkey PRIMARY KEY (applicant_id);

ALTER TABLE ONLY pp.stg_nmms_phase1_applications
    ADD CONSTRAINT stg_nmms_phase1_applications_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pp.stg_nmms_phase2_results
    ADD CONSTRAINT stg_nmms_phase2_results_pkey PRIMARY KEY (result_stg_id);

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_pkey PRIMARY KEY (attendance_id);

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_session_id_student_id_key UNIQUE (session_id, student_id);

ALTER TABLE ONLY pp.student_interview
    ADD CONSTRAINT student_interview_pkey PRIMARY KEY (interview_id);

ALTER TABLE ONLY pp.student_issue
    ADD CONSTRAINT student_issue_pkey PRIMARY KEY (tab_id, student_id);

ALTER TABLE ONLY pp.student_list_fields
    ADD CONSTRAINT student_list_fields_pkey PRIMARY KEY (list_id, field_id);

ALTER TABLE ONLY pp.student_list
    ADD CONSTRAINT student_list_pkey PRIMARY KEY (list_id);

ALTER TABLE ONLY pp.student_lists
    ADD CONSTRAINT student_lists_pkey PRIMARY KEY (list_id, student_id);

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_applicant_id_key UNIQUE (applicant_id);

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_enr_id_key UNIQUE (enr_id);

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_pkey PRIMARY KEY (student_id);

ALTER TABLE ONLY pp.subject
    ADD CONSTRAINT subject_pkey PRIMARY KEY (subject_id);

ALTER TABLE ONLY pp.subject
    ADD CONSTRAINT subject_subject_name_key UNIQUE (subject_name);

ALTER TABLE ONLY pp.system_config
    ADD CONSTRAINT system_config_pkey PRIMARY KEY (system_config_id);

ALTER TABLE ONLY pp.tab_brand
    ADD CONSTRAINT tab_brand_pkey PRIMARY KEY (brand_id);

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_imei_key UNIQUE (imei);

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_inventory_id_key UNIQUE (inventory_id);

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_pkey PRIMARY KEY (tab_id);

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_serial_number_key UNIQUE (serial_number);

ALTER TABLE ONLY pp.teacher
    ADD CONSTRAINT teacher_pkey PRIMARY KEY (teacher_id);

ALTER TABLE ONLY pp.teacher_subject
    ADD CONSTRAINT teacher_subject_pkey PRIMARY KEY (teacher_id, subject_id, medium);

ALTER TABLE ONLY pp.teacher
    ADD CONSTRAINT teacher_user_id_key UNIQUE (user_id);

ALTER TABLE ONLY pp.teaching_platform
    ADD CONSTRAINT teaching_platform_pkey PRIMARY KEY (platform_id);

ALTER TABLE ONLY pp.teaching_platform
    ADD CONSTRAINT teaching_platform_platform_name_key UNIQUE (platform_name);

ALTER TABLE ONLY pp.time_table_solution
    ADD CONSTRAINT time_table_solution_pkey PRIMARY KEY (solution_id);

ALTER TABLE ONLY pp.timetable
    ADD CONSTRAINT timetable_pkey PRIMARY KEY (timetable_id);

ALTER TABLE ONLY pp.applicant_exam
    ADD CONSTRAINT unique_hall_ticket UNIQUE (pp_hall_ticket_no);

ALTER TABLE ONLY pp."user"
    ADD CONSTRAINT user_email_uk UNIQUE (user_email);

ALTER TABLE ONLY pp."user"
    ADD CONSTRAINT user_pkey PRIMARY KEY (user_id);

ALTER TABLE ONLY pp.user_role
    ADD CONSTRAINT user_role_pkey PRIMARY KEY (user_id, role_id);

ALTER TABLE ONLY pp."user"
    ADD CONSTRAINT user_user_name_key UNIQUE (user_name);

CREATE INDEX idx_phase1_block_name ON pp.stg_nmms_phase1_applications USING btree (nmms_block, student_name);

CREATE INDEX idx_phase1_fast_match ON pp.stg_nmms_phase1_applications USING btree (nmms_year, district, nmms_block, student_name_key);

CREATE INDEX idx_phase1_merge_lookup ON pp.stg_nmms_phase1_applications USING btree (nmms_year, district, nmms_block, student_name_key);

CREATE INDEX idx_phase2_block_name ON pp.stg_nmms_phase2_results USING btree (nmms_block, student_name);

CREATE INDEX idx_phase2_fast_match ON pp.stg_nmms_phase2_results USING btree (nmms_year, district, nmms_block, student_name_key, match_status);

CREATE INDEX idx_phase2_match_status ON pp.stg_nmms_phase2_results USING btree (match_status);

CREATE INDEX idx_phase2_merge_lookup ON pp.stg_nmms_phase2_results USING btree (nmms_year, district, nmms_block, student_name_key, match_status);

ALTER TABLE ONLY pp.applicant_exam
    ADD CONSTRAINT applicant_exam_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);

ALTER TABLE ONLY pp.applicant_exam_attendance
    ADD CONSTRAINT applicant_exam_attendance_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);

ALTER TABLE ONLY pp.applicant_exam
    ADD CONSTRAINT applicant_exam_exam_id_fkey FOREIGN KEY (exam_id) REFERENCES pp.examination(exam_id);

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_app_state_fkey FOREIGN KEY (app_state) REFERENCES pp.jurisdiction(juris_code);

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_current_institute_dise_code_fkey FOREIGN KEY (current_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_district_fkey FOREIGN KEY (district) REFERENCES pp.jurisdiction(juris_code);

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_nmms_block_fkey FOREIGN KEY (nmms_block) REFERENCES pp.jurisdiction(juris_code);

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_previous_institute_dise_code_fkey FOREIGN KEY (previous_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;

ALTER TABLE ONLY pp.applicant_primary_info
    ADD CONSTRAINT applicant_primary_info_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.applicant_result
    ADD CONSTRAINT applicant_result_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);

ALTER TABLE ONLY pp.applicant_result
    ADD CONSTRAINT applicant_result_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.applicant_result
    ADD CONSTRAINT applicant_result_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.applicant_secondary_info
    ADD CONSTRAINT applicant_secondary_info_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.applicant_secondary_info
    ADD CONSTRAINT applicant_secondary_info_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.applicant_secondary_info
    ADD CONSTRAINT applicant_secondary_info_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.applicant_shortlist_info
    ADD CONSTRAINT applicant_shortlist_info_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);

ALTER TABLE ONLY pp.applicant_shortlist_info
    ADD CONSTRAINT applicant_shortlist_info_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.applicant_shortlist_info
    ADD CONSTRAINT applicant_shortlist_info_shortlist_batch_id_fkey FOREIGN KEY (shortlist_batch_id) REFERENCES pp.shortlist_batch(shortlist_batch_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.applicant_shortlist_info
    ADD CONSTRAINT applicant_shortlist_info_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.batch
    ADD CONSTRAINT batch_cohort_number_fkey FOREIGN KEY (cohort_number) REFERENCES pp.cohort(cohort_number) ON DELETE CASCADE;

ALTER TABLE ONLY pp.batch_coordinator_batches
    ADD CONSTRAINT batch_coordinator_batches_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES pp.batch(batch_id);

ALTER TABLE ONLY pp.batch_coordinator_batches
    ADD CONSTRAINT batch_coordinator_batches_user_id_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.batch
    ADD CONSTRAINT batch_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.batch
    ADD CONSTRAINT batch_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.tab_brand
    ADD CONSTRAINT brand_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.tab_brand
    ADD CONSTRAINT brand_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_classroom_id_fkey FOREIGN KEY (classroom_id) REFERENCES pp.classroom(classroom_id);

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES pp.teacher(teacher_id) ON DELETE SET NULL;

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_timetable_id_fkey FOREIGN KEY (timetable_id) REFERENCES pp.timetable(timetable_id) ON DELETE SET NULL;

ALTER TABLE ONLY pp.class_session
    ADD CONSTRAINT class_session_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.classroom_batch
    ADD CONSTRAINT classroom_batch_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES pp.batch(batch_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.classroom_batch
    ADD CONSTRAINT classroom_batch_classroom_id_fkey FOREIGN KEY (classroom_id) REFERENCES pp.classroom(classroom_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_platform_id_fkey FOREIGN KEY (platform_id) REFERENCES pp.teaching_platform(platform_id) ON DELETE SET NULL;

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_subject_id_fkey FOREIGN KEY (subject_id) REFERENCES pp.subject(subject_id) ON DELETE SET NULL;

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES pp.teacher(teacher_id) ON DELETE SET NULL;

ALTER TABLE ONLY pp.classroom
    ADD CONSTRAINT classroom_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.cohort
    ADD CONSTRAINT cohort_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.cohort
    ADD CONSTRAINT cohort_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.custom_list_fields
    ADD CONSTRAINT custom_list_fields_field_id_fkey FOREIGN KEY (field_id) REFERENCES pp.field_master(field_id) ON DELETE RESTRICT;

ALTER TABLE ONLY pp.custom_list_fields
    ADD CONSTRAINT custom_list_fields_list_id_fkey FOREIGN KEY (list_id) REFERENCES pp.custom_list(list_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.custom_list_master
    ADD CONSTRAINT custom_list_master_list_id_fkey FOREIGN KEY (list_id) REFERENCES pp.custom_list_names(id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.custom_list_master
    ADD CONSTRAINT custom_list_master_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.custom_list_students
    ADD CONSTRAINT custom_list_students_list_id_fkey FOREIGN KEY (list_id) REFERENCES pp.custom_list(list_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.custom_list_students
    ADD CONSTRAINT custom_list_students_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.edudesig
    ADD CONSTRAINT edudesig_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.edudesig
    ADD CONSTRAINT edudesig_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.eduofficer
    ADD CONSTRAINT eduofficer_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.eduofficer
    ADD CONSTRAINT eduofficer_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_block_fkey FOREIGN KEY (event_block) REFERENCES pp.jurisdiction(juris_code);

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_cohort_number_fkey FOREIGN KEY (cohort_number) REFERENCES pp.cohort(cohort_number);

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_district_fkey FOREIGN KEY (event_district) REFERENCES pp.jurisdiction(juris_code);

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT event_master_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.event_photos
    ADD CONSTRAINT event_photos_event_id_fkey FOREIGN KEY (event_id) REFERENCES pp.event_master(event_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.event_photos
    ADD CONSTRAINT event_photos_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.event_reports
    ADD CONSTRAINT event_reports_event_id_fkey FOREIGN KEY (event_id) REFERENCES pp.event_master(event_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.event_reports
    ADD CONSTRAINT event_reports_generated_by_fkey FOREIGN KEY (generated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.exam_results
    ADD CONSTRAINT exam_results_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);

ALTER TABLE ONLY pp.examination
    ADD CONSTRAINT examination_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.examination
    ADD CONSTRAINT examination_pp_exam_centre_id_fkey FOREIGN KEY (pp_exam_centre_id) REFERENCES pp.pp_exam_centre(pp_exam_centre_id);

ALTER TABLE ONLY pp.examination
    ADD CONSTRAINT examination_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.event_master
    ADD CONSTRAINT fk_event_type FOREIGN KEY (event_type_id) REFERENCES pp.event_type(event_type_id);

ALTER TABLE ONLY pp.home_verification
    ADD CONSTRAINT home_verification_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);

ALTER TABLE ONLY pp.home_verification
    ADD CONSTRAINT home_verification_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.home_verification
    ADD CONSTRAINT home_verification_rejection_reason_id_fkey FOREIGN KEY (rejection_reason_id) REFERENCES pp.rejection_reasons(rej_reason_id);

ALTER TABLE ONLY pp.home_verification
    ADD CONSTRAINT home_verification_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.inactive_students
    ADD CONSTRAINT inactive_students_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.inactive_students
    ADD CONSTRAINT inactive_students_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id);

ALTER TABLE ONLY pp.inactive_students
    ADD CONSTRAINT inactive_students_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.institute
    ADD CONSTRAINT institute_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.institute
    ADD CONSTRAINT institute_juris_code_fkey FOREIGN KEY (juris_code) REFERENCES pp.jurisdiction(juris_code);

ALTER TABLE ONLY pp.institute_medium
    ADD CONSTRAINT institute_medium_dise_code_fkey FOREIGN KEY (dise_code) REFERENCES pp.institute(dise_code) ON DELETE CASCADE;

ALTER TABLE ONLY pp.institute
    ADD CONSTRAINT institute_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.interviewer
    ADD CONSTRAINT interviewer_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.interviewer
    ADD CONSTRAINT interviewer_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.jurisdiction
    ADD CONSTRAINT jurisdiction_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.jurisdiction
    ADD CONSTRAINT jurisdiction_juris_type_fkey FOREIGN KEY (juris_type) REFERENCES pp.jurisdiction_type(juris_type);

ALTER TABLE ONLY pp.jurisdiction
    ADD CONSTRAINT jurisdiction_parent_juris_fkey FOREIGN KEY (parent_juris) REFERENCES pp.jurisdiction(juris_code);

ALTER TABLE ONLY pp.jurisdiction
    ADD CONSTRAINT jurisdiction_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.officer_desig
    ADD CONSTRAINT officer_desig_desig_id_fkey FOREIGN KEY (desig_id) REFERENCES pp.edudesig(desig_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.officer_desig
    ADD CONSTRAINT officer_desig_juris_code_fkey FOREIGN KEY (juris_code) REFERENCES pp.jurisdiction(juris_code) ON DELETE CASCADE;

ALTER TABLE ONLY pp.officer_desig
    ADD CONSTRAINT officer_desig_officer_id_fkey FOREIGN KEY (officer_id) REFERENCES pp.eduofficer(officer_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.official_issue
    ADD CONSTRAINT official_issue_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.official_issue
    ADD CONSTRAINT official_issue_tab_fkey FOREIGN KEY (tab_id) REFERENCES pp.tab_inventory(tab_id);

ALTER TABLE ONLY pp.official_issue
    ADD CONSTRAINT official_issue_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.official_issue
    ADD CONSTRAINT official_issue_user_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.pp_exam_centre
    ADD CONSTRAINT pp_exam_centre_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.pp_exam_centre
    ADD CONSTRAINT pp_exam_centre_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.role
    ADD CONSTRAINT role_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.role
    ADD CONSTRAINT role_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.shortlist_batch
    ADD CONSTRAINT shortlist_batch_criteria_id_fkey FOREIGN KEY (criteria_id) REFERENCES pp.shortlist_criteria(criteria_id) ON DELETE SET NULL;

ALTER TABLE ONLY pp.shortlist_batch_jurisdiction
    ADD CONSTRAINT shortlist_batch_jurisdiction_juris_code_fkey FOREIGN KEY (juris_code) REFERENCES pp.jurisdiction(juris_code) ON DELETE CASCADE;

ALTER TABLE ONLY pp.shortlist_batch_jurisdiction
    ADD CONSTRAINT shortlist_batch_jurisdiction_shortlist_batch_id_fkey FOREIGN KEY (shortlist_batch_id) REFERENCES pp.shortlist_batch(shortlist_batch_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.shortlist_criteria
    ADD CONSTRAINT shortlist_criteria_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.shortlist_criteria
    ADD CONSTRAINT shortlist_criteria_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.sibling_education
    ADD CONSTRAINT sibling_education_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.sibling_education
    ADD CONSTRAINT sibling_education_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.sibling_education
    ADD CONSTRAINT sibling_education_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_app_state_fkey FOREIGN KEY (app_state) REFERENCES pp.jurisdiction(juris_code);

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_current_institute_dise_code_fkey FOREIGN KEY (current_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_district_fkey FOREIGN KEY (district) REFERENCES pp.jurisdiction(juris_code);

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_nmms_block_fkey FOREIGN KEY (nmms_block) REFERENCES pp.jurisdiction(juris_code);

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_previous_institute_dise_code_fkey FOREIGN KEY (previous_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;

ALTER TABLE ONLY pp.std_applicant_primary_info
    ADD CONSTRAINT std_applicant_primary_info_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_session_id_fkey FOREIGN KEY (session_id) REFERENCES pp.class_session(session_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.student_attendance
    ADD CONSTRAINT student_attendance_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.student_interview
    ADD CONSTRAINT student_interview_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);

ALTER TABLE ONLY pp.student_interview
    ADD CONSTRAINT student_interview_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.student_interview
    ADD CONSTRAINT student_interview_interviewer_id_fkey FOREIGN KEY (interviewer_id) REFERENCES pp.interviewer(interviewer_id);

ALTER TABLE ONLY pp.student_interview
    ADD CONSTRAINT student_interview_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.student_issue
    ADD CONSTRAINT student_issue_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.student_issue
    ADD CONSTRAINT student_issue_student_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id);

ALTER TABLE ONLY pp.student_issue
    ADD CONSTRAINT student_issue_tab_fkey FOREIGN KEY (tab_id) REFERENCES pp.tab_inventory(tab_id);

ALTER TABLE ONLY pp.student_issue
    ADD CONSTRAINT student_issue_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.student_list_fields
    ADD CONSTRAINT student_list_fields_field_id_fkey FOREIGN KEY (field_id) REFERENCES pp.field_master(field_id) ON DELETE RESTRICT;

ALTER TABLE ONLY pp.student_list_fields
    ADD CONSTRAINT student_list_fields_list_id_fkey FOREIGN KEY (list_id) REFERENCES pp.student_list(list_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.student_lists
    ADD CONSTRAINT student_lists_list_id_fkey FOREIGN KEY (list_id) REFERENCES pp.student_list(list_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.student_lists
    ADD CONSTRAINT student_lists_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_applicant_id_fkey FOREIGN KEY (applicant_id) REFERENCES pp.applicant_primary_info(applicant_id);

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES pp.batch(batch_id);

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_current_institute_dise_code_fkey FOREIGN KEY (current_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_previous_institute_dise_code_fkey FOREIGN KEY (previous_institute_dise_code) REFERENCES pp.institute(dise_code) ON DELETE SET NULL;

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.student_master
    ADD CONSTRAINT student_master_user_id_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.subject
    ADD CONSTRAINT subject_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.subject
    ADD CONSTRAINT subject_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_brand_fkey FOREIGN KEY (brand_id) REFERENCES pp.tab_brand(brand_id);

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.tab_inventory
    ADD CONSTRAINT tab_inventory_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.teacher
    ADD CONSTRAINT teacher_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.teacher_subject
    ADD CONSTRAINT teacher_subject_subject_id_fkey FOREIGN KEY (subject_id) REFERENCES pp.subject(subject_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.teacher_subject
    ADD CONSTRAINT teacher_subject_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES pp.teacher(teacher_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.teacher
    ADD CONSTRAINT teacher_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.teacher
    ADD CONSTRAINT teacher_user_id_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.time_table_solution
    ADD CONSTRAINT time_table_solution_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.time_table_solution
    ADD CONSTRAINT time_table_solution_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.timetable
    ADD CONSTRAINT timetable_classroom_id_fkey FOREIGN KEY (classroom_id) REFERENCES pp.classroom(classroom_id);

ALTER TABLE ONLY pp.timetable
    ADD CONSTRAINT timetable_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.timetable
    ADD CONSTRAINT timetable_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp."user"
    ADD CONSTRAINT user_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id);

ALTER TABLE ONLY pp.user_role
    ADD CONSTRAINT user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES pp.role(role_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp.user_role
    ADD CONSTRAINT user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id) ON DELETE CASCADE;

ALTER TABLE ONLY pp."user"
    ADD CONSTRAINT user_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id);
