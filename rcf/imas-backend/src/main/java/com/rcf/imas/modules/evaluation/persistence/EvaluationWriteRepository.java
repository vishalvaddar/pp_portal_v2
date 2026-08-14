package com.rcf.imas.modules.evaluation.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
public class EvaluationWriteRepository {

    private final JdbcClient jdbc;

    public EvaluationWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record SaveResult(String listId) {}

    /**
     * saveListFull parity (customListModel.js:3-65) -- full create-or-replace flow:
     *  1. normalize listId: null/blank/"undefined" -> create path.
     *  2. create path: INSERT ... RETURNING list_id. Update path: UPDATE list_name, then UNCONDITIONALLY
     *     delete all existing custom_list_fields/custom_list_students for this list (full replace, not merge).
     *  3. per selected field: look up field_master by col_name (SHARED across all lists, keyed only by col_name --
     *     not per-list), insert if missing (fixed tab_name='pp.student_master'), then link via custom_list_fields.
     *  4. per student id: skip null/"undefined"; insert custom_list_students. NO de-dup check in application code --
     *     a genuine duplicate student_id within one call throws a unique-violation on the (list_id,student_id) PK,
     *     which (since this whole method is one @Transactional boundary) rolls back the entire save, matching Node's
     *     explicit BEGIN/COMMIT/ROLLBACK around the same statements.
     */
    @Transactional
    public SaveResult saveListFull(String listId, String listName, List<Object> studentIds,
                                    List<Map<String, Object>> selectedFields) {
        String finalId = (listId == null || listId.isBlank() || "undefined".equals(listId)) ? null : listId;

        if (finalId == null) {
            finalId = jdbc.sql("INSERT INTO pp.custom_list (list_name) VALUES (:name) RETURNING list_id")
                    .param("name", listName)
                    .query((rs, i) -> rs.getBigDecimal("list_id").toBigInteger().toString()).single();
        } else {
            jdbc.sql("UPDATE pp.custom_list SET list_name = :name WHERE list_id = :id::numeric")
                    .param("name", listName).param("id", finalId).update();
            jdbc.sql("DELETE FROM pp.custom_list_fields WHERE list_id = :id::numeric").param("id", finalId).update();
            jdbc.sql("DELETE FROM pp.custom_list_students WHERE list_id = :id::numeric").param("id", finalId).update();
        }

        if (selectedFields != null) {
            for (Map<String, Object> f : selectedFields) {
                String colName = String.valueOf(f.get("col_name"));
                String fieldId = jdbc.sql("SELECT field_id FROM pp.field_master WHERE col_name = :col")
                        .param("col", colName)
                        .query((rs, i) -> rs.getBigDecimal("field_id").toBigInteger().toString()).optional().orElse(null);
                if (fieldId == null) {
                    fieldId = jdbc.sql("INSERT INTO pp.field_master (tab_name, col_name) VALUES ('pp.student_master', :col) RETURNING field_id")
                            .param("col", colName)
                            .query((rs, i) -> rs.getBigDecimal("field_id").toBigInteger().toString()).single();
                }
                jdbc.sql("INSERT INTO pp.custom_list_fields (list_id, field_id) VALUES (:list::numeric, :field::numeric)")
                        .param("list", finalId).param("field", fieldId).update();
            }
        }

        if (studentIds != null) {
            for (Object sIdObj : studentIds) {
                String sId = sIdObj == null ? null : String.valueOf(sIdObj);
                if (sId != null && !sId.isBlank() && !"undefined".equals(sId)) {
                    jdbc.sql("INSERT INTO pp.custom_list_students (list_id, student_id) VALUES (:list::numeric, :student::numeric)")
                            .param("list", finalId).param("student", sId).update();
                }
            }
        }

        return new SaveResult(finalId);
    }

    /** deleteList parity: single autocommit DELETE, FK ON DELETE CASCADE handles custom_list_fields/custom_list_students.
     *  Node never checks affected-row count -- {success:true} is returned even for a non-existent id -- so this method
     *  intentionally has no return value / no existence check either. */
    public void deleteList(String id) {
        jdbc.sql("DELETE FROM pp.custom_list WHERE list_id = :id::numeric").param("id", id).update();
    }
}
