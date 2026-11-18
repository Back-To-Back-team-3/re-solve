package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.hint.exception.HintInvalidStatusTransitionException;
import com.backtoback.study.hint.repository.HintRequestRepository;

import lombok.RequiredArgsConstructor;

/**
 * 힌트 요청 상태 전이 서비스 (api-spec §6.2).
 */
@Service
@RequiredArgsConstructor
public class HintCommandService {

    private final Clock clock;
    private final HintRequestRepository hintRequestRepository;

    /**
     * REQUESTED → GENERATING 전이 (api-spec §6.2.1).
     * 전이 시각을 생성 시작 시각으로 기록해 회수 스케줄러가 멈춘 요청을 판정할 수 있게 한다.
     * 대상 행이 없거나 이미 REQUESTED가 아니면 {@link HintInvalidStatusTransitionException}을 던진다.
     */
    @Transactional
    public void transitionToGenerating(Long hintRequestId) {
        if (!hintRequestRepository.existsById(hintRequestId)) {
            throw new StudyException(StudyErrorCode.HINT_REQUEST_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        int updated = hintRequestRepository.updateStatusToGenerating(hintRequestId, now);
        if (updated == 0) {
            throw new HintInvalidStatusTransitionException();
        }
    }
}
