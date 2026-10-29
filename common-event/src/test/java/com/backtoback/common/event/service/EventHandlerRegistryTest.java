package com.backtoback.common.event.service;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.ReceivedEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * 이벤트 종류가 같아도 지원 버전별로 정확한 업무 핸들러를 선택하는지 검증한다.
 * 동일 등록·모르는 종류·아는 종류의 미지원 버전을 구분한다.
 */
@ExtendWith(MockitoExtension.class)
class EventHandlerRegistryTest {
    @Mock
    private EventHandler firstHandler;

    @Mock
    private EventHandler secondHandler;

    /**
     * v1·v2가 함께 등록된 상태에서 수신 버전과 일치하는 처리기만 호출되는지 확인한다.
     */
    @Test
    @DisplayName("이벤트 종류가 같아도 schemaVersion과 일치하는 핸들러만 실행한다")
    void dispatchesToMatchingSchemaVersion() {
        // given
        configureHandler(firstHandler, "SubmissionJudged", "v1");
        configureHandler(secondHandler, "SubmissionJudged", "v2");
        var registry = new EventHandlerRegistry(List.of(firstHandler, secondHandler));
        var event = event("SubmissionJudged", "v2");

        // when
        registry.dispatch(event);

        // then
        verify(secondHandler).handle(event);
        verify(firstHandler, never()).handle(event);
    }

    /**
     * 핸들러 목록 순서로 선택이 달라질 수 있는 중복 키를 구성 시점에 거절한다.
     */
    @Test
    @DisplayName("같은 이벤트 종류와 버전의 핸들러를 두 번 등록하면 거절한다")
    void rejectsDuplicateHandlerKeys() {
        // given
        configureHandler(firstHandler, "SubmissionJudged", "v1");
        configureHandler(secondHandler, "SubmissionJudged", "v1");

        // when & then
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new EventHandlerRegistry(List.of(firstHandler, secondHandler)));
    }

    /**
     * 모르는 종류는 업무 처리 없이 반환해 호출자가 소비 기록을 커밋할 수 있어야 한다.
     */
    @Test
    @DisplayName("모르는 이벤트 종류는 등록된 다른 종류의 업무 핸들러를 실행하지 않는다")
    void ignoresUnknownEventTypes() {
        // given
        configureHandler(firstHandler, "SubmissionJudged", "v1");
        var registry = new EventHandlerRegistry(List.of(firstHandler));
        var event = event("MemberUpdated", "v1");

        // when
        registry.dispatch(event);

        // then
        verify(firstHandler, never()).handle(event);
    }

    /**
     * 같은 종류라고 해서 지원하지 않는 메시지를 이전 버전 핸들러로 실행하지 않는지 확인한다.
     */
    @Test
    @DisplayName("등록된 종류의 미지원 버전은 다른 버전 핸들러로 처리하지 않고 실패한다")
    void rejectsUnsupportedVersionForKnownType() {
        // given
        configureHandler(firstHandler, "SubmissionJudged", "v1");
        var registry = new EventHandlerRegistry(List.of(firstHandler));
        var event = event("SubmissionJudged", "v2");

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> registry.dispatch(event));
        verify(firstHandler, never()).handle(event);
    }

    /**
     * 종류·버전별 테스트 시나리오에 필요한 핸들러 등록 정보만 지정한다.
     *
     * @param handler 등록할 Mock 핸들러
     * @param eventType 테스트 이벤트 종류
     * @param version 테스트 형식 버전
     */
    private void configureHandler(EventHandler handler, String eventType, String version) {
        given(handler.eventType()).willReturn(eventType);
        given(handler.schemaVersion()).willReturn(version);
    }

    /**
     * dispatch만 검증하도록 외부 DB와 무관한 수신 모델을 만든다.
     *
     * @param eventType dispatch할 종류
     * @param version dispatch할 메시지 형식 버전
     * @return 빈 업무 필드와 공통 메타데이터를 가진 수신 모델
     */
    private ReceivedEvent event(String eventType, String version) {
        var metadata
            = new EventMetadata(
                UUID.randomUUID(),
                eventType,
                version,
                OffsetDateTime.parse("2026-10-29T21:00:00.123456+09:00"),
                "55",
                "request-test"
            );
        return new ReceivedEvent(metadata, new ObjectMapper().createObjectNode());
    }
}
