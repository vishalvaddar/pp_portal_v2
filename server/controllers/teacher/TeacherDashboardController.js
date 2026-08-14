const { getTeacherDashboardStats } = require("../../models/teacher/TeacherDashboardModel");

const getTeacherDashboardController = async (req, res) => {
    try {
        const userId = req.user.user_id; // From auth middleware
        const dashboardData = await getTeacherDashboardStats(userId);
        
        res.status(200).json(dashboardData);
    } catch (error) {
        console.error("Error fetching teacher dashboard:", error);
        res.status(500).json({ error: "Internal Server Error" });
    }
};

module.exports = {
    getTeacherDashboardController
};
