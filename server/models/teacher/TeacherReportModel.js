const pool = require("../../config/db.js");

const getMyClassReports = async (userId, fromDate, toDate) => {
    const query = `
        SELECT 
            cs.session_id,
            cs.session_date AS date,
            co.cohort_name,
            string_agg(DISTINCT b.batch_name, ', ') AS batch_name,
            c.classroom_name,
            s.subject_name,
            -- Check if any attendance records exist for this session
            EXISTS (
                SELECT 1 
                FROM pp.student_attendance sa 
                WHERE sa.session_id = cs.session_id
            ) AS attendance_marked
        FROM pp.class_session cs
        JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
        JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
        JOIN pp.subject s ON c.subject_id = s.subject_id
        JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
        JOIN pp.batch b ON cb.batch_id = b.batch_id
        JOIN pp.cohort co ON b.cohort_number = co.cohort_number
        WHERE t.user_id = $1
          AND cs.session_date >= $2 
          AND cs.session_date <= $3
        GROUP BY 
            cs.session_id,
            cs.session_date,
            co.cohort_name,
            c.classroom_name,
            s.subject_name
        ORDER BY cs.session_date ASC
    `;
    
    const { rows } = await pool.query(query, [userId, fromDate, toDate]);
    return rows;
};

module.exports = {
    getMyClassReports
};
