// // // // const pool = require('../../config/db');

// // // // // Fetch all students in a specific cohort
// // // // const getStudentsByCohort = async (cohortNumber) => {
// // // //   const query = `
// // // //     SELECT 
// // // //       sm.student_id,
// // // //       sm.student_name,
// // // //       sm.enr_id,
// // // //       sm.contact_no1,
// // // //       sm.contact_no2,
// // // //       sm.parent_email,
// // // //       sm.student_email,
// // // //       sm.active_yn,
// // // //       b.batch_name,
// // // //       c.cohort_name,
// // // //       c.cohort_number
// // // //     FROM pp.student_master sm
// // // //     JOIN pp.batch b ON sm.batch_id = b.batch_id
// // // //     JOIN pp.cohort c ON b.cohort_number = c.cohort_number
// // // //     WHERE c.cohort_number = $1
// // // //     ORDER BY sm.student_id;
// // // //   `;
// // // //   const result = await pool.query(query, [cohortNumber]);
// // // //   return result.rows;
// // // // };

// // // const pool = require('../../config/db');

// // // const getStudentsByCohortAndBatch = async (cohortNumber, batchId) => {
// // //   const query = `
// // //     SELECT 
// // //       sm.student_id,
// // //       sm.student_name,
// // //       sm.enr_id,
// // //       sm.contact_no1,
// // //       sm.contact_no2,
// // //       sm.parent_email,
// // //       sm.student_email,
// // //       sm.active_yn,
// // //       b.batch_name,
// // //       c.cohort_name,
// // //       c.cohort_number
// // //     FROM pp.student_master sm
// // //     JOIN pp.batch b ON sm.batch_id = b.batch_id
// // //     JOIN pp.cohort c ON b.cohort_number = c.cohort_number
// // //     WHERE c.cohort_number = $1 AND b.batch_id = $2
// // //     ORDER BY sm.student_id;
// // //   `;
// // //   const result = await pool.query(query, [cohortNumber, batchId]);
// // //   return result.rows;
// // // };

// // // const getAllStudents = async () => {
// // //   const query = `
// // //     SELECT 
// // //       sm.student_id,
// // //       sm.student_name,
// // //       sm.enr_id,
// // //       sm.contact_no1,
// // //       sm.contact_no2,
// // //       sm.parent_email,
// // //       sm.student_email,
// // //       sm.active_yn,
// // //       b.batch_name,
// // //       c.cohort_name,
// // //       c.cohort_number
// // //     FROM pp.student_master sm
// // //     JOIN pp.batch b ON sm.batch_id = b.batch_id
// // //     JOIN pp.cohort c ON b.cohort_number = c.cohort_number
// // //     ORDER BY sm.student_id;
// // //   `;
// // //   const result = await pool.query(query);
// // //   return result.rows;
// // // };

// // // const getStudentsByCoordinator = async (user_id) => {
// // //   const query = `
// // //     SELECT 
// // //       sm.student_id,
// // //       sm.student_name,
// // //       sm.enr_id,
// // //       sm.contact_no1,
// // //       sm.contact_no2,
// // //       sm.parent_email,
// // //       sm.student_email,
// // //       sm.active_yn,
// // //       b.batch_name,
// // //       c.cohort_name,
// // //       c.cohort_number
// // //     FROM pp.student_master sm
// // //     JOIN pp.batch b ON sm.batch_id = b.batch_id
// // //     JOIN pp.cohort c ON b.cohort_number = c.cohort_number
// // //     JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
// // //     WHERE bcb.user_id = $1
// // //     ORDER BY sm.student_id;
// // //   `;
  
// // //   const { rows } = await pool.query(query, [user_id]);
// // //   return rows;
// // // };

 
// // // module.exports = { getStudentsByCohortAndBatch, getAllStudents , getStudentsByCoordinator };

// // const pool = require('../../config/db');

// // /* ===========================================================
// //    1) FETCH STUDENTS BY COHORT + BATCH
// //    =========================================================== */
// // const getStudentsByCohortAndBatch = async (cohortNumber, batchId) => {
// //   const query = `
// //     SELECT 
// //       sm.student_id,
// //       sm.student_name,
// //       sm.enr_id,
// //       sm.contact_no1,
// //       sm.contact_no2,
// //       sm.parent_email,
// //       sm.student_email,
// //       sm.active_yn,
// //       b.batch_name,
// //       c.cohort_name,
// //       c.cohort_number
// //     FROM pp.student_master sm
// //     JOIN pp.batch b ON sm.batch_id = b.batch_id
// //     JOIN pp.cohort c ON b.cohort_number = c.cohort_number
// //     WHERE c.cohort_number = $1 AND b.batch_id = $2
// //     ORDER BY sm.student_id;
// //   `;
// //   const result = await pool.query(query, [cohortNumber, batchId]);
// //   return result.rows;
// // };

// // /* ===========================================================
// //    2) FETCH STUDENTS ASSIGNED TO A COORDINATOR
// //    =========================================================== */
// // const getStudentsByCoordinator = async (user_id) => {
// //   const query = `
// //     SELECT 
// //       sm.student_id,
// //       sm.student_name,
// //       sm.enr_id,
// //       sm.contact_no1,
// //       sm.contact_no2,
// //       sm.parent_email,
// //       sm.student_email,
// //       sm.active_yn,
// //       b.batch_name,
// //       c.cohort_name,
// //       c.cohort_number
// //     FROM pp.student_master sm
// //     JOIN pp.batch b ON sm.batch_id = b.batch_id
// //     JOIN pp.cohort c ON b.cohort_number = c.cohort_number
// //     JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
// //     WHERE bcb.user_id = $1
// //     ORDER BY sm.student_id;
// //   `;
// //   const result = await pool.query(query, [user_id]);
// //   return result.rows;
// // };

// // /* ===========================================================
// //    3) UPDATE STUDENT (GENERAL EDIT)
// //    =========================================================== */
// // const updateStudentModel = async (id, payload) => {
// //   // Prevent updating inactive_reason inside main table
// //   if ("inactive_reason" in payload) {
// //     delete payload.inactive_reason;
// //   }

// //   // Normalize ACTIVE/INACTIVE so DB constraint does not break
// //   if (payload.active_yn) {
// //     payload.active_yn = payload.active_yn.toUpperCase();
// //   }

// //   const fields = Object.keys(payload)
// //     .map((k, i) => `${k} = $${i + 1}`)
// //     .join(", ");

// //   const values = Object.values(payload);

// //   const sql = `
// //     UPDATE pp.student_master
// //     SET ${fields}
// //     WHERE student_id = $${values.length + 1}
// //   `;

// //   await pool.query(sql, [...values, id]);
// // };

// // /* ===========================================================
// //    4) MARK STUDENT INACTIVE + STORE HISTORY
// //    =========================================================== */
// // const markStudentInactiveModel = async (student_id, reason, user_id) => {
// //   // Insert into inactive_students (history log)
// //   await pool.query(
// //     `
// //     INSERT INTO pp.inactive_students
// //       (student_id, inactive_reason, inactive_date, created_by, updated_by)
// //     VALUES
// //       ($1, $2, CURRENT_DATE, $3, $3)
// //     `,
// //     [student_id, reason, user_id]
// //   );

// //   // Update the master record (active_yn only)
// //   await pool.query(
// //     `
// //     UPDATE pp.student_master
// //     SET active_yn = 'INACTIVE'
// //     WHERE student_id = $1
// //     `,
// //     [student_id]
// //   );
// // };

// // /* ===========================================================
// //    5) FETCH INACTIVE HISTORY (LOGS)
// //    =========================================================== */
// // const getInactiveHistoryByStudentId = async (student_id) => {
// //   const sql = `
// //     SELECT 
// //       inactive_reason,
// //       inactive_date,
// //       created_by,
// //       updated_by
// //     FROM pp.inactive_students
// //     WHERE student_id = $1
// //     ORDER BY inactive_date DESC
// //   `;
// //   const result = await pool.query(sql, [student_id]);
// //   return result.rows;
// // };

// // module.exports = {
// //   getStudentsByCohortAndBatch,
// //   getStudentsByCoordinator,
// //   updateStudentModel,
// //   markStudentInactiveModel,
// //   getInactiveHistoryByStudentId,
// // };



// const pool = require("../../config/db");

// /* ===========================================================
//    COMMON STUDENT SELECT (USED EVERYWHERE)
//    =========================================================== */
// const STUDENT_SELECT = `
//   sm.student_id,
//   sm.applicant_id,
//   sm.enr_id,
//   sm.student_name,
//   sm.gender,

//   sm.father_name,
//   sm.father_occupation,
//   sm.mother_name,
//   sm.mother_occupation,

//   sm.student_email,
//   sm.student_email_password,
//   sm.parent_email,

//   sm.contact_no1,
//   sm.contact_no2,
//   sm.home_address,

//   sm.current_institute_dise_code AS current_institute,
//   sm.previous_institute_dise_code AS previous_institute,
//   sm.sim_name,

//   sm.teacher_name,
//   sm.teacher_mobile_number,

//   sm.active_yn,
//   sm.recharge_status,
//   sm.sponsor,

//   sm.photo_link,

//   sm.batch_id,
//   b.batch_name,

//   c.cohort_number,
//   c.cohort_name
// `;

// /* ===========================================================
//    1) FETCH STUDENTS BY COHORT + BATCH
//    =========================================================== */
// const getStudentsByCohortAndBatch = async (cohortNumber, batchId) => {
//   const sql = `
//     SELECT ${STUDENT_SELECT}
//     FROM pp.student_master sm
//     JOIN pp.batch b 
//       ON sm.batch_id = b.batch_id
//     JOIN pp.cohort c 
//       ON b.cohort_number = c.cohort_number
//     WHERE c.cohort_number = $1
//       AND b.batch_id = $2
//     ORDER BY sm.student_id;
//   `;

//   const { rows } = await pool.query(sql, [cohortNumber, batchId]);
//   return rows;
// };

// /* ===========================================================
//    2) FETCH STUDENTS ASSIGNED TO A COORDINATOR
//    =========================================================== */
// const getStudentsByCoordinator = async (user_id) => {
//   const sql = `
//     SELECT ${STUDENT_SELECT}
//     FROM pp.student_master sm
//     JOIN pp.batch b 
//       ON sm.batch_id = b.batch_id
//     JOIN pp.cohort c 
//       ON b.cohort_number = c.cohort_number
//     JOIN pp.batch_coordinator_batches bcb
//       ON b.batch_id = bcb.batch_id
//     WHERE bcb.user_id = $1
//     ORDER BY sm.student_id;
//   `;

//   const { rows } = await pool.query(sql, [user_id]);
//   return rows;
// };

// /* ===========================================================
//    3) UPDATE STUDENT (GENERAL EDIT)
//    =========================================================== */
// const updateStudentModel = async (student_id, payload) => {
//   // inactive_reason is stored in history table
//   if ("inactive_reason" in payload) {
//     delete payload.inactive_reason;
//   }

//   // Normalize ACTIVE / INACTIVE
//   if (payload.active_yn) {
//     payload.active_yn = payload.active_yn.toUpperCase();
//   }

//   const fields = Object.keys(payload)
//     .map((key, index) => `${key} = $${index + 1}`)
//     .join(", ");

//   const values = Object.values(payload);

//   const sql = `
//     UPDATE pp.student_master
//     SET ${fields},
//         updated_at = CURRENT_TIMESTAMP
//     WHERE student_id = $${values.length + 1};
//   `;

//   await pool.query(sql, [...values, student_id]);
// };

// /* ===========================================================
//    4) MARK STUDENT INACTIVE + LOG HISTORY
//    =========================================================== */
// const markStudentInactiveModel = async (student_id, reason, user_id) => {
//   // Log inactive history
//   await pool.query(
//     `
//     INSERT INTO pp.inactive_students
//       (student_id, inactive_reason, inactive_date, created_by, updated_by)
//     VALUES
//       ($1, $2, CURRENT_DATE, $3, $3);
//     `,
//     [student_id, reason, user_id]
//   );

//   // Update master record
//   await pool.query(
//     `
//     UPDATE pp.student_master
//     SET active_yn = 'INACTIVE',
//         updated_at = CURRENT_TIMESTAMP
//     WHERE student_id = $1;
//     `,
//     [student_id]
//   );
// };

// /* ===========================================================
//    5) FETCH INACTIVE HISTORY (FOR PROFILE VIEW)
//    =========================================================== */
// const getInactiveHistoryByStudentId = async (student_id) => {
//   const sql = `
//     SELECT
//       inactive_reason,
//       inactive_date,
//       created_by,
//       updated_by
//     FROM pp.inactive_students
//     WHERE student_id = $1
//     ORDER BY inactive_date DESC;
//   `;

//   const { rows } = await pool.query(sql, [student_id]);
//   return rows;
// };

// /* ===========================================================
//    EXPORTS
//    =========================================================== */
// module.exports = {
//   getStudentsByCohortAndBatch,
//   getStudentsByCoordinator,
//   updateStudentModel,
//   markStudentInactiveModel,
//   getInactiveHistoryByStudentId,
// };


const pool = require("../../config/db");

/* ===========================================================
   COMMON STUDENT SELECT (USED EVERYWHERE)
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

  /* keep DISE codes for edit */
  sm.current_institute_dise_code,
  sm.previous_institute_dise_code,

  /* institute names for display */
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
   1) FETCH STUDENTS BY COHORT + BATCH
   =========================================================== */
const getStudentsByCohortAndBatch = async (cohortNumber, batchId) => {
  const sql = `
    SELECT ${STUDENT_SELECT}
    FROM pp.student_master sm

    JOIN pp.batch b
      ON sm.batch_id = b.batch_id

    JOIN pp.cohort c
      ON b.cohort_number = c.cohort_number

    LEFT JOIN pp.institute ci
      ON ci.dise_code = sm.current_institute_dise_code

    LEFT JOIN pp.institute pi
      ON pi.dise_code = sm.previous_institute_dise_code

    LEFT JOIN pp.inactive_students ins
      ON ins.student_id = sm.student_id
     AND sm.active_yn = 'INACTIVE'

    WHERE c.cohort_number = $1
      AND b.batch_id = $2

    ORDER BY sm.student_name;
  `;

  const { rows } = await pool.query(sql, [cohortNumber, batchId]);
  return rows;
};

/* ===========================================================
   2) FETCH STUDENTS ASSIGNED TO A COORDINATOR
   =========================================================== */
const getStudentsByCoordinator = async (user_id) => {
  const sql = `
    SELECT ${STUDENT_SELECT}
    FROM pp.student_master sm

    JOIN pp.batch b
      ON sm.batch_id = b.batch_id

    JOIN pp.cohort c
      ON b.cohort_number = c.cohort_number

    JOIN pp.batch_coordinator_batches bcb
      ON b.batch_id = bcb.batch_id

    LEFT JOIN pp.institute ci
      ON ci.dise_code = sm.current_institute_dise_code

    LEFT JOIN pp.institute pi
      ON pi.dise_code = sm.previous_institute_dise_code

    LEFT JOIN pp.inactive_students ins
      ON ins.student_id = sm.student_id
     AND sm.active_yn = 'INACTIVE'

    WHERE bcb.user_id = $1

    ORDER BY sm.student_name;
  `;

  const { rows } = await pool.query(sql, [user_id]);
  return rows;
};

/* ===========================================================
   3) UPDATE STUDENT (GENERAL EDIT)
   =========================================================== */
const updateStudentModel = async (student_id, payload) => {
  // inactive_reason is stored only in history table
  if ("inactive_reason" in payload) {
    delete payload.inactive_reason;
  }

  // Normalize ACTIVE / INACTIVE
  if (payload.active_yn) {
    payload.active_yn = payload.active_yn.toUpperCase();
  }

  if (!Object.keys(payload).length) return;

  const fields = Object.keys(payload)
    .map((key, index) => `${key} = $${index + 1}`)
    .join(", ");

  const values = Object.values(payload);

  const sql = `
    UPDATE pp.student_master
    SET ${fields},
        updated_at = CURRENT_TIMESTAMP
    WHERE student_id = $${values.length + 1};
  `;

  await pool.query(sql, [...values, student_id]);
};

/* ===========================================================
   4) MARK STUDENT INACTIVE + LOG HISTORY
   =========================================================== */
const markStudentInactiveModel = async (student_id, reason, user_id) => {
  // Log inactive history
  await pool.query(
    `
    INSERT INTO pp.inactive_students
      (student_id, inactive_reason, inactive_date, created_by, updated_by)
    VALUES
      ($1, $2, CURRENT_DATE, $3, $3);
    `,
    [student_id, reason, user_id]
  );

  // Update master record
  await pool.query(
    `
    UPDATE pp.student_master
    SET active_yn = 'INACTIVE',
        updated_at = CURRENT_TIMESTAMP
    WHERE student_id = $1;
    `,
    [student_id]
  );
};

/* ===========================================================
   5) FETCH INACTIVE HISTORY (FOR PROFILE VIEW)
   =========================================================== */
const getInactiveHistoryByStudentId = async (student_id) => {
  const sql = `
    SELECT
      inactive_reason,
      inactive_date,
      created_by,
      updated_by
    FROM pp.inactive_students
    WHERE student_id = $1
    ORDER BY inactive_date DESC;
  `;

  const { rows } = await pool.query(sql, [student_id]);
  return rows;
};




// /* ===========================================================
//    ATTENDANCE ONLY: FETCH ACTIVE STUDENTS
//    =========================================================== */
// const getActiveStudentsForAttendance = async (cohortNumber, batchId, classroomId) => {
//   const sql = `
//     SELECT
//       sm.student_id,
//       sm.enr_id,
//       sm.student_name,
//       sm.father_name,
//       sm.contact_no1,
//       sm.student_email,
//       sm.batch_id
//     FROM pp.student_master sm
//     JOIN pp.batch b
//       ON sm.batch_id = b.batch_id
//     JOIN pp.cohort c
//       ON b.cohort_number = c.cohort_number
//     WHERE c.cohort_number = $1
//       AND b.batch_id = $2
//       AND sm.active_yn = 'ACTIVE'
//     ORDER BY sm.student_name;
//   `;

//   const { rows } = await pool.query(sql, [cohortNumber, batchId]);
//   return rows;
// };


const getActiveStudentsForAttendance = async (cohortNumber, batchId) => {
  const sql = `
    SELECT 
      sm.student_id, sm.enr_id, sm.student_name, 
      sm.contact_no1, sm.student_email, sm.batch_id, sm.active_yn
    FROM pp.student_master sm
    JOIN pp.batch b ON sm.batch_id = b.batch_id
    JOIN pp.cohort c ON b.cohort_number = c.cohort_number
    WHERE c.cohort_number = $1 
      AND b.batch_id = $2
      AND sm.active_yn = 'ACTIVE'  -- ✅ Strictly Active
    ORDER BY sm.student_name;
  `;
  const { rows } = await pool.query(sql, [cohortNumber, batchId]);
  return rows;
};

/* ===========================================================
   6) FETCH STUDENT PROFILE (FOR STUDENT LOGIN)
   =========================================================== */
const getStudentProfileByUserId = async (user_id) => {
  const sql = `
    SELECT ${STUDENT_SELECT}
    FROM pp.student_master sm

    JOIN pp.batch b
      ON sm.batch_id = b.batch_id

    JOIN pp.cohort c
      ON b.cohort_number = c.cohort_number

    LEFT JOIN pp.institute ci
      ON ci.dise_code = sm.current_institute_dise_code

    LEFT JOIN pp.institute pi
      ON pi.dise_code = sm.previous_institute_dise_code

    LEFT JOIN pp.inactive_students ins
      ON ins.student_id = sm.student_id
     AND sm.active_yn = 'INACTIVE'

    WHERE sm.user_id = $1
    LIMIT 1;
  `;

  const { rows } = await pool.query(sql, [user_id]);
  return rows[0];
};

const getStudentTimetableModel = async (batchId) => {
  const sql = `
    SELECT 
        tt.timetable_id,
        tt.day_of_week,
        tt.start_time,
        tt.end_time,
        c.classroom_name,
        c.class_link,
        s.subject_name,
        t.teacher_name,
        p.platform_name
    FROM pp.timetable tt
    JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
    JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
    LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
    LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
    LEFT JOIN pp.teaching_platform p ON c.platform_id = p.platform_id
    WHERE cb.batch_id = $1
    ORDER BY 
        CASE tt.day_of_week
            WHEN 'SUNDAY' THEN 1 WHEN 'MONDAY' THEN 2 WHEN 'TUESDAY' THEN 3 
            WHEN 'WEDNESDAY' THEN 4 WHEN 'THURSDAY' THEN 5 WHEN 'FRIDAY' THEN 6 
            WHEN 'SATURDAY' THEN 7 
        END, 
        tt.start_time ASC;
  `;
  const { rows } = await pool.query(sql, [batchId]);
  return rows;
};


/* ===========================================================
   🔥 PERFORMANCE HELPERS
=========================================================== */
const getStudentIdByUserId = async (user_id) => {
  const { rows } = await pool.query(
    `SELECT student_id FROM pp.student_master WHERE user_id = $1`,
    [user_id]
  );
  return rows[0]?.student_id;
};


const getStudentSummaryModel = async (user_id) => {

  const sql = `
    SELECT 
      COUNT(cs.session_id) AS total_classes,

      COUNT(sa.session_id) FILTER (
        WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
      ) AS attended_classes,

      ROUND(
        COUNT(sa.session_id) FILTER (
          WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
        )::numeric / NULLIF(COUNT(cs.session_id),0) * 100
      ,2) AS attendance_percent

    FROM pp.student_master sm
    JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
    JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
    JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id

    LEFT JOIN pp.student_attendance sa
      ON sa.session_id = cs.session_id
      AND sa.student_id = sm.student_id

    WHERE sm.user_id = $1
  `;

  const { rows } = await pool.query(sql, [user_id]);

  /* EXAM SCORE */
  const examSql = `
    SELECT er.pp_exam_score
    FROM pp.exam_results er
    JOIN pp.student_master sm 
      ON sm.applicant_id = er.applicant_id
    WHERE sm.user_id = $1
  `;
  const examRes = await pool.query(examSql, [user_id]);

  return {
    ...rows[0],
    exam_score: examRes.rows[0]?.pp_exam_score || "-"
  };
};


const getStudentSubjectPerformanceModel = async (user_id) => {

  const sql = `
    SELECT 
      subj.subject_name,

      COUNT(cs.session_id) AS total_classes,

      COUNT(sa.session_id) FILTER (
        WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
      ) AS attended_classes,

      ROUND(
        COUNT(sa.session_id) FILTER (
          WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
        )::numeric / NULLIF(COUNT(cs.session_id),0) * 100
      ,2) AS attendance_percent

    FROM pp.student_master sm
    JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
    JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
    JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
    JOIN pp.subject subj ON subj.subject_id = c.subject_id

    LEFT JOIN pp.student_attendance sa
      ON sa.session_id = cs.session_id
      AND sa.student_id = sm.student_id

    WHERE sm.user_id = $1

    GROUP BY subj.subject_name
    ORDER BY subj.subject_name;
  `;

  const { rows } = await pool.query(sql, [user_id]);
  return rows;
};

const getStudentMonthlyAttendanceModel = async (user_id) => {

  const sql = `
    SELECT 
      TO_CHAR(cs.session_date, 'YYYY-MM') AS month,

      ROUND(
        COUNT(sa.session_id) FILTER (
          WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
        )::numeric / COUNT(cs.session_id) * 100
      ,2) AS percent

    FROM pp.student_master sm
    JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
    JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
    JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id

    LEFT JOIN pp.student_attendance sa
      ON sa.session_id = cs.session_id
      AND sa.student_id = sm.student_id

    WHERE sm.user_id = $1

    GROUP BY month
    ORDER BY month;
  `;

  const { rows } = await pool.query(sql, [user_id]);
  return rows;
};


const getStudentWeeklyAttendanceModel = async (user_id) => {

  const sql = `
    SELECT 
      TO_CHAR(DATE_TRUNC('week', cs.session_date), 'YYYY-MM-DD') AS week_start,

      ROUND(
        COUNT(sa.session_id) FILTER (
          WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
        )::numeric / COUNT(cs.session_id) * 100
      ,2) AS percent

    FROM pp.student_master sm
    JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
    JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
    JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id

    LEFT JOIN pp.student_attendance sa
      ON sa.session_id = cs.session_id
      AND sa.student_id = sm.student_id

    WHERE sm.user_id = $1

    GROUP BY week_start
    ORDER BY week_start;
  `;

  const { rows } = await pool.query(sql, [user_id]);
  return rows;
};


const getStudentCustomAttendanceModel = async (user_id, fromDate, toDate) => {

  const sql = `
    SELECT 
      subj.subject_name,

      COUNT(cs.session_id) AS total_classes,

      COUNT(sa.session_id) FILTER (
        WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
      ) AS attended_classes,

      ROUND(
        COUNT(sa.session_id) FILTER (
          WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
        )::numeric / NULLIF(COUNT(cs.session_id),0) * 100
      ,2) AS attendance_percent

    FROM pp.student_master sm
    JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
    JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
    JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
    JOIN pp.subject subj ON subj.subject_id = c.subject_id

    LEFT JOIN pp.student_attendance sa
      ON sa.session_id = cs.session_id
      AND sa.student_id = sm.student_id

    WHERE sm.user_id = $1
      AND cs.session_date BETWEEN $2 AND $3

    GROUP BY subj.subject_name
    ORDER BY subj.subject_name;
  `;

  const { rows } = await pool.query(sql, [user_id, fromDate, toDate]);
  return rows;
};

/* ===========================================================
   EXPORTS
   =========================================================== */
module.exports = {
  getStudentsByCohortAndBatch,
  getStudentsByCoordinator,
  updateStudentModel,
  markStudentInactiveModel,
  getInactiveHistoryByStudentId,
  getActiveStudentsForAttendance,  
  getStudentProfileByUserId,    // ✅ NEW
  getStudentTimetableModel,

  getStudentSummaryModel,
  getStudentSubjectPerformanceModel,
  getStudentMonthlyAttendanceModel,
  getStudentWeeklyAttendanceModel,
  getStudentCustomAttendanceModel
};
