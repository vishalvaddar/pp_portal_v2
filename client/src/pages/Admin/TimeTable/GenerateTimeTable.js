import React, { useState, useEffect } from "react";
import axios from "axios";
import { 
  FaMinusCircle, 
  FaTerminal, 
  FaChevronLeft, 
  FaCheckCircle, 
  FaExclamationTriangle 
} from "react-icons/fa";
import classes from "./GenerateTimeTable.module.css";
import TimeTableView from "./TimeTableView";

import { useParams, useSearchParams } from "react-router-dom";
import { useNavigate } from "react-router-dom";
import toast, { Toaster } from "react-hot-toast";


export default function TimeTableDashboard() {
  const BASE = `${process.env.REACT_APP_BACKEND_API_URL}/api/timetable`;
  const navigate = useNavigate();
  const handleBack = () => {navigate("/admin/academics/time-table-dashboard/generate");
};

  /* ---------- CONSTANTS ---------- */
  const days  = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  const weeklySlots = [0,1,2,3,4,5,6,7,8,9,10];
  const maxWeeklySlots = [0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15];

  /* ---------- pop-up for asking username ---------- */
  const [userName, setUserName] = useState("");
  const [showSaveModal, setShowSaveModal] = useState(null);

  /*-------------maintain the tabs state and visiblity------------ */
  const [step, setStep] = useState(1);
  const [visibleTables, setVisibleTables] = useState({
    slots: false,subjects: false,batchSubjects: false,teachers: false,combinedBatches: false,subjectTeacher: false
  });
  /*-------------all table states------------------- */
  const [loading, setLoading] = useState(false);
  const [subjects, setSubjects] = useState([]);
  const [gradesList, setGradesList] = useState([]);
  const [teachers, setTeachers] = useState([]);

  const [slotDaysDtls, setSlotDaysDtls] = useState([{slot: "slot-1",days: [...days],startTime: "",endTime: ""}]);
  const [subjectWeeklySlotDtls, setSubjectWeeklySlotDtls] = useState([{ subjectDtlsForWeeklySlot: {}, weeklySlot: "" }]);
  const [teacherAvailabilityDtls, setTeacherAvailabilityDtls] = useState([{teacherDtls: {},availability: {},teachersBySubjects: [],remuneration: "",maxWeeklySlots: ""}]);
  const [combinedBatchDtls, setCombinedBatchDtls] = useState([{grade: "",language: "",availableBatches: [],combinedBatches: [],combinedBatchGroupId: ""}]);
  const [subjectTeacherDtls, setSubjectTeacherDtls] = useState([{selectedSubjectDtlsForsubjectTeacherDtls: {},teachersForSubjectTeachearDtls: [],groupTeacherMap: {},finalBatchListForSubjectTeacherDtls :[]}]);


  /*-------------for report showing and output data from python showing states----------------- */
  const [reportContent, setReportContent] = useState(""); 
  const [showReportModal, setShowReportModal] = useState(false);
  const [genratedTimeTableData, setGenratedTimeTableData] = useState({});
  const [showGenratedTimeTableData, setShowGenratedTimeTableData] = useState(false);


  /*-------------for getting the data from the url and deffernatiating whether it is add edit or copy----------------- */
  const { id: configId } = useParams();
  const [searchParams] = useSearchParams();
  const mode = searchParams.get("mode"); 

  /*-------------------------useEffect--------------------------------- */
  useEffect(() => {
    const initializePage = async () => {
      await loadSubjects();
      await loadGrades();
      if (configId) {await fetchDraftDetails(configId);}
      else {resetForm();}
    };
    initializePage();
  }, [configId]);

  const resetForm = () => {
    setStep(1);
    setVisibleTables({ slots: false, subjects: false, teachers: false, combinedBatches: false,subjectTeacher: false });
    setSlotDaysDtls([{ slot: "slot-1", days: [...days], startTime: "", endTime: "" }]);
    setSubjectWeeklySlotDtls([{ subjectDtlsForWeeklySlot: {}, weeklySlot: "" }]);
    setTeacherAvailabilityDtls([{ teacherDtls: {}, availability: {}, teachersBySubjects: [] ,remuneration: "",maxWeeklySlots: ""}]);
    setCombinedBatchDtls([{ grade: "", language: "", availableBatches: [], combinedBatches: [],combinedBatchGroupId: "" }]);
    setSubjectTeacherDtls([{selectedSubjectDtlsForsubjectTeacherDtls: {},teachersForSubjectTeachearDtls: [],groupTeacherMap: {}}]);  
  };

const fetchDraftDetails = async (configId) => {
  try {
    const res = await axios.get(`${BASE}/timeTable/getConfigById/${configId}`);
    const draftData = res.data; // This already contains parsed JSON
    if (mode === "copy") {
      setUserName(""); 
      loadDraftToState(draftData);
    } else {
      setUserName(draftData.time_table_config_file_ins_user_name || "");
      loadDraftToState(draftData);
    }
  } catch (err) {
    toast.error("Failed to load configuration data. please refresh the page or try again later.");
  }
};

const loadDraftToState = (data) => {
  setStep(data.step || 1);
  setVisibleTables(data.visibleTables || {});
  setSlotDaysDtls(data.slotDaysDtls || []);
  setSubjectWeeklySlotDtls(data.subjectWeeklySlotDtls || []);
  setTeacherAvailabilityDtls(data.teacherAvailabilityDtls || []);
  setCombinedBatchDtls(data.combinedBatchDtls || []);
  setSubjectTeacherDtls(data.subjectTeacherDtls || []);
};

const handleSaveDraftClick = () => {
  if (!userName) {
    setShowSaveModal('draft');
    return;
  }
  handleConfirmSaveDraft(); 
};

const handleGenerateTimeTableClick = async() => {
  if (!userName) {
    setShowSaveModal('generate'); 
    return;
  }
  await handleConfirmSaveDraft();
  await handleGenerate();

  //handleGenerate(); 
};

const handleSaveUsername = async () => {
  if (!userName) {
    toast.error("Please enter username");    
    return;
  }
  const triggeredBy = showSaveModal;
  setShowSaveModal(null);
  if (triggeredBy === 'generate') {
    await handleConfirmSaveDraft();
    await handleGenerate();
  } 
  else if (triggeredBy === 'draft') {
    await handleConfirmSaveDraft();
  }
};

const handleCancelUsernameModal = () => {
  setShowSaveModal(null);
};

const handleConfirmSaveDraft = async () => { 
  setLoading(true);
  const draft = getDraftData();
  try {
    await axios.post(`${BASE}/timeTable/saveConfigurationDraftFile`, {
      userName,
      fileContent: JSON.stringify(draft),
      configId: mode === "edit" ? configId : null 
    });
    toast.success(
      mode === "edit"
        ? "Draft Updated Successfully "
        : "Draft Saved Successfully "
    );
    setShowSaveModal(false);
  } catch (err) {
    toast.error("Error saving draft . please refresh the page or try again later.");
  }
  finally {
    setLoading(false);
  }
};

const getDraftData = () => {
  return {
    step,
    visibleTables,
    slotDaysDtls,
    subjectWeeklySlotDtls,
    teacherAvailabilityDtls,
    combinedBatchDtls,
    subjectTeacherDtls
  };
};

const loadSubjects = async () => {
  try {
    const res = await axios.get(`${BASE}/data/subjectsForTimeTable`);
    setSubjects(res.data);
  } catch (err) {
    toast.error("Failed to load subjects. please refresh the page or try again later.");
  }
};

const loadGrades = async () => {
  try {
    const res = await axios.get(`${BASE}/combinedBatches/getGradesForCombinedBatches`);
    setGradesList(res.data);
  } catch (err) {
    toast.error("Failed to load grades. please refresh the page or try again later.");
  }
};


const prevStep = () => {
  setStep(prev => prev - 1);
};

const showTable = (type) => {
  setVisibleTables(prev => ({...prev,[type]: true}));
};

const nextStep = () => {
    if (step === 1) {
      if (!slotDaysDtls || slotDaysDtls.length === 0) {
        toast.error("At least one slot is required");
        return;
      }
      const isInvalid = slotDaysDtls.some(slot =>
        !slot.startTime || !slot.endTime || !slot.days || slot.days.length === 0
      );
      if (isInvalid) {
        toast.error("Please fill all slot details (days, start time, end time)");     
        return;
      }
    }
    if (step === 2) {
      if (!subjectWeeklySlotDtls || subjectWeeklySlotDtls.length === 0) {
        toast.error("At least one subject is required");
        return;
      }
      const isInvalid = subjectWeeklySlotDtls.some(row =>
        !row.subjectDtlsForWeeklySlot?.subjectId ||
        !row.weeklySlot
      );
      if (isInvalid) {
        toast.error("Please fill all Subjects Weekly Slots details");
        return;
      }
    }  
    if (step === 3) {
      if (!teacherAvailabilityDtls || teacherAvailabilityDtls.length === 0) {
        toast.error("At least one teacher is required");
        return;
      }
      const isInvalid = teacherAvailabilityDtls.some(
        row =>!row.teacherDtls?.teacherId || !(row.availability && Object.keys(row.availability).length > 0) || row.remuneration === "" || row.remuneration === null ||
          row.remuneration === undefined || row.remuneration === "" || row.remuneration === null ||row.remuneration === undefined
      );
      if (isInvalid) {
        toast.error("Please fill all Teacher Availability details");
        return;
      }
    }
    if (step === 4) {
      if (!combinedBatchDtls || combinedBatchDtls.length === 0) {
        toast.error("At least one combined batch row is required");
        return;
      }
      const isInvalid = combinedBatchDtls.some(row =>
        !row.grade || !row.combinedBatches || row.combinedBatches.length === 0
      );
      if (isInvalid) {
        toast.error("Please fill all Combined Batch details (grade and batches)");
        return;
      }
    }
    if (step === 5) {
        const isInvalid = subjectTeacherDtls.some(row => {
          const subject = row.selectedSubjectDtlsForsubjectTeacherDtls;
          const hasSubject = !subject || !subject.subjectId;
          const hasTeachers = !row.teachersForSubjectTeachearDtls || row.teachersForSubjectTeachearDtls.length === 0;
          const hasMapping = !row.groupTeacherMap || Object.values(row.groupTeacherMap).every(arr => !arr || arr.length === 0);
          const hasBatches = !row.finalBatchListForSubjectTeacherDtls || row.finalBatchListForSubjectTeacherDtls.length === 0;
          return hasSubject || hasTeachers || hasMapping || hasBatches;
        });
        
        if (isInvalid) {
          toast.error("Please complete Subject–Teacher assignment properly");
          return;
        }
        handleGenerateTimeTableClick();
        return;
    }
    setStep(prev => prev + 1);
  };


  /* ----------------------------------- SLOT TABLE ----------------------------------------- */
  const addSlotDaysDtls = () => {
    if (!visibleTables.slots) {
      showTable("slots");
      return;
    }
    if (slotDaysDtls.length === 0) {
      setSlotDaysDtls([{slot: "slot-1",days: [...days],startTime: "",endTime: ""}]);
      return;
    }
    const lastSlot = slotDaysDtls[slotDaysDtls.length - 1];
    if (!lastSlot.days || lastSlot.days.length === 0) {
      toast.error("Please select days for the previous slot before adding a new slot.");
      return;
    }
    if (!lastSlot.startTime) {
      toast.error("Please select start time for the previous slot before adding a new slot.");
      return;
    }
    if (!lastSlot.endTime) {
      toast.error("Please select end time for the previous slot before adding a new slot.");
      return;
    }
    const nextSlotNumber = slotDaysDtls.length + 1;
    setSlotDaysDtls([
      ...slotDaysDtls,
      {slot: `slot-${nextSlotNumber}`,days: [...days],startTime: "",endTime: ""}
    ]);
  };

  const handleTimeChange = (index, field, value) => {
    const newRows = [...slotDaysDtls];
    const row = { ...newRows[index], [field]: value };
    if (field === "startTime" && row.endTime && value >= row.endTime) {
      toast.error("Start time must be less than end time");
      return;
    }
    if (field === "endTime" && row.startTime && value <= row.startTime) {
      toast.error("End time must be greater than start time");
      return;
    }
    newRows[index] = row;
    setSlotDaysDtls(newRows);
  };

  const handleDayChange = (index, day) => {
    const newRows = [...slotDaysDtls];
    const row = { ...newRows[index] };
    if (row.days.includes(day)) {
      row.days = row.days.filter(d => d !== day);
    } else {
      row.days = [...row.days, day];
    }
    newRows[index] = row;
    setSlotDaysDtls(newRows);
  };

  const removeSlotDayDtls = (index) => {
    const newRows = slotDaysDtls.filter((_, i) => i !== index);
    setSlotDaysDtls(newRows);
  };


  /* ----------------------------------- SUBJECT TABLE ----------------------------------------- */
  const addSubjectWeeklySlotDtls = () => {
    if (!visibleTables.subjects) {
      showTable("subjects");
      return;
    }
    if (subjectWeeklySlotDtls.length === 0) {
      setSubjectWeeklySlotDtls([{subjectDtlsForWeeklySlot: {},weeklySlot: ""}]);
      return;
    }
    const lastRow =subjectWeeklySlotDtls[subjectWeeklySlotDtls.length - 1];
    if (!lastRow.subjectDtlsForWeeklySlot.subjectId) {
      toast.error("Select Subject Details");
      return;
    }
    if (lastRow.weeklySlot === "" || lastRow.weeklySlot === null || lastRow.weeklySlot === undefined) {
        toast.error("Select Weekly Slot Details");
        return;
    }
    const duplicate = subjectWeeklySlotDtls.slice(0, -1).some(r =>r.subjectDtlsForWeeklySlot.subjectId === lastRow.subjectDtlsForWeeklySlot.subjectId);
    if (duplicate) {
      toast.error("Duplicate Subject not allowed");
      return;
    }
    setSubjectWeeklySlotDtls([
      ...subjectWeeklySlotDtls,
      { subjectDtlsForWeeklySlot: {},weeklySlot: ""}
    ]);
  };

  const handleSubjectChange = async (index, value) => {
    let newRows = [...subjectWeeklySlotDtls];
    //CLEAR SUBJECT 
    if (value === "") {
      newRows[index].subjectDtlsForWeeklySlot = {subjectId: "",grade: ""};
    } else {
      const [subjectId, grade] = value.split("_");
      const duplicateSubject = newRows.some((row, rowIndex) => rowIndex !== index && String(row.subjectDtlsForWeeklySlot?.subjectId) === String(subjectId));
      if (duplicateSubject) {
        toast.error("Duplicate subjects are not allowed");
        return;
      }
      newRows[index].subjectDtlsForWeeklySlot.subjectId = subjectId;
      newRows[index].subjectDtlsForWeeklySlot.grade = grade;
    }
    //PREPARE SUBJECT IDS
    const subjectIds = newRows.map(row => row.subjectDtlsForWeeklySlot?.subjectId).filter(Boolean);
    const selectedTeachers = teacherAvailabilityDtls.filter(row => row.teacherDtls?.teacherId);
    //NO SUBJECTS BUT TEACHERS EXIST 
    if (subjectIds.length === 0 && selectedTeachers.length > 0) {
      const teacherNames = selectedTeachers.map(row => row.teacherDtls.teacherName || row.teacherDtls.teacherId).join(", ");
      toast.error(`Cannot remove all subjects.\n\n` +
      `The following teacher(s) are still selected:\n${teacherNames}\n\n` +
      `Please remove them from Teacher Availability first.`);
      return;
    }
    //NO SUBJECTS AND NO TEACHERS
    if (subjectIds.length === 0) {
      setSubjectWeeklySlotDtls(newRows);
      const clearedRows = (teacherAvailabilityDtls || []).map(row => ({...row,teachersBySubjects: [],teacherDtls: {},remuneration: "",maxWeeklySlots: ""}));
      setTeacherAvailabilityDtls(clearedRows);
      return;
    }
    try {
      //FETCH TEACHERS
      const res = await axios.post(`${BASE}/teachers/getTeachersBySubjects`,{ subjectIds });
      const teachersList = res.data || [];
      //VALID TEACHER IDS
      const validTeacherIds = teachersList.map(t => t.teacher_id || t.teacherId);
      //CURRENT SELECTED TEACHERS
      const selectedTeacherIds = teacherAvailabilityDtls.map(row =>row.teacherDtls?.teacherId).filter(Boolean);
      //FIND INVALID TEACHERS
      const invalidTeacherIds = selectedTeacherIds.filter(id =>!validTeacherIds.includes(id));
      const invalidTeachers = teacherAvailabilityDtls.filter(row => invalidTeacherIds.includes(row.teacherDtls?.teacherId));
      //BLOCK SUBJECT CHANGE
      if (invalidTeachers.length > 0) {
        const teacherNames = invalidTeachers.map(row =>row.teacherDtls?.teacherName ||row.teacherDtls?.teacherId).join(", ");
        toast.error(
          `Cannot change subject.\n\n` +
          `The following teacher(s) are dependent on current subjects:\n${teacherNames}\n\n` +
          `Please remove them from Teacher Availability first.`);
        return;
      }
      //UPDATE SUBJECT STATE 
      setSubjectWeeklySlotDtls(newRows);
      //UPDATE TEACHER TABLE
      let updatedRows;
      if (!teacherAvailabilityDtls || teacherAvailabilityDtls.length === 0) {
        updatedRows = [{teacherDtls: {},availability: {},teachersBySubjects:teachersList}];
      } else {
        updatedRows = teacherAvailabilityDtls.map(row => ({...row,teachersBySubjects:teachersList}));
      }
      setTeacherAvailabilityDtls(updatedRows);
    } catch (error) {
    toast.error("Failed to fetch teachers. please refresh the page or try again later.");
  }
};

const handleWeeklySlotChange = (index, value) => {
  let newRows = [...subjectWeeklySlotDtls];
  newRows[index].weeklySlot = value;
  setSubjectWeeklySlotDtls(newRows);
};


const removeSubjectWeeklySlotDtls = async (index) => {
  try {
    const newRows = subjectWeeklySlotDtls.filter((_, i) => i !== index);
    //REMAINING SUBJECT IDS
    const remainingSubjectIds = newRows.map(row => row.subjectDtlsForWeeklySlot?.subjectId).filter(Boolean);
    //SELECTED TEACHERS 
    const selectedTeachers = teacherAvailabilityDtls.filter(row => row.teacherDtls?.teacherId);
    //NO SUBJECTS BUT TEACHERS EXIST
    if (remainingSubjectIds.length === 0 && selectedTeachers.length > 0) {
      const teacherNames = selectedTeachers.map(row =>row.teacherDtls?.teacherName || row.teacherDtls?.teacherId).join(", ");
      toast.error(
        `Cannot remove subject.\n\n` +
        `The following teacher(s) are still selected:\n${teacherNames}\n\n` +
        `Please remove them from Teacher Availability first.`
      );
      return;
    }
    //NO SUBJECTS & NO TEACHERS
    if (remainingSubjectIds.length === 0) {
      setSubjectWeeklySlotDtls(newRows);
      setTeacherAvailabilityDtls([{teacherDtls: {},availability: {},teachersBySubjects: [],remuneration: "",maxWeeklySlots: ""}]);
      return;
    }
    //FETCH VALID TEACHERS
    const res = await axios.post(`${BASE}/teachers/getTeachersBySubjects`,{subjectIds:remainingSubjectIds});
    const teachersList =res.data || [];
    //VALID TEACHER IDS
    const validTeacherIds = teachersList.map(t => t.teacher_id || t.teacherId);
    //CURRENT SELECTED TEACHER IDS
    const selectedTeacherIds = teacherAvailabilityDtls.map(row =>row.teacherDtls?.teacherId).filter(Boolean);
    //INVALID TEACHERS 
    const invalidTeacherIds = selectedTeacherIds.filter(id =>!validTeacherIds.includes(id));
    const invalidTeachers = teacherAvailabilityDtls.filter(row =>invalidTeacherIds.includes(row.teacherDtls?.teacherId));
    //BLOCK REMOVE
    if (invalidTeachers.length > 0) {
      const teacherNames = invalidTeachers.map(row =>row.teacherDtls?.teacherName || row.teacherDtls?.teacherId).join(", ");
      toast.error(
        `Cannot remove subject.\n\n` +
        `The following teacher(s) are dependent on current subjects:\n${teacherNames}\n\n` +
        `Please remove them from Teacher Availability first.`
      );
      return;
    }
    //UPDATE SUBJECTS
    setSubjectWeeklySlotDtls(newRows);

    //UPDATE TEACHER TABLE
    const teacherRows = teacherAvailabilityDtls.length === 0 ? [{teacherDtls: {},availability: {},teachersBySubjects:teachersList,remuneration: "",maxWeeklySlots: ""}]
        : teacherAvailabilityDtls.map(row => ({...row,teachersBySubjects:teachersList}));
    setTeacherAvailabilityDtls(teacherRows);
  } catch (error) {
    toast.error("Failed to remove subject. please refresh the page or try again later.");
  }
};



/* ----------------------------------- TEACHER TABLE ----------------------------------------- */
  const addTeacherAvailabilityDtls = async (index) => {
    if (!visibleTables.teachers) {
      showTable("teachers");
      return;
    }
    if (teacherAvailabilityDtls.length === 0) {
      let teachersList = [];
      if (subjectWeeklySlotDtls && subjectWeeklySlotDtls.length > 0) {
        const subjectIds = subjectWeeklySlotDtls.map(row => row.subjectDtlsForWeeklySlot?.subjectId).filter(Boolean);
        if (subjectIds.length > 0) {
          const res = await axios.post(`${BASE}/teachers/getTeachersBySubjects`, { subjectIds });
          teachersList = res.data || [];
        }
      }
      setTeacherAvailabilityDtls([{ teacherDtls: {}, availability: {}, teachersBySubjects: teachersList, remuneration: "", maxWeeklySlots: "" }]);
      return;
    }
    const lastRow = teacherAvailabilityDtls[teacherAvailabilityDtls.length - 1];
    if (!lastRow.teacherDtls.teacherId) {
      toast.error("Select Teacher");
      return;
    }
    if (!lastRow.availability == {}) {
      toast.error("Select Teacher Availability");
      return;
    }
    if (lastRow.remuneration === "" || lastRow.remuneration === null || lastRow.remuneration === undefined) {
      toast.error("Enter Remuneration Details");
      return;
    }
    if (lastRow.maxWeeklySlots === "" || lastRow.maxWeeklySlots === null ||lastRow.maxWeeklySlots === undefined) {
      toast.error("Select Max Weekly Slots Details");
      return;
    }
    setTeacherAvailabilityDtls([...teacherAvailabilityDtls, { teacherDtls: {}, availability: {}, teachersBySubjects: lastRow.teachersBySubjects,remuneration: "",maxWeeklySlots: "" }]);
  };


  // const handleTeacherChange = async (index, teacherId) => {
  //   const newRows = [...teacherAvailabilityDtls];
  //   const oldTeacherId = newRows[index]?.teacherDtls?.teacherId;
  //   const teacher = newRows[index]?.teachersBySubjects?.find(t => String(t.teacher_id) === String(teacherId));
  //   if (!teacher) return;
  //   newRows[index].teacherDtls = {
  //     teacherId: teacher.teacher_id,
  //     teacherName: teacher.teacher_name
  //   };
  //   try {
  //     const updatedSubjectTeacherDtls = await Promise.all(
  //       subjectTeacherDtls.map(async (row) => {
  //         const subjectId = row?.selectedSubjectDtlsForsubjectTeacherDtls?.subjectId;
  //         if (!subjectId) return row;
  //         const res = await axios.post(`${BASE}/teachers/getTeachersBySubjects`,{ subjectIds: [subjectId] });
  //         const backendTeachers = res.data || [];
  //         const availableTeacherIds = teacherAvailabilityDtls.map(r => r.teacherDtls?.teacherId).filter(Boolean);


  //         const validTeachers = backendTeachers.filter(t => availableTeacherIds.includes(t.teacher_id));
  //         const enrichedTeachers = await Promise.all(
  //           validTeachers.map(async (t) => {
  //             const batchDetailsRes = await axios.get(
  //               `${BASE}/generate/getBatchDetailsForGroupTeacherMapDtls/${t.teacher_id}/${subjectId}/${row.selectedSubjectDtlsForsubjectTeacherDtls?.grade}`
  //             );
  //             return {...t,allowedBatches: (batchDetailsRes.data || []).map(b => b.gid)};
  //           })
  //         );
  //         const updatedGroupTeacherMap = {...(row.groupTeacherMap || {})};

  //         Object.keys(updatedGroupTeacherMap).forEach(gid => {
  //           updatedGroupTeacherMap[gid] =
  //             (updatedGroupTeacherMap[gid] || []).filter(
  //               id => String(id) !== String(oldTeacherId)
  //             );
  //         });
  //         const selectedTeacher = enrichedTeachers.find(t => String(t.teacher_id) === String(teacher.teacher_id));
  //           if (selectedTeacher) {
  //             Object.keys(updatedGroupTeacherMap).forEach(gid => {
  //               const existing = updatedGroupTeacherMap[gid] || [];
  //               const isAllowed = selectedTeacher.allowedBatches?.includes(gid);
  //               if (isAllowed && !existing.includes(teacher.teacher_id)) {
  //                 updatedGroupTeacherMap[gid] = [
  //                   ...existing,
  //                   teacher.teacher_id
  //                 ];
  //               }
  //             });
  //           }
  //         return {
  //           ...row,
  //           teachersForSubjectTeachearDtls: enrichedTeachers,
  //           groupTeacherMap: updatedGroupTeacherMap
  //         };
  //       })
  //     );
  //     setSubjectTeacherDtls(updatedSubjectTeacherDtls);
  //     setTeacherAvailabilityDtls(newRows);
  //   } catch (err) {
  //     console.error("Teacher change error:", err);
  //   }
  // };


  const handleTeacherChange = async (index, teacherId) => {
    const newRows = [...teacherAvailabilityDtls];
    const oldTeacherId = newRows[index]?.teacherDtls?.teacherId;
    const teacher = newRows[index]?.teachersBySubjects?.find(t => String(t.teacher_id) === String(teacherId));
    if (!teacher) return;
    newRows[index].teacherDtls = {
      teacherId: teacher.teacher_id,
      teacherName: teacher.teacher_name
    };
    try {
      const updatedSubjectTeacherDtls = await Promise.all(
        subjectTeacherDtls.map(async (row) => {
          const subjectId = row?.selectedSubjectDtlsForsubjectTeacherDtls?.subjectId;
          if (!subjectId) return row;
          
          const res = await axios.post(`${BASE}/teachers/getTeachersBySubjects`, { subjectIds: [subjectId] });
          const backendTeachers = res.data || [];
          
          // Get current available teacher IDs using the updated local copy
          const availableTeacherIds = newRows.map(r => r.teacherDtls?.teacherId).filter(Boolean);
          const validTeachers = backendTeachers.filter(t => availableTeacherIds.includes(t.teacher_id));
          if (validTeachers.length === 0) {
            return null; 
          }

          const enrichedTeachers = await Promise.all(
            validTeachers.map(async (t) => {
              const batchDetailsRes = await axios.get(
                `${BASE}/generate/getBatchDetailsForGroupTeacherMapDtls/${t.teacher_id}/${subjectId}/${row.selectedSubjectDtlsForsubjectTeacherDtls?.grade}`
              );
              return { ...t, allowedBatches: (batchDetailsRes.data || []).map(b => b.gid) };
            })
          );
          
          const updatedGroupTeacherMap = { ... (row.groupTeacherMap || {}) };

          Object.keys(updatedGroupTeacherMap).forEach(gid => {
            updatedGroupTeacherMap[gid] =
              (updatedGroupTeacherMap[gid] || []).filter(
                id => String(id) !== String(oldTeacherId)
              );
          });
          
          const selectedTeacher = enrichedTeachers.find(t => String(t.teacher_id) === String(teacher.teacher_id));
          if (selectedTeacher) {
            Object.keys(updatedGroupTeacherMap).forEach(gid => {
              const existing = updatedGroupTeacherMap[gid] || [];
              const isAllowed = selectedTeacher.allowedBatches?.includes(gid);
              if (isAllowed && !existing.includes(teacher.teacher_id)) {
                updatedGroupTeacherMap[gid] = [
                  ...existing,
                  teacher.teacher_id
                ];
              }
            });
          }
          
          return {
            ...row,
            teachersForSubjectTeachearDtls: enrichedTeachers,
            groupTeacherMap: updatedGroupTeacherMap
          };
        })
      );
      const cleanRows = updatedSubjectTeacherDtls.filter(Boolean);
      setSubjectTeacherDtls(cleanRows);
      setTeacherAvailabilityDtls(newRows);
    } catch (err) {
        toast.error("Failed to save draft. please refresh the page or try again later.");
    }
};



  const handleAvailabilityChange = (rowIndex, day, slot) => {
    let newRows = [...teacherAvailabilityDtls];
    const teacherId = newRows[rowIndex].teacherDtls.teacherId;
    if (!teacherId) {
      toast.error("Please select teacher first");
      return;
    }
    if (!newRows[rowIndex].availability[day]) {
      newRows[rowIndex].availability[day] = [];
    }
    if (newRows[rowIndex].availability[day].includes(slot)) {
      newRows[rowIndex].availability[day] =
        newRows[rowIndex].availability[day].filter(s => s !== slot);
    } else {
      newRows[rowIndex].availability[day].push(slot);
    }
    setTeacherAvailabilityDtls(newRows);
  };


  const removeTeacherAvailabilityDtls = (index) => {
    const teacherId = teacherAvailabilityDtls[index]?.teacherDtls?.teacherId;
    if (!teacherId) {
      const updatedRows = [...teacherAvailabilityDtls];
      updatedRows.splice(index, 1);
      setTeacherAvailabilityDtls(updatedRows);
      return;
    }
    //CHECK DEPENDENCY 
    const isTeacherUsed = subjectTeacherDtls.some(row => Object.values(row.groupTeacherMap || {}).some(teachers =>(teachers || []).some(id => String(id) === String(teacherId))));
    if (isTeacherUsed) {
      toast.error("This teacher is already assigned to subjects. Remove teacher mapping first.");
      return;
    }
    //REMOVE FROM SUBJECT STATE (SAFETY CLEANUP)
    const cleanedSubjectTeacherDtls =
      subjectTeacherDtls.map(row => {const cleanedGroupTeacherMap = {};
        Object.entries(row.groupTeacherMap || {}).forEach(([gid, teachers]) => {
          cleanedGroupTeacherMap[gid] =
            (teachers || []).filter(
              id => String(id) !== String(teacherId)
            );
        });
    const cleanedTeachersList = (row.teachersForSubjectTeachearDtls || []).filter(t => String(t.teacher_id) !== String(teacherId));
      return {
        ...row,
        groupTeacherMap: cleanedGroupTeacherMap,
        teachersForSubjectTeachearDtls: cleanedTeachersList
      };
    });
    setSubjectTeacherDtls(cleanedSubjectTeacherDtls);
    //REMOVE FROM TEACHER AVAILABILITY
    const newRows = teacherAvailabilityDtls.filter((_, i) => i !== index);
    setTeacherAvailabilityDtls(newRows);
  };


  const handleRemunerationChange = (rowIndex, value) => {
    const updatedRows = [...teacherAvailabilityDtls];
    updatedRows[rowIndex].remuneration = value;
    setTeacherAvailabilityDtls(updatedRows);
  };

  const handleMaxChange = (rowIndex, value) => {
    const updatedRows = [...teacherAvailabilityDtls];
    updatedRows[rowIndex].maxWeeklySlots = Number(value);
    setTeacherAvailabilityDtls(updatedRows);
  };

  /* ----------------------------------- COMBINED BATCH TABLE ----------------------------------------- */
  const handleGradeChange = (rowIndex, grade) => {
    const updatedRows = [...combinedBatchDtls];
    if (!grade) {
      updatedRows[rowIndex].grade = "";
      updatedRows[rowIndex].language = "";
      updatedRows[rowIndex].availableBatches = [];
      updatedRows[rowIndex].combinedBatches = [];
      updatedRows[rowIndex].combinedBatchGroupId = "";
      setCombinedBatchDtls(updatedRows);
      return;
    }
    const [gradePart, languagePart] = grade.includes("_")? grade.split("_"): [grade, ""];
    updatedRows[rowIndex].grade = gradePart;
    updatedRows[rowIndex].language = languagePart;
    axios.get(`${BASE}/combinedBatches/getBatchesByGradeForCombinedBatches/${gradePart}/${languagePart}`)
      .then((res) => {
        updatedRows[rowIndex].availableBatches = res.data;
        updatedRows[rowIndex].combinedBatches = [];
        updatedRows[rowIndex].combinedBatchGroupId = "";
        setCombinedBatchDtls(updatedRows);
      })
      .catch((err) => {
        toast.error("Failed to fetch batches. please refresh the page or try again later.");
        updatedRows[rowIndex].availableBatches = [];
        setCombinedBatchDtls(updatedRows);
      });
  };

// const addBatchToCombined = async (rowIndex,batch) => {
//     const updatedRows = [...combinedBatchDtls];
//     if (!updatedRows[rowIndex].combinedBatches.find(b => b.batch_id === batch.batch_id)) {
//       updatedRows[rowIndex].combinedBatches.push(batch);
//       //GENERATE GID 
//     const batchIds = updatedRows[rowIndex].combinedBatches.map(b => b.batch_id);
//     const grade = updatedRows[rowIndex].grade;
//     const batchNumbers =batchIds.map(id => id.split("-")[2]);
//     const gid =`g${grade}-${batchNumbers.join("-")}`;
//     updatedRows[rowIndex].combinedBatchGroupId = gid;
//     //UPDATE SUBJECT TEACHER STATE
//     if (subjectTeacherDtls.length > 0) {
//       try {
//         let finalBatchArray = [];
//         //FROM FRONTEND 
//         updatedRows.forEach(group => {
//           if (group.combinedBatchGroupId) {
//             finalBatchArray.push(group.combinedBatchGroupId);
//           }
//         });
//         //FROM BACKEND 
//         const batchRes = await axios.get(`${BASE}/generate/getBatchesByGradeForSubjectTeacherDtls/${grade}`);
//         const fetchedBatches = batchRes.data || [];
//         fetchedBatches.forEach(b => {
//           if (b.gid) {finalBatchArray.push(b.gid);}
//         });
//         //REMOVE DUPLICATES 
//         finalBatchArray = [...new Set(finalBatchArray)];
//         // UPDATE ONLY VALID SUBJECT ROWS
//         const updatedSubjectTeacherDtls =
//           subjectTeacherDtls.map(
//             row => {
//               const subjectId =row?.selectedSubjectDtlsForsubjectTeacherDtls?.subjectId;
//               // skip empty rows
//               if (!subjectId) { return row;}
//               return {
//                 ...row,
//                 finalBatchListForSubjectTeacherDtls:finalBatchArray
//               };
//             }
//           );
//         setSubjectTeacherDtls(updatedSubjectTeacherDtls);
//       } catch (err) {
//         console.error("Error updating final batch list:",err);
//       }
//     }
//     setCombinedBatchDtls(updatedRows);
//   }
// };




const addBatchToCombined = async (rowIndex, batch) => {
  const updatedRows = [...combinedBatchDtls];
  if (!updatedRows[rowIndex].combinedBatches.find(b => b.batch_id === batch.batch_id)) {
    updatedRows[rowIndex].combinedBatches.push(batch);
    const currentGrade = updatedRows[rowIndex].grade;
    const batchIds = updatedRows[rowIndex].combinedBatches.map(b => b.batch_id);
    const batchNumbers = batchIds.map(id => id.split("-")[2]);
    // 1. Calculate and assign the new GID to our edited row
    const newGid = `g${currentGrade}-${batchNumbers.join("-")}`;
    updatedRows[rowIndex].combinedBatchGroupId = newGid;
    // GUARD CLAUSE: Only proceed if there are subject rows matching the current grade
    const hasMatchingGrade = subjectTeacherDtls.some(
      row => row?.selectedSubjectDtlsForsubjectTeacherDtls?.grade === currentGrade
    );
    if (hasMatchingGrade) {
      try {
        // 2. Collect ALL frontend combined GIDs for this specific grade
        const frontendActiveGids = updatedRows
          .filter(group => group.grade === currentGrade && group.combinedBatchGroupId)
          .map(group => group.combinedBatchGroupId);
        // 3. Fetch backend batches for this grade
        const batchRes = await axios.get(`${BASE}/generate/getBatchesByGradeForSubjectTeacherDtls/${currentGrade}`);
        const backendGradeBatches = (batchRes.data || []).map(b => b.gid);
        // 4. Merge them completely and remove duplicates
        // This keeps the newly added one, previously added ones, and backend entries safely.
        const masterBatchList = [...new Set([...frontendActiveGids, ...backendGradeBatches])];
        const updatedSubjectTeacherDtls = await Promise.all(
          subjectTeacherDtls.map(async (row) => {
            const subjectId = row?.selectedSubjectDtlsForsubjectTeacherDtls?.subjectId;
            const rowGrade = row?.selectedSubjectDtlsForsubjectTeacherDtls?.grade;
            if (subjectId && rowGrade === currentGrade) {
              const enrichedTeachers = await Promise.all(
                (row.teachersForSubjectTeachearDtls || []).map(async (t) => {
                  const batchDetailsRes = await axios.get(
                    `${BASE}/generate/getBatchDetailsForGroupTeacherMapDtls/${t.teacher_id}/${subjectId}/${currentGrade}`
                  );
                  const allowed = (batchDetailsRes.data || []).map(b => b.gid);
                  
                  // Map against our unified master list
                  return { 
                    ...t, 
                    allowedBatches: masterBatchList.map(gid => allowed.includes(gid) ? gid : "-") 
                  };
                })
              );
              return {
                ...row,
                teachersForSubjectTeachearDtls: enrichedTeachers,
                finalBatchListForSubjectTeacherDtls: masterBatchList
              };
            }
            return row;
          })
        );
        setSubjectTeacherDtls(updatedSubjectTeacherDtls);
      } catch (err) {
        
      }
    }
    setCombinedBatchDtls(updatedRows);
  }
};


// const removeCombinedBatch = async (rowIndex,batchId) => {
//   const updatedRows = [...combinedBatchDtls];
//   updatedRows[rowIndex].combinedBatches =
//     updatedRows[rowIndex]
//       .combinedBatches
//       .filter(
//         b => b.batch_id !== batchId
//       );

//   // Recalculate Group ID
//   const batchIds =
//     updatedRows[rowIndex]
//       .combinedBatches
//       .map(b => b.batch_id);

//   if (batchIds.length > 0) {

//     const grade =
//       updatedRows[rowIndex].grade;

//     const batchNumbers =
//       batchIds.map(
//         id => id.split("-")[2]
//       );

//     updatedRows[rowIndex]
//       .combinedBatchGroupId =
//       `g${grade}-${batchNumbers.join("-")}`;

//   } else {

//     updatedRows[rowIndex]
//       .combinedBatchGroupId = "";
//   }

//   // Update Combined Batch State
//   setCombinedBatchDtls(updatedRows);

//   // Update Subject Teacher State
//   if (subjectTeacherDtls.length > 0) {

//     const currentGrade =
//       updatedRows[rowIndex].grade;

//     let finalBatchArray = [];

//     // Add all current UI gids
//     updatedRows.forEach(group => {

//       if (group.combinedBatchGroupId) {

//         finalBatchArray.push(
//           group.combinedBatchGroupId
//         );

//       }

//     });

//     try {

//       // Fetch backend groups for same grade
//       const batchRes = await axios.get(
//         `${BASE}/generate/getBatchesByGradeForSubjectTeacherDtls/${currentGrade}`
//       );

//       const fetchedBatches =
//         batchRes.data || [];

//       fetchedBatches.forEach(b => {

//         if (b.gid) {

//           finalBatchArray.push(b.gid);

//         }

//       });

//       // Remove duplicates
//       finalBatchArray = [
//         ...new Set(finalBatchArray)
//       ];

//       // Update all subject rows
//       const updatedSubjectTeacherDtls =
//         subjectTeacherDtls.map(row => ({

//           ...row,

//           finalBatchListForSubjectTeacherDtls:
//             finalBatchArray

//         }));

//       setSubjectTeacherDtls(
//         updatedSubjectTeacherDtls
//       );

//     } catch (err) {

//       console.error(
//         "Error updating batch list:",
//         err
//       );

//     }

//   }

// };



const removeCombinedBatch = async (rowIndex, batchId) => {
  const updatedRows = [...combinedBatchDtls];  
  // 1. Remove the batch
  updatedRows[rowIndex].combinedBatches = updatedRows[rowIndex].combinedBatches.filter(b => b.batch_id !== batchId);
  // 2. Recalculate Group ID
  const batchIds = updatedRows[rowIndex].combinedBatches.map(b => b.batch_id);
  const currentGrade = updatedRows[rowIndex].grade;
  if (batchIds.length > 1) {
    const batchNumbers = batchIds.map(id => id.split("-")[2]);
    updatedRows[rowIndex].combinedBatchGroupId = `g${currentGrade}-${batchNumbers.join("-")}`;
  } else {
    updatedRows[rowIndex].combinedBatchGroupId = "";
  }
  // 3. HANDS-OFF GUARD
  const hasMatchingGrade = subjectTeacherDtls.some(row => row?.selectedSubjectDtlsForsubjectTeacherDtls?.grade === currentGrade);
  if (hasMatchingGrade) {
    try {
      // Fetch backend batches
      const batchRes = await axios.get(`${BASE}/generate/getBatchesByGradeForSubjectTeacherDtls/${currentGrade}`);
      const fetchedBatches = (batchRes.data || []).map(b => b.gid);
      // Get ALL valid GIDs from the current UI state (updatedRows)
      const uiGroupIds = updatedRows
        .filter(r => r.grade === currentGrade && r.combinedBatchGroupId)
        .map(r => r.combinedBatchGroupId);

      // Final Master List: Combine backend + current UI state (including the new/updated GID)
      const finalBatchList = [...new Set([ ...uiGroupIds,...fetchedBatches])];
      const updatedSubjectTeacherDtls = await Promise.all(
        subjectTeacherDtls.map(async (row) => {
          const subjectId = row?.selectedSubjectDtlsForsubjectTeacherDtls?.subjectId;
          const rowGrade = row?.selectedSubjectDtlsForsubjectTeacherDtls?.grade;
          if (subjectId && rowGrade === currentGrade) {
            const enrichedTeachers = await Promise.all(
              (row.teachersForSubjectTeachearDtls || []).map(async (t) => {
                const batchDetailsRes = await axios.get(
                  `${BASE}/generate/getBatchDetailsForGroupTeacherMapDtls/${t.teacher_id}/${subjectId}/${currentGrade}`
                );
                const allowed = (batchDetailsRes.data || []).map(b => b.gid);
                
                // Map based on the master list
                return { 
                  ...t, 
                  allowedBatches: finalBatchList.map(gid => allowed.includes(gid) ? gid : "-") 
                };
              })
            );

            return {
              ...row,
              teachersForSubjectTeachearDtls: enrichedTeachers,
              finalBatchListForSubjectTeacherDtls: finalBatchList
            };
          }
          return row;
        })
      );
      setSubjectTeacherDtls(updatedSubjectTeacherDtls);
    } catch (err) {
      toast.error("Failed to update batch list. please refresh the page or try again later.");
    }
  }
  setCombinedBatchDtls(updatedRows);
};

//   const removeCombinedBatchDtls = (
//   rowIndex
// ) => {

//   let updatedRows = [
//     ...combinedBatchDtls
//   ];

//   // Get removed gid
//   const removedGid =
//     updatedRows[rowIndex]
//       ?.combinedBatchGroupId;

//   // Remove row
//   updatedRows =
//     updatedRows.filter(
//       (_, i) => i !== rowIndex
//     );

//   setCombinedBatchDtls(updatedRows);

//   // Remove gid from subjectTeacherDtls
//   if (
//     removedGid &&
//     subjectTeacherDtls.length > 0
//   ) {

//     const updatedSubjectTeacherDtls =
//       subjectTeacherDtls.map(row => ({

//         ...row,

//         finalBatchListForSubjectTeacherDtls:
//           (
//             row.finalBatchListForSubjectTeacherDtls || []
//           ).filter(
//             gid => gid !== removedGid
//           )

//       }));

//     setSubjectTeacherDtls(
//       updatedSubjectTeacherDtls
//     );

//   }

// };


const removeCombinedBatchDtls = (rowIndex) => {
  const gradeToRemove = combinedBatchDtls[rowIndex]?.grade;
  const isGradeInUse = subjectTeacherDtls.some((row) => row?.selectedSubjectDtlsForsubjectTeacherDtls?.grade === gradeToRemove && row?.selectedSubjectDtlsForsubjectTeacherDtls?.subjectId);
  if (isGradeInUse) {
    toast.error(`Cannot remove row: Subjects are already assigned to Grade ${gradeToRemove}.`);
    return;
  }
  // 2. PROCEED WITH REMOVAL
  const updatedRows = combinedBatchDtls.filter((_, i) => i !== rowIndex);
  setCombinedBatchDtls(updatedRows);
};

const addNewCombinedRow = () => {
  if (!visibleTables.combinedBatches) {
    showTable("combinedBatches");
    return;
  }
  if (combinedBatchDtls.length === 0) {
    setCombinedBatchDtls([{grade: "",language: "",availableBatches: [],combinedBatches: [],combinedBatchGroupId: ""}]);
    return;
  }
  const lastRow = combinedBatchDtls[combinedBatchDtls.length - 1];
  if (!lastRow.grade || !lastRow.language || lastRow.combinedBatches.length === 0 ||!lastRow.combinedBatchGroupId) {
    toast.error("Please complete the last row before adding a new one.");
    return;
  }
  const batchCount = (lastRow.combinedBatchGroupId.split("-").length - 1);
  if (batchCount < 2) {
    toast.error("You must combine at least 2 batches to create a group.");
    return;
  }
  // duplicate check
  const duplicateGroup = combinedBatchDtls.some((row, index) => index !== combinedBatchDtls.length - 1 && row.combinedBatchGroupId && row.combinedBatchGroupId === lastRow.combinedBatchGroupId);
  if (duplicateGroup) {
    toast.error("This batch combination is already added");
    return;
  }
  setCombinedBatchDtls([...combinedBatchDtls, {grade: "",language: "", availableBatches: [],combinedBatches: [],combinedBatchGroupId: ""}]);
};

/* -------------------------------------------- Subject-Teacher Assignment Table ------------------------------------------- */
//  const addAssignmentRow = () => {
//   if (!visibleTables.subjectTeacher) {
//     showTable("subjectTeacher");
//     return;
//   }
//   const lastRow = subjectTeacherDtls[subjectTeacherDtls.length - 1];
//   if (lastRow) {
//     const subject = lastRow.selectedSubjectDtlsForsubjectTeacherDtls;
//     if (!subject?.subjectId) {
//       toast.error("Please select subject before adding new row");
//       return;
//     }
//     if (!lastRow.teachersForSubjectTeachearDtls || lastRow.teachersForSubjectTeachearDtls.length === 0) {
//       toast.error("No teachers available for selected subject");
//       return;
//     }
//     const hasMapping = Object.values(lastRow.groupTeacherMap || {}).some(arr => arr && arr.length > 0);
//     if (!hasMapping) {
//       toast.error("Please assign at least one teacher before adding new row");
//       return;
//     }
//   }

//   setSubjectTeacherDtls(prev => [
//     ...prev,
//     {
//       selectedSubjectDtlsForsubjectTeacherDtls: {},
//       teachersForSubjectTeachearDtls: [],
//       groupTeacherMap: {},
//       finalBatchListForSubjectTeacherDtls: []
//     }
//   ]);
// };

const addAssignmentRow = () => {
  if (!visibleTables.subjectTeacher) {
    showTable("subjectTeacher");
    return;
  }
  const lastRow = subjectTeacherDtls[subjectTeacherDtls.length - 1];
  if (lastRow) {
    const subject = lastRow.selectedSubjectDtlsForsubjectTeacherDtls; 
    if (!subject?.subjectId) {
      toast.error("Please select subject before adding new row");
      return;
    }
    if (!lastRow.teachersForSubjectTeachearDtls || lastRow.teachersForSubjectTeachearDtls.length === 0) {
      toast.error("No teachers available for selected subject");
      return;
    }
    const groupMap = lastRow.groupTeacherMap || {};
    const teacherIds = Object.keys(groupMap);
    if (teacherIds.length === 0) {
      toast.error("Please assign teachers to batches before adding a new row");
      return;
    }
    const hasUnassignedTeacher = teacherIds.some(tId => !groupMap[tId] || groupMap[tId].length === 0);
    if (hasUnassignedTeacher) {
      toast.error("Every selected teacher must be assigned to at least one batch before adding a new row");
      return;
    }
  }
  setSubjectTeacherDtls([...subjectTeacherDtls,{selectedSubjectDtlsForsubjectTeacherDtls: {},teachersForSubjectTeachearDtls: [],groupTeacherMap: {},finalBatchListForSubjectTeacherDtls: []}]);
};

const removeAssignmentRow = (rowIndex) => {
  const updated = subjectTeacherDtls.filter((_, i) => i !== rowIndex);
  setSubjectTeacherDtls(
    updated.length
      ? updated
      : [{
          selectedSubjectId: "",
          teachersForSubjectTeachearDtls: [],
          groupTeacherMap: {}
        }]
  );
};

const handleSubjectChangeForsubjectTeacherDtls = async (rowIndex,value) => {
  const updated = [...subjectTeacherDtls];
  if (!value) {
    updated[rowIndex] = {
      ...updated[rowIndex],
      selectedSubjectDtlsForsubjectTeacherDtls: {},
      teachersForSubjectTeachearDtls: [],
      groupTeacherMap: {},
      finalBatchListForSubjectTeacherDtls: []
    };
    setSubjectTeacherDtls(updated);
    return;
  }
  const [subjectId, grade] = value.split("_");
  const duplicateSubject = updated.some((row, index) =>index !== rowIndex && String(row.selectedSubjectDtlsForsubjectTeacherDtls?.subjectId) === String(subjectId));
  if (duplicateSubject) {
    toast.error("Subject already selected");
    return;
  }
  updated[rowIndex].selectedSubjectDtlsForsubjectTeacherDtls = {subjectId,grade};
  updated[rowIndex].groupTeacherMap = {};
  updated[rowIndex].teachersForSubjectTeachearDtls = [];
  updated[rowIndex].finalBatchListForSubjectTeacherDtls = [];
  try {
    let finalBatchArray = [];
    combinedBatchDtls.forEach(group => {
      if (group.combinedBatchGroupId && String(group.grade) === String(grade)) {
        finalBatchArray.push(group.combinedBatchGroupId);
      }
    });
    const teacherRes = await axios.post(`${BASE}/teachers/getTeachersBySubjects`,{subjectIds: [subjectId],});
    const backendTeachers = teacherRes.data || [];
    const availableTeacherIds =teacherAvailabilityDtls.map(t => t.teacherDtls?.teacherId).filter(Boolean);
    const filteredTeachers = backendTeachers.filter(t => availableTeacherIds.includes(t.teacher_id));
  
    if (filteredTeachers.length === 0) {
      toast.error("No available teachers for this subject");
      return;
    }
    const enrichedTeachers = await Promise.all(
      filteredTeachers.map(async (t) => {
        const batchDetailsRes = await axios.get(
          `${BASE}/generate/getBatchDetailsForGroupTeacherMapDtls/${t.teacher_id}/${subjectId}/${grade}`
        );
        return {...t,allowedBatches: (batchDetailsRes.data || []).map(b => b.gid)};
      })
    );

    updated[rowIndex].teachersForSubjectTeachearDtls = enrichedTeachers;
    const batchRes = await axios.get(`${BASE}/generate/getBatchesByGradeForSubjectTeacherDtls/${grade}`);
    const fetchedBatches = batchRes.data || [];
    fetchedBatches.forEach(b => {
      if (b.gid) {finalBatchArray.push(b.gid);}
    });
    finalBatchArray = [...new Set(finalBatchArray)];
    updated[rowIndex].finalBatchListForSubjectTeacherDtls =finalBatchArray;
    setSubjectTeacherDtls(updated);
  } catch (err) {
    toast.error("Failed to load subject details. please refresh the page or try again later.");
  }
};


const toggleTeacher = (rowIndex, groupIndex, teacherId) => {
  const updated = [...subjectTeacherDtls];
  const current = updated[rowIndex].groupTeacherMap[groupIndex] || [];
  updated[rowIndex].groupTeacherMap[groupIndex] = current.includes(teacherId)? current.filter(id => id !== teacherId): [...current, teacherId];
  setSubjectTeacherDtls(updated);
};


  const handleGenerate = async () => {
      if (!userName || userName.trim() === "") {
        setShowSaveModal(true); 
        return;
      }
      if (true) {
       try {
          setLoading(true);
          const finalJSON = await prepareTimetablePayload();
          const response = await fetch(`${BASE}/generate`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              username: userName,
              timetableData: finalJSON   
            })
          });
          if (!response.ok) {
            toast.error(`Server responded with status ${response.status}`);
          }
          const data = await response.json();
          if (data.type === "report") {
            setReportContent(data.data);
            setShowReportModal(true);
          } else if (data.type === "timetable") {
            toast.success("Timetable generated successfully!");
            setShowGenratedTimeTableData(true)
            setGenratedTimeTableData(data.data);
          }
        } catch (error) {
          toast.error("Something went wrong ❌");
        }finally{
          setLoading(false);
        }
      }
      
  };


const prepareTimetablePayload = async () => {
  try {
    const prepareTimeslots = () => {
        const result = [];
        slotDaysDtls.forEach(row => {
          row.days.forEach(day => {
            result.push({
              day,
              slotName: row.slot
            });
          });
        });
        return result;
    };

    const prepareBatches = async () => {
      let grades = [];
      subjectWeeklySlotDtls.forEach(row => {
        let subjectId = row.subjectDtlsForWeeklySlot.subjectId;
        let gradeId = row.subjectDtlsForWeeklySlot.grade;
        let sub = subjects.find(s => s.subject_id == subjectId && s.grade == gradeId);
        if (!sub) return;
        let grade = sub.grade;
        grades.push(grade);
      });
      grades = [...new Set(grades)];
      /* ---------- GET BATCHES ---------- */
      const batchRes = await axios.post(
        `${BASE}/batches/byGrades`,{ grades }
      );
      return  batchRes.data;
    };

    const prepareSubjectRequirements = () => {
      let subjectRequirements = [];
      let seen = new Set();
      subjectWeeklySlotDtls.forEach(row => {
        let subjectId = row.subjectDtlsForWeeklySlot.subjectId;
        let gradeId = row.subjectDtlsForWeeklySlot.grade;
        let sub = subjects.find(s => s.subject_id == subjectId && s.grade == gradeId);
        if (!sub) return;
        const key = `${sub.grade}_${sub.subject_name}`;
        if (seen.has(key)) return;
        seen.add(key);
        subjectRequirements.push({
          subject: sub.subject_name,
          grade: String(sub.grade),
          requiredSlots: Number(row.weeklySlot)
        });
      });
      return subjectRequirements;
    };

    const prepareCombinedBatchGroups = async () => {
      let finalGroups = [];
      try {
          for (const row of combinedBatchDtls) {
            /* ===== COMBINED GROUPS FROM UI ===== */
            if (row.combinedBatches && row.combinedBatches.length > 0) {
              const batchIds = row.combinedBatches.map(b => b.batch_id);
              finalGroups.push({
                id: row.combinedBatchGroupId,
                isAtomic: batchIds.length === 1,
                batches: batchIds
              });
            }

            /* ===== ATOMIC GROUPS FROM BACKEND ===== */
            if (row.grade) {
              const res = await axios.get(
                `${BASE}/combinedBatches/getBatchesByGradeForCombinedBatchesForPrepration/${row.grade}`
              );
              const gradeBatches  = res.data || [];
              gradeBatches.forEach(b => {
                finalGroups.push({
                  id: b.group_id,
                  isAtomic: true,
                  batches: [b.batch_id]
                });
              });
            }
          }
      } catch (err) {
        toast.error("Failed to fetch batches from backend. Please refresh the page or try again later.");
      }
      /* ===== REMOVE DUPLICATES ===== */
      return finalGroups.filter(
        (group, index, self) => index === self.findIndex(g => g.id === group.id)
      );
    };

    const prepareGroupTeachers = () => {
      const result = [];
      const seen = new Set();
      subjectTeacherDtls.forEach(row => {
        const subjectId = row.selectedSubjectDtlsForsubjectTeacherDtls?.subjectId;
        const subjectObj = subjects.find(s => String(s.subject_id) === String(subjectId));
        const subjectName = subjectObj?.subject_name;
        if (!subjectName) return;
        Object.entries(row.groupTeacherMap || {}).forEach(
          ([groupId, teacherIds]) => {
            const key = `${groupId}_${subjectName}`;
            if (seen.has(key)) return;
            seen.add(key);
            const allowedTeachers = teacherIds
              .map(teacherId => {
                const teacher = row.teachersForSubjectTeachearDtls.find(t => String(t.teacher_id) === String(teacherId));
                return teacher?.teacher_name;
              }).filter(Boolean);
            result.push({
              groupId,
              subject: subjectName,
              allowedTeachers
            });
          }
        );
      });
      return result;
    };

    const prepareTeacherAvailability = async () => {
          /* ---------- GET CAN TEACH ---------- */
    const teacherIds = teacherAvailabilityDtls.map(t => t.teacherDtls.teacherId).filter(Boolean);
    const canTeachRes = await axios.post(`${BASE}/teachers/canTeachByIds`,{ teacherIds });
    const canTeachMap = {};
    canTeachRes.data.forEach(row => {
      if (!canTeachMap[row.teacher_id]) {
        canTeachMap[row.teacher_id] = [];
      }
      canTeachMap[row.teacher_id].push(row.subject);
    });
    /* ---------- FINAL TEACHER AVAILABILITY ---------- */
    const teacherAvailability = teacherAvailabilityDtls.filter(row => row.teacherDtls?.teacherId).map(row => {
        const availableSlots = [];
        Object.entries(row.availability || {}).forEach(
          ([day, slots]) => {
            slots.forEach(slot => {
              availableSlots.push({
                day,
                slotName: slot
              });
            });
          }
        );
        return {
          teacherName: row.teacherDtls.teacherName,
          availableSlots,
          maxclasses: Number(row.maxWeeklySlots || 0),
          remuneration: Number(row.remuneration || 0),
          expertise: [
            ...new Set(
              canTeachMap[row.teacherDtls.teacherId] || []
            )
          ]
        };
      });
      return teacherAvailability;
    };
    return {
      batches: await prepareBatches(),
      groups: await prepareCombinedBatchGroups(),
      timeslots: prepareTimeslots(),
      teacherAvailability: await prepareTeacherAvailability(),
      subjectRequirements: prepareSubjectRequirements(),
      groupTeachers: prepareGroupTeachers()
    };

  } catch (error) {
    toast.error("Something went wrong ❌. please refresh the page or try again later.");
  }
};


/* -------------------------------------------------------------- UI --------------------------------------------------------- */
  return (
    <>
      {loading && (
        <div className={classes.loadingOverlay}>
          <div className={classes.loaderBox}>
            <div className={classes.spinner}></div>
            <div className={classes.loadingText}>
              Please wait...
            </div>
          </div>
        </div>
      )}
      <Toaster position="top-center" toastOptions={{className: classes.customToast,duration: 2000,}}/>
      {!showGenratedTimeTableData && (
        <div className={classes.container}>
          <div className={classes.topBar}>
            <div className={classes.topBarLeft}>
              <button className={classes.backBtn} onClick={handleBack}>
                <span className={classes.btnIcon}>←</span> Back
              </button>
            </div>

            <div className={classes.topBarCenter}>
              <h2 className={classes.title}>
                <span className={classes.titleIcon}>📅</span> Timetable Scheduler
              </h2>
            </div>
            
            <div className={classes.topBarRight}>
              <button className={classes.saveDraftBtn} onClick={handleSaveDraftClick}>
                <span className={classes.btnIcon}>💾</span> Save Draft
              </button>
            </div>
          </div>
          {step === 1 && (
            <div className={classes.card}>
              <div className={classes.slotsByDaytableTag}>Slots By Day</div>
              {visibleTables.slots && (
                <div className={classes.tableWrapper}>
                  <table className={`${classes.table} ${classes.slotsByDayTable}`}>
                    <thead>
                      <tr>
                        <th>Slot</th>
                        <th>Start Time</th>
                        <th>End Time</th>
                        {days.map((day) => (
                          <th key={`slotDaysDtlsHead_${day}`}>{day}</th>
                        ))}
                        <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {slotDaysDtls.map((slotDaysRow, index) => (
                      <tr key={`slotDaysRow_${index}`}>
                        <td>
                          <input type="text" value={slotDaysRow.slot} readOnly className={classes.select}/>
                        </td>
                        <td>
                          <input type="time" value={slotDaysRow.startTime || ""} onChange={(e) => handleTimeChange(index, "startTime", e.target.value)} className={classes.timeInput}/>
                        </td>
                        <td>
                          <input type="time" value={slotDaysRow.endTime || ""} onChange={(e) => handleTimeChange(index, "endTime", e.target.value)} className={classes.timeInput}/>
                        </td>
                        {days.map((day) => (
                          <td key={`slotDay_${index}_${day}`} className={`${classes.checkboxCell} ${classes.checkboxCellForSlotDays}`}>
                            <input type="checkbox" checked={slotDaysRow.days.includes(day)} onChange={() => handleDayChange(index, day)} className={classes.checkbox}/>
                          </td>
                        ))}
                        <td>
                          <FaMinusCircle className={classes.deleteIcon} onClick={() => removeSlotDayDtls(index)}/>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <button className={classes.addButton} onClick={addSlotDaysDtls}>+ Add Slot</button>
            <div className={classes.navButtons}>
              <div/> 
              <button className={classes.nextBtn} onClick={nextStep}>Next: Subjects Weekly Slots →</button>
            </div>
          </div>
        )}
        {step === 2 && (
          <div className={classes.card}>
              <div className={classes.subjectsWeeklySlotsTableTag}>Subjects Weekly Slots</div>
              {visibleTables.subjects && (
              <div className={classes.tableWrapper}>
                <table className={`${classes.table} ${classes.subjectsWeeklySlotsTable}`}>
                  <thead>
                    <tr>
                      <th>Subject</th>
                      <th>Weekly Slot</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {subjectWeeklySlotDtls.map((subjectWeeklySlotObj, index) => (
                      <tr key={`subjectWeeklySlotRow_${index}`}>
                        <td>
                          <select value={
                            subjectWeeklySlotObj.subjectDtlsForWeeklySlot && subjectWeeklySlotObj.subjectDtlsForWeeklySlot.subjectId
                              ? `${subjectWeeklySlotObj.subjectDtlsForWeeklySlot.subjectId}_${subjectWeeklySlotObj.subjectDtlsForWeeklySlot.grade}`
                              : ""
                          }
                            onChange={(e) => handleSubjectChange(index, e.target.value)} className={`${classes.select} ${classes.selectForSubjectWeeklySlots}`}>
                            <option value="">--Select--</option>
                            {subjects.map((sub) => (
                              <option key={`subject_${index}_${sub.subject_id}`} value={`${sub.subject_id}_${sub.grade}`}>{sub.subject_name}</option>
                            ))}
                          </select>
                        </td>
                        <td>
                          <select value={subjectWeeklySlotObj.weeklySlot} onChange={(e) => handleWeeklySlotChange(index, e.target.value)} className={classes.select}>
                            <option value="">--Select--</option>
                            {weeklySlots.map((s) =>
                              (<option key={`weeklySlot_${index}_${s}`} value={s}>{s}</option>)
                            )}
                          </select>
                        </td>
                        <td>
                          <FaMinusCircle className={classes.deleteIcon} onClick={() => removeSubjectWeeklySlotDtls(index)} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                </div>
            )}
            <button className={classes.addButton} onClick={addSubjectWeeklySlotDtls}>+ Add Subject</button>
            <div className={classes.navButtons}>
              <button className={classes.prevBtn} onClick={prevStep}>← Previous: Slots By Day </button>
              <button className={classes.nextBtn} onClick={nextStep}>Next:Teacher Availability →</button>
            </div>
          </div>
        )}
        {step === 3 && (
          <div className={classes.card}>
            <div className={classes.teacherAvailabilityTableTag}>Teacher Availability</div>
            {visibleTables.teachers && (
            <div className={classes.tableWrapper}>
              <table className={`${classes.table} ${classes.teacherAvailabilityTable}`}>
                <thead>
                  <tr>
                    <th>Teacher</th>
                    {days.map((day) => (<th key={day}>{day}</th>))}
                    <th >Remuneration</th>
                    <th >Maximum weekly classess</th>
                    <th >Action</th>
                  </tr>
                </thead>
                <tbody>
                  {teacherAvailabilityDtls.map((teacherAvailabilityObj, rowIndex) => (
                    <tr key={`teacherAvailabilityRow_${rowIndex}`}>
                      <td className={classes.teacherAvailabilityTeacherCell}>
                        <select key={`teacherAvailabilityTeacher_${rowIndex}`} value={teacherAvailabilityObj.teacherDtls?.teacherId || ""} onChange={(e) => handleTeacherChange(rowIndex, e.target.value)} className={classes.select}>
                          <option value="">--Select--</option>
                          {teacherAvailabilityObj.teachersBySubjects.map((t) => (
                            <option key={`teacherAvailabilityTeacher_${rowIndex}_${t.teacher_id}`} value={t.teacher_id}>{t.teacher_name}</option>
                          ))}
                        </select>
                      </td>
                        {days.map((day) => {
                        const records = slotDaysDtls.filter((r) =>
                          r.days.includes(day)
                        );
                        return (
                          <td key={day} className={`${classes.checkboxCell} ${classes.checkboxCellForTeacherAvailability}`}>
                            {records.length > 0 ? (
                              records.map((record) => (
                                <label key={record.slot} className={classes.checkboxLabel}>
                                      <input type="checkbox" checked={teacherAvailabilityObj.availability?.[day]?.includes(record.slot) || false}
                                        onChange={() => handleAvailabilityChange(rowIndex,day,record.slot)} className={classes.checkbox}/>
                                      <span style={{ marginLeft: "6px" }}>
                                        {record.slot}
                                      </span>
                                    </label>
                                  ))
                                ) : (
                                  "-"
                                )}
                              </td>
                            );
                          })}
                        <td>
                          <input type="number" value={teacherAvailabilityObj.remuneration || ""}
                            onChange={(e) =>handleRemunerationChange(rowIndex, e.target.value)} className={classes.input} placeholder="Enter Amount" min="0"/>
                        </td>
                        <td>
                          <select value={teacherAvailabilityObj.maxWeeklySlots}
                            onChange={(e) => handleMaxChange(rowIndex,e.target.value)}className={classes.select}>
                            <option value="">-- Select --</option>
                            {maxWeeklySlots.map((num) => (
                              <option key={num} value={num}>
                                {num}
                              </option>
                            ))}
                          </select>
                        </td>
                        <td>
                          <FaMinusCircle className={classes.deleteIcon} onClick={() => removeTeacherAvailabilityDtls(rowIndex)} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                </div>
              )}
              <button className={classes.addButton} onClick={addTeacherAvailabilityDtls}> + Add Teacher </button>
              <div>
                <div className={classes.navButtons}>
                  <button className={classes.prevBtn} onClick={prevStep}>← Previous:Subjects Weekly Slots</button>
                  <button className={classes.nextBtn} onClick={nextStep}>Next: Combined Batches →</button>
                </div>
              </div>
            </div>
          )}
          {step === 4 && (
              <div className={classes.card}>
                <div className={classes.combinedBatchesTableTag}>Combined Batches</div>
                {visibleTables.combinedBatches && (
                  <div className={classes.tableWrapper}>
                    <table className={`${classes.table} ${classes.combinedBatchesTable}`}>
                      <thead>
                        <tr>
                          <th>Grade</th>
                          <th>Combined Batches</th>
                          <th>Action</th>
                        </tr>
                      </thead>
                      <tbody>
                        {combinedBatchDtls.map((row, rowIndex) => (
                          <tr key={rowIndex}>
                            <td>
                              <div className={classes.flexWrapper}>
                                <select value={row.grade+'_'+row.language}
                                  onChange={(e) =>handleGradeChange(rowIndex, e.target.value)}className={classes.select}>
                                  <option value="">--Select--</option>
                                  {gradesList.map((g, i) => (
                                    <option key={i} value={g.current_grade}>
                                      {g.current_grade}
                                    </option>
                                  ))}
                                </select>
                                </div>
                                
                              <div className={classes.availableBatches}>
                              {row.availableBatches.map((b) => (
                                <div key={b.batch_id} className={classes.batchItem}>
                                  <span className={classes.batchText}>{b.batch_name}</span> 
                                  <span className={classes.addIcon} onClick={() => addBatchToCombined(rowIndex, b)}>+</span>
                                </div>
                              ))}
                              </div>
                            </td>
                            <td>
                              <div className={classes.assignedBatchesContainer}>
                                {row.combinedBatches.map((b) => (
                                  <span key={b.batch_id} className={classes.batchBadge}>
                                    {b.batch_name} 
                                    <span className={classes.removeIcon} onClick={() => removeCombinedBatch(rowIndex, b.batch_id)}>×</span>
                                  </span>
                                ))}
                                </div>
                                {row.combinedBatchGroupId && (<div className={classes.groupIdText}>{row.combinedBatchGroupId}</div>)}
                            </td>
                            <td>
                              <FaMinusCircle className={classes.deleteIcon} onClick={() =>removeCombinedBatchDtls(rowIndex)}/>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <button className={classes.addButton} onClick={addNewCombinedRow}>+ Add New Row</button>
                <div className={classes.navButtons}>
                  <button className={classes.prevBtn} onClick={prevStep}>← Previous:Teacher Availability</button>
                  <button className={classes.nextBtn} onClick={nextStep}>Next: Subject-Teacher Assignment →</button>
                </div>
              </div>
            )}
            {step === 5 && (
              <div className={classes.card}>
                <div className={classes.subjectTeacherAssignmenttableTag}>Subject-Teacher Assignment</div>
                {visibleTables.subjectTeacher && (
                  <div className={classes.assignmentContainer}>
                    {subjectTeacherDtls.map((row, rowIndex) => (
                      <div key={`subjectTeacher_${rowIndex}`} className={classes.assignmentCard}>
                        <div className={classes.assignmentHeader}>
                          <select 
                            value={
                                row.selectedSubjectDtlsForsubjectTeacherDtls?.subjectId
                                  ? `${row.selectedSubjectDtlsForsubjectTeacherDtls.subjectId}_${row.selectedSubjectDtlsForsubjectTeacherDtls.grade}`
                                  : ""
                              }
                            onChange={(e) =>
                              handleSubjectChangeForsubjectTeacherDtls(rowIndex, e.target.value)
                            }
                            className={`${classes.select} ${classes.assignmentHeaderSelect}`}>
                            <option value="">--Select--</option>

                            {subjectWeeklySlotDtls.map((obj, i) => {
                              const sub = subjects.find(
                                s =>
                                  String(s.subject_id) ===
                                  String(obj.subjectDtlsForWeeklySlot?.subjectId)
                              );
                              return sub ? (
                                <option key={i} value={`${sub.subject_id}_${sub.grade}`}>{sub.subject_name}</option>
                              ) : null;
                            })}
                          </select>

                          {/* DELETE ICON */}
                          <FaMinusCircle
                            className={classes.deleteIcon}
                            onClick={() => removeAssignmentRow(rowIndex)}
                          />
                        </div>
                        <div className={classes.matrixWrapper}>
                          <table className={classes.innerTable}>
                            <thead>
                              <tr>
                                <th>Group</th>
                                {row.teachersForSubjectTeachearDtls.map((t) => (
                                  <th key={t.teacher_id}>{t.teacher_name}</th>
                                ))}
                              </tr>
                            </thead>
                            <tbody>
                              {row.finalBatchListForSubjectTeacherDtls?.map((gid, gIdx) => (
                                <tr key={gIdx}>         
                                  <td>
                                    {gid || "No Group"}
                                  </td>
                                  {row.teachersForSubjectTeachearDtls.map((t) => (
                                    <td key={t.teacher_id} className={classes.checkboxCell}>
                                      {((gid.match(/-/g) || []).length >= 2
                                          ? t.allowedBatches?.includes(gid.split("-").slice(0, 2).join("-")): t.allowedBatches?.includes(gid)) ? (
                                          <input type="checkbox" checked={row.groupTeacherMap[gid]?.includes(t.teacher_id) || false}
                                            onChange={() => toggleTeacher(rowIndex, gid, t.teacher_id)} className={classes.checkbox}/>
                                        ) : (
                                          "-"
                                        )}
                                    </td>
                                  ))}
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
                <button className={classes.addButton} onClick={addAssignmentRow}>+ Add Assignment Row</button>
                <div className={classes.navButtons}>
                  <button className={classes.prevBtn} onClick={prevStep}>← Back to Teachers</button>
                  <button className={classes.generateButton} onClick={nextStep}>🚀 Generate Timetable</button>
                  <button className={classes.clearButton}>Clear All</button>
                </div>
              </div>
            )}
        </div>
      )}

      {showReportModal && (
        <div className={classes.modalOverlay}>
          <div className={classes.terminalModalBox}>
            <div className={classes.terminalHeader}>
              <div className={classes.terminalLeft}>
                <FaTerminal className={classes.terminalIcon} />
                <span>System Engine Output Trace — Operator: {userName || "Admin"}</span>
              </div>
              <div className={classes.terminalActionGroup}>
                <button className={classes.savedSolutionsBtn} onClick={() => navigate("/admin/academics/time-table-dashboard/saved")}>Go to Saved Timetable Solutions →</button>
                <button className={classes.terminalCloseBtn} onClick={() => setShowReportModal(false)}>
                  <FaChevronLeft style={{ marginRight: '6px' }} /> Return to Wizard
                </button>
              </div>
            </div>
            <div className={classes.terminalSummaryGrid}>
              <div className={classes.summaryCard}>
                <FaCheckCircle className={classes.successCheckIcon} />
                <div>
                  <h4>Engine Stream</h4>
                  <p>Dynamic Content Loaded ({reportContent?.length || 0} chars)</p>
                </div>
              </div>
              <div className={classes.summaryCard}>
                <FaExclamationTriangle className={classes.warningCheckIcon} />
                <div>
                  <h4>Constraint Status</h4>
                  <p>Review logs below for validation details</p>
                </div>
              </div>
            </div>
            <div className={classes.terminalBodySingle}>
              <h5>Engine Execution Output & Logs</h5>
              <div className={classes.unifiedLogBlock}>
                <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', margin: 0 }}>
                  {reportContent || "[WAITING] Stream empty or fetching dynamic engine metrics..."}
                </pre>
              </div>
            </div>
          </div>
        </div>
      )}
      {showSaveModal && (
        <div className={classes.modalOverlay}>
          <div className={classes.colorModalCard}>
            <div className={classes.colorModalHeader}>
              <h3>Save Configuration Draft</h3>
              <p>Enter your username to save the current configuration.</p>
            </div>
            <div className={classes.colorInputGroup}>
              <label>Operator Username Identifier</label>
              <input type="text" value={userName} onChange={(e) => setUserName(e.target.value)} placeholder="Type your username..." autoFocus/>
            </div>
            <div className={classes.colorActionPanel}>
              <button className={classes.colorConfirmBtn} onClick={handleSaveUsername}>Confirm & Save </button>
              <button className={classes.colorCancelBtn} onClick={handleCancelUsernameModal}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
};