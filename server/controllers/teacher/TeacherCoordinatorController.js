const { getCoordinatorsForTeacher } = require("../../models/teacher/TeacherCoordinatorModel");

const getTeacherCoordinatorsController = async (req, res) => {
    try {
        const userId = req.user.user_id; 
        
        const coordinators = await getCoordinatorsForTeacher(userId);

        // Map over the results to inject the static photo URL path
        const formattedCoordinators = coordinators.map(coord => ({
            ...coord,
            photo_link: `user-photos/${coord.user_id}.jpg`
        }));

        res.status(200).json(formattedCoordinators);
        
    } catch (error) {
        console.error("Error fetching coordinators:", error);
        res.status(500).json({ error: "Internal Server Error" });
    }
};

module.exports = {
    getTeacherCoordinatorsController
};