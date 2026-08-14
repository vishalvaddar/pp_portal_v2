import React, { useEffect, useState, useCallback } from "react";
import axios from "axios";
import "../Coordinator/BatchManagement.css"; 
import { useAuth } from "../../contexts/AuthContext";
import jsPDF from "jspdf";
import autoTable from "jspdf-autotable";
import * as XLSX from "xlsx";
import { 
  Search, Download, ChevronDown, 
  Filter, Layout, ExternalLink, FileText
} from "lucide-react";
import Logo from "../../assets/RCF-PP.jpg";

const BACKEND = process.env.REACT_APP_BACKEND_API_URL;
const API_BASE = `${BACKEND}/api/teacher`; 
const initial = { cohort: "", batch: "" };
const DAY_ORDER = ["SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"];

export default function TeacherTimeTable() {
  const [form, setForm] = useState(initial);
  const [cohorts, setCohorts] = useState([]);
  const [batches, setBatches] = useState([]);
  const [filteredBatches, setFilteredBatches] = useState([]);
  const [timetable, setTimetable] = useState([]);
  
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState("");
  const [exportOpen, setExportOpen] = useState(false);

  const auth = useAuth();
  const token = auth?.user?.token;

  const axiosConfig = useCallback(() => ({
    headers: { Authorization: `Bearer ${token}` },
  }), [token]);

  const t12 = (timeStr) => {
    if (!timeStr) return "-";
    const [hours, minutes] = timeStr.split(":");
    let h = parseInt(hours, 10);
    const ampm = h >= 12 ? "PM" : "AM";
    h = h % 12 || 12;
    return `${h}:${minutes} ${ampm}`;
  };

  /* ------------------------- FETCH LOGIC -------------------------*/
  useEffect(() => {
    if (!token) return;
    axios.get(`${API_BASE}/cohorts`, axiosConfig()).then(({ data }) => setCohorts(data || [])).catch(console.error);
  }, [token, axiosConfig]);

  useEffect(() => {
    if (!token || !form.cohort) {
      setFilteredBatches([]); setBatches([]); return;
    }
    axios.get(`${API_BASE}/batches?cohort_number=${form.cohort}`, axiosConfig()).then(({ data }) => {
      setBatches(data || []); setFilteredBatches(data || []);
    }).catch(console.error);
  }, [token, form.cohort, axiosConfig]);

  // Changed: Will now fetch immediately on load, and re-fetch if batch changes
  useEffect(() => {
    if (!token) return;
    fetchTimetable(form.batch);
  }, [token, form.batch, axiosConfig]);

  const fetchTimetable = async (batchId) => {
    setLoading(true);
    try {
      // Pass batchId only if it is selected
      const params = batchId ? { batchId } : {};
      const { data } = await axios.get(`${API_BASE}/timetable`, { ...axiosConfig(), params });
      setTimetable(data || []);
    } catch (err) { setTimetable([]); } finally { setLoading(false); }
  };


  /* ------------------------- EXPORT LOGIC -------------------------*/
  const downloadPDF = async () => {
    const doc = new jsPDF({ unit: "pt", format: "a4" });
    const pageWidth = doc.internal.pageSize.getWidth();
    const margin = 40;

    try {
      const logoWidth = 50;
      const logoHeight = 50;
      doc.addImage(Logo, 'JPEG', pageWidth - margin - logoWidth, 20, logoWidth, logoHeight);
    } catch (e) {}

    const batchObj = filteredBatches.find(b => String(b.batch_id) === String(form.batch));
    const title = form.batch ? `COHORT ${form.cohort} - ${batchObj?.batch_name || "N/A"} - TIME TABLE` : "MY COMPLETE SCHEDULE";

    doc.setFont("helvetica", "bold");
    doc.setFontSize(14);
    doc.text(title, pageWidth / 2, 45, { align: "center" });

    const flatRows = DAY_ORDER.filter(d => groupedTimetable[d]).flatMap(day => {
      const sessions = groupedTimetable[day];
      return sessions.map((r, index) => ({
        ...r,
        rowSpan: index === 0 ? sessions.length : 0 
      }));
    });

    autoTable(doc, {
      startY: 80,
      head: [["Day", "Time", "Subject Code", "Teacher", "Classroom", "Link"]],
      body: flatRows.map(r => [
        r.rowSpan > 0 ? { content: r.day_of_week, rowSpan: r.rowSpan } : null,
        `${t12(r.start_time)} - ${t12(r.end_time)}`,
        r.subject_code || "-",
        r.teacher_name || "-",
        r.classroom_name || "-",
        r.class_link ? "JOIN CLASS" : "-"
      ].filter(cell => cell !== null)),
      
      theme: "grid",
      headStyles: { fillColor: [16, 185, 129], halign: 'center' },
      styles: { fontSize: 8, valign: 'middle' },
      columnStyles: {
        0: { halign: 'center', fontStyle: 'bold' },
        5: { halign: 'center' }                    
      },

      willDrawCell: (data) => {
        if (data.section === 'body' && data.column.index === 5) {
          const link = flatRows[data.row.index]?.class_link;
          if (link && link !== "-") {
            doc.setTextColor(0, 0, 255);
          }
        }
      },

      didDrawCell: (data) => {
        if (data.section === 'body' && data.column.index === 5) {
          const link = flatRows[data.row.index]?.class_link;
          if (link && link !== "-") {
            const text = data.cell.text[0];
            const textWidth = doc.getTextWidth(text);
            const startX = data.cell.x + (data.cell.width - textWidth) / 2;
            const startY = data.cell.y + data.cell.height - 5;
            doc.setDrawColor(0, 0, 255);
            doc.line(startX, startY, startX + textWidth, startY);
            doc.link(data.cell.x, data.cell.y, data.cell.width, data.cell.height, { url: link });
          }
        }
      }
    });

    doc.save("Timetable.pdf");
    setExportOpen(false);
  };

  const downloadExcel = () => {
    const rows = timetable.map(r => ({
      Day: r.day_of_week, Time: `${t12(r.start_time)} - ${t12(r.end_time)}`,
      "Subject Code": r.subject_code || "-", Teacher: r.teacher_name || "-",
      Classroom: r.classroom_name || "-", Link: r.class_link || "-"
    }));
    const ws = XLSX.utils.json_to_sheet(rows);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, "Timetable");
    XLSX.writeFile(wb, "Timetable.xlsx");
    setExportOpen(false);
  };

  /* ------------------------- RENDER LOGIC -------------------------*/
  const filteredTimetable = timetable.filter(r =>
    r.subject_name?.toLowerCase().includes(search.toLowerCase()) ||
    r.teacher_name?.toLowerCase().includes(search.toLowerCase()) ||
    r.classroom_name?.toLowerCase().includes(search.toLowerCase())
  );

  const groupedTimetable = filteredTimetable.reduce((acc, curr) => {
    if (!acc[curr.day_of_week]) acc[curr.day_of_week] = [];
    acc[curr.day_of_week].push(curr);
    return acc;
  }, {});

  const orderedDays = DAY_ORDER.filter(d => groupedTimetable[d]);

  return (
    <div className="full-page-container">
      {/* HEADER */}
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '15px' }}>
          <img src={Logo} alt="Logo" style={{ width: '60px', height: '60px', borderRadius: '8px' }} />
          <div>
            <h1 className="title">My Schedule</h1>
            <p className="subtitle">Viewing {timetable.length} active scheduling slots</p>
          </div>
        </div>

        <div className="export-menu-wrapper">
          <button className="btn primary" onClick={() => setExportOpen(!exportOpen)} style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Download size={18} /> Export <ChevronDown size={14} />
          </button>
          {exportOpen && (
            <div className="export-dropdown shadow-lg">
              <button onClick={downloadExcel}><FileText size={14} className="mr-2 inline"/> Excel (.xlsx)</button>
              <button onClick={downloadPDF}><FileText size={14} className="mr-2 inline"/> PDF (.pdf)</button>
            </div>
          )}
        </div>
      </div>

      {/* FILTER BAR */}
      <div className="filter-bar">
        <div className="filter-item">
          <Filter className="input-icon" size={18} />
          <select value={form.cohort} onChange={e => setForm({ cohort: e.target.value, batch: "" })}>
            <option value="">All Cohorts</option>
            {cohorts.map(c => <option key={c.cohort_number} value={c.cohort_number}>{c.cohort_name}</option>)}
          </select>
        </div>
        <div className="filter-item">
          <Layout className="input-icon" size={18} />
          <select value={form.batch} disabled={!form.cohort} onChange={e => setForm({ ...form, batch: e.target.value })}>
            <option value="">Select Batch</option>
            {filteredBatches.map(b => <option key={b.batch_id} value={b.batch_id}>{b.batch_name}</option>)}
          </select>
        </div>
        <div className="filter-item search-box" style={{ flex: 1 }}>
          <Search className="input-icon" size={18} />
          <input placeholder="Search Teacher or Classroom..." value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      </div>

      {/* DATA TABLE */}
      <div className="table-wrapper shadow-md rounded-lg bg-white">
        <table className="students-table">
          <thead>
            <tr>
              <th style={{ width: "120px" }}>Day</th>
              <th>Time Slot</th>
              <th>Subject Name</th>
              <th>Assigned Teacher</th>
              <th>Classroom</th>
              <th>Online Link</th>
            </tr>
          </thead>
          <tbody>
            {orderedDays.length > 0 ? orderedDays.map(day => groupedTimetable[day].map((r, idx) => (
              <tr key={r.timetable_id}>
                <td style={{ fontWeight: '700', color: '#1e40af' }}>{idx === 0 ? day : ""}</td>
                <td style={{ fontWeight: '600', color: '#4b5563' }}>{t12(r.start_time)} - {t12(r.end_time)}</td>
                <td>{r.subject_name}</td>
                <td>{r.teacher_name}</td>
                <td><span className="badge-room">{r.classroom_name}</span></td>
                <td>
                  {r.class_link ? (
                    <a href={r.class_link} target="_blank" rel="noreferrer" className="link-text">
                      <ExternalLink size={14} className="inline mr-1" /> Join Class
                    </a>
                  ) : <span style={{color: '#9ca3af'}}>-</span>}
                </td>
              </tr>
            ))) : (
              <tr>
                  <td colSpan="6" style={{textAlign:'center', padding:'40px', color:'#94a3b8'}}>
                      {loading ? "Loading schedule..." : "No classes scheduled for this filter."}
                  </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <style>{`
        .full-page-container { width: 100%; padding: 25px; background-color: #f9fafb; min-height: 100vh; }
        .badge-room { background: #eff6ff; color: #1e40af; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 600; border: 1px solid #dbeafe; }
        .link-text { color: #10b981; font-weight: 600; text-decoration: none; display: flex; align-items: center; gap: 4px; }
      `}</style>
    </div>
  );
}