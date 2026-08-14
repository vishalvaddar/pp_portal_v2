const express = require("express");
const router = express.Router();
const timetableController = require("../controllers/activeTimeTableController");

router.get("/dropdowns", timetableController.getDropdownData);
router.get("/batches", timetableController.getBatchesByCohort);
router.get("/fetch", timetableController.getTimetableData);


router.post("/subject/add", timetableController.addSubject);
router.get("/teacher-skills/:teacherId", timetableController.getTeacherSkills);
router.post("/teacher-skills/manage", timetableController.manageTeacherSkill);
router.post("/download-pdf", timetableController.downloadTimetablePDF);


module.exports = router;


