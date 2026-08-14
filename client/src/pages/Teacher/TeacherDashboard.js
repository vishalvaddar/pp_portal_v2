import React, { useEffect, useState } from "react";
import axios from "axios";
import "./TeacherDashboard.css";
import { useAuth } from "../../contexts/AuthContext";
import { BookOpen, Users, CalendarCheck, BarChart2 } from "lucide-react";
import Logo from "../../assets/RCF-PP.jpg";

const BACKEND = process.env.REACT_APP_BACKEND_API_URL;

export default function TeacherDashboard() {
  const { user } = useAuth();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!user?.token) return;

    axios.get(`${BACKEND}/api/teacher/dashboard`, {
      headers: { Authorization: `Bearer ${user.token}` }
    })
    .then(res => setData(res.data))
    .catch(err => console.error("Dashboard error:", err))
    .finally(() => setLoading(false));
  }, [user?.token]);

  if (loading) return <div className="loader">Loading Dashboard...</div>;
  if (!data) return <div className="error-state">Failed to load dashboard data.</div>;

  const { overview, subjectAnalysis, monthlyTrend } = data;

  // Find max values for CSS charting logic
  const maxSubjectClasses = Math.max(...subjectAnalysis.map(s => parseInt(s.classes_taken)), 1);
  const maxMonthClasses = Math.max(...monthlyTrend.map(m => parseInt(m.classes_taken)), 1);

  return (
    <div className="dashboard-wrapper">
      {/* HEADER */}
      <div className="dashboard-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: '15px' }}>
          <img src={Logo} alt="Logo" style={{ width: '60px', height: '60px', borderRadius: '8px' }} />
          <div>
            <h1 className="title">Teacher Dashboard</h1>
            <p className="subtitle">Overview of your teaching analytics and progress</p>
          </div>
        </div>
      </div>

      {/* STAT CARDS */}
      <div className="stat-cards-grid">
        <div className="stat-card">
          <div className="stat-icon" style={{ backgroundColor: '#dbeafe', color: '#1d4ed8' }}>
            <CalendarCheck size={24} />
          </div>
          <div className="stat-info">
            <h3>{overview.total_conducted || 0}</h3>
            <p>Total Classes Conducted</p>
          </div>
        </div>

        <div className="stat-card">
          <div className="stat-icon" style={{ backgroundColor: '#dcfce7', color: '#15803d' }}>
            <Users size={24} />
          </div>
          <div className="stat-info">
            <h3>{overview.avg_attendance || 0}%</h3>
            <p>Average Student Attendance</p>
          </div>
        </div>

        <div className="stat-card">
          <div className="stat-icon" style={{ backgroundColor: '#f3e8ff', color: '#7e22ce' }}>
            <BookOpen size={24} />
          </div>
          <div className="stat-info">
            <h3>{overview.total_batches || 0}</h3>
            <p>Active Batches Assigned</p>
          </div>
        </div>
      </div>

      {/* CHARTS ROW */}
      <div className="charts-grid">
        {/* SUBJECT ANALYSIS */}
        <div className="chart-card">
          <div className="chart-header">
            <BarChart2 size={18} color="#3b82f6" />
            <h2>Subject-wise Analysis</h2>
          </div>
          <div className="bar-list">
            {subjectAnalysis.length > 0 ? subjectAnalysis.map((sub, idx) => {
              const width = `${(parseInt(sub.classes_taken) / maxSubjectClasses) * 100}%`;
              return (
                <div key={idx} className="bar-row">
                  <div className="bar-label">
                    <span>{sub.subject_name}</span>
                    <span>{sub.classes_taken} classes</span>
                  </div>
                  <div className="bar-track">
                    <div className="bar-fill" style={{ width, backgroundColor: '#3b82f6' }}></div>
                  </div>
                </div>
              );
            }) : <p className="no-data">No class data available yet.</p>}
          </div>
        </div>

        {/* MONTHLY TREND (Vertical Bars) */}
        <div className="chart-card">
          <div className="chart-header">
            <BarChart2 size={18} color="#10b981" />
            <h2>Monthly Trend</h2>
          </div>
          <div className="vertical-bar-chart">
            {monthlyTrend.length > 0 ? monthlyTrend.map((month, idx) => {
              const height = `${(parseInt(month.classes_taken) / maxMonthClasses) * 100}%`;
              return (
                <div key={idx} className="v-bar-group">
                  <span className="v-bar-value">{month.classes_taken}</span>
                  <div className="v-bar-track">
                    <div className="v-bar-fill" style={{ height, backgroundColor: '#10b981' }}></div>
                  </div>
                  <span className="v-bar-label">{month.month_label.split(' ')[0]}</span>
                </div>
              );
            }) : <p className="no-data" style={{ alignSelf: 'center' }}>No historical data available.</p>}
          </div>
        </div>
      </div>
    </div>
  );
}