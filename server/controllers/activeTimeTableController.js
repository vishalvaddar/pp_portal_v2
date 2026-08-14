
const PDFDocument = require("pdfkit-table");
const path = require("path");
const fs = require("fs");
const TimetableModel = require("../models/activeTimeTableModel");

exports.getDropdownData = async (req, res) => {
  try {
    const cohorts = await TimetableModel.getCohorts();
    const teachers = await TimetableModel.getTeachers();
    res.json({ cohorts, teachers });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};

exports.getBatchesByCohort = async (req, res) => {
  try {
    const data = await TimetableModel.getBatches(req.query.cohortName);
    res.json(data);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};

exports.getTimetableData = async (req, res) => {
  const { type, id, cohort } = req.query;
  try {
    let data;
    if (type === 'combined') data = await TimetableModel.getCombined(id);
    else if (type === 'teacher') data = await TimetableModel.getTeacherWise(id);
    else if (type === 'batch') data = await TimetableModel.getBatchWise(id, cohort);
    res.json(data);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};


exports.addSubject = async (req, res) => {
  try {
    // req.user.id comes from your auth middleware/token
    const subjectData = {
      ...req.body,
      created_by: req.user ? req.user.user_id : req.body.admin_id 
    };

    const newSubject = await TimetableModel.addSubject(subjectData);

    res.status(201).json({
      message: "Subject added successfully",
      data: newSubject
    });
  } catch (error) {
    if (error.code === '23505') {
      return res.status(400).json({ error: "Subject name already exists" });
    }
    res.status(500).json({ error: "Failed to add subject to database" });
  }
};
exports.getTeacherSkills = async (req, res) => {
  try {
    const { teacherId } = req.params;
    const skills = await TimetableModel.getTeacherSkills(teacherId);
    const allSubjects = await TimetableModel.getSubjects();
    res.json({ skills, allSubjects });
  } catch (err) { res.status(500).json({ error: err.message }); }
};

exports.manageTeacherSkill = async (req, res) => {
  try {
    const { action, teacherId, subjectId, medium } = req.body;
    
    if (action === 'add') {
      // The model no longer needs the adminId/created_by for this specific table
      await TimetableModel.addTeacherSkill(teacherId, subjectId, medium);
    } else {
      await TimetableModel.deleteTeacherSkill(teacherId, subjectId, medium);
    }
    
    res.json({ message: "Skill updated successfully" });
  } catch (err) {
    console.error("Skill management error:", err.message);
    res.status(500).json({ error: "Database error: " + err.message });
  }
};


exports.downloadTimetablePDF = async (req, res) => {
    try {
        const { timetableData, cohortName, viewType, filterDetails, fileName } = req.body;
        
        const doc = new PDFDocument({ 
            layout: "landscape", 
            margin: 30,
            size: "A4"
        });

        // Use the filename sent from the frontend
        const downloadName = fileName || `TIMETABLE_${cohortName}.pdf`;

        res.setHeader("Content-Type", "application/pdf");
        res.setHeader("Content-Disposition", `attachment; filename=${downloadName}`);
        doc.pipe(res);

        const PROJECT_ROOT = path.join(__dirname, "..", "..");
        const PATH_TO_RCF_LOGO = path.join(PROJECT_ROOT, "server", "public", "assets", "rcf_logo-removebg-preview.png");
        const PATH_TO_PP_LOGO = path.join(PROJECT_ROOT, "server", "public", "assets", "logo.png");

        const MARGIN = 30;
        const PAGE_WIDTH = doc.page.width;
        const LOGO_SIZE = 60; 

        // 1. Draw Logos
        if (fs.existsSync(PATH_TO_RCF_LOGO)) {
            doc.image(PATH_TO_RCF_LOGO, MARGIN, MARGIN, { fit: [LOGO_SIZE, LOGO_SIZE] });
        }
        if (fs.existsSync(PATH_TO_PP_LOGO)) {
            doc.image(PATH_TO_PP_LOGO, PAGE_WIDTH - MARGIN - LOGO_SIZE, MARGIN, { fit: [LOGO_SIZE, LOGO_SIZE] });
        }

        // 2. Header Branding
        doc.fillColor("#000000").font("Times-Bold").fontSize(18)
            .text("RAJALAKSHMI CHILDREN FOUNDATION", MARGIN, MARGIN + 10, { align: "center" });
        
        doc.fontSize(16).moveDown(0.3)
            .text(`PRATIBHA POSHAK EXAMINATION - 2025`, { align: "center" });

        doc.font("Times-Roman").fontSize(8).fillColor("#444444").moveDown(0.3)
            .text("Kayaka Kranti Towers, CTS No. 4824C/23+24, Ayodhya Nagar, Belagavi 590016", { align: "center" })
            .text("Contact No. +91 9444900755, +91 9606930208", { align: "center" });

        doc.strokeColor("#000000").lineWidth(0.5).moveTo(MARGIN, doc.y + 5).lineTo(PAGE_WIDTH - MARGIN, doc.y + 5).stroke();
        
        // 3. Dynamic Title Section
        doc.moveDown(1.5).fillColor("#000000").font("Times-Bold").fontSize(14)
            .text("TIME TABLE", { align: "center" });

        let subtitle = "";
        if (viewType === 'teacher') {
            subtitle = `TEACHER: ${filterDetails.teacherName}`;
        } else if (viewType === 'batch') {
            // FIX: Using the batchName passed from the corrected frontend
            const bName = filterDetails.batchName || "ALL BATCHES";
            subtitle = `COHORT: ${cohortName} | BATCH: ${bName}`;
        } else {
            subtitle = `COHORT: ${cohortName}`;
        }

        doc.fontSize(11).moveDown(1.0).text(subtitle.toUpperCase(), { align: "center" });

        // 4. Table Construction
        const table = {
            headers: [
                { label: "DAY", property: "day", width: 80, headerColor: "#E2E8F0" },
                { label: "TIME", property: "time", width: 140, headerColor: "#E2E8F0" },
                { label: "SUBJECT", property: "subject", width: 200, headerColor: "#E2E8F0" },
                { label: "TEACHER", property: "teacher", width: 150, headerColor: "#E2E8F0" },
                { label: "BATCH", property: "batch", width: 110, headerColor: "#E2E8F0" }
            ],
            datas: timetableData.map(item => ({
                day: (item.day_of_week || "").toUpperCase(),
                time: `${item.start_time} - ${item.end_time}`,
                subject: (item.subject_name || "").toUpperCase(),
                teacher: (item.teacher_name || "").toUpperCase(),
                batch: (item.batch_name || "").toUpperCase()
            }))
        };

        await doc.table(table, {
            prepareHeader: () => doc.font("Helvetica-Bold").fontSize(10).fillColor("#000000"),
            prepareRow: () => doc.font("Helvetica").fontSize(9).fillColor("#000000"),
            padding: 12, // Increased padding for even more vertical gap
            minRowHeight: 35 // Increased row height for more distance between data
        });

        doc.end();
    } catch (error) {
        console.error("Backend PDF Error:", error);
        res.status(500).send("Error generating PDF");
    }
};
