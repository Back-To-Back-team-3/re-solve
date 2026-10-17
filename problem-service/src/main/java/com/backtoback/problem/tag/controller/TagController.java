package com.backtoback.problem.tag.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.global.api.ApiResponse;
import com.backtoback.problem.global.api.PageResponse;
import com.backtoback.problem.global.security.user.GatewayUser;
import com.backtoback.problem.tag.dto.request.TagCreateRequest;
import com.backtoback.problem.tag.dto.request.TagUpdateRequest;
import com.backtoback.problem.tag.dto.response.TagResponse;
import com.backtoback.problem.tag.service.TagService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class TagController {
    private final TagService tagService;

    /**
     * 조건에 맞는 태그 목록을 페이지 단위로 응답한다.
     *
     * @param keyword 태그 이름에 포함된 검색어; 빈 문자열이면 전체 조회
     * @param page 0부터 21,474,836까지 허용하는 페이지 번호; 최대 페이지 크기에서도 JPA 조회 위치의 int 범위를 유지한다
     * @param size 페이지 크기; 1부터 100까지 허용
     * @param sort 정렬 필드와 방향; displayOrder 또는 name과 asc 또는 desc의 조합
     * @return 태그 목록과 페이지 정보를 담은 성공 응답
     */
    @GetMapping("/api/v1/problems/tags")
    public ApiResponse<PageResponse<TagResponse>> find(
        @RequestParam(defaultValue = "") @Size(max = 200) String keyword,
        @RequestParam(defaultValue = "0") @Min(0) @Max(Integer.MAX_VALUE / 100) int page,
        @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "displayOrder,asc") @Pattern(regexp = "(displayOrder|name),(asc|desc)") String sort
    ) {
        return ApiResponse.success(tagService.find(keyword, page, size, sort));
    }

    /**
     * 관리자의 태그 생성 요청을 접수하고 최초 생성 결과를 응답한다.
     *
     * @param user Gateway에서 인증된 관리자
     * @param key 1~100자의 ASCII 문자로 구성된 관리자별 중복 요청 식별 키
     * @param request 등록할 태그 이름과 표시 순서
     * @return 201 상태의 태그 생성 응답
     */
    @PostMapping("/api/v1/admin/problems/tags")
    public ResponseEntity<ApiResponse<TagResponse>> create(
        @AuthenticationPrincipal GatewayUser user,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) @Pattern(regexp = "\\p{ASCII}+") String key,
        @Valid @RequestBody TagCreateRequest request
    ) {
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success("태그가 생성되었습니다.", tagService.create(user.userId(), key, request)));
    }

    /**
     * 관리자가 지정한 태그의 이름 또는 표시 순서를 수정한다.
     *
     * @param tagId 수정할 태그 ID
     * @param request 변경할 이름 또는 표시 순서
     * @return 수정된 태그를 담은 성공 응답
     */
    @PatchMapping("/api/v1/admin/problems/tags/{tagId}")
    public ApiResponse<TagResponse> update(@PathVariable long tagId, @Valid @RequestBody TagUpdateRequest request) {
        return ApiResponse.success("태그가 수정되었습니다.", tagService.update(tagId, request));
    }

    /**
     * 관리자가 지정한 미사용 태그를 삭제하고 뒤에 있는 태그의 순번을 한 칸씩 당긴다.
     *
     * @param tagId 삭제할 태그 ID
     * @return 본문이 없는 204 응답
     */
    @DeleteMapping("/api/v1/admin/problems/tags/{tagId}")
    public ResponseEntity<Void> delete(@PathVariable long tagId) {
        tagService.delete(tagId);
        return ResponseEntity.noContent().build();
    }
}
