package com.backtoback.problem.tag.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.problem.global.api.PageResponse;
import com.backtoback.problem.global.exception.custom.CommonInvalidRequestException;
import com.backtoback.problem.global.exception.custom.IdempotencyKeyConflictException;
import com.backtoback.problem.global.exception.custom.ProblemTagNotFoundException;
import com.backtoback.problem.global.exception.custom.ProblemVersionConflictException;
import com.backtoback.problem.tag.dto.request.TagCreateRequest;
import com.backtoback.problem.tag.dto.request.TagUpdateRequest;
import com.backtoback.problem.tag.dto.response.TagResponse;
import com.backtoback.problem.tag.entity.Tag;
import com.backtoback.problem.tag.repository.TagRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {
    private final TagRepository tagRepository;
    private final TagCreationRecordService creationRecords;
    private final TagOrderService tagOrderService;

    /**
     * 검색·페이지·정렬 조건으로 태그 목록을 조회한다.
     * 컨트롤러에서 검증한 검색어 길이·페이지 범위·정렬 조건을 전달받는다.
     *
     * @param keyword 태그 이름에 포함된 검색어; 빈 문자열이면 전체 조회
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지 크기; 1부터 100까지 허용
     * @param sort 정렬 필드와 방향; displayOrder 또는 name과 asc 또는 desc의 조합
     * @return 태그 목록과 페이지 정보
     */
    public PageResponse<TagResponse> find(String keyword, int page, int size, String sort) {
        // "name,asc"를 정렬 필드(parts[0])와 방향(parts[1])으로 나눈다.
        String[] parts = sort.split(",", -1);
        // 요청한 필드·방향으로 우선 정렬하고, 값이 같으면 ID 오름차순으로 정렬한다.
        Sort ordering = Sort.by(Sort.Direction.fromString(parts[1]), parts[0]).and(Sort.by("id"));
        // DB에서 검색 결과를 정렬한 후 요청 페이지를 조회하고, 항목을 응답 DTO로 변환한다.
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
     * @return 최초 생성 당시의 태그 ID와 이름
     * @throws IdempotencyKeyConflictException 유효한 멱등키에 다른 요청 본문이 전달된 경우
     * @throws DataIntegrityViolationException 태그 이름이 중복된 경우; 전역 처리기에서 태그 중복 오류로 응답한다
     * @throws ProblemVersionConflictException 잠금 대기 시간이 초과된 경우
     */
    @Transactional
    public TagResponse create(String adminId, String key, TagCreateRequest request) {
        var savedRecord = creationRecords.lock(adminId, key, request.name(), request.displayOrder());
        if (!savedRecord.matches(request.name(), request.displayOrder())) {
            throw new IdempotencyKeyConflictException();
        }
        if (savedRecord.getResultTagId() != null) {
            return new TagResponse(savedRecord.getResultTagId().toString(), savedRecord.getResultName());
        }
        tagOrderService.lock();
        int displayOrder = tagOrderService.makeRoomFor(request.displayOrder());
        Tag tag = tagRepository.saveAndFlush(new Tag(request.name().strip(), displayOrder));
        TagResponse response = TagResponse.from(tag);
        savedRecord.complete(tag.getId(), tag.getName());
        return response;
    }

    /**
     * 태그의 이름 또는 표시 순서를 부분 수정한다.
     * 요청 순번을 보정한 위치로 이동하고 전체 순번을 0부터 연속되게 유지한다.
     *
     * @param tagId 수정할 태그 ID
     * @param request 변경할 이름 또는 표시 순서
     * @return 수정한 태그 ID와 이름
     * @throws CommonInvalidRequestException 변경값이 없거나 이름이 공백인 경우
     * @throws ProblemTagNotFoundException 해당 태그가 없는 경우
     * @throws DataIntegrityViolationException 변경할 이름이 중복된 경우; 전역 처리기에서 태그 중복 오류로 응답한다
     * @throws ProblemVersionConflictException 잠금 대기 시간이 초과된 경우
     */
    @Transactional
    public TagResponse update(long tagId, TagUpdateRequest request) {
        if ((request.name() == null && request.displayOrder() == null)
            || (request.name() != null && request.name().isBlank())) {
            throw new CommonInvalidRequestException();
        }
        tagOrderService.lock();
        Tag tag = tagRepository.findById(tagId).orElseThrow(ProblemTagNotFoundException::new);
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
     * @param tagId 삭제할 태그 ID
     * @throws ProblemTagNotFoundException 해당 태그가 없는 경우
     * @throws DataIntegrityViolationException 문제 회차에서 태그를 사용 중인 경우; 전역 처리기에서 사용 중 오류로 응답한다
     * @throws ProblemVersionConflictException 잠금 대기 시간이 초과된 경우
     */
    @Transactional
    public void delete(long tagId) {
        tagOrderService.lock();
        Tag tag = tagRepository.findById(tagId).orElseThrow(ProblemTagNotFoundException::new);
        tagRepository.delete(tag);
        tagRepository.flush();
        tagOrderService.closeGap();
    }

}
