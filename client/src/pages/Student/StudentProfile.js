
// import React, { useEffect, useState } from "react";
// import axios from "axios";
// import {
//   User,
//   Phone,
//   BookOpen,
//   Briefcase,
//   GraduationCap,
//   History,
//   Calendar,
//   Lock
// } from "lucide-react";
// import { useAuth } from "../../contexts/AuthContext";
// import Logo from "../../assets/RCF-PP.jpg";

// const BACKEND = process.env.REACT_APP_BACKEND_API_URL;

// export default function MyProfile() {
//   const { user } = useAuth();
//   const token = user?.token;

//   const [student, setStudent] = useState(null);
//   const [inactiveHistory, setInactiveHistory] = useState([]);
//   const [fullPhoto, setFullPhoto] = useState(null);

//   const axiosConfig = {
//     headers: { Authorization: `Bearer ${token}` }
//   };

//   useEffect(() => {
//     if (!token) return;

//     const fetchProfile = async () => {
//       try {
//         const { data } = await axios.get(
//           `${BACKEND}/api/student/profile`,
//           axiosConfig
//         );

//         setStudent(data);

//         if (data?.student_id) {
//           const hist = await axios.get(
//             `${BACKEND}/api/student/${data.student_id}/inactive-history`,
//             axiosConfig
//           );

//           setInactiveHistory(hist.data || []);
//         }
//       } catch (err) {
//         console.error("Profile load error:", err);
//       }
//     };

//     fetchProfile();
//   }, [token]);

//   if (!student) {
//     return (
//       <div className="container">
//         <p>Loading Profile...</p>
//       </div>
//     );
//   }

//   return (
//     <div className="container">

//       {/* PAGE HEADER */}
//       <div className="page-header">
//         <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
//           <img src={Logo} alt="Logo" style={{ width: 50 }} />

//           <div>
//             <h1 className="title">My Profile</h1>
//             <p className="subtitle">Student Profile Information</p>
//           </div>
//         </div>
//       </div>

//       {/* PROFILE PHOTO */}
//       <div style={{ textAlign: "center", marginBottom: 25 }}>

//         <div
//           className="student-photo-circle"
//           onClick={() =>
//             student.photo_link &&
//             setFullPhoto(`${BACKEND}/${student.photo_link}`)
//           }
//         >
//           <img
//             src={
//               student.photo_link
//                 ? `${BACKEND}/${student.photo_link}`
//                 : "https://via.placeholder.com/120"
//             }
//             alt="Student"
//           />
//         </div>

//         <h2 style={{ marginTop: 10 }}>{student.student_name}</h2>

//         <span
//           className={`status-badge ${
//             student.active_yn === "ACTIVE" ? "active" : "inactive"
//           }`}
//         >
//           {student.active_yn}
//         </span>

//       </div>

//       {/* PERSONAL */}
//       <div className="profile-section">
//         <h4><User size={16}/> Personal Details</h4>

//         <div className="profile-grid">
//           <div>
//             <strong>Gender:</strong>{" "}
//             {student.gender === "M"
//               ? "Male"
//               : student.gender === "F"
//               ? "Female"
//               : "-"}
//           </div>

//           <div>
//             <strong>Enrollment ID:</strong> {student.enr_id}
//           </div>
//         </div>
//       </div>

//       {/* ACADEMIC */}
//       <div className="profile-section">
//         <h4><BookOpen size={16}/> Academic Details</h4>

//         <div className="profile-grid">
//           <div>
//             <strong>Cohort:</strong> {student.cohort_name}
//           </div>

//           <div>
//             <strong>Batch:</strong> {student.batch_name}
//           </div>

//           <div>
//             <strong>Sponsor:</strong> {student.sponsor || "-"}
//           </div>
//         </div>
//       </div>

//       {/* CONTACT */}
//       <div className="profile-section">
//         <h4><Phone size={16}/> Contact Information</h4>

//         <div className="profile-grid">

//           <div>
//             <strong>Student Contact:</strong> {student.contact_no1}
//           </div>

//           <div>
//             <strong>Student Email:</strong> {student.student_email}
//           </div>

//           <div>
//             <Lock size={14}/> <strong>Email Password:</strong>{" "}
//             {student.student_email_password}
//           </div>

//           <div>
//             <strong>Parent Contact:</strong> {student.contact_no2}
//           </div>

//           <div>
//             <strong>Parent Email:</strong> {student.parent_email}
//           </div>

//           <div>
//             <strong>Home Address:</strong> {student.home_address}
//           </div>

//         </div>
//       </div>

//       {/* FAMILY */}
//       <div className="profile-section">
//         <h4><Briefcase size={16}/> Family Details</h4>

//         <div className="profile-grid">

//           <div>
//             <strong>Father:</strong> {student.father_name}
//           </div>

//           <div>
//             <strong>Father Occupation:</strong> {student.father_occupation}
//           </div>

//           <div>
//             <strong>Mother:</strong> {student.mother_name}
//           </div>

//           <div>
//             <strong>Mother Occupation:</strong> {student.mother_occupation}
//           </div>

//         </div>
//       </div>

//       {/* INSTITUTE */}
//       <div className="profile-section">
//         <h4><GraduationCap size={16}/> Institute & Teacher</h4>

//         <div className="profile-grid">

//           <div>
//             <strong>Current Institute:</strong>{" "}
//             {student.current_institute} ({student.current_institute_dise_code})
//           </div>

//           <div>
//             <strong>Previous Institute:</strong>{" "}
//             {student.previous_institute} ({student.previous_institute_dise_code})
//           </div>

//           <div>
//             <strong>Teacher Name:</strong> {student.teacher_name}
//           </div>

//           <div>
//             <strong>Teacher Mobile:</strong> {student.teacher_mobile_number}
//           </div>

//         </div>
//       </div>

//       {/* OTHER */}
//       <div className="profile-section">
//         <h4>Other Details</h4>

//         <div className="profile-grid">

//           <div>
//             <strong>SIM Name:</strong> {student.sim_name}
//           </div>

//           <div>
//             <strong>Recharge Status:</strong> {student.recharge_status}
//           </div>

//         </div>
//       </div>

//       {/* INACTIVE HISTORY */}
//       <div className="profile-section">
//         <h4><History size={16}/> Inactive History</h4>

//         {inactiveHistory.length > 0 ? (
//           inactiveHistory.map((h, i) => (
//             <div key={i} style={{ marginBottom: 10 }}>
//               <div style={{ fontSize: 12 }}>
//                 <Calendar size={12}/>{" "}
//                 {new Date(h.inactive_date).toLocaleDateString()}
//               </div>

//               <div>
//                 <strong>Reason:</strong> {h.inactive_reason}
//               </div>
//             </div>
//           ))
//         ) : (
//           <p>No history available</p>
//         )}

//       </div>

//       {/* FULL PHOTO MODAL */}
//       {fullPhoto && (
//         <div className="modal" onClick={() => setFullPhoto(null)}>
//           <div className="modal-content small">
//             <img src={fullPhoto} alt="Full"/>
//           </div>
//         </div>
//       )}

//     </div>
//   );
// }


import React, { useEffect, useState } from "react";
import axios from "axios";
import "./MyProfile.css"; 

import { 
  User, Mail, Phone, Award, ShieldCheck, 
  GraduationCap, Eye, EyeOff, Lock
} from "lucide-react";
import { useAuth } from "../../contexts/AuthContext";
import Logo from "../../assets/RCF-PP.jpg";

const BACKEND = process.env.REACT_APP_BACKEND_API_URL;

export default function MyProfile() {
  const { user } = useAuth();
  const [student, setStudent] = useState(null);
  const [showPwd, setShowPwd] = useState(false);

  const axiosConfig = { headers: { Authorization: `Bearer ${user?.token}` } };

  useEffect(() => {
    if (!user?.token) return;
    axios.get(`${BACKEND}/api/student/profile`, axiosConfig)
      .then(res => setStudent(res.data))
      .catch(err => console.error(err));
  }, [user?.token]);

  if (!student) return <div className="loader">Loading...</div>;

  return (
    <div className="profile-wrapper">
      {/* HEADER */}
      <div className="profile-header-flat">
        <div className="profile-identity">
          <div className="avatar-container">
            <img 
              src={student.photo_link ? `${BACKEND}/${student.photo_link}` : "https://via.placeholder.com/100"} 
              alt="Profile" 
            />
            <div className={`status-indicator ${student.active_yn === "ACTIVE" ? "is-active" : "is-inactive"}`} />
          </div>
          <div className="identity-text">
            <h1>{student.student_name}</h1>
            <p>{student.cohort_name} • {student.batch_name}</p>
          </div>
        </div>
        <div className="header-badges">
          <div className="stat-pill">
            <span className="label">ENROLLMENT</span>
            <span className="value">{student.enr_id}</span>
          </div>
        </div>
      </div>

      <div className="profile-content-grid">
        {/* LEFT COLUMN */}
        <div className="main-info-column">
          <div className="info-card">
            <div className="card-header">
              <Mail size={18} color="#3182ce" />
              <h3>Contact & Communication</h3>
            </div>
            <div className="grid-2-col">
              <div className="data-item">
                <label>Email Address</label>
                <p>{student.student_email}</p>
              </div>
              <div className="data-item">
                <label>Email Password</label>
                <div className="password-reveal">
                  <span>{showPwd ? student.student_email_password : "••••••••"}</span>
                  <button onClick={() => setShowPwd(!showPwd)}>
                    {showPwd ? <EyeOff size={14}/> : <Eye size={14}/>}
                  </button>
                </div>
              </div>
              <div className="data-item">
                <label>Phone Number</label>
                <p>{student.contact_no1}</p>
              </div>
              <div className="data-item">
                <label>Home Address</label>
                <p>{student.home_address}</p>
              </div>
            </div>
          </div>

          <div className="info-card">
            <div className="card-header">
              <GraduationCap size={18} color="#805ad5" />
              <h3>Academic Institution</h3>
            </div>
            <div className="institute-highlight">
              <h4>{student.current_institute}</h4>
              <p>DISE CODE: {student.current_institute_dise_code}</p>
            </div>
            <div className="teacher-strip">
              <div className="teacher-info"><User size={14} /> <strong>{student.teacher_name}</strong></div>
              <div className="teacher-info"><Phone size={14} /> <strong>{student.teacher_mobile_number}</strong></div>
            </div>
          </div>
        </div>

        {/* RIGHT COLUMN */}
        <div className="side-info-column">
          <div className="info-card">
            <div className="card-header">
              <ShieldCheck size={18} color="#38a169" />
              <h3>Family Details</h3>
            </div>
            <div className="simple-list">
              <div className="list-item"><span className="key">Father</span><span className="val">{student.father_name}</span></div>
              <div className="list-item"><span className="key">Mother</span><span className="val">{student.mother_name}</span></div>
              <div className="list-item"><span className="key">Sponsor</span><span className="val">{student.sponsor || "None"}</span></div>
            </div>
          </div>

          <div className="info-card">
            <div className="card-header">
              <Award size={18} color="#dd6b20" />
              <h3>Device Connectivity</h3>
            </div>
            <div className="simple-list">
              <div className="list-item"><span className="key">SIM</span><span className="val">{student.sim_name}</span></div>
              <div className="list-item"><span className="key">Recharge</span><span className="val">{student.recharge_status}</span></div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}