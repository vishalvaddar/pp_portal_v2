const express = require("express");
const router = express.Router();
const auth = require("../middleware/authMiddleware");

// --- Student Controllers ---
const {
    getStudentsController,
    getInactiveHistoryController,
    getCohortsController, 
    getBatchesController
} = require("../controllers/teacher/TeacherStudentController.js");

const { getTeacherProfileController } = require("../controllers/teacher/TeacherProfileController");
// --- Timetable Controllers ---
const {
    getTimetableController
} = require("../controllers/teacher/TeacherTimetableController");

const { getTeacherCoordinatorsController } = require("../controllers/teacher/TeacherCoordinatorController");
const { getTeacherDashboardController } = require("../controllers/teacher/TeacherDashboardController.js");
const { getMyClassReportsController } = require("../controllers/teacher/TeacherReportController.js");


// --- Filters ---
router.get("/cohorts", auth, getCohortsController);
router.get("/batches", auth, getBatchesController);

// --- Timetable Data ---
router.get("/timetable", auth, getTimetableController);

// --- Student Data ---
router.get(
    "/students",
    auth,
    getStudentsController
);

router.get(
    "/students/:id/inactive-history",
    auth,
    getInactiveHistoryController
);



router.get("/profile", auth, getTeacherProfileController);
router.get("/coordinators", auth, getTeacherCoordinatorsController);
router.get("/dashboard", auth, getTeacherDashboardController);
router.get("/reports/my-classes", auth, getMyClassReportsController);

module.exports = router;



