const pool = require("../../config/db");

const getTimetableByBatchAndTeacher = async (batchId, userId) => {
    let query = `
        SELECT DISTINCT
            t.timetable_id,
            t.day_of_week,
            t.start_time,
            t.end_time,
            c.classroom_id,
            c.classroom_name,
            c.class_link,
            s.subject_name,
            s.subject_code,
            tch.teacher_name,
            -- FIXED: We must include the sorting calculation in the SELECT list when using DISTINCT
            CASE t.day_of_week 
                WHEN 'SUNDAY' THEN 1
                WHEN 'MONDAY' THEN 2
                WHEN 'TUESDAY' THEN 3
                WHEN 'WEDNESDAY' THEN 4
                WHEN 'THURSDAY' THEN 5
                WHEN 'FRIDAY' THEN 6
                WHEN 'SATURDAY' THEN 7
            END as day_order
        FROM pp.timetable t
        JOIN pp.classroom c ON t.classroom_id = c.classroom_id
        LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
        INNER JOIN pp.teacher tch ON c.teacher_id = tch.teacher_id
    `;
    
    let values = [];

    // If batchId is provided, filter by it. Otherwise, just filter by teacher.
    if (batchId) {
        query += ` JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id `;
        query += ` WHERE tch.user_id = $1 AND cb.batch_id = $2 `;
        values = [userId, batchId];
    } else {
        query += ` WHERE tch.user_id = $1 `;
        values = [userId];
    }

    // FIXED: Order by the alias we created in the SELECT list
    query += `
        ORDER BY 
            day_order,
            t.start_time;
    `;
    
    const { rows } = await pool.query(query, values);
    return rows;
};

module.exports = {
    getTimetableByBatchAndTeacher
};