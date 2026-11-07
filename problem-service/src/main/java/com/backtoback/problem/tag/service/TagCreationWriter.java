package com.backtoback.problem.tag.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.tag.domain.Tag;
import com.backtoback.problem.tag.dto.request.TagCreateRequest;
import com.backtoback.problem.tag.dto.response.TagResponse;
import com.backtoback.problem.tag.repository.TagRepository;

import lombok.RequiredArgsConstructor;

/** 태그 생성과 멱등 응답 저장을 공통 잠금 아래 하나의 트랜잭션으로 처리한다. */
@Service
@RequiredArgsConstructor
public class TagCreationWriter {
    private final TagRepository tagRepository;
    private final TagCreationRecordService creationRecords;
    private final TagOrderService tagOrderService;

    /**
     * 잠금 획득 후 멱등 기록을 다시 확인하고 태그와 최초 응답을 함께 저장한다.
     * 사전 조회 후 경쟁 요청이 완료됐으면 그 요청의 최초 응답을 반환한다.
     *
     * @param adminId 요청 관리자 ID
     * @param key 관리자별 멱등키
     * @param request 생성할 태그 이름과 순번
     * @return 최초 생성 응답
     * @throws ProblemException 멱등 본문 충돌 또는 공통 잠금 대기 시간 초과
     * @throws DataIntegrityViolationException 태그 이름이 중복된 경우
     */
    @Transactional
    public TagResponse create(String adminId, String key, TagCreateRequest request) {
        // DB 잠금을 보유한 채 Redis 잠금을 기다리지 않도록 공통 잠금을 먼저 획득한다.
        tagOrderService.lock();
        var savedRecord = creationRecords.lock(adminId, key, request.name(), request.displayOrder());
        if (!savedRecord.matches(request.name(), request.displayOrder())) {
            throw new ProblemException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (savedRecord.getResultTagId() != null) {
            return TagResponse.from(savedRecord);
        }
        int displayOrder = tagOrderService.makeRoomFor(request.displayOrder());
        Tag tag = tagRepository.saveAndFlush(Tag.create(request.name().strip(), displayOrder));
        TagResponse response = TagResponse.from(tag);
        savedRecord.complete(tag.getId(), tag.getName(), tag.getDisplayOrder());
        return response;
    }
}
