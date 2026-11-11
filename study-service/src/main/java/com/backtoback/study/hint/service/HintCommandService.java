package com.backtoback.study.hint.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.study.hint.exception.HintInvalidStatusTransitionException;
import com.backtoback.study.hint.repository.HintRequestRepository;

import lombok.RequiredArgsConstructor;

/**
 * 힌트 요청 상태 전이 서비스 (api-spec §6.2).
 */
@Service
@RequiredArgsConstructor
public class HintCommandService {

    private final HintRequestRepository hintRequestRepository;

    /**
     * REQUESTED → GENERATING 전이 (api-spec §6.2.1).
     * 대상 행이 없거나 이미 REQUESTED가 아니면 {@link HintInvalidStatusTransitionException}을 던진다.
     */
    @Transactional
    public void transitionToGenerating(Long hintRequestId) {
        int updated = hintRequestRepository.updateStatusToGenerating(hintRequestId);
        if (updated == 0) {
            throw new HintInvalidStatusTransitionException();
        }
    }
}
