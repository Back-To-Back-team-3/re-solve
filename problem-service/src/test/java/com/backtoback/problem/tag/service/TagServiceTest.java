package com.backtoback.problem.tag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.tag.domain.TagCreationRecord;
import com.backtoback.problem.tag.dto.request.TagCreateRequest;
import com.backtoback.problem.tag.dto.response.TagResponse;
import com.backtoback.problem.tag.repository.TagRepository;

/** 태그 등록의 공통 잠금 순서와 멱등 응답 보존을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TagServiceTest {
    @Mock
    private TagRepository tagRepository;
    @Mock
    private TagCreationRecordService creationRecords;
    @Mock
    private TagOrderService tagOrderService;
    @Mock
    private TagCreationRecord savedRecord;
    @InjectMocks
    private TagService service;

    /** 공통 잠금 이후 멱등 기록을 잠그고 최초 응답을 그대로 반환하는지 검증한다. */
    @Test
    @DisplayName("재요청도 공통 잠금을 먼저 획득하고 최초 응답을 반환한다")
    void locksBeforeReplayingResponse() {
        given(creationRecords.lock("admin", "key", "태그", 10)).willReturn(savedRecord);
        given(savedRecord.matches("태그", 10)).willReturn(true);
        given(savedRecord.getResultTagId()).willReturn(1L);
        given(savedRecord.getResultName()).willReturn("태그");
        given(savedRecord.getResultDisplayOrder()).willReturn(0);

        TagResponse response = service.create("admin", "key", new TagCreateRequest("태그", 10));

        assertThat(response).isEqualTo(new TagResponse("1", "태그", 0));
        var order = inOrder(tagOrderService, creationRecords);
        order.verify(tagOrderService).lock();
        order.verify(creationRecords).lock("admin", "key", "태그", 10);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(tagRepository);
    }

    /** 공통 잠금 획득 실패 시 멱등 기록을 포함한 DB 작업을 시작하지 않는지 검증한다. */
    @Test
    @DisplayName("공통 잠금 획득 실패 시 멱등 기록과 태그에 접근하지 않는다")
    void rejectsBeforeDatabaseAccessWhenLockFails() {
        var failure = new ProblemException(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
        willThrow(failure).given(tagOrderService).lock();

        assertThatThrownBy(() -> service.create("admin", "key", new TagCreateRequest("태그", 0))).isSameAs(failure);
        verifyNoInteractions(creationRecords, tagRepository);
    }
}
