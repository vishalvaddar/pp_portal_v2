const { getTeacherProfileByUserId } = require("../../models/teacher/TeacherProfileModel");

const getTeacherProfileController = async (req, res) => {
    try {
        const userId = req.user.user_id; // From your auth middleware
        
        const profile = await getTeacherProfileByUserId(userId);

        if (!profile) {
            return res.status(404).json({ error: "Teacher profile not found" });
        }

        // Apply your photo storage logic here
        profile.photo_link = `user-photos/${userId}.jpg`;

        res.status(200).json(profile);
        
    } catch (error) {
        console.error("Error fetching teacher profile:", error);
        res.status(500).json({ error: "Internal Server Error" });
    }
};

module.exports = {
    getTeacherProfileController
};