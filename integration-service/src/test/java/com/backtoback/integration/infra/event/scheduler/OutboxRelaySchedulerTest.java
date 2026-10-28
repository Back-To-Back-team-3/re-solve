package com.backtoback.integration.infra.event.scheduler;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.integration.infra.event.service.OutboxRelayService;

@ExtendWith(MockitoExtension.class)
class OutboxRelaySchedulerTest {

    @Mock
    private OutboxRelayService outboxRelayService;

    @InjectMocks
    private OutboxRelayScheduler outboxRelayScheduler;

    @Test
    @DisplayName("runRelay 호출 시 outboxRelayService.relayPendingEvents가 실행된다")
    void runRelaySuccess() {
        outboxRelayScheduler.runRelay();

        verify(outboxRelayService).relayPendingEvents();
    }
}
