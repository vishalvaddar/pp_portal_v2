const ShortlistInfoModel = require("../models/shortlistInfoModel");
const xlsx = require("xlsx");
const pool = require("../config/db");
const path = require('path');
const fs = require('fs');

const shortlistInfoController = {
    // Fetch all shortlist batch names for a specific year
    getShortlistNames: async (req, res) => {
        const { year } = req.query;
        try {
            const names = await ShortlistInfoModel.getAllShortlistNames(year);
            res.json(names);
        } catch (error) {
            console.error("getShortlistNames Error:", error);
            res.status(500).json({ message: "Error fetching shortlist names", error: error.message });
        }
    },

    // Fetch only non-frozen shortlist batch names for a specific year
    getNonFrozenShortlistNames: async (req, res) => {
        const { year } = req.query;
        try {
            const nonFrozenNames = await ShortlistInfoModel.getNonFrozenShortlistNames(year);
            res.json(nonFrozenNames);
        } catch (error) {
            console.error("getNonFrozenShortlistNames Error:", error);
            res.status(500).json({ message: "Error fetching non-frozen shortlist names", error: error.message });
        }
    },

    // Fetch detailed shortlist batch info
    getShortlistDetails: async (req, res) => {
        const { shortlistName } = req.params;
        const { year } = req.query;
        try {
            const info = await ShortlistInfoModel.getShortlistInfo(shortlistName, year);
            if (!info) return res.status(404).json({ message: "Shortlist not found" });
            res.json(info);
        } catch (error) {
            console.error(`getShortlistDetails Error [${shortlistName}]:`, error);
            res.status(500).json({ message: "Error fetching shortlist info", error: error.message });
        }
    },

    // Fetch total applicant and shortlisted counts
    getCounts: async (req, res) => {
        const { year } = req.query;
        try {
            const totalApplicants = await ShortlistInfoModel.getTotalApplicantCount(year);
            const totalShortlisted = await ShortlistInfoModel.getTotalShortlistedCount(year);
            res.json({ totalApplicants, totalShortlisted });
        } catch (error) {
            console.error("getCounts Error:", error);
            res.status(500).json({ message: "Error fetching counts", error: error.message });
        }
    },

    /**
     * 🔥 Main Freeze Logic
     * 1. Auto-updates single-medium school students.
     * 2. Detects multi-medium school conflicts.
     * 3. Freezes ONLY if zero conflicts remain.
     */
    freezeShortlist: async (req, res) => {
        const { shortlistBatchId, filterMediums } = req.body;

        if (!shortlistBatchId) return res.status(400).json({ message: "Batch ID required" });
        if (!filterMediums || filterMediums.length === 0) {
            return res.status(400).json({ message: "Select at least one medium" });
        }

        try {
            // STEP 1: Auto-update students in schools with ONLY ONE matching medium
            await ShortlistInfoModel.autoUpdateSingleMediumStudents(shortlistBatchId, filterMediums);

            // STEP 2: Fetch the "Others" (Students in multi-medium schools requiring manual review)
            const invalidStudents = await ShortlistInfoModel.getInvalidMediumStudents(shortlistBatchId, filterMediums);

            // STEP 3: If there are ANY remaining conflicts, STOP and return them to the UI
            if (invalidStudents.length > 0) {
                return res.status(400).json({
                    requiresCorrection: true,
                    message: `${invalidStudents.length} students require manual medium selection (Multi-medium schools detected).`,
                    students: invalidStudents
                });
            }

            // STEP 4: If invalidStudents is 0, freeze the batch
            const success = await ShortlistInfoModel.freezeShortlist(shortlistBatchId);
            if (success) {
                res.json({ message: "Shortlist filtered and frozen successfully" });
            } else {
                res.status(404).json({ message: "Shortlist not found or already frozen" });
            }
        } catch (error) {
            console.error(`freezeShortlist Error:`, error);
            res.status(500).json({ message: "Error during freeze process", error: error.message });
        }
    },

    // Handle manual decisions submitted from the correction table
    bulkUpdateMediums: async (req, res) => {
        const { updates, batchId } = req.body;
        if (!updates || !batchId) return res.status(400).json({ message: "Missing data" });
        try {
            const success = await ShortlistInfoModel.bulkUpdateMediumsAndStatus(updates, batchId);
            if (success) {
                res.json({ message: "Medium decisions updated successfully" });
            }
        } catch (error) {
            console.error("bulkUpdateMediums Error:", error);
            res.status(500).json({ message: "Failed to update student data", error: error.message });
        }
    },

    // Delete a shortlist batch
    deleteShortlist: async (req, res) => {
        const { shortlistBatchId } = req.body;
        const { year } = req.query;
        try {
            const success = await ShortlistInfoModel.deleteShortlist(shortlistBatchId, year);
            if (success) res.json({ message: "Shortlist deleted successfully" });
            else res.status(404).json({ message: "Shortlist not found" });
        } catch (error) {
            console.error(`deleteShortlist Error:`, error);
            res.status(500).json({ message: "Error deleting shortlist", error: error.message });
        }
    },

    // Get shortlisted applicants for display
    getShortlistedApplicantsForShow: async (req, res) => {
        const { shortlistName } = req.params;
        const { year } = req.query;
        try {
            const info = await ShortlistInfoModel.getShortlistInfo(shortlistName, year);
            if (!info) return res.status(404).json({ message: "Shortlist not found" });
            const data = await ShortlistInfoModel.getShortlistedApplicantsForShow(info.id, year);
            res.json({ name: info.name, data: data });
        } catch (error) {
            console.error(`getShortlistedApplicantsForShow Error:`, error);
            res.status(500).json({ message: "Error fetching display data", error: error.message });
        }
    },

    // Generate Excel and send for download
    getShortlistedApplicantsForDownload: async (req, res) => {
        const { shortlistName } = req.params;
        const { year: queryYear } = req.query;

        try {
            const year = queryYear;
            const shortlistInfo = await ShortlistInfoModel.getShortlistInfo(shortlistName, year);
            if (!shortlistInfo) return res.status(404).json({ message: "Shortlist not found" });

            // Check if data exists for download
            const totalStudentsRes = await pool.query(
                `SELECT COUNT(*) AS total_students 
                 FROM pp.applicant_shortlist_info 
                 WHERE shortlist_batch_id = $1 AND shortlisted_yn = 'Y'`,
                [shortlistInfo.id]
            );
            
            if (parseInt(totalStudentsRes.rows[0].total_students) === 0) {
                return res.status(200).json({ status: "no_data", message: "No shortlisted students found." });
            }

            let applicants = await ShortlistInfoModel.getShortlistedApplicantsForDownload(shortlistInfo.id, year);

            // Add Serial Number
            const formattedData = applicants.map((app, i) => ({ "S. No.": i + 1, ...app }));

            const fileName = `${shortlistName}_Applicants.xlsx`;
            const worksheet = xlsx.utils.json_to_sheet(formattedData);
            const workbook = xlsx.utils.book_new();
            xlsx.utils.book_append_sheet(workbook, worksheet, "Applicants");

            const FILE_DIR = path.join(process.env.FILE_STORAGE_PATH, 'generated-shortlist-data');
            if (!fs.existsSync(FILE_DIR)) {
                fs.mkdirSync(FILE_DIR, { recursive: true });
            }

            const localSavePath = path.join(FILE_DIR, fileName);
            xlsx.writeFile(workbook, localSavePath);

            const buffer = xlsx.write(workbook, { type: 'buffer', bookType: 'xlsx' });
            res.setHeader('Content-Disposition', `attachment; filename="${fileName}"`);
            res.setHeader('Content-Type', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
            return res.status(200).send(buffer);
        } catch (error) {
            console.error(`Download Error:`, error);
            res.status(500).json({ message: "Error generating download", error: error.message });
        }
    },

    // Reset medium filtering
    resetMediums: async (req, res) => {
        const { shortlistBatchId } = req.body;
        try {
            const success = await ShortlistInfoModel.resetMediumFiltering(shortlistBatchId);
            if (success) {
                res.json({ message: "Medium filtering reset successfully." });
            } else {
                res.status(400).json({ message: "Reset failed. Batch may be frozen." });
            }
        } catch (error) {
            console.error(`resetMediums Error:`, error);
            res.status(500).json({ message: "Error resetting filtering", error: error.message });
        }
    },
};

module.exports = shortlistInfoController;