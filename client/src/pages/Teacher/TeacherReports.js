import React, { useEffect, useState, useRef } from "react";
import axios from "axios";
import jsPDF from "jspdf"; 
import autoTable from "jspdf-autotable";
import * as XLSX from "xlsx";
import {
  Calendar, Loader2, Download,
  ChevronDown, FileText, Package, Play, ClipboardList
} from "lucide-react";
import { useAuth } from "../../contexts/AuthContext";
import Logo from "../../assets/RCF-PP.jpg";

const BACKEND_BASE = process.env.REACT_APP_BACKEND_API_URL;

export default function TeacherReports() {
  const { user } = useAuth();
  
  const todayISO = new Date().toISOString().split("T")[0];
  const sevenDaysAgoISO = new Date(Date.now() - 6 * 24 * 60 * 60 * 1000).toISOString().split("T")[0];

  const [fromDate, setFromDate] = useState(sevenDaysAgoISO);
  const [toDate, setToDate] = useState(todayISO);
  const [reportLoading, setReportLoading] = useState(false);
  const [reportData, setReportData] = useState(null);
  
  const [exportOpen, setExportOpen] = useState(false);
  const exportRef = useRef(null);

  // Close export dropdown when clicking outside
  useEffect(() => {
    const handleClick = (e) => {
      if (exportRef.current && !exportRef.current.contains(e.target)) setExportOpen(false);
    };
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, []);

  const formatDateLabel = (dateString) => {
    if (!dateString) return "-";
    const [y, m, d] = dateString.split("T")[0].split("-");
    return `${d}/${m}/${y}`;
  };

  const generateReport = async () => {
    if (!user?.token) return;
    setReportData(null); 
    setReportLoading(true);
    
    try {
      const res = await axios.get(`${BACKEND_BASE}/api/teacher/reports/my-classes`, {
        headers: { Authorization: `Bearer ${user.token}` },
        params: { fromDate, toDate }
      });
      setReportData(res.data.classes || []);
    } catch (err) { 
        console.error("Failed to generate report", err);
        alert("Failed to fetch report data.");
    } finally { 
        setReportLoading(false); 
    }
  };

  // Helper for generating PDFs with images
  const imageUrlToDataURL = (url) => fetch(url).then((res) => res.blob()).then((blob) => new Promise((resolve, reject) => {
    const reader = new FileReader(); reader.onloadend = () => resolve(reader.result); reader.onerror = reject; reader.readAsDataURL(blob);
  }));

  const exportReport = async (format) => {
    if (!reportData || reportData.length === 0) return alert("Generate a report with data first.");
    
    const userFileName = prompt("Enter a name for your file:", "My_Classes_Report");
    if (userFileName === null) return; 
    const finalFileName = userFileName.trim() || "My_Classes_Report";
    
    setExportOpen(false);

    // --- EXCEL EXPORT ---
    if (format === "excel") {
      const workbook = XLSX.utils.book_new();
      let rows = [
          [finalFileName.toUpperCase()], 
          [`From: ${formatDateLabel(fromDate)}  To: ${formatDateLabel(toDate)}`], 
          [],
          ["Sl No", "Date", "Cohort", "Batch(es)", "Classroom", "Subject", "Attendance Status"]
      ];

      reportData.forEach((r, idx) => {
        rows.push([
            idx + 1, 
            formatDateLabel(r.date), 
            r.cohort_name, 
            r.batch_name, 
            r.classroom_name, 
            r.subject_name,
            r.attendance_marked ? "Marked" : "PENDING"
        ]);
      });

      const ws = XLSX.utils.aoa_to_sheet(rows);
      XLSX.utils.book_append_sheet(workbook, ws, "Report");
      XLSX.writeFile(workbook, `${finalFileName}.xlsx`);
    }

    // --- PDF EXPORT ---
    if (format === "pdf") {
      const doc = new jsPDF({ unit: "pt", format: "a4" });
      const pageWidth = doc.internal.pageSize.getWidth();
      
      let logoDataUrl = null;
      try { logoDataUrl = await imageUrlToDataURL(Logo); } catch (e) { }
      
      // Header
      doc.setFontSize(11); doc.setFont("helvetica", "bold");
      doc.text(finalFileName.toUpperCase(), (pageWidth - doc.getTextWidth(finalFileName.toUpperCase())) / 2, 45);
      if (logoDataUrl) { doc.addImage(logoDataUrl, "JPEG", pageWidth - 80, 15, 45, 45); }
      
      doc.setFontSize(9);
      doc.text(`From: ${formatDateLabel(fromDate)}`, 40, 75);
      doc.text(`To: ${formatDateLabel(toDate)}`, pageWidth - 120, 75);

      // Table Data
      const columns = ["Sl No", "Date", "Cohort", "Batch(es)", "Classroom", "Subject", "Attendance Status"];
      const body = reportData.map((r, i) => [
          i + 1, 
          formatDateLabel(r.date), 
          r.cohort_name, 
          r.batch_name, 
          r.classroom_name, 
          r.subject_name,
          r.attendance_marked ? "Marked" : "PENDING"
      ]);

      autoTable(doc, {
        startY: 90, head: [columns], body: body, theme: "grid",
        headStyles: { fillColor: [59, 130, 246] }, // Teacher blue theme
        styles: { fontSize: 8, halign: 'center' },
        didDrawCell: (data) => {
          // Highlight pending attendance in light red
          if (data.section === 'body' && data.column.index === 6 && reportData[data.row.index]?.attendance_marked === false) {
            doc.setFillColor(254, 226, 226);
            doc.rect(data.cell.x, data.cell.y, data.cell.width, data.cell.height, 'F');
            doc.setTextColor(185, 28, 28);
            doc.text("PENDING", data.cell.x + data.cell.width / 2, data.cell.y + data.cell.height / 2, { align: 'center', baseline: 'middle' });
          }
        }
      });
      doc.save(`${finalFileName}.pdf`);
    }
  };

  return (
    <div className="reports-page-container">
      <div className="container" style={{ maxWidth: '1100px', margin: '0 auto', padding: '24px' }}>
        
        {/* HEADER */}
        <div className="page-header shadow-sm bg-white rounded-lg" style={{ marginBottom: '24px', padding: '20px 24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', border: '1px solid #e2e8f0' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '15px' }}>
            <img src={Logo} alt="Logo" style={{ width: '60px', height: '60px', borderRadius: '8px' }} />
            <div>
              <h1 className="title" style={{ margin: 0, fontSize: '22px', color: '#0f172a', fontWeight: '700' }}>My Class Reports</h1>
              <p className="subtitle" style={{ margin: 0, color: '#64748b', fontSize: '14px' }}>View and export your teaching history</p>
            </div>
          </div>
          
          <div className="export-menu-wrapper" ref={exportRef} style={{ position: 'relative' }}>
            <button className="btn-export" onClick={() => setExportOpen(!exportOpen)} disabled={!reportData}>
              <Download size={18} /> Export <ChevronDown size={14} />
            </button>
            {exportOpen && (
              <div className="export-dropdown">
                <button onClick={() => exportReport('pdf')}><FileText size={14} className="mr-2 inline" /> PDF Document</button>
                <button onClick={() => exportReport('excel')}><Package size={14} className="mr-2 inline" /> Excel Sheet</button>
              </div>
            )}
          </div>
        </div>

        {/* FILTER BAR */}
        <div className="filter-bar bg-white shadow-sm rounded-lg" style={{ padding: '20px', marginBottom: '24px', border: '1px solid #e2e8f0' }}>
          <div className="filter-item">
             <label>From Date</label>
             <div className="input-wrap">
               <Calendar className="input-icon" />
               <input type="date" value={fromDate} onChange={(e) => { setFromDate(e.target.value); setReportData(null); }} />
             </div>
          </div>
          <div className="filter-item">
             <label>To Date</label>
             <div className="input-wrap">
               <Calendar className="input-icon" />
               <input type="date" value={toDate} onChange={(e) => { setToDate(e.target.value); setReportData(null); }} />
             </div>
          </div>
          <button className="btn-generate" onClick={generateReport} disabled={reportLoading}>
              {reportLoading ? <Loader2 className="animate-spin inline mr-2" size={18} /> : <Play className="inline mr-2" size={18} />} 
              Generate Report
          </button>
        </div>

        {/* DATA TABLE */}
        <div className="table-wrapper shadow-sm rounded-lg bg-white" style={{ border: '1px solid #e2e8f0', overflow: 'hidden' }}>
          {!reportData ? (
            <div className="text-center py-32" style={{ padding: '80px 20px', color: '#94a3b8', textAlign: 'center' }}>
              <ClipboardList size={56} style={{ margin: '0 auto 16px auto', opacity: 0.5 }} />
              <p style={{ fontSize: '15px', fontWeight: '500' }}>Select a date range and click Generate to view your classes.</p>
            </div>
          ) : reportData.length === 0 ? (
            <div className="text-center py-32" style={{ padding: '80px 20px', color: '#94a3b8', textAlign: 'center' }}>
              <p style={{ fontSize: '15px', fontWeight: '500' }}>No classes found for this date range.</p>
            </div>
          ) : (
            <div className="report-data-output">
              <table>
                <thead>
                  <tr>
                    <th>Sl No</th>
                    <th>Date</th>
                    <th>Cohort</th>
                    <th>Batch</th>
                    <th>Classroom</th>
                    <th>Subject</th>
                    <th>Attendance</th>
                  </tr>
                </thead>
                <tbody>
                  {reportData.map((s, i) => (
                    <tr key={i} className={!s.attendance_marked ? 'row-pending' : ''}>
                      <td className="text-center">{i + 1}</td>
                      <td style={{ fontWeight: '600', whiteSpace: 'nowrap' }}>{formatDateLabel(s.date)}</td>
                      <td>{s.cohort_name}</td>
                      <td>{s.batch_name}</td>
                      <td style={{ color: '#0369a1', fontWeight: '600' }}>{s.classroom_name}</td>
                      <td>{s.subject_name}</td>
                      <td className="text-center">
                        {s.attendance_marked ? (
                           <span className="badge-success">Marked</span>
                        ) : (
                           <span className="badge-pending">Pending</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
      
      {/* INLINE STYLES */}
      <style>{`
        .reports-page-container { background: #f8fafc; min-height: calc(100vh - 80px); box-sizing: border-box; }
        .filter-bar { display: flex; gap: 20px; align-items: flex-end; flex-wrap: wrap; }
        .filter-item { flex: 1; min-width: 200px; display: flex; flex-direction: column; gap: 6px; }
        .filter-item label { font-size: 13px; font-weight: 700; color: #475569; text-transform: uppercase; }
        .input-wrap { position: relative; }
        .input-wrap input { width: 100%; padding: 10px 10px 10px 36px; border: 1px solid #cbd5e1; border-radius: 8px; font-size: 14px; box-sizing: border-box; outline: none; transition: border 0.2s; }
        .input-wrap input:focus { border-color: #3b82f6; }
        .input-icon { position: absolute; left: 12px; top: 12px; width: 16px; height: 16px; color: #64748b; pointer-events: none; }
        
        .btn-export { display: flex; align-items: center; gap: 8px; background: #ffffff; border: 1px solid #cbd5e1; padding: 10px 16px; border-radius: 8px; font-weight: 600; color: #334155; cursor: pointer; transition: all 0.2s; }
        .btn-export:hover:not(:disabled) { background: #f1f5f9; border-color: #94a3b8; }
        .btn-export:disabled { opacity: 0.5; cursor: not-allowed; }
        
        .btn-generate { background: #2563eb; color: white; border: none; padding: 10px 24px; border-radius: 8px; font-weight: 600; font-size: 14px; cursor: pointer; transition: background 0.2s; height: 41px; }
        .btn-generate:hover:not(:disabled) { background: #1d4ed8; }
        .btn-generate:disabled { opacity: 0.7; cursor: not-allowed; }
        
        .export-dropdown { position: absolute; top: calc(100% + 5px); right: 0; background: white; border: 1px solid #e2e8f0; box-shadow: 0 10px 15px -3px rgba(0,0,0,0.1); border-radius: 8px; padding: 6px 0; z-index: 1000; min-width: 180px; }
        .export-dropdown button { width: 100%; padding: 10px 16px; border: none; background: none; text-align: left; cursor: pointer; font-size: 14px; font-weight: 500; color: #334155; }
        .export-dropdown button:hover { background: #f8fafc; color: #2563eb; }
        
        /* TABLE STYLES */
        .report-data-output { overflow-x: auto; width: 100%; }
        .report-data-output table { width: 100%; min-width: 900px; border-collapse: collapse; }
        .report-data-output table th { 
          background: #f8fafc; 
          padding: 14px 16px; 
          font-size: 12px; 
          border: 1px solid #e2e8f0; 
          font-weight: 700; 
          color: #475569; 
          text-transform: uppercase; 
          text-align: left; 
          white-space: nowrap;
        }
        .report-data-output table td { 
          padding: 14px 16px; 
          border: 1px solid #e2e8f0; 
          font-size: 14px; 
          text-align: left; 
          color: #334155; 
        }
        .report-data-output table th.text-center, .report-data-output table td.text-center { text-align: center; }
        .row-pending td { background-color: #fef2f2; }
        
        /* BADGES */
        .badge-success { background: #dcfce7; color: #166534; padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: 700; border: 1px solid #bbf7d0; display: inline-block; white-space: nowrap; }
        .badge-pending { background: #fee2e2; color: #b91c1c; padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: 700; border: 1px solid #fecaca; display: inline-block; white-space: nowrap; }
        
        .inline { display: inline-block; vertical-align: middle; }
        .mr-2 { margin-right: 8px; }
        .animate-spin { animation: spin 1s linear infinite; }
        @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
      `}</style>
    </div>
  );
}