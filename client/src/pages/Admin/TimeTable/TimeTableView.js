import { useState } from "react";
import classes from "./TimeTableView.module.css";
import * as XLSX from "xlsx";

function TimeTableView({ timeTableData, userName, slotDaysDtls = [],onBack }) {
  const BASE = `${process.env.REACT_APP_BACKEND_API_URL}/api/timetable`;

  
  const [activeTab, setActiveTab] = useState("batch");
  const [isSaved, setIsSaved] = useState(false);
  const selectedSolutionData = timeTableData || {};
  
  const slots = [...new Set((selectedSolutionData.timeslots || []).map((slot) => slot.slotName)),].sort((a, b) => {
    const numA = parseInt(a.split("-")[1]);
    const numB = parseInt(b.split("-")[1]);
    return numA - numB;
  });
  const daysOrder = ["MON","TUE","WED","THU","FRI","SAT","SUN",];

  const days = [...new Set((selectedSolutionData.timeslots || []).map((slot) => slot.day)),].sort(
    (a, b) =>
      daysOrder.indexOf(a) -
      daysOrder.indexOf(b)
  );

  const slotTimeMap = {};
  slotDaysDtls.forEach(({ slot, startTime, endTime }) => {slotTimeMap[slot] =
    startTime && endTime ? `${startTime} - ${endTime}`: "";}
  );

  const batchWiseData = {};
  (selectedSolutionData.assignments || []).forEach((a) => {
    a.batches.forEach((batch) => {
      if (!batchWiseData[batch]) {
        batchWiseData[batch] = {};
      }
      if (!batchWiseData[batch][a.day]) {
        batchWiseData[batch][a.day] = {};
      }
      batchWiseData[batch][a.day][a.slotName] = {
        subject: a.subject,
        teacher_name: a.teacher,
        batch:batch
      };
    });
  });

  const teacherWiseData = {};
  (selectedSolutionData.assignments || []).forEach((a) => {
    if (!teacherWiseData[a.teacher]) {
      teacherWiseData[a.teacher] = {};
    }
    if (!teacherWiseData[a.teacher][a.day]) {
      teacherWiseData[a.teacher][a.day] = {};
    }
    teacherWiseData[a.teacher][a.day][a.slotName] = {
      teacher_name :a.teacher,
      subject: a.subject,
      batch: a.batches.join(", "),
    };
  });

  const overallData = {};
  (selectedSolutionData.assignments || []).forEach((a) => {
    if (!overallData[a.day]) {
      overallData[a.day] = {};
    }
    if (!overallData[a.day][a.slotName]) {
      overallData[a.day][a.slotName] = [];
    }
    overallData[a.day][a.slotName].push({
      batch: a.batches.join(", "),
      subject: a.subject,
      teacher: a.teacher,
    });
  });

  const [selectedBatch, setSelectedBatch] = useState(Object.keys(batchWiseData)[0] || "");
  const [selectedTeacher, setSelectedTeacher] = useState(Object.keys(teacherWiseData)[0] || "");

  const handleSaveSolution = async () => {
    if (!userName) return;
    try {
      const response = await fetch(`${BASE}/timeTable/saveTimeTableSolution`,
        {
          method: "POST",
          headers: {"Content-Type": "application/json",},
          body: JSON.stringify({userName, solutionData: selectedSolutionData,}),
        }
      );
      if (!response.ok) {
        throw new Error("Failed");
      }
      alert("Timetable saved successfully!");
      setIsSaved(true); 
    } catch (error) {
      console.error(error);
      alert("Error saving timetable");
    }
  };


  // --- EXCEL DOWNLOAD METHODS ONLY ---

  // --- EXCEL DOWNLOAD METHODS (USING YOUR DATA OBJECTS) ---

  // 1. GLOBAL / OVERALL DOWNLOAD (Slots on top, unique Days as rows, items stacked inside cells)
  const handleGlobalDownload = () => {
    const workbook = XLSX.utils.book_new();
    
    // Header row: Day, Slot 1, Slot 2...
    const headers = ["Day", ...slots.map(s => `${s} ${slotTimeMap[s] ? `(${slotTimeMap[s]})` : ''}`)];
    const matrixRows = [headers];

    // Iterates uniquely through days (Guarantees NO duplicate rows)
    days.forEach((day) => {
      const row = [day]; 
      
      slots.forEach((slot) => {
        // Look up directly inside your overallData structure
        const assignments = overallData?.[day]?.[slot];
        
        if (assignments && assignments.length > 0) {
          // Join concurrent classes with line breaks inside the single Excel cell
          const cellContent = assignments
            .map(item => `📚 ${item.subject}\n👨‍🏫 ${item.teacher}\n👥 ${item.batch}`)
            .join("\n------------------\n");
          row.push(cellContent);
        } else {
          row.push("-");
        }
      });
      
      matrixRows.push(row);
    });

    const worksheet = XLSX.utils.aoa_to_sheet(matrixRows);
    XLSX.utils.book_append_sheet(workbook, worksheet, "Global Master Schedule");
    XLSX.writeFile(workbook, "Global_Timetable_Master.xlsx");
  };

  // 2. ALL BATCHES DOWNLOAD (One structured sheet tab per batch using batchWiseData)
  const handleAllBatchesDownload = () => {
    const workbook = XLSX.utils.book_new();

    // Loop through the batches exactly as structured in your batchWiseData keys
    Object.keys(batchWiseData).forEach((batch) => {
      const headers = ["Day", ...slots.map(s => `${s} ${slotTimeMap[s] ? `(${slotTimeMap[s]})` : ''}`)];
      const matrixRows = [headers];

      days.forEach((day) => {
        const row = [day];
        
        slots.forEach((slot) => {
          // Look up directly inside your batchWiseData structure
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
  };

  // 3. ALL TEACHERS DOWNLOAD (One structured sheet tab per teacher using teacherWiseData)
  const handleAllTeachersDownload = () => {
    const workbook = XLSX.utils.book_new();

    // Loop through the teachers exactly as structured in your teacherWiseData keys
    Object.keys(teacherWiseData).forEach((teacher) => {
      const headers = ["Day", ...slots.map(s => `${s} ${slotTimeMap[s] ? `(${slotTimeMap[s]})` : ''}`)];
      const matrixRows = [headers];

      days.forEach((day) => {
        const row = [day];
        
        slots.forEach((slot) => {
          // Look up directly inside your teacherWiseData structure
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
  };



return (
  <div className={classes.mainContainer}>
    {/* HEADER */}
    <div className={classes.summaryBar}>
      <div className={classes.summaryLeft}>

        {onBack && (
            <button className={classes.backButton} onClick={onBack}>
              ← Back
            </button>
          )}
          
        <span className={classes.statusBadge}>
          TIMETABLE
        </span>
        <span className={classes.allocatedBadge}>
          Total Allocated Classes : <strong>{selectedSolutionData?.totalAllocatedClasses}</strong>
        </span>
        <span className={classes.costBadge}>
          Total Weekly Cost : <strong>{selectedSolutionData?.totalWeeklyCost}/-</strong>
        </span>
      </div>
      {!isSaved && (
        <div className={classes.summaryRight}>
          <button className={classes.btnCls} onClick={handleSaveSolution}>Save</button>
        </div>
      )}
      <div className={classes.summaryRight}>
        <button className={classes.btnCls} onClick={handleGlobalDownload} >Global</button>
      </div>
      <div className={classes.summaryRight}>
        <button className={classes.btnCls} onClick={handleAllBatchesDownload} >All Batches</button>
      </div>
      <div className={classes.summaryRight}>
        <button className={classes.btnCls} onClick={handleAllTeachersDownload} >All Teacher</button>
      </div>
    </div>

    {/* VIEW MODE */}
    <div className={classes.controlBar}>
      <div className={classes.viewTabs}>
        <button className={activeTab === "overall" ? classes.activeViewTab : classes.viewTab} onClick={() => setActiveTab("overall")}>
          Overall
        </button>
        <button className={ activeTab === "batch"? classes.activeViewTab: classes.viewTab}onClick={() => setActiveTab("batch")}>
          Batch View
        </button>
        <button className={ activeTab === "teacher" ? classes.activeViewTab: classes.viewTab} onClick={() => setActiveTab("teacher")}>
          Teacher View
        </button>
      </div>
      <div className={classes.focusBox}>
        <span className={classes.focusLabel}>Focus Target:</span> 
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

    {/* TABLE CARD */}
    <div className={classes.tableCard}>
      <div className={classes.tableWrapper}>
        <table className={classes.table}>
          <thead>
            <tr>
              <th className={classes.dayHeader}>Day</th>
              {slots.map((slot) => ( <th key={slot}> <div>{slot}</div><div className={classes.slotTime}>{slotTimeMap[slot]}</div></th>))}
            </tr>
          </thead>
          <tbody>
            {days.map((day) => (
              <tr key={day}>
                <td className={classes.dayCell}>{day}</td>
                {slots.map((slot) => {
                  let content = null;
                  if (activeTab === "overall") { content = overallData?.[day]?.[slot];}
                  if (activeTab === "batch" && selectedBatch) {content = batchWiseData?.[selectedBatch]?.[day]?.[slot];}
                  if (activeTab === "teacher" && selectedTeacher) {content =teacherWiseData?.[selectedTeacher]?.[day]?.[slot];}
                  return (
                    <td key={day + slot} className={classes.cell}>

                      {/* OVERALL */}
                      {activeTab === "overall" ? (
                        content && content.length > 0 ? (
                          content.map((item, index) => (
                              <div key={index} className={classes.assignmentCard}>
                                <div className={classes.subjectBadge}>
                                  {item.subject}
                                </div>
                                <div className={classes.teacherName}>
                                  👨‍🏫 {item.teacher}
                                </div>
                                <div className={classes.batchTag}>
                                  {item.batch}
                                </div>
                              </div>
                            )
                          )
                        ) : (<div className={classes.freeCell}>-</div>)
                      ) : content ? (<div className={classes.assignmentCard}>
                          <div className={classes.subjectBadge}>
                            {content.subject}
                          </div>
                          <div className={classes.teacherName}>
                             👨‍🏫 {content.teacher_name}
                          </div>
                          <div className={classes.batchName}>
                            {content.batch}
                          </div>
                        </div>
                      ) : (<div className={classes.freeCell}>-</div>)}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>

        </table>
      </div>
    </div>
  </div>
);
}

export default TimeTableView;