package com.rcf.imas.modules.evaluation.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** POI port of evaluationController.js downloadStudentExcel: fixed 34-column layout, 4 fill-color groups. */
@Component
public class StudentExcelSupport {

    private record Col(String header, String key, String fillArgb) {}

    private static final List<Col> COLUMNS = List.of(
        new Col("Applicant ID", "applicant_id", "FFFFCC"),
        new Col("Student Name", "student_name", "FFFFCC"),
        new Col("Father Name", "father_name", "FFFFCC"),
        new Col("Mother Name", "mother_name", "FFFFCC"),
        new Col("Village", "village", "FFFFCC"),
        new Col("Gender(M,F)", "gender", "FFFFCC"),
        new Col("Aadhaar", "aadhaar", "FFFFCC"),
        new Col("Date of Birth", "dob", "FFFFCC"),
        new Col("Medium", "medium", "FFFFCC"),
        new Col("Home Address", "home_address", "FFFFCC"),
        new Col("Family Income", "family_income_total", "FFFFCC"),
        new Col("Father Occupation", "father_occupation", "CCFFCC"),
        new Col("Mother Occupation", "mother_occupation", "CCFFCC"),
        new Col("Father Education", "father_education", "CCFFCC"),
        new Col("Mother Education", "mother_education", "CCFFCC"),
        new Col("Household Size", "household_size", "CCFFCC"),
        new Col("Own House(Y,N)", "own_house", "CCFFCC"),
        new Col("Smart Phone at Home(Y,N)", "smart_phone_home", "CCFFCC"),
        new Col("Internet Facility at Home(Y,N)", "internet_facility_home", "CCFFCC"),
        new Col("Career Goals", "career_goals", "CCFFCC"),
        new Col("Subjects of Interest", "subjects_of_interest", "CCFFCC"),
        new Col("Transportation Mode", "transportation_mode", "CCFFCC"),
        new Col("Distance to School", "distance_to_school", "CCFFCC"),
        new Col("Number of Two Wheelers", "num_two_wheelers", "CCFFCC"),
        new Col("Number of Four Wheelers", "num_four_wheelers", "CCFFCC"),
        new Col("Irrigation Land", "irrigation_land", "CCFFCC"),
        new Col("Neighbor Name", "neighbor_name", "CCFFCC"),
        new Col("Neighbor Phone", "neighbor_phone", "CCFFCC"),
        new Col("Favorite Teacher Name", "favorite_teacher_name", "CCFFCC"),
        new Col("Favorite Teacher Phone", "favorite_teacher_phone", "CCFFCC"),
        new Col("Exam Appeared Y/N", "pp_exam_appeared_yn", "FFCCCC"),
        new Col("Exam Score", "pp_exam_score", "CCFFFF"),
        new Col("Exam cleared Y/N", "pp_exam_cleared", "CCFFFF"),
        new Col("Interview Required", "interview_required_yn", "CCFFFF")
    );

    private static final int DOB_COL_1BASED = 8;
    private static final int FAMILY_INCOME_COL_1BASED = 11;
    private static final java.util.Set<Integer> NUMFMT_0_00 = java.util.Set.of(32, 23, 24, 25, 26);
    private static final java.util.Set<Integer> YN_CENTERED = java.util.Set.of(6, 17, 18, 19, 31, 33, 34);

    public byte[] build(List<Map<String, Object>> students) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Students");
            DataFormat fmt = wb.createDataFormat();

            Row header = sheet.createRow(0);
            for (int c = 0; c < COLUMNS.size(); c++) {
                Col col = COLUMNS.get(c);
                CellStyle style = wb.createCellStyle();
                Font f = wb.createFont();
                f.setFontName("Calibri");
                f.setFontHeightInPoints((short) 11);
                f.setBold(true);
                style.setFont(f);
                style.setFillForegroundColor(new XSSFColor(javaAwtColorFromRgb(col.fillArgb()), null));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                style.setAlignment(HorizontalAlignment.CENTER);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                style.setWrapText(true);
                Cell cell = header.createCell(c);
                cell.setCellValue(col.header());
                cell.setCellStyle(style);
            }

            CellStyle dobStyle = dataStyle(wb, fmt, "dd-mm-yyyy", HorizontalAlignment.CENTER);
            CellStyle incomeStyle = dataStyle(wb, fmt, "₹#,##0.00", HorizontalAlignment.RIGHT);
            CellStyle numStyle = dataStyle(wb, fmt, "0.00", HorizontalAlignment.RIGHT);
            CellStyle centeredStyle = dataStyle(wb, fmt, null, HorizontalAlignment.CENTER);
            CellStyle plainStyle = dataStyle(wb, fmt, null, null);

            for (int r = 0; r < students.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> s = students.get(r);
                for (int c = 0; c < COLUMNS.size(); c++) {
                    int oneBased = c + 1;
                    Object v = s.get(COLUMNS.get(c).key());
                    Cell cell = row.createCell(c);
                    cell.setCellValue(v == null ? "" : String.valueOf(v));
                    if (oneBased == DOB_COL_1BASED) cell.setCellStyle(dobStyle);
                    else if (oneBased == FAMILY_INCOME_COL_1BASED) cell.setCellStyle(incomeStyle);
                    else if (NUMFMT_0_00.contains(oneBased)) cell.setCellStyle(numStyle);
                    else if (YN_CENTERED.contains(oneBased)) cell.setCellStyle(centeredStyle);
                    else cell.setCellStyle(plainStyle);
                }
            }

            // Best-effort cosmetics (Firm Decision 7): Gender + Y/N dropdown validations, DOB note.
            addListValidation(sheet, 5, "M,F", students.size());          // column 6 (0-based idx 5)
            for (int oneBased : List.of(17, 18, 19, 31, 33, 34)) {
                addListValidation(sheet, oneBased - 1, "Y,N", students.size());
            }
            addDobNote(wb, sheet, DOB_COL_1BASED - 1, students.size());

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CellStyle dataStyle(Workbook wb, DataFormat fmt, String numFmt, HorizontalAlignment align) {
        CellStyle style = wb.createCellStyle();
        Font f = wb.createFont();
        f.setFontName("Calibri");
        f.setFontHeightInPoints((short) 10);
        style.setFont(f);
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        if (numFmt != null) style.setDataFormat(fmt.getFormat(numFmt));
        if (align != null) style.setAlignment(align);
        return style;
    }

    private static java.awt.Color javaAwtColorFromRgb(String rgbHex) {
        int r = Integer.parseInt(rgbHex.substring(0, 2), 16);
        int g = Integer.parseInt(rgbHex.substring(2, 4), 16);
        int b = Integer.parseInt(rgbHex.substring(4, 6), 16);
        return new java.awt.Color(r, g, b);
    }

    private static void addListValidation(Sheet sheet, int col0, String csv, int rowCount) {
        if (rowCount == 0) return;
        DataValidationHelper helper = sheet.getDataValidationHelper();
        CellRangeAddressList range = new CellRangeAddressList(1, rowCount, col0, col0);
        DataValidationConstraint constraint = helper.createExplicitListConstraint(csv.split(","));
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setEmptyCellAllowed(true);
        sheet.addValidationData(validation);
    }

    private static void addDobNote(Workbook wb, Sheet sheet, int col0, int rowCount) {
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        CreationHelper factory = wb.getCreationHelper();
        for (int r = 1; r <= rowCount; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            ClientAnchor anchor = factory.createClientAnchor();
            anchor.setCol1(col0);
            anchor.setRow1(r);
            anchor.setCol2(col0 + 2);
            anchor.setRow2(r + 3);
            Comment comment = drawing.createCellComment(anchor);
            comment.setString(factory.createRichTextString("Double click for calendar or enter date as DD-MM-YYYY"));
            row.getCell(col0).setCellComment(comment);
        }
    }
}
