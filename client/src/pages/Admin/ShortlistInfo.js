import React, { useState, useEffect, useRef } from "react";
import styles from "./ShortlistInfo.module.css";
import Breadcrumbs from "../../components/Breadcrumbs/Breadcrumbs";
import { useSystemConfig } from "../../contexts/SystemConfigContext";

function ShortlistInfo({ onClose }) {
  // --- Year Configuration from Context ---
  const { appliedConfig, loading } = useSystemConfig();
  const currentYear = appliedConfig?.academic_year ? appliedConfig.academic_year.split("-")[0] : "";

  const currentPath = ['Admin', 'Admissions', 'Shortlisting', 'Shortlist-Info'];
  const isAdmissionsOpen = !loading && appliedConfig?.phase === "Admissions are started";

  // --- State Variables ---
  const [applicantCount, setApplicantCount] = useState(0);
  const [shortlistedCount, setShortlistedCount] = useState(0);
  const [activeBox, setActiveBox] = useState(null);
  const [shortlistNames, setShortlistNames] = useState([]);
  const [nonFrozenShortlistNames, setNonFrozenShortlistNames] = useState([]);
  const [selectedShortlistInfo, setSelectedShortlistInfo] = useState(null);

  const [selectedShortlistFreezeName, setSelectedShortlistFreezeName] = useState("");
  const [selectedShortlistFreezeId, setSelectedShortlistFreezeId] = useState(null);
  const [selectedShortlistDeleteName, setSelectedShortlistDeleteName] = useState("");
  const [selectedShortlistDeleteId, setSelectedShortlistDeleteId] = useState(null);
  const [selectedShortlistDownloadName, setSelectedShortlistDownloadName] = useState("");
  const [showDownloadConfirmation, setShowDownloadConfirmation] = useState(false);
  const [isDownloading, setIsDownloading] = useState(false);

  // --- Filtration & Pagination States ---
  const [selectedMediums, setSelectedMediums] = useState([]);
  const [correctionData, setCorrectionData] = useState([]);
  const [isUpdating, setIsUpdating] = useState(false);
  const [currentPage, setCurrentPage] = useState(1);
  const rowsPerPage = 25;
  const availableMediums = ["KANNADA", "ENGLISH", "URDU", "MARATHI"];

  // Loading/Error states
  const [loadingShortlistNames, setLoadingShortlistNames] = useState(true);
  const [loadingNonFrozenNames, setLoadingNonFrozenNames] = useState(true);
  const [shortlistNamesError, setShortlistNamesError] = useState(null);
  const [nonFrozenNamesError, setNonFrozenNamesError] = useState(null);

  const applicantCountRef = useRef(0);
  const shortlistedCountRef = useRef(0);

  const BASE_API_URL = `${process.env.REACT_APP_BACKEND_API_URL}/api/shortlist-info`;

  // --- Data Fetching ---
  useEffect(() => {
    if (currentYear) {
      fetchInitialData();
    }
  }, [currentYear]);

  const fetchInitialData = async () => {
    await Promise.all([fetchShortlistNames(), fetchNonFrozenShortlistNames(), fetchCounts()]);
  };

  const fetchShortlistNames = async () => {
    setLoadingShortlistNames(true);
    try {
      const res = await fetch(`${BASE_API_URL}/names?year=${currentYear}`);
      if (!res.ok) throw new Error(`HTTP error!`);
      const data = await res.json();
      setShortlistNames(data);
    } catch (error) { setShortlistNamesError("Failed to load names."); }
    finally { setLoadingShortlistNames(false); }
  };

  const fetchNonFrozenShortlistNames = async () => {
    setLoadingNonFrozenNames(true);
    try {
      const res = await fetch(`${BASE_API_URL}/non-frozen-names?year=${currentYear}`);
      const data = await res.json();
      setNonFrozenShortlistNames(data);
    } catch (error) { setNonFrozenNamesError("Failed to load shortlists."); }
    finally { setLoadingNonFrozenNames(false); }
  };

  const fetchCounts = async () => {
    try {
      const res = await fetch(`${BASE_API_URL}/counts?year=${currentYear}`);
      const data = await res.json();
      animateCount(0, data.totalApplicants, setApplicantCount, applicantCountRef);
      animateCount(0, data.totalShortlisted, setShortlistedCount, shortlistedCountRef);
    } catch (error) { }
  };

  const animateCount = (start, end, setState, ref) => {
    let current = start;
    const increment = Math.ceil((end - start) / 90);
    const timer = setInterval(() => {
      current += increment;
      if (current >= end) { clearInterval(timer); setState(end); }
      else setState(current);
    }, 16);
    ref.current = end;
  };

  // --- Handlers ---
  const handleBoxClick = (boxId) => {
    setActiveBox(boxId);
    setSelectedShortlistInfo(null);
    setShowDownloadConfirmation(false);
    setSelectedShortlistFreezeName("");
    setSelectedShortlistDeleteName("");
    setSelectedShortlistDownloadName("");
    setSelectedMediums([]);
    setCurrentPage(1);
  };

  const handleMediumToggle = (med) => {
    setSelectedMediums(prev => prev.includes(med) ? prev.filter(m => m !== med) : [...prev, med]);
  };

  const handleShortlistSelectInfo = async (event) => {
    const selectedName = event.target.value;
    if (!selectedName) {
      setSelectedShortlistInfo(null);
      return;
    }
    try {
      const res = await fetch(`${BASE_API_URL}/${selectedName}?year=${currentYear}`);
      const data = await res.json();
      setSelectedShortlistInfo(data);
    } catch (error) {
      setSelectedShortlistInfo(null);
      alert(`Failed to fetch shortlist info: ${error.message}`);
    }
  };

  // const handleShortlistSelectFreeze = (event) => {
  //   const value = event.target.value;
  //   const selected = nonFrozenShortlistNames.find((item) => item.name === value);
  //   setSelectedShortlistFreezeName(value);
  //   setSelectedShortlistFreezeId(selected?.id ?? null);
  // };

  const handleShortlistSelectFreeze = async (event) => {
    const value = event.target.value;
    const selected = nonFrozenShortlistNames.find((item) => item.name === value);

    setSelectedShortlistFreezeName(value);
    setSelectedShortlistFreezeId(selected?.id ?? null);

    // 🔥 NEW: Fetch the batch details immediately so we have the shortlistedCount
    if (value) {
      try {
        const res = await fetch(`${BASE_API_URL}/${value}?year=${currentYear}`);
        const data = await res.json();
        setSelectedShortlistInfo(data); // This populates the "Batch Size"
      } catch (error) {
        console.error("Error pre-fetching batch info:", error);
      }
    }
  };

  const handleShortlistSelectDelete = (event) => {
    const value = event.target.value;
    const selected = nonFrozenShortlistNames.find((item) => item.name === value);
    setSelectedShortlistDeleteName(value);
    setSelectedShortlistDeleteId(selected?.id ?? null);
  };

  const handleShortlistSelectDownload = (event) => {
    setSelectedShortlistDownloadName(event.target.value);
    setShowDownloadConfirmation(false);
  };

  // --- API Handlers ---
  const handleFreezeSubmit = async () => {
    if (!selectedShortlistFreezeId || selectedMediums.length === 0) {
      alert("Please select a batch and at least one medium.");
      return;
    }
    try {
      setIsUpdating(true);
      const res = await fetch(`${BASE_API_URL}/freeze`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          shortlistBatchId: selectedShortlistFreezeId,
          filterMediums: selectedMediums
        }),
      });
      const data = await res.json();

      if (res.status === 400 && data.requiresCorrection) {
        setCorrectionData(data.students.map(s => ({ ...s, status: 'Y' })));
        setActiveBox("correction");
        setCurrentPage(1);
        return;
      }

      if (!res.ok) throw new Error(data.message || "Error");
      alert(data.message);
      window.location.reload();
    } catch (error) { alert(`Failed: ${error.message}`); }
    finally { setIsUpdating(false); }
  };

  const handleUpdateStudentField = (indexOnPage, field, value) => {
    const actualIdx = (currentPage - 1) * rowsPerPage + indexOnPage;
    const newData = [...correctionData];
    newData[actualIdx][field] = value;
    setCorrectionData(newData);
  };

  // const handleBulkCorrectionSubmit = async () => {
  //   if (correctionData.some(s => !s.selected_medium)) {
  //     alert("Please select a medium for all applicants in the list.");
  //     return;
  //   }
  //   setIsUpdating(true);
  //   try {
  //     const res = await fetch(`${BASE_API_URL}/bulk-update-mediums`, {
  //       method: "POST",
  //       headers: { "Content-Type": "application/json" },
  //       body: JSON.stringify({ updates: correctionData, batchId: selectedShortlistFreezeId }),
  //     });
  //     if (!res.ok) throw new Error("Update failed");

  //     // After manual updates, re-trigger freeze to flip the 'Y' flags
  //     const finalRes = await fetch(`${BASE_API_URL}/freeze`, {
  //       method: "POST",
  //       headers: { "Content-Type": "application/json" },
  //       body: JSON.stringify({
  //         shortlistBatchId: selectedShortlistFreezeId,
  //         filterMediums: selectedMediums
  //       }),
  //     });
  //     const finalData = await finalRes.json();
  //     alert(finalData.message);
  //     window.location.reload();
  //   } catch (error) { alert(error.message); }
  //   finally { setIsUpdating(false); }
  // };

  const handleBulkCorrectionSubmit = async () => {
    // 1. Validation: Ensure all 26 conflicts have a selection
    if (correctionData.some(s => !s.selected_medium)) {
      alert("Please select a medium for all applicants in the list.");
      return;
    }

    if (!selectedShortlistFreezeId) {
      alert("Batch selection error. Please try again.");
      return;
    }

    setIsUpdating(true);
    try {
      // 2. ONE CALL TO RULE THEM ALL
      // This now updates students, auto-rejects mismatches, and freezes the batch
      const res = await fetch(`${BASE_API_URL}/bulk-update-mediums`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ 
          updates: correctionData, 
          batchId: selectedShortlistFreezeId,
          allowedMediums: selectedMediums // 🔥 Pass the English/Kannada list
        }),
      });

      const data = await res.json();

      if (!res.ok) {
        throw new Error(data.message || "Bulk update and freeze process failed.");
      }

      // 3. Success handling
      // We no longer call /freeze here because the Model already set frozen_yn = 'Y'
      alert("Batch processed and frozen successfully!");
      window.location.reload();

    } catch (error) {
      console.error("Submission Error:", error);
      alert(`Process Failed: ${error.message}`);
    } finally {
      setIsUpdating(false);
    }
  };

  const handleResetFiltering = async () => {
    if (window.confirm("Clear all assigned mediums for this batch?")) {
      try {
        const res = await fetch(`${BASE_API_URL}/reset-mediums`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ shortlistBatchId: selectedShortlistFreezeId }),
        });
        const data = await res.json();
        alert(data.message);
        setCorrectionData([]);
        setActiveBox(null);
        fetchInitialData();
      } catch (error) { alert("Reset failed"); }
    }
  };

  const handleDeleteSubmit = async () => {
    if (!selectedShortlistDeleteId) return;
    if (window.confirm(`Delete ${selectedShortlistDeleteName}?`)) {
      try {
        const res = await fetch(`${BASE_API_URL}/delete?year=${currentYear}`, {
          method: "DELETE",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ shortlistBatchId: selectedShortlistDeleteId }),
        });
        if (!res.ok) throw new Error("Delete failed");
        alert("Deleted successfully");
        window.location.reload();
      } catch (error) { alert(error.message); }
    }
  };

  const handleInitiateDownload = () => {
    if (!selectedShortlistDownloadName) return alert("Select a shortlist");
    setShowDownloadConfirmation(true);
  };

  const handleDownloadConfirmationResponse = async (confirm) => {
    setShowDownloadConfirmation(false);
    if (confirm) {
      setIsDownloading(true);
      try {
        const response = await fetch(`${BASE_API_URL}/download-data/${selectedShortlistDownloadName}?year=${currentYear}`);
        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${selectedShortlistDownloadName}_Applicants.xlsx`;
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(url);
      } catch (error) { alert(error.message); }
      finally { setIsDownloading(false); }
    }
  };

  // --- Pagination Logic ---
  const indexOfLastRow = currentPage * rowsPerPage;
  const indexOfFirstRow = indexOfLastRow - rowsPerPage;
  const currentRows = correctionData.slice(indexOfFirstRow, indexOfLastRow);
  const totalPages = Math.ceil(correctionData.length / rowsPerPage);

  // --- Render Helpers ---
  const renderOptions = (names, loading, error, isObj) => {
    if (loading) return <option>Loading...</option>;
    return [
      <option key="d" value="" disabled>Select Shortlist</option>,
      ...names.map(n => <option key={isObj ? n.id : n} value={isObj ? n.name : n}>{isObj ? n.name : n}</option>)
    ];
  };

  // --- Views ---
  const renderMainView = () => (
    <div className={styles.container}>
      <h1 className={styles.heading}>Shortlist Management ({appliedConfig?.academic_year})</h1>
      <div className={styles.countsContainer}>
        <div className={styles.countBox}><p className={styles.countBoxText}>Total Students: {applicantCount}</p></div>
        <div className={styles.countBox}><p className={styles.countBoxText}>Shortlisted Students: {shortlistedCount}</p></div>
      </div>
      <div className={styles.shortlistingStepsGrid}>
        {[
          { icon: "ℹ", label: "Get Shortlist Info", boxId: "getInfo", protected: false },
          { icon: "🔒", label: "Freeze Shortlist", boxId: "freeze", protected: true },
          { icon: "🗑", label: "Delete Shortlist", boxId: "delete", protected: true },
          { icon: "⬇", label: "Download Shortlist", boxId: "download", protected: false },
        ].map((item) => (
          <div key={item.boxId} className={`${styles.optionBox} ${item.protected && !isAdmissionsOpen ? styles.blockedCursor : ""}`} onClick={() => (!item.protected || isAdmissionsOpen) && handleBoxClick(item.boxId)}>
            <div className={styles.iconBox}>{item.icon}</div>
            <div className={styles.textBox}>{item.label}</div>
          </div>
        ))}
      </div>
    </div>
  );

  const renderCorrectionView = () => {
    // --- Dynamic Calculations for Summary Cards ---
    // Fallback to global shortlistedCount if the specific batch info isn't loaded yet
    const batchSize = selectedShortlistInfo?.shortlistedCount || shortlistedCount || 0;
    const conflictsFlagged = correctionData.length;
    const autoProcessed = batchSize - conflictsFlagged;

    return (
      <div className={styles.correctionContainer}>
        {/* DYNAMIC SUMMARY CARDS */}
        <div className={styles.summaryGrid}>
          <div className={styles.summaryCard}>
            <label>Auto-Mapped Students</label>
            <span className={styles.textGreen}>
              {autoProcessed > 0 ? autoProcessed : 0}
            </span>
          </div>
          <div className={styles.summaryCard}>
            <label>Medium Conficts with the current Selection</label>
            <span className={styles.textRed}>{conflictsFlagged}</span>
          </div>
          <div className={styles.summaryCard}>
            <label>top 6 percentile students</label>
            <span>{batchSize}</span>
          </div>
        </div>

        <div className={styles.dashHeader}>
          <div className={styles.dashTitle}>
            <h2>MEDIUM BASED FILTERING PROCESS</h2>
            <p>Verify the medium selection for multi-medium schools</p>
          </div>
          <div className={styles.dashActions}>
            <button className={styles.resetBtnLink} onClick={handleResetFiltering}>Reset the filtering</button>
            <button className={styles.backButton} onClick={() => setActiveBox("freeze")}>Cancel</button>
            <button className={styles.primaryBtn} onClick={handleBulkCorrectionSubmit} disabled={isUpdating}>
              {isUpdating ? "Processing..." : "Submit & Freeze"}
            </button>
          </div>
        </div>

        <div className={styles.tableScrollArea}>
          <table className={styles.dashTable}>
            <thead>
              <tr>
                <th>Sl No</th>
                <th>Student Name</th>
                <th>School Name (DISE)</th>
                <th>Contact No 1</th>
                <th>Contact No 2</th>
                <th>Medium</th>
                <th>Decision</th>
              </tr>
            </thead>
            <tbody>
              {currentRows.map((student, idx) => (
                <tr
                  key={student.applicant_id}
                  className={!selectedMediums.includes(student.selected_medium) ? styles.rowMismatch : ""}
                >
                  <td>{indexOfFirstRow + idx + 1}</td>
                  <td><strong>{student.student_name}</strong></td>
                  <td>{student.institute_name} <br /> <small>{student.dise_code}</small></td>
                  <td>{student.contact_no1 || "N/A"}</td>
                  <td>{student.contact_no2 || "N/A"}</td>
                  <td>
                    <select
                      className={styles.dashSelect}
                      value={student.selected_medium || ""}
                      onChange={(e) => handleUpdateStudentField(idx, 'selected_medium', e.target.value)}
                    >
                      <option value="">Select Medium</option>
                      {student.supported_mediums.map(m => (
                        <option key={m} value={m}>{m}</option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <div className={styles.dashBtnGroup}>
                      <button
                        className={student.status === 'Y' ? styles.btnActiveAccept : styles.btnInactive}
                        onClick={() => handleUpdateStudentField(idx, 'status', 'Y')}
                      >Accept</button>
                      <button
                        className={student.status === 'N' ? styles.btnActiveReject : styles.btnInactive}
                        onClick={() => handleUpdateStudentField(idx, 'status', 'N')}
                      >Reject</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className={styles.dashFooter}>
          <div className={styles.paginationInfo}>
            Showing <strong>{currentRows.length}</strong> of <strong>{correctionData.length}</strong> conflicts
          </div>
          <div className={styles.paginationActions}>
            <button
              disabled={currentPage === 1}
              onClick={() => { setCurrentPage(p => p - 1); window.scrollTo(0, 0); }}
            >Prev</button>
            <span>Page {currentPage} of {totalPages}</span>
            <button
              disabled={currentPage === totalPages}
              onClick={() => { setCurrentPage(p => p + 1); window.scrollTo(0, 0); }}
            >Next</button>
          </div>
        </div>
      </div>
    );
  };
  const renderFreeze = () => (
    <div className={styles.detailedView}>
      <h2 className={styles.detailedViewHeading}>Freeze Shortlist</h2>
      <div className={styles.setupSection}>
        <label>Select Batch</label>
        <select onChange={handleShortlistSelectFreeze} value={selectedShortlistFreezeName}>
          {renderOptions(nonFrozenShortlistNames, loadingNonFrozenNames, nonFrozenNamesError, true)}
        </select>

        <label style={{ marginTop: '20px', display: 'block' }}>Select Allowed Mediums</label>
        <div className={styles.mediumChipGrid}>
          {availableMediums.map(m => (
            <div key={m} className={`${styles.mediumChip} ${selectedMediums.includes(m) ? styles.chipActive : ""}`} onClick={() => handleMediumToggle(m)}>
              {m}
            </div>
          ))}
        </div>
      </div>
      <button className={styles.freezeButton} onClick={handleFreezeSubmit} disabled={!selectedShortlistFreezeId || selectedMediums.length === 0 || isUpdating}>
        {isUpdating ? "Processing..." : "Filter & Freeze"}
      </button>
      <button className={styles.backButton} onClick={() => setActiveBox(null)}>Back</button>
    </div>
  );

  const renderGetInfo = () => (
    <div className={`${styles.detailedView} ${styles.getInfoView}`}>
      <h2 className={styles.detailedViewHeading}>Get Shortlist Information</h2>
      <select onChange={handleShortlistSelectInfo} value={selectedShortlistInfo?.name || ""}>
        {renderOptions(shortlistNames, loadingShortlistNames, shortlistNamesError, false)}
      </select>
      {selectedShortlistInfo && (
        <div className={styles.shortlistDetailsCard}>
          <h3 className={styles.cardTitle}>{selectedShortlistInfo.name}</h3>
          <div className={styles.infoRow}><strong>Description:</strong> <span>{selectedShortlistInfo.description}</span></div>
          <div className={styles.infoRow}><strong>Criteria:</strong> <span>{selectedShortlistInfo.criteria}</span></div>
          <div className={styles.infoRow}><strong>Frozen:</strong> <span>{selectedShortlistInfo.isFrozen}</span></div>
          <div className={styles.infoRow}><strong>Jurisdictions:</strong> <span>{selectedShortlistInfo.blocks?.join(", ")}</span></div>
        </div>
      )}
      <button className={styles.backButton} onClick={() => setActiveBox(null)}>Back</button>
    </div>
  );

  const renderDelete = () => (
    <div className={styles.detailedView}>
      <h2 className={styles.detailedViewHeading}>Delete Shortlist</h2>
      <select onChange={handleShortlistSelectDelete} value={selectedShortlistDeleteName}>
        {renderOptions(nonFrozenShortlistNames, loadingNonFrozenNames, nonFrozenNamesError, true)}
      </select>
      <button className={styles.deleteButton} onClick={handleDeleteSubmit} disabled={!selectedShortlistDeleteId}>Delete</button>
      <button className={styles.backButton} onClick={() => setActiveBox(null)}>Back</button>
    </div>
  );

  const renderDownload = () => (
    <div className={`${styles.detailedView} ${styles.downloadView}`}>
      <h2 className={styles.detailedViewHeading}>Download Data</h2>
      <select onChange={handleShortlistSelectDownload} value={selectedShortlistDownloadName}>
        {renderOptions(shortlistNames, loadingShortlistNames, shortlistNamesError, false)}
      </select>
      <button className={styles.downloadButton} onClick={handleInitiateDownload} disabled={isDownloading}>
        {isDownloading ? "Downloading..." : "Download Shortlist"}
      </button>
      {showDownloadConfirmation && (
        <div className={styles.downloadConfirmation}>
          <p>Download "{selectedShortlistDownloadName}"?</p>
          <div className={styles.confirmationButtons}>
            <button onClick={() => handleDownloadConfirmationResponse(true)}>Yes</button>
            <button onClick={() => handleDownloadConfirmationResponse(false)}>No</button>
          </div>
        </div>
      )}
      <button className={styles.backButton} onClick={() => setActiveBox(null)}>Back</button>
    </div>
  );

  const renderContent = () => {
    switch (activeBox) {
      case "getInfo": return renderGetInfo();
      case "freeze": return renderFreeze();
      case "delete": return renderDelete();
      case "download": return renderDownload();
      case "correction": return renderCorrectionView();
      default: return renderMainView();
    }
  };

  return (
    <div className={styles.shortlistInfoWrapper}>
      <Breadcrumbs path={currentPath} nonLinkSegments={['Admin', 'Admissions']} />
      <div className={styles.fullWidthContent}>
        {renderContent()}
      </div>
    </div>
  );
}

export default ShortlistInfo;