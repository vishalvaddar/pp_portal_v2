const pool = require("../../config/db");

const getTeacherDashboardStats = async (userId) => {
    // 1. Get total classes conducted and overall average attendance
    const statsQuery = `
        SELECT 
            COUNT(DISTINCT cs.session_id) as total_conducted,
            COALESCE(ROUND(AVG(sa.attendance_percent), 2), 0) as avg_attendance
        FROM pp.teacher t
        LEFT JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
        LEFT JOIN pp.student_attendance sa ON cs.session_id = sa.session_id
        WHERE t.user_id = $1
    `;

    // 2. Get subject-wise breakdown
    const subjectQuery = `
        SELECT 
            s.subject_name,
            COUNT(cs.session_id) as classes_taken
        FROM pp.teacher t
        JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
        JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
        JOIN pp.subject s ON c.subject_id = s.subject_id
        WHERE t.user_id = $1
        GROUP BY s.subject_name
        ORDER BY classes_taken DESC
    `;

    // 3. Get month-wise trend (Last 6 months)
    const monthQuery = `
        SELECT 
            TO_CHAR(cs.session_date, 'Mon YYYY') as month_label,
            COUNT(cs.session_id) as classes_taken
        FROM pp.teacher t
        JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
        WHERE t.user_id = $1
        GROUP BY TO_CHAR(cs.session_date, 'Mon YYYY'), DATE_TRUNC('month', cs.session_date)
        ORDER BY DATE_TRUNC('month', cs.session_date) ASC
        LIMIT 6
    `;

    // 4. Get active batches count
    const batchQuery = `
        SELECT COUNT(DISTINCT cb.batch_id) as total_batches
        FROM pp.teacher t
        JOIN pp.classroom c ON t.teacher_id = c.teacher_id
        JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
        WHERE t.user_id = $1
    `;

    // Execute all queries concurrently for maximum performance
    const [statsRes, subjectRes, monthRes, batchRes] = await Promise.all([
        pool.query(statsQuery, [userId]),
        pool.query(subjectQuery, [userId]),
        pool.query(monthQuery, [userId]),
        pool.query(batchQuery, [userId])
    ]);

    return {
        overview: {
            ...statsRes.rows[0],
            total_batches: batchRes.rows[0].total_batches
        },
        subjectAnalysis: subjectRes.rows,
        monthlyTrend: monthRes.rows
    };
};

module.exports = {
    getTeacherDashboardStats
};