const pool = require("../../config/db.js");

/* ===========================================================
   COMMON STUDENT SELECT
=========================================================== */
const STUDENT_SELECT = `
    sm.student_id,
    sm.applicant_id,
    sm.enr_id,
    sm.student_name,
    sm.gender,
    sm.father_name,
    sm.father_occupation,
    sm.mother_name,
    sm.mother_occupation,
    sm.student_email,
    sm.student_email_password,
    sm.parent_email,
    sm.contact_no1,
    sm.contact_no2,
    sm.home_address,
    sm.current_institute_dise_code,
    sm.previous_institute_dise_code,
    ci.institute_name AS current_institute,
    pi.institute_name AS previous_institute,
    sm.sim_name,
    sm.teacher_name,
    sm.teacher_mobile_number,
    sm.active_yn,
    sm.recharge_status,
    sm.sponsor,
    sm.photo_link,
    sm.batch_id,
    b.batch_name,
    c.cohort_number,
    c.cohort_name,
    ins.inactive_reason,
    sm.created_at,
    sm.updated_at
`;

/* ===========================================================
   GET ALL STUDENTS OF LOGGED-IN TEACHER
=========================================================== */

const getStudentsByTeacher = async (user_id) => {

    const sql = `
        SELECT DISTINCT
            ${STUDENT_SELECT}

        FROM pp.teacher t

        JOIN pp.classroom cr
            ON cr.teacher_id = t.teacher_id

        JOIN pp.classroom_batch cb
            ON cb.classroom_id = cr.classroom_id

        JOIN pp.batch b
            ON b.batch_id = cb.batch_id

        JOIN pp.cohort c
            ON c.cohort_number = b.cohort_number

        JOIN pp.student_master sm
            ON sm.batch_id = b.batch_id

        LEFT JOIN pp.institute ci
            ON ci.dise_code = sm.current_institute_dise_code

        LEFT JOIN pp.institute pi
            ON pi.dise_code = sm.previous_institute_dise_code

        LEFT JOIN pp.inactive_students ins
            ON ins.student_id = sm.student_id
           AND sm.active_yn='INACTIVE'

        WHERE t.user_id = $1

        ORDER BY
            c.cohort_number,
            b.batch_name,
            sm.student_name;
    `;

    // Fixed to use pool.query
    const { rows } = await pool.query(sql,[user_id]);

    return rows;
};

/* ===========================================================
   FILTER BY COHORT + BATCH
=========================================================== */

const getStudentsByTeacherBatch = async (
    user_id,
    cohortNumber,
    batchId
) => {

    const sql = `
        SELECT DISTINCT
            ${STUDENT_SELECT}

        FROM pp.teacher t

        JOIN pp.classroom cr
            ON cr.teacher_id=t.teacher_id

        JOIN pp.classroom_batch cb
            ON cb.classroom_id=cr.classroom_id

        JOIN pp.batch b
            ON b.batch_id=cb.batch_id

        JOIN pp.cohort c
            ON c.cohort_number=b.cohort_number

        JOIN pp.student_master sm
            ON sm.batch_id=b.batch_id

        LEFT JOIN pp.institute ci
            ON ci.dise_code=sm.current_institute_dise_code

        LEFT JOIN pp.institute pi
            ON pi.dise_code=sm.previous_institute_dise_code

        LEFT JOIN pp.inactive_students ins
            ON ins.student_id=sm.student_id
           AND sm.active_yn='INACTIVE'

        WHERE
            t.user_id=$1
            AND c.cohort_number=$2
            AND b.batch_id=$3

        ORDER BY sm.student_name;
    `;

    // Fixed to use pool.query
    const { rows } = await pool.query(sql,[
        user_id,
        cohortNumber,
        batchId
    ]);

    return rows;
};

/* ===========================================================
   INACTIVE HISTORY
=========================================================== */

const getInactiveHistoryByStudentId = async(student_id)=>{

    // Fixed to use pool.query
    const { rows } = await pool.query(
        `
        SELECT
            inactive_reason,
            inactive_date,
            created_by,
            updated_by
        FROM pp.inactive_students
        WHERE student_id=$1
        ORDER BY inactive_date DESC
        `,
        [student_id]
    );

    return rows;
};

// Fixed to use module.exports
module.exports = {
    getStudentsByTeacher,
    getStudentsByTeacherBatch,
    getInactiveHistoryByStudentId
};