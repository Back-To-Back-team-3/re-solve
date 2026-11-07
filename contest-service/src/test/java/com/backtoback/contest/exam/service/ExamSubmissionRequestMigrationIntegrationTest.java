package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.backtoback.contest.exam.dto.ExamSubmissionRequest;

/** 기존 접수의 요청 보존본 백필을 MySQL에서 검증한다. */
class ExamSubmissionRequestMigrationIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("정확히 일치하는 원본만 백필하고 중복과 원본 없는 접수는 보존한다")
    void backfillsOnlyUniqueMatchingOriginal() {
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        var original = jdbc.queryForMap("SELECT * FROM outbox_events WHERE aggregate_id=?", receipt.examSubmissionId());
        var source = jdbc.queryForMap("SELECT * FROM exam_submissions WHERE id=?", receipt.examSubmissionId());
        jdbc.execute((ConnectionCallback<Void>)connection -> {
            try {
                try (var statement = connection.createStatement()) {
                    statement
                        .execute(
                            "CREATE TEMPORARY TABLE exam_submissions (id BIGINT PRIMARY KEY, "
                                + "exam_participant_id BIGINT, exam_id BIGINT, problem_id BIGINT, draft_seq BIGINT, "
                                + "language VARCHAR(30), source_code MEDIUMTEXT,"
                                + "submission_type VARCHAR(30), received_at DATETIME(6))"
                        );
                    statement
                        .execute(
                            "CREATE TEMPORARY TABLE exam_participants (id BIGINT,exam_id BIGINT,member_id BIGINT)"
                        );
                    statement
                        .execute(
                            "CREATE TEMPORARY TABLE exam_problems (exam_id BIGINT,problem_id BIGINT,"
                                + "problem_revision_id BIGINT)"
                        );
                    statement
                        .execute(
                            "CREATE TEMPORARY TABLE outbox_events (id BIGINT,event_id VARCHAR(64),"
                                + "event_type VARCHAR(50),"
                                + "schema_version VARCHAR(10),aggregate_type VARCHAR(30),aggregate_id VARCHAR(64),"
                                + "correlation_id VARCHAR(64),occurred_at DATETIME(6),payload JSON)"
                        );
                }
                long id = Long.parseLong(receipt.examSubmissionId());
                try (
                    var insert = connection.prepareStatement("INSERT INTO exam_submissions VALUES (?,?,?,?,?,?,?,?,?)")
                ) {
                    for (long value : new long[] {
                        id,
                        id + 1,
                        id + 2
                    }) {
                        insert.setLong(1, value);
                        insert.setLong(2, participantId);
                        insert.setLong(3, Long.parseLong(examId));
                        insert.setLong(4, 101);
                        insert.setLong(5, 1);
                        insert.setString(6, "JAVA");
                        insert.setString(7, "코드");
                        insert.setString(8, "MANUAL");
                        insert.setObject(9, source.get("received_at"));
                        insert.executeUpdate();
                    }
                }
                try (var insert = connection.prepareStatement("INSERT INTO exam_participants VALUES (?,?,7)")) {
                    insert.setLong(1, participantId);
                    insert.setLong(2, Long.parseLong(examId));
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO exam_problems VALUES (?,101,1001)")) {
                    insert.setLong(1, Long.parseLong(examId));
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO outbox_events VALUES (?,?,?,?,?,?,?,?,?)")) {
                    for (int i = 0; i < 3; i++) {
                        insert.setLong(1, i + 1);
                        String eventId
                            = i == 0 ? (String)original.get("event_id") : java.util.UUID.randomUUID().toString();
                        long receiptId = i == 0 ? id : id + 1;
                        var payload
                            = (tools.jackson.databind.node.ObjectNode)new tools.jackson.databind.ObjectMapper()
                                .readTree((String)original.get("payload"));
                        payload.put("eventId", eventId);
                        payload.put("aggregateId", Long.toString(receiptId));
                        payload.put("examSubmissionId", Long.toString(receiptId));
                        insert.setString(2, eventId);
                        insert.setString(3, (String)original.get("event_type"));
                        insert.setString(4, (String)original.get("schema_version"));
                        insert.setString(5, (String)original.get("aggregate_type"));
                        insert.setString(6, Long.toString(receiptId));
                        insert.setString(7, (String)original.get("correlation_id"));
                        insert.setObject(8, original.get("occurred_at"));
                        insert.setString(9, payload.toString());
                        insert.executeUpdate();
                    }
                }
                ScriptUtils
                    .executeSqlScript(
                        connection,
                        new ClassPathResource("db/migration/V16_2__examsubmission_preserve_request.sql")
                    );
                try (
                    var query
                        = connection.prepareStatement("SELECT id,request_snapshot FROM exam_submissions ORDER BY id")
                ) {
                    try (var rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(2)).isNotNull();
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(2)).isNull();
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(2)).isNull();
                    }
                }
                return null;
            } finally {
                try (var cleanup = connection.createStatement()) {
                    cleanup.execute("DROP TEMPORARY TABLE IF EXISTS outbox_events");
                    cleanup.execute("DROP TEMPORARY TABLE IF EXISTS exam_problems");
                    cleanup.execute("DROP TEMPORARY TABLE IF EXISTS exam_participants");
                    cleanup.execute("DROP TEMPORARY TABLE IF EXISTS exam_submissions");
                }
            }
        });
    }
}
