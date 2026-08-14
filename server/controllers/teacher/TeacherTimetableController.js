const { getTimetableByBatchAndTeacher } = require("../../models/teacher/TeacherTimetableModel");

const getTimetableController = async (req, res) => {
    try {
        const { batchId } = req.query;
        const userId = req.user.user_id;
        
        // Removed the strict requirement for batchId so it fetches all by default
        const timetable = await getTimetableByBatchAndTeacher(batchId, userId);
        res.status(200).json(timetable);
        
    } catch (error) {
        console.error("Error fetching teacher timetable:", error);
        res.status(500).json({ error: "Internal Server Error" });
    }
};

module.exports = {
    getTimetableController
};