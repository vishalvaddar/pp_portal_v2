import React, { useEffect, useState } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import axios from "axios";
import ProfileSection from "../../components/ProfileSection";
import ProfileField from "../../components/ProfileField";
import classes from "./ViewStudentInfo.module.css";
import {
  useFetchStates,
  useFetchEducationDistricts,
  useFetchBlocks,
  useFetchInstitutes
} from "../../hooks/useJurisData";

const ViewStudentInfo = () => {
  const { nmms_reg_number } = useParams();
  const navigate = useNavigate();
  const location = useLocation();

  const [formData, setFormData] = useState(null);
  const [secondaryData, setSecondaryData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [photoPreview, setPhotoPreview] = useState("");
  const [isImageOpen, setIsImageOpen] = useState(false);

  const [states, setStates] = useState([]);
  const [districts, setDistricts] = useState([]);
  const [blocks, setBlocks] = useState([]);
  const [institutes, setInstitutes] = useState([]);

  const [expandedSections, setExpandedSections] = useState({
    personal: true,
    special_conditions: true
  });

  const isFromBatches = location.pathname.includes("/batches/view-student-info/") || location.pathname.includes("/students/view-student-info/");
  const pageTitle = isFromBatches ? "Student Profile" : "Applicant Profile";

  useFetchStates(setStates);
  useFetchEducationDistricts(formData?.app_state, setDistricts);
  useFetchBlocks(formData?.district, setBlocks);
  useFetchInstitutes(formData?.nmms_block, setInstitutes);

  const fieldLabels = {
    nmms_year: "NMMS Year",
    nmms_reg_number: "NMMS Registration Number",
    student_name: "Student Name",
    gender: "Gender",
    dob: "Date of Birth",
    medium: "Medium of Instruction",
    aadhaar: "Aadhaar Number",
    father_name: "Father's Name",
    mother_name: "Mother's Name",
    family_income_total: "Family Income (₹)",
    home_address: "Home Address",
    contact_no1: "Primary Contact",
    contact_no2: "Secondary Contact",
    app_state: "State",
    district: "District",
    nmms_block: "NMMS Block",
    current_institute_dise_code: "Current School",
    previous_institute_dise_code: "Previous School",
    village: "Village/Town",
    father_occupation: "Father's Occupation",
    mother_occupation: "Mother's Occupation",
    father_education: "Father's Education",
    mother_education: "Mother's Education",
    household_size: "Household Size",
    own_house: "Own House",
    smart_phone_home: "Smart Phone at Home",
    internet_facility_home: "Internet Facility",
    spl_health_cond: "Special Health Condition",
    spl_health_cond_dtls: "Health Condition Details",
    spl_family_cond: "Special Family Condition",
    spl_family_cond_dtls: "Family Condition Details",
    career_goals: "Career Goals",
    subjects_of_interest: "Subjects of Interest",
    transportation_mode: "Transportation Mode",
    distance_to_school: "Distance to School (km)",
    num_two_wheelers: "Two Wheelers",
    num_four_wheelers: "Four Wheelers",
    irrigation_land: "Irrigation Land (acres)",
    neighbor_name: "Neighbor's Name",
    neighbor_phone: "Neighbor's Phone",
    favorite_teacher_name: "Favorite Teacher",
    favorite_teacher_phone: "Teacher's Contact",
    gmat_score: "GMAT Score",
    sat_score: "SAT Score",
    enr_id: "Enrollment ID",
    cohort_name: "Cohort",
    batch_name: "Batch",
    active_yn: "Academic Status"
  };

  const academicSection = {
    key: "academic_info",
    title: "Pratibha Poshak Academic Information",
    icon: "📚",
    fields: ["enr_id", "cohort_name", "batch_name", "active_yn"]
  }

  const sections = [
    {
      key: "personal",
      title: "Personal Information",
      icon: "👤",
      fields: ["student_name", "gender", "dob", "aadhaar", "medium", "father_name", "mother_name"]
    },
    ...(isFromBatches ? [academicSection] : []),
    {
      key: "special_conditions",
      title: "Special Conditions",
      icon: "⚠️",
      fields: [ "spl_health_cond_dtls", "spl_family_cond_dtls"]
    },
    {
      key: "address",
      title: "Address Information",
      icon: "🏠",
      fields: ["state_name", "district_name", "block_name", "home_address", "village"]
    },
    {
      key: "educational",
      title: "Educational Information",
      icon: "🎓",
      fields: ["current_institute_dise_code", "previous_institute_dise_code", "gmat_score", "sat_score", "subjects_of_interest", "career_goals"]
    },
    {
      key: "family",
      title: "Family Information",
      icon: "👨‍👩‍👧‍👦",
      fields: ["father_occupation", "mother_occupation", "father_education", "mother_education", "family_income_total", "household_size"]
    },
    {
      key: "contact",
      title: "Contact Information",
      icon: "📞",
      fields: ["contact_no1", "contact_no2", "neighbor_name", "neighbor_phone"]
    },
    {
      key: "transportation",
      title: "Transportation & Facilities",
      icon: "🚌",
      fields: ["transportation_mode", "distance_to_school", "smart_phone_home", "internet_facility_home"]
    },
    {
      key: "teacher",
      title: "Teacher Information",
      icon: "👨‍🏫",
      fields: ["favorite_teacher_name", "favorite_teacher_phone"]
    },
    {
      key: "property",
      title: "Property Information",
      icon: "🏘️",
      fields: ["own_house", "num_two_wheelers", "num_four_wheelers", "irrigation_land"]
    }
  ];

  const toggle = (key) => {
    setExpandedSections((prev) => ({
      ...prev,
      [key]: !prev[key]
    }));
  };

  const openImage = () => setIsImageOpen(true);
  const closeImage = () => setIsImageOpen(false);

  useEffect(() => {
    const fetchStudentDetails = async () => {
      try {
        setLoading(true);

        const res = await axios.get(
          `${process.env.REACT_APP_BACKEND_API_URL}/api/applicants/reg/${nmms_reg_number}`
        );

        const data = res.data.data;

        if (!data) {
          setError("Student not found.");
          return;
        }

        const formattedDOB = data.dob
          ? new Date(data.dob).toISOString().split("T")[0]
          : "";

        setFormData({
          applicant_id: data.applicant_id,
          nmms_year: data.nmms_year,
          nmms_reg_number: data.nmms_reg_number,
          app_state: data.app_state,
          state_name:data.state_name,
          district_name:data.district_name,
          block_name:data.block_name,
          district: data.district,
          nmms_block: data.nmms_block,
          student_name: data.student_name,
          father_name: data.father_name,
          mother_name: data.mother_name,
          gmat_score: data.gmat_score,
          sat_score: data.sat_score,
          gender: data.gender,
          aadhaar: data.aadhaar,
          dob: formattedDOB,
          home_address: data.home_address,
          family_income_total: data.family_income_total,
          contact_no1: data.contact_no1,
          contact_no2: data.contact_no2,
          current_institute_dise_code: data.current_institute_dise_code,
          previous_institute_dise_code: data.previous_institute_dise_code,
          medium: data.medium,
          enr_id: data.enr_id,
          cohort_name: data.cohort_name,
          batch_name: data.batch_name,
          active_yn: data.active_yn
        });

        setSecondaryData({
          village: data.village || "",
          father_occupation: data.father_occupation || "",
          mother_occupation: data.mother_occupation || "",
          father_education: data.father_education || "",
          mother_education: data.mother_education || "",
          household_size: data.household_size || "",
          own_house: data.own_house || "",
          smart_phone_home: data.smart_phone_home || "",
          internet_facility_home: data.internet_facility_home || "",
          spl_health_cond: data.spl_health_cond === 'Y' ? "Yes" : "No",
          spl_health_cond_dtls: data.spl_health_cond_dtls || "",
          spl_family_cond: data.spl_family_cond === 'Y' ? "Yes" : "No",
          spl_family_cond_dtls: data.spl_family_cond_dtls || "",
          career_goals: data.career_goals || "",
          subjects_of_interest: data.subjects_of_interest || "",
          transportation_mode: data.transportation_mode || "",
          distance_to_school: data.distance_to_school || "",
          num_two_wheelers: data.num_two_wheelers || "",
          num_four_wheelers: data.num_four_wheelers || "",
          irrigation_land: data.irrigation_land || "",
          neighbor_name: data.neighbor_name || "",
          neighbor_phone: data.neighbor_phone || "",
          favorite_teacher_name: data.favorite_teacher_name || "",
          favorite_teacher_phone: data.favorite_teacher_phone || ""
        });

        const cleanedPhotoPath = data.photo_link
          ? data.photo_link.replace(/^students\//, "")
          : null;

        const imageUrl = cleanedPhotoPath
          ? `${process.env.REACT_APP_BACKEND_API_URL}/students/${cleanedPhotoPath}`
          : data.gender === "Male"
          ? "/default-boy.png"
          : "/default-girl.png";

        setPhotoPreview(imageUrl);

      } catch (err) {
        setError("Failed to fetch student data.");
      } finally {
        setLoading(false);
      }
    };

    fetchStudentDetails();
  }, [nmms_reg_number]);

  if (loading) return <p>Loading...</p>;
  if (!formData) return <p>{error || "No applicant found"}</p>;

  return (
    <div className={classes.container}>
      <div className={classes.headerSection}>
        <div className={classes.centerInfo}>
          <div className={classes.imageWrapper}>
            <img
              src={photoPreview}
              alt="Applicant"
              className={classes.profileImage}
              onClick={openImage}
            />
          </div>
          <h2 className={classes.studentName}>{formData.student_name}</h2>
          <div className={classes.metaRow}>
            <p className={classes.metaLeft}><strong>Applicant ID:</strong> {formData.applicant_id}</p>
            <p className={classes.metaRight}><strong>Reg No:</strong> {formData.nmms_reg_number}</p>
          </div>
        </div>
      </div>

      <button
        className={classes.editBtn}
        onClick={() => navigate(`/admin/admissions/edit-form/${nmms_reg_number}`)}
      >
        Edit Profile
      </button>

      {sections.map((section) => (
        <ProfileSection
          key={section.key}
          section={section}
          expanded={expandedSections[section.key]}
          toggle={() => toggle(section.key)}
          renderField={(field) => {
            const value = formData?.[field] || secondaryData?.[field];
            
            // Conditional logic: Hide details if condition is not "Yes"
            if (field === "spl_health_cond_dtls" && secondaryData?.spl_health_cond !== "Yes") return null;
            if (field === "spl_family_cond_dtls" && secondaryData?.spl_family_cond !== "Yes") return null;

            return (
              <ProfileField
                key={field}
                label={fieldLabels[field]}
                value={value || "N/A"}
              />
            );
          }}
        />
      ))}

      {isImageOpen && (
        <div className={classes.imageModal} onClick={closeImage}>
          <div className={classes.imageModalContent} onClick={(e) => e.stopPropagation()}>
            <img src={photoPreview} alt="Preview" className={classes.modalImage} />
          </div>
        </div>
      )}
    </div>
  );
};

export default ViewStudentInfo;