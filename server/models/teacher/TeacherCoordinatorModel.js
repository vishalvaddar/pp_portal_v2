const pool = require("../../config/db");

const getCoordinatorsForTeacher = async (userId) => {
    const query = `
        SELECT 
            u.user_id,
            u.full_name,
            u.user_email,
            u.contact_no,
            u.active_yn,
            -- Combine the names of the batches they share into a single string
            string_agg(DISTINCT b.batch_name, ', ') AS shared_batches
        FROM pp.teacher t
        JOIN pp.classroom cl ON t.teacher_id = cl.teacher_id
        JOIN pp.classroom_batch cb ON cl.classroom_id = cb.classroom_id
        JOIN pp.batch b ON cb.batch_id = b.batch_id
        JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
        JOIN pp.user u ON bcb.user_id = u.user_id
        WHERE t.user_id = $1 
          AND u.active_yn = 'Y' -- Only show active coordinators
        GROUP BY 
            u.user_id, 
            u.full_name, 
            u.user_email, 
            u.contact_no, 
            u.active_yn
        ORDER BY u.full_name ASC;
    `;
    
    const { rows } = await pool.query(query, [userId]);
    return rows;
};

module.exports = {
    getCoordinatorsForTeacher
};
