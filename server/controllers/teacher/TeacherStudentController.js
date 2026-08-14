const pool = require("../../config/db.js");

const {
    getStudentsByTeacher,
    getStudentsByTeacherBatch,
    getInactiveHistoryByStudentId
} = require("../../models/teacher/TeacherStudentModel.js");

/* ===========================================================
   GET COHORTS (Assigned to logged-in teacher only)
=========================================================== */
const getCohortsController = async (req, res) => {
    try {
        const userId = req.user.user_id;

        const query = `
            SELECT DISTINCT c.cohort_number, c.cohort_name 
            FROM pp.cohort c
            JOIN pp.batch b ON c.cohort_number = b.cohort_number
            JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
            JOIN pp.classroom cl ON cb.classroom_id = cl.classroom_id
            JOIN pp.teacher t ON cl.teacher_id = t.teacher_id
            WHERE t.user_id = $1
            ORDER BY c.cohort_number DESC
        `;
        
        const result = await pool.query(query, [userId]);
        res.status(200).json(result.rows);
    } catch (error) {
        console.error("Error fetching cohorts:", error);
        res.status(500).json({ error: "Internal Server Error" });
    }
};

/* ===========================================================
   GET BATCHES (Assigned to logged-in teacher + Filtered by Cohort)
=========================================================== */
const getBatchesController = async (req, res) => {
    try {
        const { cohort_number } = req.query;
        const userId = req.user.user_id;

        let query = `
            SELECT DISTINCT b.batch_id, b.batch_name 
            FROM pp.batch b
            JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
            JOIN pp.classroom cl ON cb.classroom_id = cl.classroom_id
            JOIN pp.teacher t ON cl.teacher_id = t.teacher_id
            WHERE t.user_id = $1
        `;
        let values = [userId];

        // If a cohort is selected, apply the filter
        if (cohort_number) {
            query += ` AND b.cohort_number = $2`;
            values.push(cohort_number);
        }

        query += ` ORDER BY b.batch_name ASC`;

        const result = await pool.query(query, values);
        res.status(200).json(result.rows);
    } catch (error) {
        console.error("Error fetching batches:", error);
        res.status(500).json({ error: "Internal Server Error" });
    }
};

/* ===========================================================
   GET STUDENTS OF LOGGED-IN TEACHER
=========================================================== */
const getStudentsController = async (req, res) => {
    try {
        const user_id = req.user.user_id;
        const {
            cohortNumber,
            batchId
        } = req.query;

        let students = [];

        if (cohortNumber && batchId) {
            students = await getStudentsByTeacherBatch(
                user_id,
                cohortNumber,
                batchId
            );
            return res.json(students);
        }

        students = await getStudentsByTeacher(user_id);
        return res.json(students);

    }
    catch (err) {
        console.error(err);
        return res.status(500).json({
            error: "Failed to fetch students."
        });
    }
};

/* ===========================================================
   STUDENT INACTIVE HISTORY
=========================================================== */
const getInactiveHistoryController = async (req, res) => {
    try {
        const student_id = req.params.id;
        const history =
            await getInactiveHistoryByStudentId(student_id);

        res.json(history);

    }
    catch (err) {
        console.error(err);
        res.status(500).json({
            error: "Failed to fetch inactive history."
        });
    }
};

module.exports = {
    getCohortsController,
    getBatchesController,
    getStudentsController,
    getInactiveHistoryController
};