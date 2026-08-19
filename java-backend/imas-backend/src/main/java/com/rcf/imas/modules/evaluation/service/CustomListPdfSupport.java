package com.rcf.imas.modules.evaluation.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenPDF port of customListController.js downloadListPDF (Firm Decision 4). Text-only header (no logos --
 * simplification, human-facing download with no automated consumer). Standard built-in font (Times-Roman
 * equivalent) -- Node's own Times-Roman can't render Kannada either, so no special font family is needed.
 */
@Component
public class CustomListPdfSupport {

    private static final Font TITLE_FONT = new Font(Font.TIMES_ROMAN, 18, Font.BOLD);
    private static final Font SUBTITLE_FONT = new Font(Font.TIMES_ROMAN, 12, Font.NORMAL);
    private static final Font LIST_NAME_FONT = new Font(Font.TIMES_ROMAN, 22, Font.BOLD, java.awt.Color.BLUE);
    private static final Font HEADER_CELL_FONT = new Font(Font.TIMES_ROMAN, 10, Font.BOLD);
    private static final Font BODY_CELL_FONT = new Font(Font.TIMES_ROMAN, 9, Font.NORMAL);

    public byte[] build(String listName, List<Map<String, Object>> students, List<Map<String, Object>> fields) {
        boolean hasId = fields.stream().anyMatch(f -> "student_id".equals(f.get("col_name")));
        boolean hasName = fields.stream().anyMatch(f -> "student_name".equals(f.get("col_name")));

        List<String> headers = new ArrayList<>();
        List<String> colNames = new ArrayList<>();
        List<Float> widths = new ArrayList<>();
        if (hasId) { headers.add("ID"); colNames.add("student_id"); widths.add(50f); }
        if (hasName) { headers.add("Name"); colNames.add("student_name"); widths.add(150f); }
        for (Map<String, Object> f : fields) {
            String col = String.valueOf(f.get("col_name"));
            if ("student_id".equals(col) || "student_name".equals(col)) continue;
            headers.add(String.valueOf(f.get("display_name")));
            colNames.add(col);
            widths.add(100f);
        }

        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            Paragraph title = new Paragraph("RAJALAKSHMI CHILDREN FOUNDATION", TITLE_FONT);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);
            Paragraph subtitle = new Paragraph("PRATIBHA POSHAK - 2025", SUBTITLE_FONT); // hard-coded literal, matches Node
            subtitle.setAlignment(Element.ALIGN_CENTER);
            doc.add(subtitle);

            Paragraph listNamePara = new Paragraph(listName.toUpperCase(), LIST_NAME_FONT);
            listNamePara.setAlignment(Element.ALIGN_CENTER);
            listNamePara.setSpacingBefore(12f);
            listNamePara.setSpacingAfter(12f);
            doc.add(listNamePara);

            float[] widthArr = new float[widths.size()];
            for (int i = 0; i < widthArr.length; i++) widthArr[i] = widths.get(i);
            PdfPTable table = new PdfPTable(widthArr);
            table.setWidthPercentage(100);

            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(h, HEADER_CELL_FONT));
                cell.setGrayFill(0.9f);
                table.addCell(cell);
            }
            for (Map<String, Object> s : students) {
                for (String col : colNames) {
                    table.addCell(new PdfPCell(new Phrase(CustomListValueMapper.cellText(col, s), BODY_CELL_FONT)));
                }
            }
            doc.add(table);
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException(e);
        }
    }
}
