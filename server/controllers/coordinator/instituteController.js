const { searchInstitutesModel } = require("../../models/coordinator/instituteModel");


const searchInstitutes = async (req, res) => {
    try {
        const { q } = req.query;

     
        if (!q || q.trim().length < 3) {
            return res.status(200).json([]);
        }

        const searchTerm = q.trim();

        const institutes = await searchInstitutesModel(searchTerm);

        res.status(200).json(institutes);

    } catch (error) {
        console.error("Error in searchInstitutes controller:", error);
        res.status(500).json({ 
            success: false, 
            message: "Failed to search institutes. Please try again." 
        });
    }
};


module.exports = {
    searchInstitutes
};