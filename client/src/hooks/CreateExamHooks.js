import { useState, useEffect } from "react";
import axios from "axios";
import { useSystemConfig } from "../contexts/SystemConfigContext";
const API_BASE_URL = process.env.REACT_APP_BACKEND_API_URL;

const useCreateExamHooks = () => {
  const [entries, setEntries] = useState([]);
  const [showForm, setShowForm] = useState(false);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState("");
  const [assignedApplicants] = useState([]);
  const [centres, setCentres] = useState([]);
  const [newCentreName, setNewCentreName] = useState("");
  const [examBlocks, setExamBlocks] = useState({});
   const [isCreatingCentre, setIsCreatingCentre] = useState(false);
     const [viewcentres,setviewcentres] = useState([]);
     const { appliedConfig } = useSystemConfig();
     

     const [newCentre, setNewCentre] = useState({
    pp_exam_centre_code: "",
    pp_exam_centre_name: "",
    address: "",
    village: "",
    pincode: "",
    contact_person: "",
    contact_phone: "",
    contact_email: "",
    sitting_capacity: "",
    active_yn: "Y",    
    district: "",      // numeric (string here, convert before sending)
    latitude: "",     // numeric (string here)
    longitude: "", 
  });

  const [formData, setFormData] = useState({
   division: "",
    education_district: "",
    block: "",
    cluster: "",
    app_state: 1,
  });
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
   const [divisions, setDivisions] = useState([]);
  const [educationDistricts, setEducationDistricts] = useState([]);
  const [blocks, setBlocks] = useState([]);
  const [clusters, setClusters] = useState([]);
    const [usedBlocks, setUsedBlocks] = useState([]);

     const [editingCentreId, setEditingCentreId] = useState(null);
const [editedCentreName, setEditedCentreName] = useState("");

const [editedCentre, setEditedCentre] = useState({
  pp_exam_centre_name: '',
  pp_exam_centre_code: '',
  sitting_capacity: '',
  latitude: '',
  longitude: '',
  address: '',
  village: '',
  pincode: '',
  contact_person: '',
  contact_phone: '',
  contact_email: '',
  active_yn: 'Y'
});


 const handleEditCentre = (centre) => {
    setEditingCentreId(centre.pp_exam_centre_id);
    setEditedCentre({
      pp_exam_centre_name: centre.pp_exam_centre_name || '',
      pp_exam_centre_code: centre.pp_exam_centre_code || '',
      sitting_capacity: centre.sitting_capacity || '',
      latitude: centre.latitude || '',
      longitude: centre.longitude || '',
      address: centre.address || '',
      village: centre.village || '',
      pincode: centre.pincode || '',
      contact_person: centre.contact_person || '',
      contact_phone: centre.contact_phone || '',
      contact_email: centre.contact_email || '',
      google_map_link: centre.google_map_link || '',
      active_yn: centre.active_yn || 'Y'
    });
  };


  const updateCentre = async (id) => {
    try {
      const response = await axios.put(
        `${API_BASE_URL}/api/exams/exam-centres/${id}`,
        editedCentre
      );

      setviewcentres((prev) =>
        prev.map((c) =>
          c.pp_exam_centre_id === id
            ? { ...c, ...response.data.centre }
            : c
        )
      );

      setEditingCentreId(null);
      setEditedCentre({
        pp_exam_centre_name: '',
        pp_exam_centre_code: '',
        sitting_capacity: '',
        latitude: '',
        longitude: '',
        address: '',
        village: '',
        pincode: '',
        contact_person: '',
        contact_phone: '',
        contact_email: '',
        google_map_link: '',
        active_yn: 'Y'
      });
      
      return true;
    } catch (err) {
      console.error(err);
      return false;
    }
  };
    //view exam centres
    useEffect(() =>{
      const fetchviewcentres = async() =>{
        try {
          const resp = await axios.get(`${API_BASE_URL}/api/exams/viewcentres`)
           setviewcentres(resp.data);
        } catch (error) {
          console.error("Error fetching exam centres ");
        }
      };
      fetchviewcentres();
    },[]);


  // Fetch all exams student assigned when component mounts
 useEffect(() => {
  if (!appliedConfig?.academic_year) return;

  const fetchExams = async () => {
    setIsLoading(true);
    setError(null);

    try {
      const response = await axios.get(
        `${API_BASE_URL}/api/exams/assigned`,
        {
          params: {
            year: appliedConfig.academic_year, // "2025-26"
          },
        }
      );

      if (response.data && Array.isArray(response.data)) {
        const formattedEntries = response.data.map((exam) => ({
          exam_id: exam.exam_id || exam.examId,
          exam_name: exam.exam_name || exam.examName,
          exam_date: exam.exam_date || exam.examDate,
          exam_start_time: exam.exam_start_time,
          exam_end_time: exam.exam_end_time,
          pp_exam_centre_id:
            exam.pp_exam_centre_id || exam.centreId,
          pp_exam_centre_name: exam.pp_exam_centre_name,
          district: exam.district_ids
            ? exam.district_ids[0]
            : null,
          district_name: exam.district_names
            ? exam.district_names[0]
            : null,
          blocks: exam.block_ids
            ? exam.block_ids.map((id, index) => ({
                id,
                name: exam.block_names[index],
              }))
            : [],
          frozen_yn: exam.frozen_yn || "N",
        }));

        setEntries(formattedEntries);

        // Fetch blocks
        await Promise.all(
          formattedEntries.map(async (exam) => {
            if (exam.district && exam.district !== ":district") {
              try {
                const blocksResponse = await axios.get(
                  `${API_BASE_URL}/api/exams/blocks-by-district/${exam.district}`
                );

                setExamBlocks((prev) => ({
                  ...prev,
                  [exam.district]: blocksResponse.data,
                }));
              } catch (err) {
                console.error(
                  `Error fetching blocks for ${exam.district}`,
                  err
                );
              }
            }
          })
        );
      }
    } catch (error) {
      console.error("Error fetching exams:", error);
      setError("Failed to fetch exam data");
      setMessage("❌ Failed to fetch exams");
    } finally {
      setIsLoading(false);
    }
  };

  fetchExams();
}, [appliedConfig]);


  // fe;tch the centres
  useEffect(() => {
    const fetchCentres = async () => {
      try {
        const response = await axios.get(`${API_BASE_URL}/api/exams/exam-centres`);
        setCentres(response.data);
      } catch (error) {
        console.error("Error fetching centres:", error);
      }
    };
    fetchCentres();
  }, []);


 useEffect(() => {
  if (!formData.app_state) return;

  const fetchDivisions = async () => {
    try {
      const response = await axios.get(
        `${API_BASE_URL}/api/exams/divisions-by-state/${formData.app_state}`
      );
      setDivisions(response.data);
    } catch (error) {
      console.error("Error fetching divisions:", error);
    }
  };

  fetchDivisions();
}, [formData.app_state]);

useEffect(() => {
  if (!formData.division) return;

  const fetchEducationDistricts = async () => {
    try {
      const response = await axios.get(
        `${API_BASE_URL}/api/exams/education-districts-by-division/${formData.division}`
      );
      setEducationDistricts(response.data);
    } catch (error) {
      console.error("Error fetching education districts:", error);
    }
  };

  fetchEducationDistricts();
}, [formData.division]);

  // ✅ Fetch Blocks when Education District changes
  useEffect(() => {
    if (!formData.education_district) {
      setBlocks([]);
      return;
    }
    const fetchBlocks = async () => {
      try {
        const response = await axios.get(
          `${API_BASE_URL}/api/exams/blocks-by-district/${formData.education_district}`
        );
        setBlocks(response.data);
      } catch (error) {
        console.error("Error fetching blocks:", error);
      }
    };
    fetchBlocks();
  }, [formData.education_district, setBlocks]);

  // ✅ Fetch already used blocks
useEffect(() => {
  const fetchUsedBlocks = async () => {
    try {
      const year = appliedConfig?.academic_year
        ? appliedConfig.academic_year.split("-")[0]
        : "";

      const response = await axios.get(
        `${API_BASE_URL}/api/exams/used-blocks?year=${year}`
      );

      setUsedBlocks(response.data || []);
    } catch (error) {
      console.error("Error fetching used blocks:", error);
    }
  };

  if (appliedConfig) {
    fetchUsedBlocks();
  }
}, [appliedConfig]);


  

  const handleBlockCheckboxChange = blockId => {
    setFormData(prev => {
      const newBlocks = prev.blocks.includes(blockId)
        ? prev.blocks.filter(id => id !== blockId)
        : [...prev.blocks, blockId];
      return { ...prev, blocks: newBlocks };
    });
  };

  // main submit button not assigned 
const handleSubmit = async (e) => {
  e.preventDefault();
  setLoading(true);
  setMessage("");

  if (formData.examendtime <= formData.examstarttime) {
    setMessage("❌ Exam end time must be after start time");
    setLoading(false);
    return;
  }

  try {
    const payload = {
      centreId: formData.centreId,
      examName: formData.examName,
      date: formData.examDate,
      startTime: formData.examstarttime,
      endTime: formData.examendtime,
      academic_year: appliedConfig?.academic_year, // ✅ ADD THIS
    };

    await axios.post(`${API_BASE_URL}/api/exams/create`, payload);

    // ✅ refetch with year
    const updatedExamsResponse = await axios.get(
      `${API_BASE_URL}/api/exams/notassigned`,
      {
        params: {
          year: appliedConfig?.academic_year,
        },
      }
    );

    setEntries(updatedExamsResponse.data);

    setFormData({
      centreId: "",
      examName: "",
      examDate: "",
      app_state: formData.app_state,
      examstarttime: "",
      examendtime: "",
    });

    setShowForm(false);
    setMessage("✅ Exam created successfully!");

    return { success: true };
  } catch (error) {
    console.error(error);

    if (error.response?.status === 409) {
      setMessage(`❌ ${error.response.data.message}`);
    } else {
      setMessage("❌ Failed to create exam");
    }

    return { success: false };
  } finally {
    setLoading(false);
  }
};

  // delete exam
  const deleteExam = async examId => {
    try {
      await axios.delete(`${API_BASE_URL}/api/exams/${examId}`);
      const response = await axios.get(`${API_BASE_URL}/api/exams`);
      setEntries(response.data);
      setMessage("✅ Exam deleted successfully");
    } catch (err) {
      console.error("Failed to delete exam:", err);
      setMessage("❌ Failed to delete exam");
    }
  };


    // ✅ Update any field easily
  const handleCentreChange = (key, value) => {
    setNewCentre(prev => ({ ...prev, [key]: value }));
  };

  // create centre
const createCentre = async () => {
  if (!newCentre.pp_exam_centre_name.trim()) {
    window.alert("❌ Centre name is required.");
    return;
  }

  // Optional client-side validation
  if (newCentre.pincode && !/^\d{5,12}$/.test(newCentre.pincode)) {
    window.alert("❌ Invalid pincode format (must be 5–12 digits).");
    return;
  }

  if (newCentre.contact_phone && !/^\d{7,12}$/.test(newCentre.contact_phone)) {
    window.alert("❌ Invalid phone number format (must be 7–12 digits).");
    return;
  }

  if (newCentre.contact_email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(newCentre.contact_email)) {
    window.alert("❌ Invalid email address.");
    return;
  }

  // Format data properly for backend
  const formattedCentre = {
    ...newCentre,
    sitting_capacity: newCentre.sitting_capacity
      ? parseInt(newCentre.sitting_capacity)
      : null,
    latitude: newCentre.latitude ? parseFloat(newCentre.latitude) : null,
    longitude: newCentre.longitude ? parseFloat(newCentre.longitude) : null,
  };

try {
  const res = await axios.post(
    `${API_BASE_URL}/api/exams/exam-centres`,
    formattedCentre
  );

  // ✅ Only show success if status is 200 or 201
  if (res.status === 200 || res.status === 201) {
    setCentres([...centres, res.data]);
    setMessage("✅ Centre created successfully");
    window.alert("✅ Centre created successfully!");
    return true;
  } else {
    // ❌ Unexpected success response
    setMessage("❌ Something went wrong. Please try again.");
    return false;
  }

} catch (err) {
  console.error("Create centre error:", err);

  if (err.response && err.response.data?.message) {
    setMessage(`❌ ${err.response.data.message}`);
    window.alert(`❌ ${err.response.data.message}`);
  } else {
    setMessage("❌ Failed to create centre. Please try again.");
    window.alert("❌ Failed to create centre. Please try again.");
  }

  return false;
}
};



  // delete centre
 const deleteCentre = async (id) => {
  try {
    await axios.delete(`${API_BASE_URL}/api/exams/exam-centres/${id}`);
    setCentres(centres.filter(c => c.pp_exam_centre_id !== id));

    if (formData.centreId === id) {
      setFormData({ ...formData, centreId: "" });
    }

    setMessage("✅ Centre deleted");
  } catch (err) {
    console.error("Delete centre error:", err);

    const msg =
      err.response?.data?.message || "❌ Failed to delete centre";

    setMessage(`❌ ${msg}`);
  }
};

  // handle input change
  const handleChange = e => {
    const { name, value } = e.target;
    setFormData(prev => ({ ...prev, [name]: value }));
  };

  const toggleFreezeExam = async examId => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/exams/${examId}/freeze`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
        },
      });

      if (!response.ok) {
        throw new Error("Failed to freeze exam");
      }

      setEntries(prev =>
        prev.map(entry =>
          entry.exam_id === examId ? { ...entry, frozen_yn: "Y" } : entry
        )
      );
    } catch (error) {
      console.error("Freeze error:", error);
    }
  };

 return {
  entries,
  showForm,
  setShowForm,
  blocks,
  setBlocks,
  loading,
  setLoading,
  message,
  setMessage,
  centres,
  setCentres,
  isCreatingCentre,
  setIsCreatingCentre,
  newCentre,
  handleCentreChange,
  divisions,
  educationDistricts,
  clusters,
  newCentreName,
  setNewCentreName,
  formData,
  setFormData,
  handleChange,
  handleBlockCheckboxChange,
  handleSubmit,
  createCentre,
  deleteCentre,
  deleteExam,
  examBlocks,
  usedBlocks,
  isLoading,
  error,
  toggleFreezeExam,
  viewcentres,
  editingCentreId,
  editedCentreName,
  setEditedCentreName,
  setEditingCentreId,
  editedCentre,
  setEditedCentre,
  handleEditCentre,
  updateCentre
};
};

export default useCreateExamHooks;
