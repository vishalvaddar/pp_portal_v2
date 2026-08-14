const timetableService = require("../service/timetableService");

exports.getSubjectsForTimeTable = async (req, res) => {
    try {
        const rows = await timetableService.getSubjectsForTimeTable();
        res.status(200).json(rows);
    } catch (error) {
        res.status(500).json({message: "Server error."});
    }
};



exports.getTeachersForTimeTable = async (req, res) => {
    try {
        const rows = await timetableService.getTeachersForTimeTable();
        res.status(200).json(rows);
    } catch (error) {
        res.status(500).json({message: "Server error."});
    }
};


exports.getBatchesByGrades = async (req, res) => {
    try {
        const grades = req.body.grades;
        if (!grades || grades.length === 0) {
            return res.json([]);
        }
        const rows = await timetableService.getBatchesByGrades(grades);
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).send("Server Error");
    }
};


exports.getCanTeachByTeacherIds = async (req, res) => {
    try {
        let teacherIds = req.body.teacherIds || [];
        if (teacherIds.length === 0) {
            return res.json([]);
        }
        const rows = await timetableService.getCanTeachByTeacherIds(teacherIds);
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).send("Server Error");
    }
};


exports.getBatchDetailsForGroupTeacherMapDtls   = async (req, res) => {
    try {
        const { teacherId,subjectId,grade } = req.params;
        if (!teacherId) {
            return res.status(400).json({error: "teacherId is required"});
        }
        if (!subjectId) {
            return res.status(400).json({error: "subjectId is required"});
        }
        if (!grade) {
            return res.status(400).json({error: "grade is required"});
        }
        const rows = await timetableService.getBatchDetailsForGroupTeacherMapDtls(teacherId, subjectId, grade);
        res.status(200).json(rows);
    }catch(err) {        
        res.status(500).send("Server Error");
    }
}


exports.getTeachersBySubjects = async (req, res) => {
    try {
        const subjectIds = req.body.subjectIds;
        if (!subjectIds || subjectIds.length === 0) {
            return res.json([]);
        }
        const rows = await timetableService.getTeachersBySubjects(subjectIds);
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).send("Server Error");
    }
};


exports.getAllConfigurationDraftFileDtls = async (req, res) => {
    try {
        const rows = await timetableService.getAllConfigurationDraftFileDtls();
        res.status(200).json(rows);
    } catch (error) {
        res.status(500).send("Server Error");
    }
};



exports.generateFinalOutputFromPython = async (req, res) => {
    try {
        const result = await timetableService.generateFinalOutputFromPython(req.body);
        return res.status(200).json(result);
    } catch (err) {
        return res.status(500).json({error: err.message || "Server Error"});
    }
};


exports.deleteConfigurationDraftFile = async (req, res) => {
    try {
        const { id } = req.params;
        if (!id) {
            return res.status(400).json({
                message: "Configuration ID is required."
            });
        }
        const result = await timetableService.deleteConfigurationDraftFile(id);
        return res.status(result.statusCode).json(result.body);
    } catch (err) {
        res.status(500).send("Server Error");
    }
};


exports.getConfigById = async (req, res) => {
    try {
        const { configId } = req.params;
        if (!configId) {return res.status(400).json({message: "Config ID is required"});}
        const result = await timetableService.getConfigById(configId);
        return res.status(result.statusCode).json(result.body);
    } catch (error) {
        res.status(500).send("Server Error");
    }
};


exports.saveConfigurationDraftFile = async (req, res) => {
    try {
        const result = await timetableService.saveConfigurationDraftFile(req.body);
        return res.status(result.statusCode).json(result.body);
    } catch (err) {
        console.log(err)
        res.status(500).send("Server Error");
    }
};


exports.saveTimeTableSolution = async (req, res) => {
    try {
        const result = await timetableService.saveTimeTableSolution(req.body);
        return res.status(result.statusCode).json(result.body);
    } catch (err) {
        res.status(500).send("Server Error");
    }
};



exports.getGradesForCombinedBatches = async (req, res) => {
    try {
        const rows = await timetableService.getGradesForCombinedBatches();
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).json({error: "Server Error"});
    }
};

exports.getBatchesByGradeForCombinedBatches = async (req, res) => {
    try {
        const { grade, language } = req.params;
        if (!grade) {
            return res.status(400).json({error: "Grade is required"});
        }
        if (!language) {
            return res.status(400).json({error: "Language is required"});
        }
        const rows = await timetableService.getBatchesByGradeForCombinedBatches(grade, language);
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).json({error: "Server Error"});
    }
};

exports.getBatchesByGradeForCombinedBatchesForPrepration =
    async (req, res) => {
    try {
        const { grade } = req.params;
        if (!grade) {
            return res.status(400).json({error: "Grade is required"});
        }
        const rows =await timetableService.getBatchesByGradeForCombinedBatchesForPrepration(grade);
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).json({error: "Server Error"});
    }
};


exports.getBatchesByGradeForSubjectTeacherDtls =
    async (req, res) => {
    try {
        const { grade } = req.params;
        if (!grade) {
            return res.status(400).json({error: "Grade is required"});
        }
        const rows =await timetableService.getBatchesByGradeForSubjectTeacherDtls(grade);
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).json({error: "Server Error"});
    }
};



exports.getBatchesForBatchWeeklyPeriod = async (req, res) => {
    try {
        const rows = await timetableService.getBatchesForBatchWeeklyPeriod();
        return res.status(200).json(rows);
    } catch (err) {
        res.status(500).json({error: "Server Error"});
    }
};

exports.getSubjectsByBatchIdForBatchWeeklyPeriod = async (req, res) => {
    try {
        const { batchId } = req.query;
        if (!batchId) {
            return res.status(400).json({message: "batchId is required"});
        }
        const rows = await timetableService.getSubjectsByBatchIdForBatchWeeklyPeriod(batchId);
        return res.status(200).json(rows);
    } catch (err) {
        res.status(500).json({error: "Server Error"});
    }
};


exports.getSavedTimeTableSolutionList = async (req, res) => {
    try {
        const rows = await timetableService.getSavedTimeTableSolutionList();
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).json({error: "Server Error"});
    }
};

exports.getTimeTableSolutionBySolutionId = async (req, res) => {
    try {
        const { solutionId } = req.params;
        if (!solutionId) {
            return res.status(400).json({
                message: "solutionId is required"
            });
        }
        const data = await timetableService.getTimeTableSolutionBySolutionId(solutionId);
        if (!data) {
            return res.status(404).json({
                message: "Solution not found"
            });
        }
        return res.status(200).json(data);
    } catch (err) {
        res.status(500).json({
            error: "Server Error"
        });
    }
};


exports.getBatches = async (req, res) => {
    try {
        const rows = await timetableService.getBatches();
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).json({error: "Server Error"});
    }
};


exports.getSubjectsByBatchId = async (req, res) => {
    try {
        const { batchId } = req.query;
        if (!batchId) {
            return res.status(400).json({
                message: "batchId is required"
            });
        }
        const rows = await timetableService.getSubjectsByBatchId(batchId);
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).json({
            error: "Server Error"
        });
    }
};


exports.getTeachersBySubject = async (req, res) => {
    try {
        const { subjectId, medium } = req.query;
        if (!subjectId || !medium) {
            return res.status(400).json({
                message: "subjectId and medium are required"
            });
        }
        const rows = await timetableService.getTeachersBySubject(subjectId,medium);
        res.status(200).json(rows);
    } catch (err) {
        res.status(500).json({error: "Server Error"});
    }
};

exports.updateTimeTableSolution = async (req, res) => {
    try {
        const { solutionId: id } = req.params;
        const updatedData = req.body.updatedCells;
        if (!updatedData || Object.keys(updatedData).length === 0) {
            return res.status(400).json({
                message: "No updates provided"
            });
        }
        const response = await timetableService.updateTimeTableSolution(id,updatedData);
        if (!response) {
            return res.status(404).json({
                message: "Solution not found"
            });
        }
        return res.json({message: "Updated successfully ✅"});
    } catch (err) {
        res.status(500).json({
            error: "Server Error"
        });
    }
};


