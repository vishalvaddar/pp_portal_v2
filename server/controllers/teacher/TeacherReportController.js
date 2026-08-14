const { getMyClassReports } = require("../../models/teacher/TeacherReportModel.js");

const getMyClassReportsController = async (req, res) => {
    try {
        const userId = req.user.user_id; 
        const { fromDate, toDate } = req.query;

        if (!fromDate || !toDate) {
            return res.status(400).json({ error: "fromDate and toDate are required" });
        }

        const reportData = await getMyClassReports(userId, fromDate, toDate);
        
        res.status(200).json({ classes: reportData });
    } catch (error) {
        console.error("Error fetching teacher reports:", error);
        res.status(500).json({ error: "Internal Server Error" });
    }
};

module.exports = {
    getMyClassReportsController
};
