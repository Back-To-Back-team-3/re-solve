package com.backtoback.study.global.config;

import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.config.OutboxRelayConfiguration;
import com.backtoback.common.event.dto.EventDestination;

import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Study Outbox 이벤트를 공통 Relay로 {@code study-events} SNS 토픽에 발행한다 (이벤트 계약서 §2.1, §3 S1~S9).
 * 발행 설정을 켠 경우에만 등록하며, 꺼져 있으면 Outbox 저장만 하고 발행하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("study.events.publish-enabled")
@Import(OutboxRelayConfiguration.class)
public class StudyOutboxConfiguration {
    private static final String SCHEMA_VERSION = "v1";
    private static final Set<String> TOPIC_EVENTS
        = Set
            .of(
                "StudyMemberJoined",
                "StudyMemberLeft",
                "StudyApplicationDecided",
                "StudyClosed",
                "AssignmentPublished",
                "AssignmentDeadlineApproaching",
                "AssignmentIncompleteRepeated",
                "HintReady",
                "HintRequested"
            );

    @Bean
    SnsClient studySnsClient(AwsClientBuilderConfigurer configurer) {
        return configurer.configure(SnsClient.builder()).build();
    }

    /**
     * 계약에 정의된 Study 이벤트만 SNS 토픽으로 보낸다.
     * 계약 밖 이벤트는 거절해 Relay 재시도 후 {@code FAILED}로 남긴다 (스케줄러 명세서 §0.2.1).
     *
     * @param properties 검증된 발행 설정
     * @return Study 이벤트 발행 목적지 선택기
     */
    @Bean
    EventDestinationResolver studyEventDestinationResolver(StudyOutboxProperties properties) {
        var destination = new EventDestination(EventDestination.Channel.SNS, properties.topicArn());
        return event -> {
            var metadata = event.metadata();
            if (!SCHEMA_VERSION.equals(metadata.schemaVersion()) || !TOPIC_EVENTS.contains(metadata.eventType())) {
                throw new IllegalArgumentException("지원하지 않는 Study 발행 이벤트입니다.");
            }
            return destination;
        };
    }
}
