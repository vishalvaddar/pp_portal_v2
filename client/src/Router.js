import React from "react";
import { createBrowserRouter, Navigate } from "react-router-dom";
import ProtectedRoute from "./components/ProtectedRoute";
import Layout from "./Layout";
import LoginForm from "./components/login/LoginForm";

// --- Admin pages ---
import AdminDashboard from "./pages/Admin/AdminDashboard";
import Applications from "./pages/Admin/Applications";
import NewApplication from "./pages/Admin/NewApplication";
import BulkUploadApplications from "./pages/Admin/BulkUploadApplications";
import SearchApplications from "./pages/Admin/SearchApplications";
import Shortlisting from "./pages/Admin/Shortlisting";
import ViewApplications from "./pages/Admin/ViewApplications";
import ViewStudentInfo from "./pages/Admin/ViewStudentInfo";
import EditForm from "./pages/Admin/EditForm";
import GenerateShortlist from "./pages/Admin/GenerateShortlist";
import ShortlistInfo from "./pages/Admin/ShortlistInfo";
import Students from "./pages/Admin/Students";
import Batches from "./pages/Admin/Batches";
import ViewBatchStudents from "./pages/Admin/ViewBatchStudents";
import Reports from "./pages/Admin/Reports";
import UserRoles from "./pages/Admin/UserRoles";
import SystemConfig from "./pages/Admin/SystemConfig";
import MyProfile from "./pages/Admin/MyProfile";
import CreateExam from "./pages/Admin/Exam/CreateExam";
import NMMSMerge from "./pages/Admin/NMMSMerge";
//Time Table
import TimeTableDashboard from "./pages/Admin/TimeTable/TimeTableDashboard";
import ActiveTimeTable from "./pages/Admin/TimeTable/ActiveTimeTable";
import ConfigurationDraftFileList from "./pages/Admin/TimeTable/ConfigurationDraftFileList";
import SavedTimeTableSolutionList from "./pages/Admin/TimeTable/SavedTimeTableSolutionList";
import GenerateTimeTable from "./pages/Admin/TimeTable/GenerateTimeTable"
import SavedTimeTableSolution from "./pages/Admin/TimeTable/SavedTimeTableSolution";

import ClassroomManager from "./pages/Admin/ClassroomManager";

//Reports
import ReportsDashboard from "./pages/Admin/Reports/ReportsDashboard";
import SelectionReports from "./pages/Admin/Reports/SelectionReports";
import AcademicReports from "./pages/Admin/Reports/AcademicReports";
import SammelanReports from "./pages/Admin/Reports/SammelanReports";
import CustomList from "./pages/Admin/Reports/CustomList";

// Admin Evaluation
import EvaluationDashboard from "./pages/Admin/Evaluation/EvaluationDashboard";
import EvaluationMarksEntry from "./pages/Admin/Evaluation/EvaluationMarksEntry";
import EvaluationInterview from "./pages/Admin/Evaluation/EvaluationInterview";
import EvaluationTracking from "./pages/Admin/Evaluation/EvaluationTracking";
import Resultandrank from "./pages/Admin/Result/Resultandranking";

// Events
import Events from "./pages/Admin/Events/EventsDashboardPage";
import EventDetailsPage from "./pages/Admin/Events/EventDetailsPage";
import EventEditPage from "./pages/Admin/Events/EventEditPage";

// --- Coordinator pages ---
import CoordinatorDashboard from "./pages/Coordinator/CoordinatorDashboard";
import ViewApplication from "./pages/Coordinator/ViewApplication";
import BatchManagement from "./pages/Coordinator/BatchManagement";
import BatchReports from "./pages/Coordinator/BatchReports";
// In Router.js

import AttendanceTracker from "./pages/Coordinator/AttendanceTracker";
import CoordinatorTimeTable from "./pages/Coordinator/TimeTableManagement";

// --- Teacher pages ---
import TeacherDashboard from "./pages/Teacher/TeacherDashboard";
import StudentsList from "./pages/Teacher/MyProfile";
import MyStudents from "./pages/Teacher/MyStudents";
import TeacherTimeTable from "./pages/Teacher/TimeTable";
import TeacherProfile from "./pages/Teacher/MyProfile";
// --- Student pages ---
import StudentDashboard from "./pages/Student/StudentDashboard";
import StudentProfile from "./pages/Student/StudentProfile";

import StudentTimetable from "./pages/Student/studentTimetable";
import StudentCorner from "./pages/Student/StudentCorner";

// --- Interviewer pages ---
import InterviewerDashboard from "./pages/Interviewer/InterviewerDashboard";
import InterviewSchedule from "./pages/Interviewer/InterviewSchedule";
import InterviewFeedback from "./pages/Interviewer/InterviewFeedback";

// --- Tab inventory pages ---
import TabInventory from "./pages/Admin/TabInventory";

import LogoutHandler from "./components/LogoutHandler";

import StudentHallticketPage from "./pages/Student_Hallticket/StudentHallticketPage"

import StudentAttendance from "./pages/Student/studentAttendance"

import TeacherCoordinators from  "./pages/Teacher/BatchCoordinators"
import TeacherReports from "./pages/Teacher/TeacherReports"

export const appRouter = createBrowserRouter([
  {
    path: "/login",
    element: <LoginForm />,
  },
  {
    path: "/hallticket",   // 🔥 PUBLIC URL
    element: <StudentHallticketPage />,
  },
  {
    path: "/",
    element: <ProtectedRoute />,
    children: [
      {
        index: true,
        element: <Navigate to="/admin/admin-dashboard" replace />,
      },


      // ---------------- Admin Routes ----------------
      {
        path: "admin",
        element: <Layout />,
        children: [
          { path: "admin-dashboard", element: <AdminDashboard /> },


          {
            path: "admissions",
            children: [
              { path: "new-application", element: <NewApplication /> },
              {
                path: "bulk-upload-applications",
                element: <BulkUploadApplications />,
              },
              { path: "search-applications", element: <SearchApplications /> },
              { path: "applications", element: <Applications /> },
              { path: "shortlisting", element: <Shortlisting /> },
              { path: "generate-shortlist", element: <GenerateShortlist /> },
              { path: "shortlist-info", element: <ShortlistInfo /> },
              { path: "exam-management", element: <CreateExam /> },
              {
                path: "view-student-info/:nmms_reg_number",
                element: <ViewStudentInfo />,
              },
              { path: "view-applications", element: <ViewApplications /> },
              { path: "edit-form/:nmms_reg_number", element: <EditForm /> },
              { path: "results", element: <Resultandrank /> },
              { path: "nmms-merge", element: <NMMSMerge /> },
              // Evaluation Sub-Routes
              {
                path: "evaluation",
                children: [
                  { path: "", element: <EvaluationDashboard /> },
                  { path: "marks-entry", element: <EvaluationMarksEntry /> },
                  { path: "interview", element: <EvaluationInterview /> },
                  { path: "tracking", element: <EvaluationTracking /> },
                ],
              },
            ],
          },

          {
            path: "academics",
            children: [
              { path: "students", element: <Students /> },
              { path: "batches", element: <Batches /> },
              {
                path: "batches/:batchId/students",
                element: <ViewBatchStudents />,
              },
              {
                path: "batches/view-student-info/:nmms_reg_number",
                element: <ViewStudentInfo />,
              },
              {
                path: "students/view-student-info/:nmms_reg_number",
                element: <ViewStudentInfo />,
              },
              { path: "classrooms", element: <ClassroomManager /> },
              {
                path: "reports",
                children: [
                  { index: true, element: <ReportsDashboard /> },
                  { path: "selection", element: <SelectionReports /> },
                  { path: "academic", element: <AcademicReports /> },
                  { path: "sammelan", element: <SammelanReports /> },
                  { path: "custom-lists", element: <CustomList /> },
                ],
              },
              // Router.js -> Inside Admin -> Academics
              {
                path: "time-table-dashboard",
                  children: [
                    { index: true, element: <TimeTableDashboard /> },
                    { path: "active", element: <ActiveTimeTable /> },
                    { path: "saved", element: <SavedTimeTableSolutionList /> },
                    { path: "generate", element: <ConfigurationDraftFileList /> },
                    
                    // Add these specifically for the Admin Editor
                    { path: "configure", element: <GenerateTimeTable /> },
                    { path: "configure/:id", element: <GenerateTimeTable /> },
                    { path: "savedTimeTableSolution/:id", element: <SavedTimeTableSolution /> },
                    
                  ],
              },




              // EVENTS
              { path: "events", element: <Events /> },
              { path: "events/:eventId", element: <EventDetailsPage /> },
              { path: "events/:eventId/edit", element: <EventEditPage /> },
              {
                path: "events/attendance/manage",
                element: <EventDetailsPage />,
              },
              { path: "tab-inventory", element: <TabInventory /> },
              { path: "tab-inventory", element: <TabInventory /> },
            ],
          },

          {
            path: "settings",
            children: [
              { path: "my-profile", element: <MyProfile /> },
              { path: "user-roles", element: <UserRoles /> },
              { path: "system", element: <SystemConfig /> },
            ],
          },
        ],
      },

      // ---------------- Coordinator Routes ----------------
      {
        path: "coordinator",
        element: <Layout />,
        children: [
          { path: "coordinator-dashboard", element: <CoordinatorDashboard /> },
          { path: "view-application", element: <ViewApplication /> },
          { path: "batch-management", element: <BatchManagement /> },
          { path: "batch-reports", element: <BatchReports /> },
          { path: "attendance-tracker", element: <AttendanceTracker /> },
          { path: "time-table", element: <CoordinatorTimeTable /> },
        ],
      },

      // ---------------- Student Routes ----------------
      {
        path: "student",
        element: <Layout />,
        children: [
          { path: "student-dashboard", element: <StudentDashboard /> },
          { path: "student-profile", element: <StudentProfile /> },
          { path: "student-timetable", element: <StudentTimetable /> },
          { path: "student-attendance", element: <StudentAttendance />},
          { path: "student-corner", element: <StudentCorner /> },
        ],
      },

      // ---------------- Teacher Routes ----------------
      {
        path: "teacher",
        element: <Layout />,
        children: [
          { path: "teacher-dashboard", element: <TeacherDashboard /> },
          { path: "teacher-profile", element : <TeacherProfile />},
          { path: "teacher-coordinators", element : <TeacherCoordinators /> },
          { path: "teacher-reports", element : <TeacherReports />},
          { path: "my-students", element: <MyStudents /> },
          { path: "time-table", element: <TeacherTimeTable /> },
        ],
      },

      // ---------------- Interviewer Routes ----------------
      {
        path: "interviewer",
        element: <Layout />,
        children: [
          { path: "interviewer-dashboard", element: <InterviewerDashboard /> },
          { path: "interview-schedule", element: <InterviewSchedule /> },
          { path: "interview-feedback", element: <InterviewFeedback /> },
        ],
      },

      // Logout
      { path: "logout", element: <LogoutHandler /> },

      // 404
      { path: "*", element: <div>404 - Page Not Found</div> },
    ],
  },
]);
