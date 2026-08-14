// import React, { useEffect, useState, useCallback } from "react";
// import axios from "axios";
// import { useAuth } from "../../contexts/AuthContext";
// import { Loader2, Search } from "lucide-react";
// import Logo from "../../assets/RCF-PP.jpg";

// const BACKEND = process.env.REACT_APP_BACKEND_API_URL;
// const API_BASE = `${BACKEND}/api/student`;

// export default function StudentPerformanceAdvanced() {
//   const [profile, setProfile] = useState(null);
//   const [summary, setSummary] = useState({});
//   const [subjectData, setSubjectData] = useState([]);
//   const [monthlyData, setMonthlyData] = useState([]);
//   const [weeklyData, setWeeklyData] = useState([]);
//   const [customData, setCustomData] = useState([]);

//   const [search, setSearch] = useState("");
//   const [fromDate, setFromDate] = useState("");
//   const [toDate, setToDate] = useState("");

//   const [loading, setLoading] = useState(true);

//   const auth = useAuth();
//   const token = auth?.user?.token;

//   const fetchData = useCallback(async () => {
//     if (!token) return;

//     setLoading(true);
//     try {
//       const headers = { Authorization: `Bearer ${token}` };

//       const [p, s, sub, m, w] = await Promise.all([
//         axios.get(`${API_BASE}/profile`, { headers }),
//         axios.get(`${API_BASE}/summary`, { headers }),
//         axios.get(`${API_BASE}/performance`, { headers }),
//         axios.get(`${API_BASE}/monthly`, { headers }),
//         axios.get(`${API_BASE}/weekly`, { headers }),
//       ]);

//       setProfile(p.data);
//       setSummary(s.data);
//       setSubjectData(sub.data);
//       setMonthlyData(m.data);
//       setWeeklyData(w.data);

//     } catch (e) {
//       console.error(e);
//     } finally {
//       setLoading(false);
//     }
//   }, [token]);

//   useEffect(() => {
//     fetchData();
//   }, [fetchData]);

//   // 🔥 FETCH CUSTOM RANGE
//   const fetchCustom = async () => {
//     if (!fromDate || !toDate) return;

//     try {
//       const res = await axios.get(`${API_BASE}/custom`, {
//         params: { fromDate, toDate },
//         headers: { Authorization: `Bearer ${token}` }
//       });
//       setCustomData(res.data);
//     } catch (e) {
//       console.error(e);
//     }
//   };

//   const filtered = subjectData.filter(s =>
//     s.subject_name?.toLowerCase().includes(search.toLowerCase())
//   );

//   if (loading) {
//     return (
//       <div className="loader-container">
//         <Loader2 className="spinner" size={40} />
//         <p>Loading performance...</p>
//       </div>
//     );
//   }

//   return (
//     <div className="full-page-container">

//       {/* HEADER */}
//       <div className="page-header" style={{ display: "flex", gap: "15px", alignItems: "center" }}>
//         <img src={Logo} style={{ width: "60px" }} />
//         <div>
//           <h1 className="title">My Performance</h1>
//           <p className="subtitle">{profile?.cohort_name} | {profile?.batch_name}</p>
//         </div>
//       </div>

//       {/* SUMMARY */}
//       <div style={{ display: "flex", gap: "15px", marginBottom: "20px" }}>
//         <Card title="Overall %" value={`${summary?.attendance_percent || 0}%`} />
//         <Card title="Total Classes" value={summary?.total_classes || 0} />
//         <Card title="Attended" value={summary?.attended_classes || 0} />
//         <Card title="Exam Score" value={summary?.exam_score || "-"} />
//       </div>

//       {/* FILTERS */}
//       <div className="filter-bar" style={{ gap: "10px" }}>
//         <input type="date" value={fromDate} onChange={e => setFromDate(e.target.value)} />
//         <input type="date" value={toDate} onChange={e => setToDate(e.target.value)} />

//         <button className="btn primary" onClick={fetchCustom}>
//           Apply Range
//         </button>

//         <div className="filter-item search-box" style={{ flex: 1 }}>
//           <Search size={18} />
//           <input
//             placeholder="Search subject..."
//             value={search}
//             onChange={e => setSearch(e.target.value)}
//           />
//         </div>
//       </div>

//       {/* MONTHLY */}
//       <Section title="Monthly Attendance">
//         {monthlyData.map((m, i) => (
//           <Bar key={i} label={m.month} value={m.percent} />
//         ))}
//       </Section>

//       {/* WEEKLY */}
//       <Section title="Weekly Attendance">
//         {weeklyData.map((w, i) => (
//           <Bar key={i} label={w.week_start} value={w.percent} />
//         ))}
//       </Section>

//       {/* CUSTOM RANGE */}
//       {customData.length > 0 && (
//         <Section title="Custom Range Attendance">
//           {customData.map((c, i) => (
//             <Bar key={i} label={c.subject_name} value={c.attendance_percent} />
//           ))}
//         </Section>
//       )}

//       {/* SUBJECT TABLE */}
//       <div className="table-wrapper shadow-md rounded-lg bg-white">
//         <table className="students-table">
//           <thead>
//             <tr>
//               <th>Subject</th>
//               <th>Total</th>
//               <th>Attended</th>
//               <th>%</th>
//             </tr>
//           </thead>
//           <tbody>
//             {filtered.map((s, i) => (
//               <tr key={i}>
//                 <td>{s.subject_name}</td>
//                 <td>{s.total_classes}</td>
//                 <td>{s.attended_classes}</td>
//                 <td>
//                   <span className={s.attendance_percent > 75 ? "badge-success" : "badge-danger"}>
//                     {s.attendance_percent}%
//                   </span>
//                 </td>
//               </tr>
//             ))}
//           </tbody>
//         </table>
//       </div>

//     </div>
//   );
// }

// /* COMPONENTS */

// const Card = ({ title, value }) => (
//   <div style={{
//     flex: 1,
//     background: "white",
//     padding: "15px",
//     borderRadius: "10px",
//     textAlign: "center"
//   }}>
//     <p>{title}</p>
//     <h2>{value}</h2>
//   </div>
// );

// const Section = ({ title, children }) => (
//   <div style={{ margin: "20px 0" }}>
//     <h3 style={{ marginBottom: "10px" }}>{title}</h3>
//     {children}
//   </div>
// );

// const Bar = ({ label, value }) => (
//   <div style={{ marginBottom: "8px" }}>
//     <div style={{ fontSize: "12px" }}>{label}</div>
//     <div style={{
//       height: "10px",
//       background: "#e5e7eb",
//       borderRadius: "5px"
//     }}>
//       <div style={{
//         width: `${value}%`,
//         background: value > 75 ? "#10b981" : "#ef4444",
//         height: "100%",
//         borderRadius: "5px"
//       }} />
//     </div>
//     <div style={{ fontSize: "12px" }}>{value}%</div>
//   </div>
// );




import React, { useEffect, useState, useCallback } from "react";
import axios from "axios";
import { useAuth } from "../../contexts/AuthContext";
import { Loader2, Search } from "lucide-react";
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer,
  BarChart, Bar, CartesianGrid
} from "recharts";
import Logo from "../../assets/RCF-PP.jpg";

const BACKEND = process.env.REACT_APP_BACKEND_API_URL;
const API_BASE = `${BACKEND}/api/student`;

export default function StudentPerformanceAdvanced() {
  const [profile, setProfile] = useState(null);
  const [summary, setSummary] = useState({});
  const [subjectData, setSubjectData] = useState([]);
  const [monthlyData, setMonthlyData] = useState([]);
  const [weeklyData, setWeeklyData] = useState([]);
  const [customData, setCustomData] = useState([]);

  const [search, setSearch] = useState("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [loading, setLoading] = useState(true);

  const auth = useAuth();
  const token = auth?.user?.token;

  const fetchData = useCallback(async () => {
    if (!token) return;

    setLoading(true);
    try {
      const headers = { Authorization: `Bearer ${token}` };

      const [p, s, sub, m, w] = await Promise.all([
        axios.get(`${API_BASE}/profile`, { headers }),
        axios.get(`${API_BASE}/summary`, { headers }),
        axios.get(`${API_BASE}/performance`, { headers }),
        axios.get(`${API_BASE}/monthly`, { headers }),
        axios.get(`${API_BASE}/weekly`, { headers }),
      ]);

      setProfile(p.data);
      setSummary(s.data);
      setSubjectData(sub.data);
      setMonthlyData(m.data);
      setWeeklyData(w.data);

    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const fetchCustom = async () => {
    if (!fromDate || !toDate) return;

    try {
      const res = await axios.get(`${API_BASE}/custom`, {
        params: { fromDate, toDate },
        headers: { Authorization: `Bearer ${token}` }
      });
      setCustomData(res.data);
    } catch (e) {
      console.error(e);
    }
  };

  const filtered = subjectData.filter(s =>
    s.subject_name?.toLowerCase().includes(search.toLowerCase())
  );

  if (loading) {
    return (
      <div className="loader-container">
        <Loader2 className="spinner" size={40} />
        <p>Loading performance...</p>
      </div>
    );
  }

  return (
    <div className="full-page-container">

      {/* HEADER */}
      <div className="page-header flex">
        <img src={Logo} className="logo" />
        <div>
          <h1 className="title">My Performance Dashboard</h1>
          <p className="subtitle">
            {profile?.cohort_name} | {profile?.batch_name}
          </p>
        </div>
      </div>

      {/* SUMMARY */}
      <div className="summary-grid">
        <Card title="Overall %" value={`${summary?.attendance_percent || 0}%`} />
        <Card title="Total Classes" value={summary?.total_classes || 0} />
        <Card title="Attended" value={summary?.attended_classes || 0} />
        <Card title="Exam Score" value={summary?.exam_score || "-"} />
      </div>

      {/* FILTERS */}
      <div className="filter-bar">
        <input type="date" value={fromDate} onChange={e => setFromDate(e.target.value)} />
        <input type="date" value={toDate} onChange={e => setToDate(e.target.value)} />

        <button className="btn primary" onClick={fetchCustom}>
          Apply Range
        </button>

        <div className="search-box">
          <Search size={16} />
          <input
            placeholder="Search subject..."
            value={search}
            onChange={e => setSearch(e.target.value)}
          />
        </div>
      </div>

      {/* CHARTS */}
      <div className="chart-grid">

        {/* MONTHLY */}
        <div className="card">
          <h3 className="section-title">📊 Monthly Attendance</h3>
          <ResponsiveContainer width="100%" height={280}>
            <LineChart data={monthlyData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="month" />
              <YAxis />
              <Tooltip />
              <Line type="monotone" dataKey="percent" stroke="#3b82f6" strokeWidth={3} />
            </LineChart>
          </ResponsiveContainer>
        </div>

        {/* WEEKLY */}
        <div className="card">
          <h3 className="section-title">📅 Weekly Attendance</h3>
          <ResponsiveContainer width="100%" height={280}>
            <BarChart data={weeklyData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="week_start" />
              <YAxis />
              <Tooltip />
              <Bar dataKey="percent" fill="#10b981" />
            </BarChart>
          </ResponsiveContainer>
        </div>

      </div>

      {/* SUBJECT TABLE */}
      <div className="card">
        <h3 className="section-title">📚 Subject Performance</h3>
        <table className="students-table">
          <thead>
            <tr>
              <th>Subject</th>
              <th>Total</th>
              <th>Attended</th>
              <th>%</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((s, i) => (
              <tr key={i}>
                <td>{s.subject_name}</td>
                <td>{s.total_classes}</td>
                <td>{s.attended_classes}</td>
                <td>
                  <span className={s.attendance_percent > 75 ? "badge-success" : "badge-danger"}>
                    {s.attendance_percent}%
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* CUSTOM */}
      {customData.length > 0 && (
        <div className="card">
          <h3 className="section-title">📅 Custom Range</h3>
          {customData.map((c, i) => (
            <p key={i}>{c.subject_name} - {c.attendance_percent}%</p>
          ))}
        </div>
      )}

      {/* STYLES */}
      <style>{`
        .full-page-container { padding: 25px; background:#f9fafb; }
        .page-header { display:flex; gap:15px; align-items:center; margin-bottom:20px; }
        .logo { width:60px; height:60px; border-radius:8px; }

        .summary-grid { display:grid; grid-template-columns: repeat(4,1fr); gap:15px; margin-bottom:20px; }

        .card {
          background:white;
          padding:20px;
          border-radius:12px;
          box-shadow:0 4px 10px rgba(0,0,0,0.05);
        }

        .chart-grid { display:grid; grid-template-columns:1fr 1fr; gap:20px; margin-bottom:20px; }

        .section-title { margin-bottom:10px; font-weight:600; }

        .search-box { display:flex; align-items:center; gap:5px; border:1px solid #ddd; padding:5px 10px; border-radius:6px; }

        .loader-container { height:70vh; display:flex; justify-content:center; align-items:center; flex-direction:column; }
        .spinner { animation: spin 1s linear infinite; }
        @keyframes spin { to { transform: rotate(360deg); } }
      `}</style>
    </div>
  );
}

const Card = ({ title, value }) => (
  <div className="card" style={{ textAlign:"center" }}>
    <p style={{ color:"#6b7280" }}>{title}</p>
    <h2 style={{ fontSize:"22px", fontWeight:"bold" }}>{value}</h2>
  </div>
);