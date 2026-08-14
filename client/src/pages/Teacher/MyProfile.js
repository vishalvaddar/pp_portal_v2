// import React, { useEffect, useState } from "react";
// import axios from "axios";
// import "./MyProfile.css"; 

// import { 
//   Mail, Award, BookOpen, Layout
// } from "lucide-react";
// import { useAuth } from "../../contexts/AuthContext";

// const BACKEND = process.env.REACT_APP_BACKEND_API_URL;

// export default function TeacherProfile() {
//   const { user } = useAuth();
//   const [profile, setProfile] = useState(null);

//   const axiosConfig = { headers: { Authorization: `Bearer ${user?.token}` } };

//   useEffect(() => {
//     if (!user?.token) return;
//     axios.get(`${BACKEND}/api/teacher/profile`, axiosConfig)
//       .then(res => setProfile(res.data))
//       .catch(err => console.error(err));
//   }, [user?.token]);

//   if (!profile) return <div className="loader">Loading...</div>;

//   return (
//     <div className="profile-wrapper">
//       {/* HEADER */}
//       <div className="profile-header-flat">
//         <div className="profile-identity">
//           <div className="avatar-container">
//             <img 
//               src={profile.photo_link ? `${BACKEND}/${profile.photo_link}` : "https://via.placeholder.com/100"} 
//               alt="Profile" 
//               onError={(e) => { e.target.src = "https://via.placeholder.com/100"; }} // Fallback if image missing
//             />
//             <div className="status-indicator is-active" />
//           </div>
//           <div className="identity-text">
//             <h1>{profile.teacher_name}</h1>
//             <p>{profile.qualification || "Qualification Not Specified"}</p>
//           </div>
//         </div>
//         <div className="header-badges">
//           <div className="stat-pill">
//             <span className="label">EXPERIENCE</span>
//             <span className="value">{profile.experience_yrs != null ? `${profile.experience_yrs} Years` : "N/A"}</span>
//           </div>
//         </div>
//       </div>

//       <div className="profile-content-grid">
//         {/* LEFT COLUMN */}
//         <div className="main-info-column">
//           <div className="info-card">
//             <div className="card-header">
//               <Mail size={18} color="#3182ce" />
//               <h3>Contact & Account Information</h3>
//             </div>
//             <div className="grid-2-col">
//               <div className="data-item">
//                 <label>System Username / Email</label>
//                 <p>{profile.username}</p>
//               </div>
//               <div className="data-item">
//                 <label>Contact Number</label>
//                 <p>{profile.contact_no || "Not Provided"}</p>
//               </div>
//               <div className="data-item">
//                 <label>Date of Joining (DOJ)</label>
//                 <p>{profile.doj ? new Date(profile.doj).toLocaleDateString('en-IN') : "Not Provided"}</p>
//               </div>
//             </div>
//           </div>
//         </div>

//         {/* RIGHT COLUMN */}
//         <div className="side-info-column">
//           <div className="info-card">
//             <div className="card-header">
//               <BookOpen size={18} color="#805ad5" />
//               <h3>Teaching Portfolio</h3>
//             </div>

//             <div className="portfolio-section">
//               {/* Subjects Group */}
//               <div className="portfolio-group">
//                 <span className="key"><Award size={14} className="inline mr-1"/> Subjects Taught</span>
//                 <div className="badge-container">
//                   {profile.subjects_taught ? (
//                     profile.subjects_taught.split(', ').map((subject, idx) => (
//                       <span key={idx} className="portfolio-badge subject-badge">
//                         {subject}
//                       </span>
//                     ))
//                   ) : (
//                     <span className="no-data">No Subjects Assigned</span>
//                   )}
//                 </div>
//               </div>

//               {/* Classrooms Group */}
//               <div className="portfolio-group" style={{ marginTop: '24px' }}>
//                 <span className="key"><Layout size={14} className="inline mr-1"/> Assigned Classrooms</span>
//                 <div className="badge-container">
//                   {profile.assigned_classrooms ? (
//                     profile.assigned_classrooms.split(', ').map((room, idx) => (
//                       <span key={idx} className="portfolio-badge room-badge">
//                         {room}
//                       </span>
//                     ))
//                   ) : (
//                     <span className="no-data">No Classrooms Assigned</span>
//                   )}
//                 </div>
//               </div>
//             </div>

//           </div>
//         </div>
//       </div>
//     </div>
//   );
// }


import React, { useEffect, useState } from "react";
import axios from "axios";
import "./MyProfile.css";

import {
  Mail,
  Award,
  BookOpen,
  Layout
} from "lucide-react";
import { useAuth } from "../../contexts/AuthContext";

const BACKEND = process.env.REACT_APP_BACKEND_API_URL;

export default function TeacherProfile() {
  const { user } = useAuth();
  const [profile, setProfile] = useState(null);

  useEffect(() => {
    if (!user?.token) return;

    axios
      .get(`${BACKEND}/api/teacher/profile`, {
        headers: {
          Authorization: `Bearer ${user.token}`,
        },
      })
      .then((res) => {
        console.log("Teacher Profile:", res.data);
        setProfile(res.data);
      })
      .catch((err) => console.error(err));
  }, [user?.token]);

  if (!profile) {
    return <div className="loader">Loading...</div>;
  }

  return (
    <div className="profile-wrapper">
      {/* HEADER */}
      <div className="profile-header-flat">
        <div className="profile-identity">
          <div className="avatar-container">
            <img
              src={`${BACKEND}/${profile.photo_link}`}
              alt="Profile"
              onError={(e) => {
                e.target.onerror = null;
                e.target.src = "/default-profile.png";
              }}
            />
            <div className="status-indicator is-active" />
          </div>

          <div className="identity-text">
            <h1>{profile.teacher_name}</h1>
            <p>
              {profile.qualification || "Qualification Not Specified"}
            </p>
          </div>
        </div>

        <div className="header-badges">
          <div className="stat-pill">
            <span className="label">EXPERIENCE</span>
            <span className="value">
              {profile.experience_yrs != null
                ? `${profile.experience_yrs} Years`
                : "N/A"}
            </span>
          </div>
        </div>
      </div>

      <div className="profile-content-grid">
        {/* LEFT COLUMN */}
        <div className="main-info-column">
          <div className="info-card">
            <div className="card-header">
              <Mail size={18} color="#3182ce" />
              <h3>Contact & Account Information</h3>
            </div>

            <div className="grid-2-col">
              <div className="data-item">
                <label>System Username / Email</label>
                <p>{profile.username}</p>
              </div>

              <div className="data-item">
                <label>Contact Number</label>
                <p>{profile.contact_no || "Not Provided"}</p>
              </div>

              <div className="data-item">
                <label>Date of Joining (DOJ)</label>
                <p>
                  {profile.doj
                    ? new Date(profile.doj).toLocaleDateString("en-IN")
                    : "Not Provided"}
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* RIGHT COLUMN */}
        <div className="side-info-column">
          <div className="info-card">
            <div className="card-header">
              <BookOpen size={18} color="#805ad5" />
              <h3>Teaching Portfolio</h3>
            </div>

            <div className="portfolio-section">
              {/* Subjects */}
              <div className="portfolio-group">
                <span className="key">
                  <Award size={14} className="inline mr-1" />
                  {" "}Subjects Taught
                </span>

                <div className="badge-container">
                  {profile.subjects_taught ? (
                    profile.subjects_taught
                      .split(", ")
                      .map((subject, idx) => (
                        <span
                          key={idx}
                          className="portfolio-badge subject-badge"
                        >
                          {subject}
                        </span>
                      ))
                  ) : (
                    <span className="no-data">
                      No Subjects Assigned
                    </span>
                  )}
                </div>
              </div>

              {/* Classrooms */}
              <div
                className="portfolio-group"
                style={{ marginTop: "24px" }}
              >
                <span className="key">
                  <Layout size={14} className="inline mr-1" />
                  {" "}Assigned Classrooms
                </span>

                <div className="badge-container">
                  {profile.assigned_classrooms ? (
                    profile.assigned_classrooms
                      .split(", ")
                      .map((room, idx) => (
                        <span
                          key={idx}
                          className="portfolio-badge room-badge"
                        >
                          {room}
                        </span>
                      ))
                  ) : (
                    <span className="no-data">
                      No Classrooms Assigned
                    </span>
                  )}
                </div>
              </div>
            </div>

          </div>
        </div>
      </div>
    </div>
  );
}