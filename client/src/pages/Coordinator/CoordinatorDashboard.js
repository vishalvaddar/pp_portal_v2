import React, { useEffect, useState, useMemo } from "react";
import axios from "axios";
import {
  Users,
  Calendar,
  TrendingUp,
  BookOpen,
  Clock,
  Activity,
  Info,
} from "lucide-react";
import {
  PieChart,
  Pie,
  Cell,
  ResponsiveContainer,
} from "recharts";

import StatsCard from "../../components/Dashboard/StatsCard";
import RecentActivity from "../../components/Dashboard/RecentActivity";
import AttendanceChart from "../../components/Dashboard/AttendanceChart";
import UpcomingClasses from "../../components/Dashboard/UpcomingClasses";

import { useAuth } from "../../contexts/AuthContext";
import "./CoordinatorDashboard.css";

/* ===========================================================
    SUB-COMPONENT: SIMPLE RAINBOW GAUGE (Cohort Matrix)
   =========================================================== */
const AttendanceGauge = ({ value, batchName, classesHeld }) => {
  const COLORS = ["#ef4444", "#f97316", "#eab308", "#84cc16", "#22c55e"];
  const percentage = Math.min(Math.max(parseFloat(value) || 0, 0), 100);

  return (
    <div className="bg-white p-5 rounded-[2rem] border border-slate-200 shadow-sm flex flex-col items-center relative overflow-hidden hover:shadow-md transition-all">
      <div className="absolute top-0 right-0 bg-slate-50 px-3 py-1 rounded-bl-xl border-b border-l border-slate-100">
        <p className="text-[8px] font-black text-slate-400 uppercase">
          {classesHeld} Sessions
        </p>
      </div>

      <h4 className="text-[10px] font-black text-slate-400 mb-4 truncate w-full text-center uppercase tracking-widest mt-2">
        {batchName}
      </h4>

      <div style={{ width: "100%", height: "90px", position: "relative" }}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={[{ v: 1 }, { v: 1 }, { v: 1 }, { v: 1 }, { v: 1 }]}
              cx="50%"
              cy="100%"
              startAngle={180}
              endAngle={0}
              innerRadius="70%"
              outerRadius="90%"
              paddingAngle={3}
              dataKey="v"
              stroke="none"
            >
              {COLORS.map((c, i) => (
                <Cell key={i} fill={c} opacity={0.1} />
              ))}
            </Pie>

            <Pie
              data={[{ v: percentage }, { v: 100 - percentage }]}
              cx="50%"
              cy="100%"
              startAngle={180}
              endAngle={180 - percentage * 1.8}
              innerRadius="70%"
              outerRadius="100%"
              stroke="none"
              dataKey="v"
            >
              <Cell
                fill={
                  percentage > 85
                    ? "#22c55e"
                    : percentage > 70
                    ? "#84cc16"
                    : "#ef4444"
                }
              />
              <Cell fill="transparent" />
            </Pie>
          </PieChart>
        </ResponsiveContainer>

        <div className="absolute bottom-0 left-0 right-0 flex justify-center">
          <span className="text-2xl font-black text-slate-800">
            {Math.round(percentage)}%
          </span>
        </div>
      </div>
    </div>
  );
};

/* ===========================================================
    MAIN COORDINATOR DASHBOARD
   =========================================================== */
export default function CoordinatorDashboard() {
  const { user } = useAuth();
  const token = user?.token;
  const API = process.env.REACT_APP_BACKEND_API_URL;

  const [students, setStudents] = useState([]);
  const [batches, setBatches] = useState([]);
  const [timetable, setTimetable] = useState([]);
  const [globalAttendance, setGlobalAttendance] = useState([]);

  const axiosConfig = () => ({
    headers: { Authorization: `Bearer ${token}` },
  });

  /* --- FETCH INITIAL DATA --- */
  useEffect(() => {
    if (!token) return;

    Promise.all([
      axios.get(`${API}/api/coordinator/students`, axiosConfig()),
      axios.get(`${API}/api/coordinator/batches`, axiosConfig()),
      axios.get(`${API}/api/coordinator/reports/global-attendance`, axiosConfig()),
    ])
      .then(([studentsRes, batchesRes, attendanceRes]) => {
        setStudents(studentsRes.data || []);
        setBatches(batchesRes.data || []);
        setGlobalAttendance(attendanceRes.data || []);
      })
      .catch((err) => console.error("Dashboard Fetch Error:", err));
  }, [token]);

  /* --- FETCH TIMETABLE --- */
  useEffect(() => {
    if (!token || !batches.length) return;
    const activeBatch =
      batches.find((b) => b.is_active || b.active) || batches[0];

    axios
      .get(`${API}/api/coordinator/timetable`, {
        ...axiosConfig(),
        params: { batchId: activeBatch.batch_id },
      })
      .then(({ data }) => setTimetable(data || []))
      .catch((err) => console.error("Timetable Fetch Error:", err));
  }, [token, batches]);

  /* --- COMPUTED VALUES --- */
  const totalStudents = students.length;
  const activeBatchesCount = batches.filter(
    (b) => b.is_active || b.active
  ).length;

  const systemAvg = useMemo(() => {
    if (!globalAttendance.length) return "0.00";
    const total = globalAttendance.reduce(
      (acc, c) => acc + parseFloat(c.cohort_avg),
      0
    );
    return (total / globalAttendance.length).toFixed(2);
  }, [globalAttendance]);

  const todayClasses = useMemo(() => {
    const weekday = new Date()
      .toLocaleDateString("en-US", { weekday: "long" })
      .toUpperCase();
    return timetable.filter(
      (t) => (t.day_of_week || "").toUpperCase() === weekday
    );
  }, [timetable]);

  const stats = [
    { name: "Total Students", value: totalStudents, icon: Users },
    { name: "Active Batches", value: activeBatchesCount, icon: BookOpen },
    { name: "Today's Classes", value: todayClasses.length, icon: Calendar },
    { name: "System Month Avg", value: `${systemAvg}%`, icon: TrendingUp },
  ];

  return (
    <div className="dashboard-container p-6 bg-[#f8fafc] min-h-screen">
      {/* HEADER */}
      <div className="dashboard-header mb-6">
        <h1 className="text-3xl font-black text-slate-900 tracking-tight">
          Welcome back, {user?.username || "Coordinator"}!
        </h1>
        <p className="text-slate-500 font-medium flex items-center gap-2">
          <Activity size={16} className="text-indigo-600" />
          Current Performance Overview
        </p>
      </div>

      {/* VERSION NOTICE */}
      <div className="mb-8 flex items-start gap-3 bg-indigo-50 border border-indigo-200 text-indigo-700 px-4 py-3 rounded-xl">
        <Info size={18} className="mt-0.5 text-indigo-600" />
        <p className="text-sm font-medium">
          This file will be visible soon in <strong>Version 02</strong>.
          Currently, this feature has not been implemented.
        </p>
      </div>

      {/* SUMMARY STATS */}
      <div className="stats-grid mb-10">
        {stats.map((stat) => (
          <StatsCard key={stat.name} {...stat} />
        ))}
      </div>

      {/* CHART + UPCOMING CLASSES */}
      <div className="main-grid">
        <div className="chart-section bg-white p-6 rounded-[2rem] border border-slate-200 shadow-sm">
          <div className="flex items-center gap-2 mb-4">
            <TrendingUp size={18} className="text-indigo-600" />
            <h3 className="font-black text-slate-800 uppercase tracking-widest text-xs">
              Attendance Trends
            </h3>
          </div>
          <AttendanceChart />
        </div>

        <div className="classes-section bg-white p-6 rounded-[2rem] border border-slate-200 shadow-sm">
          <UpcomingClasses classes={todayClasses} />
        </div>
      </div>

      {/* RECENT ACTIVITY + QUICK ACTIONS */}
      <div className="bottom-grid mt-8">
        <RecentActivity />

        <div className="quick-actions">
          <h3 className="font-black text-slate-800 uppercase tracking-widest text-xs mb-4">
            Quick Actions
          </h3>

          <div className="actions-grid">
            <button className="action-btn blue">
              <Calendar className="icon" />
              <p>Mark Attendance</p>
            </button>
            <button className="action-btn green">
              <Users className="icon" />
              <p>View Students</p>
            </button>
            <button className="action-btn purple">
              <BookOpen className="icon" />
              <p>Upload Notes</p>
            </button>
            <button className="action-btn orange">
              <Clock className="icon" />
              <p>View Timetable</p>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
