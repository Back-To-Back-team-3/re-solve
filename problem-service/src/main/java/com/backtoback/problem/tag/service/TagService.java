package com.backtoback.problem.tag.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.api.PageResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.tag.domain.Tag;
import com.backtoback.problem.tag.dto.request.TagCreateRequest;
import com.backtoback.problem.tag.dto.request.TagUpdateRequest;
import com.backtoback.problem.tag.dto.response.TagExistsResponse;
import com.backtoback.problem.tag.dto.response.TagResponse;
import com.backtoback.problem.tag.repository.TagRepository;

import lombok.RequiredArgsConstructor;

/** 태그 조회와 변경을 처리하며 잠금·순번 조정·저장을 하나의 트랜잭션으로 묶는다. */
@Service
@RequiredArgsConstructor
public class TagService {
    private final TagRepository tagRepository;
    private final TagCreationRecordService creationRecords;
    private final TagOrderService tagOrderService;

    /**
     * 태그 ID로 조회해 내부 API(2.6.11)가 반환할 존재 응답을 만든다.
     *
     * @param tagId 조회할 태그 ID
     * @return 태그의 문자열 ID·이름을 담은 내부 응답
     * @throws ProblemException 태그가 없으면 {@code PROBLEM_TAG_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public TagExistsResponse getExisting(long tagId) {
        return tagRepository
            .findById(tagId)
            .map(TagExistsResponse::from)
            .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_TAG_NOT_FOUND));
    }

    /**
     * 컨트롤러에서 검증한 검색어 길이·페이지 범위·정렬 조건을 전달받는다.
     *
     * @param keyword 태그 이름에 포함된 검색어; 빈 문자열이면 전체 조회
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지 크기; 1부터 100까지 허용
     * @param sort 정렬 필드와 방향; {@code displayOrder} 또는 {@code name}과 {@code asc} 또는 {@code desc}의 조합
     */
    @Transactional(readOnly = true)
    public PageResponse<TagResponse> find(String keyword, int page, int size, String sort) {
        String[] parts = sort.split(",", -1);
        // 정렬값이 같은 태그도 페이지 조회 순서가 일정하도록 ID로 구분한다.
        Sort ordering = Sort.by(Sort.Direction.fromString(parts[1]), parts[0]).and(Sort.by("id"));
        return PageResponse
            .from(
                tagRepository
                    .findByNameContainingIgnoreCase(keyword.strip(), PageRequest.of(page, size, ordering))
                    .map(TagResponse::from)
            );
    }

    /**
     * 멱등키를 확인하고 지정한 순번에 태그를 등록한다.
     * 24시간 이내의 동일 요청은 최초 응답을 반환한다. 요청 순번을 보정한 위치에 삽입하고 기존 태그의 순번을 맞춘다.
     * 순번 변경·태그 저장·요청 기록 완료를 같은 트랜잭션으로 처리한다.
     *
     * @param adminId 요청한 관리자 ID
     * @param key 관리자별 중복 요청 식별 키
     * @param request 등록할 태그 이름과 표시 순서
     * @return 최초 생성 당시의 태그 ID·이름·보정된 순번
     * @throws ProblemException 유효한 멱등키에 다른 요청 본문이 전달되어 {@code IDEMPOTENCY_KEY_CONFLICT}가 발생한 경우
     * @throws DataIntegrityViolationException 태그 이름이 중복된 경우; 전역 처리기에서 태그 중복 오류로 응답한다
     * @throws ProblemException 잠금 대기 시간이 초과되어 {@code PROBLEM_VERSION_CONFLICT}가 발생한 경우
     */
    @Transactional
    public TagResponse create(String adminId, String key, TagCreateRequest request) {
        var savedRecord = creationRecords.lock(adminId, key, request.name(), request.displayOrder());
        if (!savedRecord.matches(request.name(), request.displayOrder())) {
            throw new ProblemException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (savedRecord.getResultTagId() != null) {
            return new TagResponse(
                savedRecord.getResultTagId().toString(),
                savedRecord.getResultName(),
                savedRecord.getResultDisplayOrder()
            );
        }
        tagOrderService.lock();
        int displayOrder = tagOrderService.makeRoomFor(request.displayOrder());
        Tag tag = tagRepository.saveAndFlush(Tag.create(request.name().strip(), displayOrder));
        TagResponse response = TagResponse.from(tag);
        savedRecord.complete(tag.getId(), tag.getName(), tag.getDisplayOrder());
        return response;
    }

    /**
     * 태그의 이름 또는 표시 순서를 부분 수정한다.
     * 요청 순번을 보정한 위치로 이동하고 전체 순번을 0부터 연속되게 유지한다.
     *
     * @throws ProblemException 변경값이 없으면 {@code COMMON_INVALID_REQUEST}, 태그가 없으면 {@code PROBLEM_TAG_NOT_FOUND}
     *     잠금 대기 시간이 초과되면 {@code PROBLEM_VERSION_CONFLICT}
     * @throws DataIntegrityViolationException 변경할 이름이 중복된 경우; 전역 처리기에서 태그 중복 오류로 응답한다
     */
    @Transactional
    public TagResponse update(long tagId, TagUpdateRequest request) {
        if (request.name() == null && request.displayOrder() == null) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        tagOrderService.lock();
        Tag tag
            = tagRepository
                .findById(tagId)
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_TAG_NOT_FOUND));
        if (request.displayOrder() != null) {
            tagOrderService.move(tag, request.displayOrder());
        }
        tag.update(request.name() == null ? null : request.name().strip(), null);
        return TagResponse.from(tag);
    }

    /**
     * 문제 회차에서 사용하지 않는 태그를 삭제하고 뒤에 있는 태그의 순번을 한 칸씩 당긴다.
     * 삭제와 순번 변경은 같은 트랜잭션에서 처리한다.
     *
     * @throws ProblemException 태그가 없으면 {@code PROBLEM_TAG_NOT_FOUND}, 잠금 대기가 초과되면 {@code PROBLEM_VERSION_CONFLICT}
     * @throws DataIntegrityViolationException 문제 회차에서 태그를 사용 중인 경우; 전역 처리기에서 사용 중 오류로 응답한다
     */
    @Transactional
    public void delete(long tagId) {
        tagOrderService.lock();
        Tag tag
            = tagRepository
                .findById(tagId)
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_TAG_NOT_FOUND));
        tagRepository.delete(tag);
        tagRepository.flush();
        tagOrderService.closeGap();
    }

}
