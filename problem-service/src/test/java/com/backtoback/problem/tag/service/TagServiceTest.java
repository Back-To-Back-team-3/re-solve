package com.backtoback.problem.tag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private TagCreationWriter creationWriter;
    @InjectMocks
    private TagService service;

    /** 완료된 요청은 공통 잠금을 다시 획득하지 않는다. */
    @Test
    @DisplayName("완료된 재요청은 공통 잠금 없이 최초 응답을 반환한다")
    void replaysWithoutLock() {
        var expected = new TagResponse("1", "태그", 0);
        given(creationRecords.replay("admin", "key", "태그", 10)).willReturn(Optional.of(expected));
        assertThat(service.create("admin", "key", new TagCreateRequest("태그", 10))).isEqualTo(expected);
        verifyNoInteractions(tagOrderService, tagRepository, creationWriter);
    }

    /** 사전 조회가 끝난 뒤 쓰기 서비스에 위임한다. */
    @Test
    @DisplayName("완료 기록이 없으면 사전 조회 이후 쓰기 서비스로 등록을 위임한다")
    void writesAfterReplay() {
        var request = new TagCreateRequest("태그", 10);
        var expected = new TagResponse("1", "태그", 0);
        given(creationWriter.create("admin", "key", request)).willReturn(expected);
        assertThat(service.create("admin", "key", request)).isEqualTo(expected);
        var order = inOrder(creationRecords, creationWriter);
        order.verify(creationRecords).replay("admin", "key", "태그", 10);
        order.verify(creationWriter).create("admin", "key", request);
        verifyNoInteractions(tagOrderService, tagRepository);
    }
}
