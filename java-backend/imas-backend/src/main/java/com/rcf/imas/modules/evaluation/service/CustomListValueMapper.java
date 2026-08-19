package com.rcf.imas.modules.evaluation.service;

import java.util.Map;

/** Special-case cell mapping shared by download-xlsx and download-pdf (customListController.js duplicates this logic
 *  identically in both handlers). Missing/null -> literal '-'. */
final class CustomListValueMapper {

    private CustomListValueMapper() {}

    static String cellText(String colName, Map<String, Object> s) {
        Object val;
        switch (colName) {
            case "batch_id" -> val = s.get("batch_name");
            case "current_institute_dise_code" -> val = s.get("current_institute_name");
            case "previous_institute_dise_code" -> val = s.get("previous_institute_name");
            case "district", "district_id" -> val = s.get("district");
            case "nmms_block", "block_id" -> val = s.get("block");
            default -> val = s.get(colName);
        }
        return val == null ? "-" : String.valueOf(val);
    }
}
