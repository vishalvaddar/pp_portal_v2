const pool = require("../../config/db");

const getTeacherProfileByUserId = async (userId) => {
    const query = `
        SELECT 
            t.teacher_id,
            t.teacher_name,
            t.qualification,
            t.experience_yrs,
            t.doj,
            t.contact_no,
            u.user_name AS username,
            -- Combine all subjects and their mediums into a single string
            (
                SELECT string_agg(DISTINCT s.subject_name || ' (' || ts.medium || ')', ', ')
                FROM pp.teacher_subject ts
                JOIN pp.subject s ON ts.subject_id = s.subject_id
                WHERE ts.teacher_id = t.teacher_id
            ) AS subjects_taught,
            -- Combine all assigned classrooms into a single string
            (
                SELECT string_agg(DISTINCT c.classroom_name, ', ')
                FROM pp.classroom c
                WHERE c.teacher_id = t.teacher_id
            ) AS assigned_classrooms
        FROM pp.teacher t
        JOIN pp.user u ON t.user_id = u.user_id
        WHERE t.user_id = $1
    `;
    
    const { rows } = await pool.query(query, [userId]);
    return rows[0]; 
};

module.exports = {
    getTeacherProfileByUserId
};