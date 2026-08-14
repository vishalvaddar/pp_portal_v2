
import React, { useEffect, useState } from "react";
import axios from "axios";
import styles from "./NMMSMerge.module.css";
import {
  UploadCloud, FileText, CheckCircle, AlertCircle, Loader2,
  Download, Search, ChevronRight, Lock, RefreshCw, Send, Eye, Edit, Trash2, X, Zap, UserCheck
} from "lucide-react";

const MergeDashboard = () => {
  const [activeSection, setActiveSection] = useState("");
  const [subAction, setSubAction] = useState("");
  const API_BASE = `${process.env.REACT_APP_BACKEND_API_URL}/api/merge`;

  const [filters, setFilters] = useState({ year: "2026", state: "", division: "", district: "", search: "" });
  const [states, setStates] = useState([]);
  const [divisions, setDivisions] = useState([]);
  const [districts, setDistricts] = useState([]);

  const [listData, setListData] = useState([]);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [isLoading, setIsLoading] = useState(false);

  const [file, setFile] = useState(null);
  const [uploadLog, setUploadLog] = useState([]);
  const [isUploading, setIsUploading] = useState(false);

  const [mergeData, setMergeData] = useState({ summary: { total_students: 0, mapped: 0, conflicts: 0 }, blockWise: {} });
  const [mergedDistricts, setMergedDistricts] = useState([]);

  const [selectedApp, setSelectedApp] = useState(null);
  const [selectedRes, setSelectedRes] = useState(null);
  const [localLinks, setLocalLinks] = useState({});

  const [districtStudents, setDistrictStudents] = useState([]);
  const [showDistrictModal, setShowDistrictModal] = useState(false);
  const [selectedDistrictName, setSelectedDistrictName] = useState("");
  const [modalIsCommitted, setModalIsCommitted] = useState(false);

  const [currentDistrict, setCurrentDistrict] = useState(null);
  const [isDistrictMerged, setIsDistrictMerged] = useState(false);
  const [deleteLog, setDeleteLog] = useState([]);
  const [isDeleting, setIsDeleting] = useState(false);

  const [districtStatusMap, setDistrictStatusMap] = useState({});

  // MODIFICATION 1: Clear data when filters change
  useEffect(() => {
    setListData([]);
    setMergeData({ summary: { total_students: 0, mapped: 0, conflicts: 0 }, blockWise: {} });
    setUploadLog([]);
    setDeleteLog([]);
    setLocalLinks({});
    setPage(1);
  }, [filters.state, filters.division, filters.district, filters.year]);

  useEffect(() => {
    const init = async () => {
      await fetchJurisdiction("STATE", null, setStates);
      await fetchMergedStatus();
    };
    init();
  }, []);

  useEffect(() => {
    if (activeSection === "merge") {
      fetchMergedStatus();
      setMergeData({ summary: { total_students: 0, mapped: 0, conflicts: 0 }, blockWise: {} });
      setLocalLinks({});
    }
  }, [activeSection, filters.year]);

  useEffect(() => {
    if (subAction === "view" && activeSection !== "") handleViewData();
  }, [page, activeSection, subAction]);

  useEffect(() => {
    if (selectedApp && selectedRes) {
      setLocalLinks(prev => ({ ...prev, [selectedApp.phase1_id]: selectedRes }));
      setSelectedApp(null);
      setSelectedRes(null);
    }
  }, [selectedApp, selectedRes]);

  useEffect(() => {
    fetchCommitStatus(mergedDistricts, setDistrictStatusMap);
  }, [mergedDistricts]);

  const fetchJurisdiction = async (type, parent, setter) => {
    try {
      const res = await axios.get(`${API_BASE}/jurisdiction`, { params: { type, parent } });
      setter(res.data || []);
    } catch (err) { setter([]); }
  };

  const fetchMergedStatus = async () => {
    try {
      const res = await axios.get(`${API_BASE}/draft-districts`, { params: { year: filters.year } });
      setMergedDistricts(res.data || []);
    } catch (err) { console.error(err); }
  };

  const handleStateChange = (val) => {
    setFilters({ ...filters, state: val, division: "", district: "" });
    setDivisions([]); setDistricts([]);
    if (val) fetchJurisdiction("DIVISION", val, setDivisions);
  };

  const handleDivisionChange = (val) => {
    setFilters({ ...filters, division: val, district: "" });
    setDistricts([]);
    if (val) fetchJurisdiction("EDUCATION DISTRICT", val, setDistricts);
  };

  const handleViewData = async () => {
    setIsLoading(true);
    setListData([]);
    const endpoint = activeSection === "p1" ? "/applications" : "/results";
    try {
      const res = await axios.get(`${API_BASE}${endpoint}`, { params: { ...filters, page, limit: 50 } });
      setListData(res.data.rows || []);
      setTotalPages(res.data.totalPages || 1);
    } catch (err) { alert("Error fetching data."); }
    finally { setIsLoading(false); }
  };

  const handleSearchClick = () => {
    setPage(1);
    if (activeSection === "merge") runMergeLookup();
    else { setSubAction("view"); handleViewData(); }
  };

  // const handleBulkUpload = async () => {
  //   if (!file || !filters.state || !filters.district) return alert("Please select Year, State, and District.");
  //   setIsUploading(true);
  //   setUploadLog([]);
  //   const formData = new FormData();
  //   formData.append("file", file);
  //   formData.append("year", filters.year);
  //   formData.append("state_id", filters.state);
  //   formData.append("district_id", filters.district);

  //   // MODIFICATION 2: Showing Juris Name in Log report
  //   const districtObj = districts.find(d => d.juris_code === filters.district);
  //   const jurisName = districtObj ? districtObj.juris_name : filters.district;

  //   const endpoint = activeSection === "p1" ? "/upload-p1" : "/upload-p2";
  //   try {
  //     const res = await axios.post(`${API_BASE}${endpoint}`, formData);
  //     const formattedLogs = (res.data.logs || []).map(log => 
  //       log.includes(filters.district) ? log.replace(filters.district, jurisName) : log
  //     );
  //     setUploadLog(formattedLogs);
  //     if (res.data.success) alert("Upload Successful!");
  //   } catch (err) {
  //     const errLogs = (err.response?.data?.logs || ["Upload failed."]).map(log => 
  //       log.includes(filters.district) ? log.replace(filters.district, jurisName) : log
  //     );
  //     setUploadLog(errLogs);
  //   } finally { setIsUploading(false); setFile(null); }
  // };

  const handleBulkUpload = async () => {
    if (!file || !filters.state || !filters.district) return alert("Please select Year, State, and District.");
    setIsUploading(true);
    setUploadLog([]);
    const formData = new FormData();
    formData.append("file", file);
    formData.append("year", filters.year);
    formData.append("state_id", filters.state);
    formData.append("district_id", filters.district);

    const districtObj = districts.find(d => d.juris_code === filters.district);
    const jurisName = districtObj ? districtObj.juris_name : filters.district;

    const endpoint = activeSection === "p1" ? "/upload-p1" : "/upload-p2";
    
    try {
      const res = await axios.post(`${API_BASE}${endpoint}`, formData);
      
      // Prevent replacing numeric codes in DISE error messages
      const formattedLogs = (res.data.logs || []).map(log => {
        if (log.includes("DISE Code")) return log; 
        return log.includes(filters.district) ? log.replace(filters.district, jurisName) : log;
      });

      setUploadLog(formattedLogs);
      if (res.data.success) alert("Upload Successful!");
    } catch (err) {
      // Apply the same logic to error responses
      const errLogs = (err.response?.data?.logs || ["Upload failed."]).map(log => {
        if (log.includes("DISE Code")) return log;
        return log.includes(filters.district) ? log.replace(filters.district, jurisName) : log;
      });
      setUploadLog(errLogs);
    } finally { 
      setIsUploading(false); 
      setFile(null); 
    }
  };

  const runMergeLookup = async () => {
    if (!filters.district) return alert("Select a District first.");
    setIsLoading(true);
    try {
      const res = await axios.post(`${API_BASE}/preview-merge`, { year: filters.year, district: filters.district });
      if (res.data.summary.mapped > 0) {
        await axios.post(`${API_BASE}/bulk-auto-map`, { year: filters.year, district: filters.district });
        alert(`${res.data.summary.mapped} unique students were automatically matched.`);
        const refresh = await axios.post(`${API_BASE}/preview-merge`, { year: filters.year, district: filters.district });
        setMergeData(refresh.data);
      } else {
        setMergeData(res.data);
      }
      setLocalLinks({});
      await fetchMergedStatus();
    } catch (err) { alert("Lookup failed."); }
    finally { setIsLoading(false); }
  };

  const confirmMapping = async () => {
    const links = Object.entries(localLinks);
    if (links.length === 0) return alert("Please map students first.");
    try {
      for (const [appId, resObj] of links) {
        await axios.post(`${API_BASE}/resolve-lively`, { app_id: appId, res_id: resObj.result_stg_id });
      }
      alert("Mappings saved successfully.");
      setLocalLinks({});
      await runMergeLookup();
    } catch (err) { alert("Submission failed."); }
  };

  const submitToPrimary = async (distId) => {
    if (!window.confirm("Commit to Primary Table?")) return;
    try {
      await axios.post(`${API_BASE}/commit-to-primary`, { district: distId, year: filters.year });
      alert("Committed Successfully.");
      fetchMergedStatus();
      setMergeData({ summary: { total_students: 0, mapped: 0, conflicts: 0 }, blockWise: {} });
    } catch (err) { alert("Submission failed."); }
  };

  const viewDistrictStudents = async (distId, distName, isCommitted) => {
    try {
      const res = await axios.get(`${API_BASE}/draft-district-students`, { params: { district: distId, year: filters.year, isCommitted } });
      setDistrictStudents(res.data || []);
      setSelectedDistrictName(distName);
      setModalIsCommitted(isCommitted);
      setShowDistrictModal(true);
    } catch (err) { alert("Unable to load details."); }
  };

  const handleDeleteDistrict = async () => {
    if (!filters.district) return alert("Please select a district first.");
    if (!window.confirm("Are you sure you want to delete data for this district?")) return;

    try {
      setIsDeleting(true);
      setDeleteLog([]);
      const section = activeSection === "merge" ? "merge" : "";
      const phase = activeSection === "p1" ? "p1" : activeSection === "p2" ? "p2" : "";

      const payload = { district: filters.district, year: filters.year, phase, section };

      const res = await axios.delete(`${API_BASE}/delete-district-data`, {
        headers: { "Content-Type": "application/json" },
        data: payload
      });

      setDeleteLog([res.data.message || "Deleted successfully"]);
      alert(res.data.message || "Deleted successfully");
      if (activeSection === "merge") await fetchMergedStatus();
    } catch (err) {
      const errorMsg = err.response?.data?.error || "Delete failed";
      setDeleteLog([errorMsg]);
      alert(errorMsg);
    } finally { setIsDeleting(false); }
  };

  const downloadTemplate = async (phase) => {
    try {
      const res = await axios.get(`${API_BASE}/download-template`, { params: { phase }, responseType: "blob" });
      const url = window.URL.createObjectURL(new Blob([res.data]));
      const link = document.createElement("a");
      link.href = url; link.setAttribute("download", `NMMS_${phase}_Template.csv`);
      document.body.appendChild(link); link.click(); link.remove();
    } catch (err) { alert("Failed to download template"); }
  };

  const downloadLogFile = () => {
    if (uploadLog.length === 0) return;
    const blob = new Blob([uploadLog.join("\n")], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url; link.download = `Log_${activeSection}_${filters.year}.txt`; link.click();
  };

  const fetchCommitStatus = async (districts, setter) => {
    try {
      if (districts.length === 0) return;
      const districtIds = districts.map(d => d.district_id).join(",");
      const mergeRes = await axios.get(`${API_BASE}/merge-status`, { params: { district_ids: districtIds, year: filters.year } });
      const commitRes = await axios.get(`${API_BASE}/commit-status`, { params: { year: filters.year } });

      const mergeMap = {};
      mergeRes.data.data.forEach(d => {
        mergeMap[d.district_id] = { total_merged: d.total_merged_applicants, remaining: d.remaining_applicants };
      });

      const statusMap = {};
      commitRes.data.data.forEach(d => {
        const merged = mergeMap[d.district_id] || { total_merged: 0, remaining: d.total_applicants };
        statusMap[d.district_id] = {
          total_applicants: d.total_applicants,
          total_merged: merged.total_merged,
          remaining: merged.remaining,
          is_committed: d.is_committed,
          show_commit_btn: !d.is_committed,
          show_delete_btn: !d.is_committed
        };
      });
      setter(statusMap);
    } catch (err) { console.error(err); }
  };

  const downloadDistrictCSV = async (districtId, districtName) => {
    try {
      const res = await axios.get(`${API_BASE}/district/${districtId}/download-csv`, { responseType: "blob" });
      const url = window.URL.createObjectURL(new Blob([res.data]));
      const link = document.createElement("a");
      link.href = url; link.setAttribute("download", `${districtName}.csv`);
      document.body.appendChild(link); link.click(); link.remove();
    } catch (err) { alert("Failed to download CSV"); }
  };

  const renderMergedDistricts = () => {
    if (!mergedDistricts || mergedDistricts.length === 0)
      return <p style={{ textAlign: "center", padding: "20px" }}>No merged districts found.</p>;

    return mergedDistricts.map((dist, index) => {
      const status = districtStatusMap[dist.district_id] || {};
      const isCommitted = status.is_committed || false;
      const totalMerged = status.total_merged || 0;
      const remainingApplicants = status.remaining || 0;

      return (
        <div key={dist.district_id} className={styles.mergedCard}>
          <div className={styles.col}>{index + 1}</div>
          <div className={styles.col}>
            {dist.district_name}
            {totalMerged > 0 && <span style={{ color: "#f59e0b", fontWeight: "bold" }}> [Merged]</span>}
            {isCommitted && <span style={{ color: "#10b981", fontWeight: "bold" }}> [Committed]</span>}
          </div>
          <div className={styles.col}>{dist.total_applicants || 0}</div>
          <div className={styles.col}>{totalMerged}</div>
          <div className={styles.col}>{remainingApplicants}</div>
          <div className={`${styles.col} ${styles.distActions}`}>
            <button className={styles.mapBtn} onClick={() => viewDistrictStudents(dist.district_id, dist.district_name, isCommitted)}>
              <Eye size={14} /> View
            </button>
            {!isCommitted && remainingApplicants === 0 && totalMerged > 0 && (
              <button className={styles.finalSubmitBtn} onClick={() => submitToPrimary(dist.district_id)}>
                <Send size={14} /> Submit
              </button>
            )}
            <button className={styles.processBtn} style={{ background: "#ef4444", padding: "12px 40px", fontSize: "15px" }} onClick={handleDeleteDistrict} disabled={isDeleting}>
              {isDeleting ? <><Loader2 className={styles.spinner} /> Deleting...</> : <><Trash2 size={16} /> Delete District Data</>}
            </button>
            <button className={styles.downloadBtn} onClick={() => downloadDistrictCSV(dist.district_id, dist.district_name)}>
              <Download size={14} /> CSV
            </button>
          </div>
        </div>
      );
    });
  };

  return (
    <div className={styles.container}>
      <header className={styles.header}><h1>NMMS Student Reconciliation Dashboard</h1></header>

      <nav className={styles.boxContainer}>
        <div className={`${styles.bigBox} ${activeSection === 'p1' ? styles.activeBox : ''}`}
          onClick={() => { setActiveSection("p1"); setSubAction(""); setListData([]); setPage(1); }}>
          Bulk Upload Applications
        </div>
        <div className={`${styles.bigBox} ${activeSection === 'p2' ? styles.activeBox : ''}`}
          onClick={() => { setActiveSection("p2"); setSubAction(""); setListData([]); setPage(1); }}>
          Bulk Upload Results
        </div>
        <div className={`${styles.bigBox} ${activeSection === 'merge' ? styles.activeBox : ''}`}
          onClick={() => { setActiveSection("merge"); setSubAction(""); setPage(1); }}>
          Merge & Reconcile
        </div>
      </nav>

      {activeSection && (
        <main className={styles.sectionCard}>
          <div className={styles.topActionLine}>
            {(activeSection === "p1" || activeSection === "p2") && (
              <div className={styles.subActionGroup}>
                <button className={`${styles.subBtn} ${subAction === 'view' ? styles.activeSub : ''}`} onClick={() => { setSubAction("view"); setPage(1); }}><Eye size={18} /> View Data</button>
                <button className={`${styles.subBtn} ${subAction === 'upload' ? styles.activeSub : ''}`} onClick={() => setSubAction("upload")}><UploadCloud size={18} /> Upload New</button>
                <button className={`${styles.subBtn} ${subAction === 'delete' ? styles.activeSub : ''}`} onClick={() => setSubAction("delete")}><Trash2 size={18} /> Delete</button>
              </div>
            )}

            {(subAction !== "" || activeSection === "merge") && (
              <div className={styles.filterGroup}>
                <select className={styles.input} value={filters.year} onChange={e => setFilters({ ...filters, year: e.target.value })}>
                  <option value="2026">2026</option><option value="2025">2025</option>
                </select>
                <select className={styles.input} value={filters.state} onChange={e => handleStateChange(e.target.value)}>
                  <option value="">State</option>{states.map(s => <option key={s.juris_code} value={s.juris_code}>{s.juris_name}</option>)}
                </select>
                <select className={styles.input} value={filters.division} onChange={e => handleDivisionChange(e.target.value)}>
                  <option value="">Division</option>{divisions.map(d => <option key={d.juris_code} value={d.juris_code}>{d.juris_name}</option>)}
                </select>
                <select className={styles.input} value={filters.district} onChange={e => setFilters({ ...filters, district: e.target.value })}>
                  <option value="">District</option>{districts.map(d => <option key={d.juris_code} value={d.juris_code}>{d.juris_name}</option>)}
                </select>

                <div className={styles.searchWrapper}>
                  <input type="text" placeholder="Search..." className={styles.input} value={filters.search} onChange={e => setFilters({ ...filters, search: e.target.value })} />
                  <button className={styles.searchBtn} onClick={handleSearchClick}><Search size={16} /></button>
                </div>

                {activeSection === "merge" && (
                  <button className={styles.processBtn} onClick={runMergeLookup} disabled={isLoading}>{isLoading ? <Loader2 className={styles.spinner} /> : "Run Merge Lookup"}</button>
                )}
              </div>
            )}
          </div>

          {subAction === "upload" && (
            <div className={styles.uploadInterface}>
              <div className={styles.uploadGrid}>
                <div className={styles.uploadCard} onClick={() => downloadTemplate(activeSection)}><Download size={32} /><p>Download Template</p></div>
                <div className={styles.uploadCard}>
                  <input type="file" id="csvFile" accept=".csv" onChange={e => setFile(e.target.files[0])} hidden />
                  <label htmlFor="csvFile" style={{ cursor: 'pointer' }}><FileText size={32} /><p>{file ? file.name : "Choose CSV File"}</p></label>
                </div>
              </div>
              <button className={styles.processBtn} onClick={handleBulkUpload} disabled={isUploading || !file}>{isUploading ? <Loader2 className={styles.spinner} /> : "Start Processing"}</button>
            </div>
          )}

          {uploadLog.length > 0 && (
            <div className={styles.logReport}>
              <div className={styles.logHeader}><h3>Summary</h3><button className={styles.mapBtn} onClick={downloadLogFile}><Download size={14} /> Save Log</button></div>
              <div className={styles.logList}>{uploadLog.map((log, i) => <p key={i} className={log.includes('ERROR') ? styles.logErr : styles.logSuccess}>{log}</p>)}</div>
            </div>
          )}

          {subAction === "delete" && (
            <div className={styles.deleteCenterWrapper}>
              <div className={styles.deleteCenterBox}>
                <Trash2 size={32} color="#dc2626" />
                <p style={{ marginTop: "10px", fontWeight: "600" }}>Delete Uploaded Data for Selected District</p>
                <p style={{ fontSize: "13px", color: "#6b7280" }}>This will delete all {activeSection === "p1" ? "application" : "result"} data for the selected district.</p>
                <select className={styles.input} value={filters.year} onChange={e => setFilters({ ...filters, year: e.target.value })}>
                  <option value="2026">2026</option><option value="2025">2025</option>
                </select>
                <select className={styles.input} value={filters.state} onChange={e => handleStateChange(e.target.value)}>
                  <option value="">State</option>{states.map(s => <option key={s.juris_code} value={s.juris_code}>{s.juris_name}</option>)}
                </select>
                <select className={styles.input} value={filters.division} onChange={e => handleDivisionChange(e.target.value)}>
                  <option value="">Division</option>{divisions.map(d => <option key={d.juris_code} value={d.juris_code}>{d.juris_name}</option>)}
                </select>
                <select className={styles.input} value={filters.district} onChange={e => setFilters({ ...filters, district: e.target.value })}>
                  <option value="">District</option>{districts.map(d => <option key={d.juris_code} value={d.juris_code}>{d.juris_name}</option>)}
                </select>
                <button className={styles.processBtn} style={{ background: "#ef4444", padding: "12px 40px", fontSize: "15px" }} onClick={handleDeleteDistrict} disabled={isDeleting}>
                  {isDeleting ? <><Loader2 className={styles.spinner} /> Deleting...</> : <><Trash2 size={16} /> Delete District Data</>}
                </button>
                {deleteLog.length > 0 && (
                  <div className={styles.logReport} style={{ marginTop: "15px" }}>
                    <div className={styles.logList}>{deleteLog.map((log, i) => <p key={i} className={log.includes("ERROR") ? styles.logErr : styles.logSuccess}>{log}</p>)}</div>
                  </div>
                )}
              </div>
            </div>
          )}

          {subAction === "view" && listData.length > 0 && (
            <div className={styles.tableContainer}>
              <table className={styles.table}>
                <thead>
                  {activeSection === "p1" ? (
                    <tr><th>Sl No</th><th>Student Name</th><th>Father Name</th><th>District</th><th>Block</th><th>School Name</th><th>Contact 1</th><th>SATS ID</th></tr>
                  ) : (
                    <tr><th>Sl No</th><th>Student Name</th><th>Reg Number</th><th>District</th><th>Block</th><th>GMAT Score</th><th>SAT Score</th></tr>
                  )}
                </thead>
                <tbody>
                  {listData.map((row, i) => (
                    <tr key={i}>
                      <td>{(page - 1) * 50 + (i + 1)}</td><td>{row.student_name}</td>
                      {activeSection === "p1" ? (
                        <><td style={{ textAlign: 'left' }}>{row.father_name || "N/A"}</td><td>{row.district_name}</td><td>{row.nmms_block_name}</td><td>{row.institute_name}</td><td>{row.contact_no1}</td><td>{row.students_sats_id}</td></>
                      ) : (
                        <><td style={{ textAlign: 'left' }}>{row.nmms_reg_number}</td><td>{row.district_name}</td><td>{row.nmms_block_name}</td><td>{row.gmat_score}</td><td>{row.sat_score}</td></>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className={styles.logHeader} style={{ marginTop: '15px' }}>
                <button disabled={page === 1} className={styles.mapBtn} onClick={() => setPage(p => p - 1)}>Prev</button>
                <span>Page {page} of {totalPages}</span>
                <button disabled={page === totalPages} className={styles.mapBtn} onClick={() => setPage(p => p + 1)}>Next</button>
              </div>
            </div>
          )}

          {activeSection === "merge" && (
            <section className={styles.mergeSection}>
              <div className={styles.reconcileWindow} style={{ display: 'block' }}>
                {Object.keys(mergeData.blockWise).length > 0 ? Object.keys(mergeData.blockWise).map(block => {
                  const conflictApps = mergeData.blockWise[block].filter(app => app.candidates && app.candidates.length > 1);
                  if (conflictApps.length === 0) return null;
                  const resultPool = Array.from(new Set(conflictApps.flatMap(app => app.candidates.map(c => JSON.stringify(c))))).map(s => JSON.parse(s));
                  return (
                    <div key={block} className={styles.blockGroup} style={{ border: "1px solid #e2e8f0", padding: "20px", borderRadius: "12px", marginBottom: "30px", background: "#fff" }}>
                      <p style={{ marginBottom: "20px", color: "#ef4444", fontWeight: "500" }}>Multiple students with the same names found in Block : {block}. Please resolve conflicts by mapping NMMS application with NMMS Results..</p>
                      <h2 className={styles.blockTitle}>EDUCATION BLOCK: {block}</h2>
                      <div className={styles.reconcileRow} style={{ display: "flex", gap: "25px" }}>
                        <div className={styles.leftSide} style={{ flex: 1 }}>
                          <div className={styles.badge} style={{ backgroundColor: "#e0f2fe", color: "#0369a1", textAlign: "center" }}>NMMS APPLICATIONS</div>
                          {conflictApps.map((app, i) => {
                            const link = localLinks[app.phase1_id];
                            const isSelected = selectedApp?.phase1_id === app.phase1_id;
                            return (
                              <div key={app.phase1_id} onClick={() => !link && setSelectedApp(app)} className={`${styles.matchCard} ${isSelected ? styles.selectedCard : ""}`} style={{ border: isSelected ? "2px solid #0284c7" : link ? "2px solid #10b981" : "1px solid #e2e8f0", background: isSelected ? "#e0f2fe" : link ? "#f0fdf4" : "#fff", marginBottom: "15px", padding: "12px", borderRadius: "8px", position: "relative", cursor: link ? "default" : "pointer" }}>
                                <span style={{ position: "absolute", right: "10px", top: "5px", fontSize: "11px", fontWeight: "bold", color: "#94a3b8" }}>#{i + 1}</span>
                                <div style={{ fontSize: "12px", lineHeight: "1.6" }}>
                                  <div><strong>Student name :</strong> {app.student_name}</div>
                                  <div><strong>Father name :</strong> {app.father_name}</div>
                                  <div><strong>Sats id :</strong> {app.students_sats_id}</div>
                                  <div><strong>School :</strong> {app.institute_name}</div>
                                  <div><strong>Contacts :</strong> {app.contact_no1}{app.contact_no2 ? `, ${app.contact_no2}` : ""}</div>
                                </div>
                                {link && <div style={{ fontSize: "11px", color: "#16a34a", fontWeight: "bold", marginTop: "10px", borderTop: "1px dashed #16a34a", paddingTop: "5px" }}>Linked to Pool R{resultPool.findIndex(r => r.result_stg_id === link.result_stg_id) + 1}</div>}
                              </div>
                            );
                          })}
                        </div>
                        <div style={{ alignSelf: "center" }}><ChevronRight size={32} color="#cbd5e1" /></div>
                        <div className={styles.rightSide} style={{ flex: 1 }}>
                          <div className={styles.badge} style={{ background: "#fef3c7", color: "#92400e", textAlign: "center" }}>NMMS Results</div>
                          {resultPool.map((res, i) => {
                            const isTaken = Object.values(localLinks).some(link => link.result_stg_id === res.result_stg_id);
                            const isSelected = selectedRes?.result_stg_id === res.result_stg_id;
                            return (
                              <div key={res.result_stg_id} onClick={() => !isTaken && setSelectedRes(res)} className={`${styles.matchCard} ${isSelected ? styles.selectedCard : ""}`} style={{ opacity: isTaken ? 0.4 : 1, border: isSelected ? "2px solid #d97706" : "1px solid #fde68a", background: isSelected ? "#fffbeb" : "#fff", marginBottom: "15px", padding: "12px", borderRadius: "8px", position: "relative", cursor: isTaken ? "not-allowed" : "pointer" }}>
                                <span style={{ position: "absolute", right: "10px", top: "5px", fontSize: "11px", fontWeight: "bold", color: "#d97706" }}>R{i + 1}</span>
                                <div style={{ fontSize: "12px", lineHeight: "1.6" }}>
                                  <div><strong>NMMS Reg :</strong> {res.nmms_reg_number}</div>
                                  <div><strong>Student :</strong> {res.student_name}</div>
                                  <div style={{ color: "#92400e", fontWeight: "bold", marginTop: "5px" }}>GMAT: {res.gmat_score} | SAT: {res.sat_score}</div>
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    </div>
                  );
                }) : <div style={{ textAlign: "center", padding: "40px" }}><p>Run Merge Lookup to resolve conflicts.</p></div>}

                {Object.keys(localLinks).length > 0 && (
                  <div style={{ padding: "40px 0", textAlign: "center", borderTop: "2px dashed #e2e8f0", marginTop: "20px" }}>
                    <button className={styles.finalSubmitBtn} onClick={confirmMapping} disabled={Object.values(mergeData.blockWise).flatMap(block => block.filter(app => app.candidates && app.candidates.length > 1)).length !== Object.keys(localLinks).length} style={{ background: Object.values(mergeData.blockWise).flatMap(block => block.filter(app => app.candidates && app.candidates.length > 1)).length === Object.keys(localLinks).length ? "#10b981" : "#94d3c2", padding: "15px 60px", fontSize: "16px", borderRadius: "50px", boxShadow: "0 10px 15px -3px rgba(16, 185, 129, 0.3)", cursor: "pointer" }}>
                      <UserCheck size={20} /> Confirm Mapping Selection ({Object.keys(localLinks).length} Links Ready)
                    </button>
                    <button onClick={() => setLocalLinks({})} style={{ display: "block", margin: "10px auto", color: "#ef4444", background: "none", border: "none", cursor: "pointer", textDecoration: "underline" }}>Cancel and Reset Mapping</button>
                  </div>
                )}
              </div>
              <h3>Merged Districts</h3>
              <div className={styles.mergedGrid}>
                <div className={`${styles.mergedCard} ${styles.headerCard}`}>
                  <div className={styles.col}>#</div><div className={styles.col}>District Name</div><div className={styles.col}>Total Applicants</div><div className={styles.col}>Total Merged</div><div className={styles.col}>Remaining</div><div className={styles.col}>Actions</div>
                </div>
                {renderMergedDistricts()}
              </div>
            </section>
          )}
        </main>
      )}

      {showDistrictModal && (
        <div className={styles.modalOverlay}>
          <div className={styles.modalContent} style={{ width: "95%", maxWidth: "1300px" }}>
            <div className={styles.modalHeader}><h3>{selectedDistrictName} - Data Records</h3><button onClick={() => setShowDistrictModal(false)}><X size={20} /></button></div>
            <div className={styles.tableContainer}><table className={styles.table}><thead><tr><th>Sl No</th><th>Student Name</th><th>Father Name</th><th>Reg Number</th><th>GMAT</th><th>SAT</th><th>School Name</th><th>Contact 1</th></tr></thead><tbody>{districtStudents.map((r, i) => (<tr key={i}><td>{i + 1}</td><td>{r.student_name}</td><td>{r.father_name || "N/A"}</td><td>{r.nmms_reg_number}</td><td>{r.gmat_score}</td><td>{r.sat_score}</td><td>{r.institute_name || r.school_name || "N/A"}</td><td>{r.contact_no1}</td></tr>))}</tbody></table></div>
          </div>
        </div>
      )}
    </div>
  );
};

export default MergeDashboard;