// import React, { useEffect, useState } from "react";
// import axios from "axios";
// import "./CoordinatorContacts.css"; 
// import { useAuth } from "../../contexts/AuthContext";
// import Logo from "../../assets/RCF-PP.jpg";
// import { Mail, Phone, ShieldCheck, Users } from "lucide-react";

// const BACKEND = process.env.REACT_APP_BACKEND_API_URL;

// export default function CoordinatorContacts() {
//   const { user } = useAuth();
//   const [coordinators, setCoordinators] = useState([]);
//   const [loading, setLoading] = useState(true);

//   useEffect(() => {
//     if (!user?.token) return;
    
//     const fetchCoordinators = async () => {
//       try {
//         const res = await axios.get(`${BACKEND}/api/teacher/coordinators`, {
//           headers: { Authorization: `Bearer ${user.token}` }
//         });
//         setCoordinators(res.data || []);
//       } catch (err) {
//         console.error("Failed to fetch coordinators", err);
//       } finally {
//         setLoading(false);
//       }
//     };

//     fetchCoordinators();
//   }, [user?.token]);

//   return (
//     <div className="contacts-wrapper">
//       {/* HEADER */}
//       <div className="contacts-header">
//         <div style={{ display: 'flex', alignItems: 'center', gap: '15px' }}>
//           <img src={Logo} alt="Logo" style={{ width: '60px', height: '60px', borderRadius: '8px' }} />
//           <div>
//             <h1 className="title">Batch Coordinators</h1>
//             <p className="subtitle">Points of contact for your assigned batches</p>
//           </div>
//         </div>
//       </div>

//       {loading ? (
//         <div className="loader">Loading contacts...</div>
//       ) : coordinators.length === 0 ? (
//         <div className="empty-state">
//           <Users size={48} className="empty-icon" />
//           <h3>No Coordinators Found</h3>
//           <p>There are no active batch coordinators assigned to your current classes.</p>
//         </div>
//       ) : (
//         <div className="contacts-grid">
//           {coordinators.map((coord) => (
//             <div key={coord.user_id} className="contact-card">
              
//               <div className="card-top">
//                 <div className="avatar-wrap">
//                   <img 
//                     src={coord.photo_link ? `${BACKEND}/${coord.photo_link}` : "https://via.placeholder.com/80"} 
//                     alt={coord.full_name} 
//                     onError={(e) => { e.target.src = "https://via.placeholder.com/80"; }} 
//                   />
//                   <div className="active-dot"></div>
//                 </div>
//                 <div className="role-badge">
//                   <ShieldCheck size={14} className="inline mr-1" /> Coordinator
//                 </div>
//               </div>

//               <div className="card-body">
//                 <h2>{coord.full_name || "Name Not Set"}</h2>
                
//                 <div className="contact-details">
//                   <div className="detail-row">
//                     <Phone size={16} className="detail-icon" />
//                     <span>{coord.contact_no || "No Phone Provided"}</span>
//                   </div>
//                   <div className="detail-row">
//                     <Mail size={16} className="detail-icon" />
//                     <a href={`mailto:${coord.user_email}`} className="email-link">
//                       {coord.user_email || "No Email Provided"}
//                     </a>
//                   </div>
//                 </div>

//                 <div className="shared-batches">
//                   <span className="batch-label">Coordinating your batches:</span>
//                   <div className="batch-tags">
//                     {coord.shared_batches ? coord.shared_batches.split(', ').map((batch, idx) => (
//                       <span key={idx} className="batch-tag">{batch}</span>
//                     )) : <span className="no-batch">None</span>}
//                   </div>
//                 </div>

//               </div>
//             </div>
//           ))}
//         </div>
//       )}
//     </div>
//   );
// }

import React, { useEffect, useState } from "react";
import axios from "axios";
import "./CoordinatorContacts.css";
import { useAuth } from "../../contexts/AuthContext";
import Logo from "../../assets/RCF-PP.jpg";
import { Mail, Phone, ShieldCheck, Users } from "lucide-react";

const BACKEND = process.env.REACT_APP_BACKEND_API_URL;

export default function CoordinatorContacts() {
  const { user } = useAuth();

  const [coordinators, setCoordinators] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!user?.token) return;

    const fetchCoordinators = async () => {
      try {
        const res = await axios.get(
          `${BACKEND}/api/teacher/coordinators`,
          {
            headers: {
              Authorization: `Bearer ${user.token}`,
            },
          }
        );

        setCoordinators(res.data || []);
      } catch (err) {
        console.error("Failed to fetch coordinators", err);
      } finally {
        setLoading(false);
      }
    };

    fetchCoordinators();
  }, [user?.token]);

  return (
    <div className="contacts-wrapper">
      {/* Header */}
      <div className="contacts-header">
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: "15px",
          }}
        >
          <img
            src={Logo}
            alt="Logo"
            style={{
              width: "60px",
              height: "60px",
              borderRadius: "8px",
            }}
          />

          <div>
            <h1 className="title">Batch Coordinators</h1>
            <p className="subtitle">
              Points of contact for your assigned batches
            </p>
          </div>
        </div>
      </div>

      {loading ? (
        <div className="loader">Loading contacts...</div>
      ) : coordinators.length === 0 ? (
        <div className="empty-state">
          <Users size={48} className="empty-icon" />
          <h3>No Coordinators Found</h3>
          <p>
            There are no active batch coordinators assigned to your current
            classes.
          </p>
        </div>
      ) : (
        <div className="contacts-grid">
          {coordinators.map((coord) => (
            <div
              key={coord.user_id}
              className="contact-card"
            >
              <div className="card-top">
                <div className="avatar-wrap">
                  <img
                    src={`${BACKEND}/user-photos/${coord.user_id}.jpg`}
                    alt={coord.full_name}
                    onError={(e) => {
                      // Local fallback image
                      e.target.onerror = null;
                      e.target.src = "/default-profile.png";
                    }}
                  />

                  <div className="active-dot"></div>
                </div>

                <div className="role-badge">
                  <ShieldCheck
                    size={14}
                    className="inline mr-1"
                  />{" "}
                  Coordinator
                </div>
              </div>

              <div className="card-body">
                <h2>{coord.full_name || "Name Not Set"}</h2>

                <div className="contact-details">
                  <div className="detail-row">
                    <Phone
                      size={16}
                      className="detail-icon"
                    />
                    <span>
                      {coord.contact_no || "No Phone Provided"}
                    </span>
                  </div>

                  <div className="detail-row">
                    <Mail
                      size={16}
                      className="detail-icon"
                    />

                    <a
                      href={`mailto:${coord.user_email}`}
                      className="email-link"
                    >
                      {coord.user_email || "No Email Provided"}
                    </a>
                  </div>
                </div>

                <div className="shared-batches">
                  <span className="batch-label">
                    Coordinating your batches:
                  </span>

                  <div className="batch-tags">
                    {coord.shared_batches ? (
                      coord.shared_batches
                        .split(", ")
                        .map((batch, idx) => (
                          <span
                            key={idx}
                            className="batch-tag"
                          >
                            {batch}
                          </span>
                        ))
                    ) : (
                      <span className="no-batch">None</span>
                    )}
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}