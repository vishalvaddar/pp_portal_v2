import {
  Dashboard,
  School,
  Description,
  FactCheck,
  Event,
  Grading,
  EmojiEvents,
  MenuBook,
  Group,
  Badge,
  Settings,
  AccountCircle,
  ManageAccounts,
  Tune,
  GroupWork,
  Assessment,
  NoteAlt,
  AssignmentInd,
  Schedule,
  CalendarMonth,
  Feedback,
  Inventory,
  CalendarToday,
  SupervisorAccount
} from "@mui/icons-material";

export const navConfig = {
  admin: [
    { path: "/admin/admin-dashboard", label: "Dashboard", icon: <Dashboard /> },
    {
      label: "Admissions",
      icon: <School />,
      children: [
        { path: "/admin/admissions/applications", icon: <Description />, label: "Applications" },
        { path: "/admin/admissions/shortlisting", icon: <FactCheck />, label: "Shortlisting" },
        { path: "/admin/admissions/exam-management", icon: <Event />, label: "Selection Tests" },
        { path: "/admin/admissions/evaluation", icon: <Grading />, label: "Evaluation" },
        { path: "/admin/admissions/results", icon: <EmojiEvents />, label: "Final Results" }, 
      ],
    },
    {
      label: "Academics",
      icon: <MenuBook />,
      children: [
        { path: "/admin/academics/students", icon: <Group />, label: "Students" },
        { path: "/admin/academics/batches", icon: <Badge />, label: "Batches" },
        { path: "/admin/academics/time-table-dashboard", icon: <CalendarMonth />, label: "Time Table" },
        { path: "/admin/academics/reports", icon: <Assessment />, label: "Reports" },
        { path: "/admin/academics/classrooms", icon: <School />, label: "Classrooms" },
        { path: "/admin/academics/events", icon: <Event />, label: "Events" },
         { path: "/admin/academics/tab-inventory", icon: <Inventory />, label: "Tab Inventory" }
      ],
    },
    {
      label: "System-Settings",
      icon: <Settings />,
      children: [
        { path: "/admin/settings/my-profile", icon: <AccountCircle />, label: "My Profile" },
        { path: "/admin/settings/user-roles", icon: <ManageAccounts />, label: "Users & Roles" },
        { path: "/admin/settings/system", icon: <Tune />, label: "System" },
      ],
    },
  ],

  coordinator: [
    { path: "/coordinator/coordinator-dashboard", label: "Dashboard", icon: <Dashboard /> },
    { path: "/coordinator/batch-management", icon: <GroupWork />, label: "Batch Management" },
    { path: "/coordinator/batch-reports", icon: <Assessment />, label: "Batch Reports" },
    { path: "/coordinator/attendance-tracker", icon: <Assessment />, label: "Student Attendance" },
    { path: "/coordinator/time-table", icon: <Schedule />, label: "Time Table" }
  ],

  student: [
    { path: "/student/student-dashboard", label: "Dashboard", icon: <Dashboard /> },
    { path: "/student/student-profile", icon: <AccountCircle />, label: "Profile" },
    { path: "/student/student-timetable", icon: <CalendarToday />, label: "Timetable" },
    { path: "/student/student-attendance", icon: <Assessment />, label: "My attendance" },
    { path: "/student/student-corner", icon: <NoteAlt />, label: "Student Corner" },
  ],

  teacher: [
    { path: "/teacher/teacher-dashboard", label: "Dashboard", icon: <Dashboard /> },
    { path: "/teacher/teacher-profile", label: "Dashboard", icon: <AccountCircle />, label : "My Profile" },
    { path: "/teacher/teacher-coordinators", icon: <SupervisorAccount />, label: "Coordinators" },
    { path: "/teacher/my-students", icon: <AssignmentInd />, label: "My Students" },
    { path: "/teacher/teacher-reports", icon : <Assessment />, label: "Class Reports"},
    { path: "/teacher/time-table", icon: <Schedule />, label: "Time Table" },
  ],

  interviewer: [
    { path: "/interviewer/interviewer-dashboard", label: "Dashboard", icon: <Dashboard /> },
    { path: "/interviewer/interview-schedule", icon: <Schedule />, label: "Interview Schedule" },
    { path: "/interviewer/interview-feedback", icon: <Feedback />, label: "Interview Feedback" },
  ],
};
