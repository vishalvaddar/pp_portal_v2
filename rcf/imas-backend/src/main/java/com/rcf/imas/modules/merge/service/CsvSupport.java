package com.rcf.imas.modules.merge.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CSV parse/write for the merge uploads and downloads (Apache Commons CSV). */
@Component
public class CsvSupport {

    /**
     * Parse CSV bytes into a list of ordered header→value maps.
     * @param stripBomTrimHeaders p1 semantics (trim + strip leading BOM from header names); p2 passes false (headers verbatim).
     */
    public List<Map<String, String>> parse(byte[] bytes, boolean stripBomTrimHeaders) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        CSVFormat fmt = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).build();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), fmt)) {
            List<String> headers = new ArrayList<>(parser.getHeaderNames());
            if (stripBomTrimHeaders) {
                for (int i = 0; i < headers.size(); i++) {
                    headers.set(i, headers.get(i).trim().replace("﻿", ""));
                }
            }
            for (CSVRecord rec : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < rec.size() ? rec.get(i) : null);
                }
                out.add(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Write header + rows to a CSV string (json2csv parity: header-only when rows is a single empty map). */
    public String write(List<String> fields, List<Map<String, Object>> rows) {
        StringWriter sw = new StringWriter();
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader(fields.toArray(new String[0])).build();
        try (CSVPrinter printer = new CSVPrinter(sw, fmt)) {
            for (Map<String, Object> row : rows) {
                List<Object> vals = new ArrayList<>();
                for (String f : fields) vals.add(row.get(f));
                printer.printRecord(vals);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sw.toString();
    }
}
