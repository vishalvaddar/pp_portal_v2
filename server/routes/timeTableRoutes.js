const express = require("express");
const router = express.Router();
const timetableController = require("../controllers/timetableController");

/* --------------------------------------- generateTimeTable  -------------------------------------------------------*/
router.get("/data/subjectsForTimeTable", timetableController.getSubjectsForTimeTable);
router.get("/combinedBatches/getGradesForCombinedBatches", timetableController.getGradesForCombinedBatches);
router.get("/generateTimeTable/getBatchesForBatchWeeklyPeriod", timetableController.getBatchesForBatchWeeklyPeriod);
router.post("/teachers/getTeachersBySubjects", timetableController.getTeachersBySubjects);
router.get("/generateTimeTable/getSubjectsByBatchIdForBatchWeeklyPeriod", timetableController.getSubjectsByBatchIdForBatchWeeklyPeriod);
router.get("/combinedBatches/getBatchesByGradeForCombinedBatches/:grade/:language", timetableController.getBatchesByGradeForCombinedBatches);
router.get("/combinedBatches/getBatchesByGradeForCombinedBatchesForPrepration/:grade", timetableController.getBatchesByGradeForCombinedBatchesForPrepration);
router.get("/generate/getBatchesByGradeForSubjectTeacherDtls/:grade", timetableController.getBatchesByGradeForSubjectTeacherDtls);
router.post("/batches/byGrades", timetableController.getBatchesByGrades);
router.post("/teachers/canTeachByIds", timetableController.getCanTeachByTeacherIds);

router.get("/generate/getBatchDetailsForGroupTeacherMapDtls/:teacherId/:subjectId/:grade", timetableController.getBatchDetailsForGroupTeacherMapDtls);

router.post("/generate", timetableController.generateFinalOutputFromPython);

/* -------------------------------------- Configuration Drafts -------------------------------------------------------*/
router.post("/timeTable/saveConfigurationDraftFile", timetableController.saveConfigurationDraftFile);
router.post("/timeTable/getAllConfigurationDraftFileDtls", timetableController.getAllConfigurationDraftFileDtls);
router.get("/timeTable/getConfigById/:configId", timetableController.getConfigById);
router.delete("/timeTable/deleteConfigurationDraftFile/:id", timetableController.deleteConfigurationDraftFile);


/* -------------------------------------- Saved timetable solutions -------------------------------------------------------*/
router.post("/timeTable/saveTimeTableSolution", timetableController.saveTimeTableSolution);
router.post("/savedTimeTable/getSavedTimeTableSolutionList", timetableController.getSavedTimeTableSolutionList);
router.get("/savedTimeTable/getTimeTableSolutionBySolutionId/:solutionId", timetableController.getTimeTableSolutionBySolutionId);
router.put("/savedTimeTable/updateTimeTableSolution/:solutionId", timetableController.updateTimeTableSolution);
router.get("/savedTimeTable/getBatches", timetableController.getBatches);
router.get("/savedTimeTable/getSubjectsByBatchId", timetableController.getSubjectsByBatchId);
router.get("/savedTimeTable/getTeachersBySubject", timetableController.getTeachersBySubject);


module.exports = router;