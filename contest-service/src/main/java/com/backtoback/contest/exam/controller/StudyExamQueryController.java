package com.backtoback.contest.exam.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.contest.exam.dto.StudyExamResponse;
import com.backtoback.contest.exam.service.StudyExamQueryService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** 내부망에서 스터디 시험 상태 조회를 제공한다. (API 명세서 §4.7.5) */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "contest.internal-http.mode",
    havingValue = "real"
)
public class StudyExamQueryController {
    private final StudyExamQueryService service;

    /** 4.7.5 스터디 시험 상태 조회. */
    @GetMapping("/internal/v1/contest/studies/{studyId}/exams")
    public ResponseEntity<StudyExamResponse> getStudyExams(
        @PathVariable String studyId,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "20")
        @Min(
            value = 1,
            message = "페이지 크기는 1 이상이어야 합니다."
        )
        @Max(
            value = 100,
            message = "페이지 크기는 100 이하여야 합니다."
        ) int size
    ) {
        return ResponseEntity.ok(service.getStudyExams(studyId, after, size));
    }
}
