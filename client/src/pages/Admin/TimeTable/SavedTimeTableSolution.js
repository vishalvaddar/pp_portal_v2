import React, { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import axios from "axios";
import * as XLSX from "xlsx";
import { toast } from "react-toastify";
import classes from "./SavedTimeTableSolution.module.css";

export default function SavedTimeTableSolution() {
  const { id } = useParams();
  const navigate = useNavigate();

  const BASE = `${process.env.REACT_APP_BACKEND_API_URL}/api/timetable`;

  // --- States ---
  const [timeTableData, setTimeTableData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState("overall");
  const [selectedBatch, setSelectedBatch] = useState("");
  const [selectedTeacher, setSelectedTeacher] = useState("");
  const [selectedDay, setSelectedDay] = useState("ALL"); // Add this line
  // --- Fetch timetable data by ID on mount ---
  useEffect(() => {
    if (id) {
      fetchSolutionById();
    }
  }, [id]);

  const fetchSolutionById = async () => {
    try {
      setLoading(true);
      const res = await axios.get(`${BASE}/savedTimeTable/getTimeTableSolutionBySolutionId/${id}`);
      
      // CRITICAL FIX: Extracting the actual inner payload object (res.data.data)
      const fetchedData = res.data?.data || res.data?.solutionData || res.data;
      setTimeTableData(fetchedData);
    } catch (err) {
      toast.error("Error fetching timetable details!");
    } finally {
      setLoading(false);
    }
  };

  const selectedSolutionData = timeTableData || {};

  // --- Slots extraction logic ---
  const slots = [...new Set((selectedSolutionData.timeslots || []).map((slot) => slot.slotName))].sort((a, b) => {
    const numA = parseInt(a.split("-")[1]) || 0;
    const numB = parseInt(b.split("-")[1]) || 0;
    return numA - numB;
  });

  const daysOrder = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"];

  // --- Days extraction logic (Normalized to UPPERCASE) ---
  const days = [...new Set((selectedSolutionData.timeslots || []).map((slot) => slot.day.toUpperCase()))].sort(
    (a, b) => daysOrder.indexOf(a) - daysOrder.indexOf(b)
  );

  // --- Batch mapping logic ---
  const batchWiseData = {};
  
  // Pre-populate all valid batches from your database list
  (selectedSolutionData.batches || []).forEach((bName) => {
    batchWiseData[bName] = {};
  });

  // Map the assignments into your batch lookup matrix
  (selectedSolutionData.assignments || []).forEach((a) => {
    const normalizedDay = a.day.toUpperCase();
    a.batches.forEach((batch) => {
      if (!batchWiseData[batch]) {
        batchWiseData[batch] = {};
      }
      if (!batchWiseData[batch][normalizedDay]) {
        batchWiseData[batch][normalizedDay] = {};
      }
      batchWiseData[batch][normalizedDay][a.slotName] = {
        subject: a.subject,
        teacher_name: a.teacher,
        batch: batch
      };
    });
  });

  // --- Teacher mapping logic ---
  const teacherWiseData = {};
  (selectedSolutionData.assignments || []).forEach((a) => {
    const normalizedDay = a.day.toUpperCase();
    if (!teacherWiseData[a.teacher]) {
      teacherWiseData[a.teacher] = {};
    }
    if (!teacherWiseData[a.teacher][normalizedDay]) {
      teacherWiseData[a.teacher][normalizedDay] = {};
    }
    teacherWiseData[a.teacher][normalizedDay][a.slotName] = {
      teacher_name: a.teacher,
      subject: a.subject,
      batch: a.batches.join(", "),
    };
  });

  // --- Overall mapping logic ---
  const overallData = {};
  (selectedSolutionData.assignments || []).forEach((a) => {
    const normalizedDay = a.day.toUpperCase();
    if (!overallData[normalizedDay]) {
      overallData[normalizedDay] = {};
    }
    if (!overallData[normalizedDay][a.slotName]) {
      overallData[normalizedDay][a.slotName] = [];
    }
    overallData[normalizedDay][a.slotName].push({
      batch: a.batches.join(", "),
      subject: a.subject,
      teacher: a.teacher,
    });
  });

  // Dynamic Dropdown synchronization whenever data finishes fetching
  useEffect(() => {
    const batchKeys = Object.keys(batchWiseData);
    const teacherKeys = Object.keys(teacherWiseData);

    if (batchKeys.length > 0 && !selectedBatch) {
      setSelectedBatch(batchKeys[0]);
    }
    if (teacherKeys.length > 0 && !selectedTeacher) {
      setSelectedTeacher(teacherKeys[0]);
    }
  }, [timeTableData, selectedBatch, selectedTeacher]);

  const handleGlobalDownload = () => {
  const workbook = XLSX.utils.book_new();
  const matrixRows = [];

  days.forEach((day) => {
    // 1. Add the Day Header (Similar to your UI header)
    matrixRows.push([`${day} SCHEDULE`, "", "", "", ""]);
    
    // 2. Add sub-headers
    matrixRows.push(["Slot", "Subject", "Teacher", "Batch"]);

    // 3. Add slots and assignments for this day
    slots.forEach((slot) => {
      const assignments = overallData?.[day]?.[slot] || [];
      
      if (assignments.length === 0) {
        matrixRows.push([slot, "-", "-", "-"]);
      } else {
        assignments.forEach((item) => {
          matrixRows.push([slot, item.subject, item.teacher, item.batch]);
        });
      }
    });

    // 4. Add a blank row for spacing between days
    matrixRows.push(["", "", "", ""]);
  });

  const worksheet = XLSX.utils.aoa_to_sheet(matrixRows);
  
  // Format column widths
  worksheet['!cols'] = [{ wch: 15 }, { wch: 25 }, { wch: 20 }, { wch: 20 }];

  XLSX.utils.book_append_sheet(workbook, worksheet, "Overall Schedule");
  XLSX.writeFile(workbook, "Overall_Timetable_UI_Format.xlsx");
  toast.success("UI-Format export complete!");
};

  const handleAllBatchesDownload = () => {
    const workbook = XLSX.utils.book_new();

    Object.keys(batchWiseData).forEach((batch) => {
      const headers = ["Day", ...slots];
      const matrixRows = [headers];

      days.forEach((day) => {
        const row = [day];
        slots.forEach((slot) => {
          const item = batchWiseData[batch]?.[day]?.[slot];
          row.push(item ? `📚 ${item.subject}\n👨‍🏫 ${item.teacher_name}` : "-");
        });
        matrixRows.push(row);
      });

      const worksheet = XLSX.utils.aoa_to_sheet(matrixRows);
      const safeTabName = `Batch_${batch}`.replace(/[\\*?:/[\]]/g, "").substring(0, 31);
      XLSX.utils.book_append_sheet(workbook, worksheet, safeTabName);
    });

    XLSX.writeFile(workbook, "All_Batches_Timetable.xlsx");
    toast.success("All Batches Timetable downloaded!");
  };

  const handleAllTeachersDownload = () => {
    const workbook = XLSX.utils.book_new();

    Object.keys(teacherWiseData).forEach((teacher) => {
      const headers = ["Day", ...slots];
      const matrixRows = [headers];

      days.forEach((day) => {
        const row = [day];
        slots.forEach((slot) => {
          const item = teacherWiseData[teacher]?.[day]?.[slot];
          row.push(item ? `📚 ${item.subject}\n👥 ${item.batch}` : "-");
        });
        matrixRows.push(row);
      });

      const worksheet = XLSX.utils.aoa_to_sheet(matrixRows);
      const safeTabName = `Teacher_${teacher}`.replace(/[\\*?:/[\]]/g, "").substring(0, 31);
      XLSX.utils.book_append_sheet(workbook, worksheet, safeTabName);
    });

    XLSX.writeFile(workbook, "All_Teachers_Timetable.xlsx");
    toast.success("All Teachers Timetable downloaded!");
  };

  return (
    <div className={classes.mainContainer}>
      {/* Dynamic Overlay Loader */}
      {loading && (
        <div className={classes.loadingOverlay}>
          <div className={classes.loaderBox}>
            <div className={classes.spinner}></div>
            <div className={classes.loadingText}>Please wait...</div>
          </div>
        </div>
      )}

      {/* HEADER SECTION */}
      <div className={classes.summaryBar}>
        <div className={classes.summaryLeft}>
          <button className={classes.backButton} onClick={() => navigate(-1)}>
            ← Back
          </button>
          
          <span className={classes.statusBadge}>TIMETABLE</span>
          <span className={classes.allocatedBadge}>
            Total Allocated Classes : <strong>{selectedSolutionData?.totalAllocatedClasses || 0}</strong>
          </span>
          <span className={classes.costBadge}>
            Total Weekly Cost : <strong>{selectedSolutionData?.totalWeeklyCost || 0}/-</strong>
          </span>
        </div>

        {/* Hidden layout element to handle nth-of-type styling */}

        <div className={classes.summaryRight}>
          <button className={classes.btnCls} onClick={handleGlobalDownload}>Global</button>
        </div>
        <div className={classes.summaryRight}>
          <button className={classes.btnCls} onClick={handleAllBatchesDownload}>All Batches</button>
        </div>
        <div className={classes.summaryRight}>
          <button className={classes.btnCls} onClick={handleAllTeachersDownload}>All Teacher</button>
        </div>
      </div>

      {/* TABS CONTROL BAR */}
      <div className={classes.controlBar}>
        <div className={classes.viewTabs}>
          <button className={activeTab === "overall" ? classes.activeViewTab : classes.viewTab} onClick={() => setActiveTab("overall")}>Overall</button>
          <button className={activeTab === "batch" ? classes.activeViewTab : classes.viewTab} onClick={() => setActiveTab("batch")}>Batch View</button>
          <button className={activeTab === "teacher" ? classes.activeViewTab : classes.viewTab} onClick={() => setActiveTab("teacher")}>Teacher View</button>
        </div>
        
        <div className={classes.focusBox}>
          <span className={classes.focusLabel}>Focus Target:</span> 
          {activeTab === "overall" && (
            <select className={classes.dropdown} value={selectedDay} onChange={(e) => setSelectedDay(e.target.value)}>
              <option value="ALL">All Days</option>
              {days.map((day) => <option key={day} value={day}>{day}</option>)}
            </select>
          )}
        
          {activeTab === "batch" && (
            <select className={classes.dropdown} value={selectedBatch} onChange={(e) => setSelectedBatch(e.target.value)}>
              {Object.keys(batchWiseData).map((batch) => (
                <option key={batch} value={batch}>
                  {batch}
                </option>
              ))}
            </select>
          )}
          {activeTab === "teacher" && (
            <select className={classes.dropdown} value={selectedTeacher} onChange={(e) => setSelectedTeacher(e.target.value)}>
              {Object.keys(teacherWiseData).map((teacher) => (
                <option key={teacher} value={teacher}>
                  {teacher}
                </option>
              ))}
            </select>
          )}
        </div>
      </div>

      <div className={classes.tableCard}>
  <div className={classes.tableWrapper}>
    {activeTab === "overall" && (
  <table className={classes.table}>
   <tbody>
  {days
    .filter((day) => selectedDay === "ALL" || day === selectedDay)
    .map((day) => { // Use { instead of (
      
      // Now the if statement works because we are in a code block
      if (!overallData?.[day]) return null;

      return ( // You must return the JSX here
        <React.Fragment key={day}>
          {/* Day Header Row */}
          <tr className={classes.dayHeaderRow}>
            <td colSpan={4} className={classes.dayTitleCell}>
              🚀 {day} SCHEDULE
            </td>
          </tr>

          {/* Slots Logic */}
          {slots.map((slot) => {
            const content = overallData?.[day]?.[slot] || [];
            
            // Empty Row Logic
            if (content.length === 0) {
              return (
                <tr key={`${day}-${slot}`} className={classes.slotRow}>
                  <td className={classes.slotNameCell}></td>
                  <td className={classes.slotContentCell}><div className={classes.emptyCell}>-</div></td>
                  <td className={classes.slotContentCell}><div className={classes.emptyCell}>-</div></td>
                  <td className={classes.slotContentCell}><div className={classes.emptyCell}>-</div></td>
                </tr>
              );
            }

            // Data logic...
            const chunks = [];
            for (let i = 0; i < content.length; i += 3) {
              chunks.push(content.slice(i, i + 3));
            }

            return chunks.map((chunk, chunkIndex) => (
              <tr key={`${day}-${slot}-${chunkIndex}`} className={classes.slotRow}>
                <td className={classes.slotNameCell}>{chunkIndex === 0 ? slot : ""}</td>
                {[0, 1, 2].map((i) => (
                  <td key={i} className={classes.slotContentCell}>
                    {chunk[i] ? (
                      <div className={classes.assignmentCard}>
                        <div className={classes.subjectBadge}><strong>{chunk[i].subject}</strong></div>
                        <div className={classes.teacherName}>👨‍🏫 {chunk[i].teacher}</div>
                        <div className={classes.batchTag}>👥 {chunk[i].batch}</div>
                      </div>
                    ) : (
                      <div className={classes.emptyCell}>-</div>
                    )}
                  </td>
                ))}
              </tr>
            ));
          })}
        </React.Fragment>
      );
    })}
</tbody>
  </table>
)}

    {/* --- TABLE 2: BATCH VIEW (Matrix) --- */}
    {activeTab === "batch" && (
      <table className={classes.table}>
        <thead>
          <tr>
            <th className={classes.dayHeader}>Day</th>
            {slots.map((slot) => <th key={slot} className={classes.slotHeader}>{slot}</th>)}
          </tr>
        </thead>
        <tbody>
          {days.map((day) => (
            <tr key={day} className={classes.tableRow}>
              <td className={classes.dayCell}>{day}</td>
              {slots.map((slot) => {
                const content = batchWiseData?.[selectedBatch]?.[day]?.[slot];
                return (
                  <td key={day + slot}>
                    {content ? (
                      <div className={classes.assignmentCard}>
                        <div className={classes.subjectBadge}>{content.subject}</div>
                        <div className={classes.teacherName}>👨‍🏫 {content.teacher_name}</div>
                        <div className={classes.batchName}>{content.batch}</div>
                      </div>
                    ) : <div className={classes.freeCell}>-</div>}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    )}

    {/* --- TABLE 3: TEACHER VIEW (Matrix) --- */}
    {activeTab === "teacher" && (
      <table className={classes.table}>
        <thead>
          <tr>
            <th className={classes.dayHeader}>Day</th>
            {slots.map((slot) => <th key={slot} className={classes.slotHeader}>{slot}</th>)}
          </tr>
        </thead>
        <tbody>
          {days.map((day) => (
            <tr key={day} className={classes.tableRow}>
              <td className={classes.dayCell}>{day}</td>
              {slots.map((slot) => {
                const content = teacherWiseData?.[selectedTeacher]?.[day]?.[slot];
                return (
                  <td key={day + slot}>
                    {content ? (
                      <div className={classes.assignmentCard}>
                        <div className={classes.subjectBadge}>{content.subject}</div>
                        <div className={classes.teacherName}>👨‍🏫 {content.teacher_name}</div>
                        <div className={classes.batchName}>{content.batch}</div>
                      </div>
                    ) : <div className={classes.freeCell}>-</div>}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    )}

  </div>
</div>
    </div>
  );
}