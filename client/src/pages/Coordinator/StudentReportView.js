import React from "react";
import { AlertTriangle, ShieldCheck } from "lucide-react";

export default function StudentReportView({
  reportData,
  fromDate,
  toDate,
  hideHeader
}) {
  const students = reportData?.students || [];
  const cohortName = reportData?.cohort_name || "Cohort";
  const batchName = reportData?.batch_name || "Batch";
  const subjects = reportData?.subjects || {};

const formatTeacherName = (name) => {
  if (!name) return "-";

  let cleaned = name.trim().replace(/\s+/g, " ");

  // 🎯 Handle non-person entries (like institute)
  if (!/^(MR|MRS|MS|DR)\b/i.test(cleaned)) {
    return cleaned
      .toLowerCase()
      .replace(/\b\w/g, (c) => c.toUpperCase()); // Title Case
  }

  const parts = cleaned.split(" ");

  let rawTitle = parts[0].toUpperCase();
  let titleMap = {
    MR: "Mr.",
    MRS: "Mrs.",
    MS: "Ms.",
    DR: "Dr."
  };

  let title = titleMap[rawTitle] || "Mr.";

  // Remove title
  let nameParts = parts.slice(1);

  if (nameParts.length === 0) return title;

  // 🎯 Convert full name properly
  let formattedName = nameParts
    .map((part) => {
      if (part.length === 1) return part.toUpperCase(); // initials like M, S
      return part.charAt(0).toUpperCase() + part.slice(1).toLowerCase();
    })
    .join(" ");

  return `${title} ${formattedName}`;
};

  // 🔥 Flatten columns (NO MERGING)
  const columns = [];
  Object.entries(subjects).forEach(([subject, teachers]) => {
    teachers.forEach((t) => {
      columns.push({
        subject,
        teacher: t.teacher_name,
        conducted: t.conducted
      });
    });
  });

  const LOW_ATTENDANCE_THRESHOLD = 75.0;

  return (
    <div className="space-y-6">

      {!hideHeader && (
        <div className="flex justify-between items-center bg-gray-50 border rounded-lg p-4 shadow-sm">
          <div>From: <b>{fromDate}</b></div>
          <div className="text-lg font-bold text-center">
            {cohortName} - {batchName} - ATTENDANCE REPORT
          </div>
          <div>To: <b>{toDate}</b></div>
        </div>
      )}

      {students.length === 0 ? (
        <p className="text-center text-gray-500 py-8">
          No student attendance data available.
        </p>
      ) : (
        <div className="overflow-x-auto border rounded-lg shadow-sm bg-white">
          <table className="min-w-full divide-y divide-gray-200 text-sm">

            <thead className="bg-gray-100">

              {/* 🔥 HEADER ROW 1 (SEPARATE SUBJECTS) */}
              <tr>
                <th rowSpan="2" className="px-4 py-2">Sl No</th>
                <th rowSpan="2" className="px-4 py-2">Student</th>

                {columns.map((col, idx) => (
                  <th key={idx} className="px-4 py-2 text-center">
                    {col.subject} ({col.conducted})
                  </th>
                ))}

                <th rowSpan="2" className="px-4 py-2 text-center">Attended</th>
                <th rowSpan="2" className="px-4 py-2 text-center">%</th>
                <th rowSpan="2" className="px-4 py-2 text-center">Status</th>
              </tr>

              {/* 🔥 HEADER ROW 2 (TEACHERS) */}
              <tr>
                {columns.map((col, idx) => (
                  <th key={idx} className="px-4 py-2 text-center text-xs text-gray-600">
                    {formatTeacherName(col.teacher)}
                  </th>
                ))}
              </tr>

            </thead>

            <tbody>
              {students.map((s, idx) => {

                let totalAttended = 0;
                let totalConducted = 0;

                return (
                  <tr key={s.id}>
                    <td className="px-4 py-2">{idx + 1}</td>
                    <td className="px-4 py-2 font-medium">{s.name}</td>

                    {columns.map((col, i) => {
                      const val =
                        s.subjects?.[col.subject]?.[col.teacher] ?? 0;

                      totalAttended += val;
                      totalConducted += col.conducted;

                      return (
                        <td key={i} className="px-4 py-2 text-center">
                          {val}
                        </td>
                      );
                    })}

                    {/* 🔥 TOTAL + % */}
                    <td className="px-4 py-2 text-center">{totalAttended}</td>

                    <td className="px-4 py-2 text-center font-semibold">
                      {totalConducted > 0
                        ? ((totalAttended / totalConducted) * 100).toFixed(2)
                        : 0}%
                    </td>

                    <td className="px-4 py-2 text-center">
                      {(totalConducted > 0 &&
                        (totalAttended / totalConducted) * 100 < 75) ? (
                        <span className="text-red-600">Low</span>
                      ) : (
                        <span className="text-green-600">OK</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>

          </table>
        </div>
      )}
    </div>
  );
}