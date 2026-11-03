package com.backtoback.contest.exam.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.exam.dto.StudyExamResponse;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** 스터디 시험을 ID 순서로 조회하며 페이지마다 현재 데이터를 사용한다. (API 명세서 §4.7.5) */
@Service
@RequiredArgsConstructor
public class StudyExamQueryService {
    private final ExamRepository exams;

    /**
     * 양의 BIGINT 스터디 ID와 1~100 크기를 사용해 현재 페이지를 조회한다.
     * 잘못된 ID·크기·커서나 다른 스터디의 커서는 {@code COMMON_INVALID_REQUEST}로 거절한다.
     * 각 요청은 별도 조회이므로 페이지 전체의 단일 스냅샷을 보장하지 않는다. (API 명세서 §4.7.5)
     */
    @Transactional(readOnly = true)
    public StudyExamResponse getStudyExams(String studyId, String after, int size) {
        long study = positiveId(studyId);
        if (size < 1 || size > 100) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        long lastId = after == null ? 0 : decodeCursor(after, study);
        var rows = exams.findByStudyIdAndIdGreaterThanOrderByIdAsc(study, lastId, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        var page = hasMore ? rows.subList(0, size) : rows;
        var items = page.stream().map(StudyExamResponse.Item::from).toList();
        String next = hasMore ? encodeCursor(study, page.get(page.size() - 1).getId()) : null;
        return new StudyExamResponse(items, next, hasMore);
    }

    private String encodeCursor(long study, long lastId) {
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(("study:" + study + ":" + lastId).getBytes(StandardCharsets.UTF_8));
    }

    private long decodeCursor(String cursor, long study) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String prefix = "study:" + study + ":";
            if (!decoded.startsWith(prefix)) {
                throw new IllegalArgumentException();
            }
            long id = positiveId(decoded.substring(prefix.length()));
            if (!encodeCursor(study, id).equals(cursor)) {
                throw new IllegalArgumentException();
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    private long positiveId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }
}
