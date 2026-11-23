package com.backtoback.study.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.study.community.domain.PostStatus;
import com.backtoback.study.community.dto.PostDetailResponse;
import com.backtoback.study.community.dto.PostModerationResponse;
import com.backtoback.study.community.dto.PostSummaryResponse;
import com.backtoback.study.community.service.PostCommandService;
import com.backtoback.study.community.service.PostModerationService;
import com.backtoback.study.community.service.PostQueryService;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.service.MemberStatusGuard;

/**
 * 게시글 API의 요청 바인딩·검증·응답 변환을 검증한다(API 명세서 5.5.1~5.5.5).
 * {@code JpaAuditingConfiguration} 때문에 {@code @WebMvcTest}는 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 6.7)
 * standalone MockMvc로 작성한다(선례 {@code StudyCommandControllerTest}).
 */
class PostControllerTest {

    private static final Long MEMBER_ID = 7L;
    private static final String POSTS = "/api/v1/studies/1/posts";
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-11-23T10:00:00+09:00");

    private MockMvc mockMvc;
    private MemberStatusGuard memberStatusGuard;
    private PostCommandService postCommandService;
    private PostQueryService postQueryService;
    private PostModerationService postModerationService;

    @BeforeEach
    void setUp() {
        memberStatusGuard = mock(MemberStatusGuard.class);
        postCommandService = mock(PostCommandService.class);
        postQueryService = mock(PostQueryService.class);
        postModerationService = mock(PostModerationService.class);
        PostController controller
            = new PostController(memberStatusGuard, postCommandService, postQueryService, postModerationService);
        mockMvc
            = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(
                    new PageableHandlerMethodArgumentResolver(),
                    new AuthenticationPrincipalArgumentResolver()
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder
            .getContext()
            .setAuthentication(new TestingAuthenticationToken(new GatewayUser("7", UserRole.USER), null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("5.5.2 작성 요청이 유효하면 201과 문자열 postId를 반환한다")
    void createPost_valid_returns201() throws Exception {
        // given
        given(postCommandService.write(eq(1L), eq(MEMBER_ID), any())).willReturn(88L);

        // when & then
        mockMvc
            .perform(json(post(POSTS), """
                { "title": "이번 주 풀이 공유", "content": "BFS로 풀었습니다.", "isNotice": false }"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("게시글이 작성되었습니다."))
            .andExpect(jsonPath("$.data.postId").value("88"));
    }

    @Test
    @DisplayName("5.5.2 제목 201자·빈 본문·본문 10,001자면 작성 서비스를 호출하지 않고 400 COMMON_INVALID_REQUEST를 반환한다")
    void createPost_invalidBody_returns400() throws Exception {
        List<String> bodies = List.of(body("가".repeat(201), "본문"), body("제목", " "), body("제목", "가".repeat(10_001)));
        for (String body : bodies) {
            mockMvc
                .perform(json(post(POSTS), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_INVALID_REQUEST.getCode()));
        }
        then(postCommandService).should(never()).write(any(), any(), any());
    }

    @Test
    @DisplayName("5.5.2·5.5.4·5.5.5 정지 회원이 작성·수정·삭제·운영진 관리를 요청하면 서비스를 호출하지 않고 403 MEMBER_SUSPENDED를 반환한다")
    void writeRequests_suspended_return403() throws Exception {
        // given
        willThrow(new StudyException(StudyErrorCode.MEMBER_SUSPENDED))
            .given(memberStatusGuard)
            .requireNotSuspended(MEMBER_ID);
        List<MockHttpServletRequestBuilder> requests
            = List
                .of(
                    json(post(POSTS), body("제목", "본문")),
                    json(patch(POSTS + "/88"), body("제목", "본문")),
                    delete(POSTS + "/88"),
                    json(patch(POSTS + "/88/moderation"), """
                        { "isPinned": true, "isNotice": false, "isHidden": false }""")
                );

        // when & then
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc
                .perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(StudyErrorCode.MEMBER_SUSPENDED.getCode()));
        }
        then(postCommandService).shouldHaveNoInteractions();
        then(postModerationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("5.5.1 목록은 페이지 응답 구조로 반환하고, size는 최대 100, 허용하지 않은 정렬 필드는 무시하며 isNotice=true일 때만 공지로 거른다")
    void getPosts_returnsPageAndSanitizesPaging() throws Exception {
        // given
        PostSummaryResponse item = new PostSummaryResponse("88", "풀이 공유", "7", "user7", true, false, 2, CREATED_AT);
        given(postQueryService.getPosts(eq(1L), eq(MEMBER_ID), anyBoolean(), any(Pageable.class)))
            .willReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        // when
        mockMvc
            .perform(get(POSTS).param("size", "1000").param("sort", "title,asc").param("isNotice", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].postId").value("88"))
            .andExpect(jsonPath("$.data.content[0].authorNickname").value("user7"))
            .andExpect(jsonPath("$.data.content[0].isPinned").value(true))
            .andExpect(jsonPath("$.data.content[0].isNotice").value(false))
            .andExpect(jsonPath("$.data.content[0].commentCount").value(2))
            .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-11-23T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false));
        mockMvc.perform(get(POSTS).param("isNotice", "false")).andExpect(status().isOk());

        // then
        ArgumentCaptor<Boolean> noticeOnly = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(postQueryService)
            .should(times(2))
            .getPosts(eq(1L), eq(MEMBER_ID), noticeOnly.capture(), pageable.capture());
        assertThat(noticeOnly.getAllValues()).containsExactly(true, false);
        assertThat(pageable.getAllValues().get(0).getPageSize()).isEqualTo(100);
        assertThat(pageable.getAllValues().get(0).getSort()).containsExactly(Sort.Order.desc("createdAt"));
    }

    @Test
    @DisplayName("5.5.3 상세는 작성자 객체와 댓글 목록 구조로 200을 반환한다")
    void getPost_returnsDetail() throws Exception {
        // given
        PostDetailResponse.Author author = new PostDetailResponse.Author("7", "user7");
        PostDetailResponse detail
            = new PostDetailResponse(
                "88",
                "풀이 공유",
                "BFS로 풀었습니다.",
                author,
                false,
                true,
                List
                    .of(
                        new PostDetailResponse.CommentItem(
                            "301",
                            "좋네요",
                            new PostDetailResponse.Author("8", "탈퇴한 사용자"),
                            CREATED_AT
                        )
                    ),
                CREATED_AT,
                CREATED_AT
            );
        given(postQueryService.getPost(1L, 88L, MEMBER_ID)).willReturn(detail);

        // when & then
        mockMvc
            .perform(get(POSTS + "/88"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.author.memberId").value("7"))
            .andExpect(jsonPath("$.data.isNotice").value(true))
            .andExpect(jsonPath("$.data.comments[0].commentId").value("301"))
            .andExpect(jsonPath("$.data.comments[0].author.nickname").value("탈퇴한 사용자"))
            .andExpect(jsonPath("$.data.updatedAt").value("2026-11-23T10:00:00+09:00"));
    }

    @Test
    @DisplayName("5.5.4 수정은 200과 postId를, 삭제는 본문 없이 204를 반환한다")
    void updateAndDelete_returnSpecStatus() throws Exception {
        // given
        given(postCommandService.edit(eq(1L), eq(88L), eq(MEMBER_ID), any())).willReturn(88L);

        // when & then
        mockMvc
            .perform(json(patch(POSTS + "/88"), body("수정한 제목", "수정한 본문")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.postId").value("88"));
        mockMvc.perform(delete(POSTS + "/88")).andExpect(status().isNoContent());
        then(postCommandService).should().delete(1L, 88L, MEMBER_ID);
    }

    @Test
    @DisplayName("5.5.5 운영진 관리 바디에 값이 빠지면 400, 정상이면 200과 변경된 상태를 반환한다")
    void moderatePost_validatesAndReturnsState() throws Exception {
        // given
        given(postModerationService.moderate(eq(1L), eq(88L), eq(MEMBER_ID), any()))
            .willReturn(new PostModerationResponse("88", true, true, PostStatus.HIDDEN));

        // when & then
        mockMvc
            .perform(json(patch(POSTS + "/88/moderation"), """
                { "isPinned": true, "isNotice": true }"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_INVALID_REQUEST.getCode()));
        mockMvc
            .perform(json(patch(POSTS + "/88/moderation"), """
                { "isPinned": true, "isNotice": true, "isHidden": true }"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("게시글 설정이 변경되었습니다."))
            .andExpect(jsonPath("$.data.isPinned").value(true))
            .andExpect(jsonPath("$.data.status").value("HIDDEN"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(String title, String content) {
        return "{ \"title\": \"" + title + "\", \"content\": \"" + content + "\" }";
    }
}
