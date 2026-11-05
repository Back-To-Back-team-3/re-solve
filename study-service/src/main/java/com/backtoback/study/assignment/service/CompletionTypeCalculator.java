package com.backtoback.study.assignment.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.backtoback.study.assignment.domain.CompletionType;

/**
 * 근거 제출의 접수 시각만으로 완료 유형을 판정하는 순수 계산 컴포넌트 (정책 및 상태 §완료 유형).
 * 기존 풀이 인정 옵션이 꺼진 경우 starts_at 이전 AC는 호출 전 단계에서 근거 후보에서 제외되므로
 * 이 메서드는 전달된 basisSubmittedAt 범위만 판정한다.
 */
@Component
public class CompletionTypeCalculator {

    /**
     * 근거 제출 접수 시각·문제집 시작·마감을 받아 완료 유형을 반환한다.
     *
     * @param basisSubmittedAt 완료 근거 제출의 서버 접수 시각 (정책 및 상태 §완료 유형 — 이벤트 도착 순서와 무관)
     * @param startsAt 문제집 시작 시각
     * @param deadlineAt 문제집 마감 시각
     */
    public CompletionType calculate(LocalDateTime basisSubmittedAt, LocalDateTime startsAt, LocalDateTime deadlineAt) {
        if (basisSubmittedAt.isBefore(startsAt)) {
            return CompletionType.PRE_SOLVED;
        }
        // 정각은 ON_TIME으로 처리한다(정책 및 상태 §완료 유형 — submitted_at > deadline_at → LATE).
        if (!basisSubmittedAt.isAfter(deadlineAt)) {
            return CompletionType.ON_TIME;
        }
        return CompletionType.LATE;
    }
}
