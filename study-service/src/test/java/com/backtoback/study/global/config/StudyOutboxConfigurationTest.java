package com.backtoback.study.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.transaction.PlatformTransactionManager;

import com.backtoback.common.event.client.AwsEventPublisher;
import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.OutboxEvent;
import com.backtoback.common.event.service.OutboxRelay;

import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StudyOutboxConfigurationTest {
    private static final String TOPIC_ARN = "arn:aws:sns:ap-northeast-2:000000000000:study-events";

    @Mock
    private SnsClient sns;

    @ParameterizedTest
    @ValueSource(
        strings = {
            "StudyMemberJoined",
            "StudyMemberLeft",
            "StudyApplicationDecided",
            "StudyClosed",
            "AssignmentPublished",
            "AssignmentDeadlineApproaching",
            "AssignmentIncompleteRepeated",
            "HintReady",
            "HintRequested"
        }
    )
    @DisplayName("계약에 정의된 Study 이벤트는 study-events SNS 토픽에 원본 그대로 발행한다")
    void publishesStudyEvent(String eventType) {
        var event = event(eventType, "v1");

        new AwsEventPublisher(sns, null, resolver()).publish(event);

        var request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(request.capture());
        assertThat(request.getValue().topicArn()).isEqualTo(TOPIC_ARN);
        assertThat(request.getValue().message()).isEqualTo(event.message());
        assertThat(request.getValue().messageAttributes().get("eventType").stringValue()).isEqualTo(eventType);
    }

    @Test
    @DisplayName("계약에 없는 이벤트는 발행 목적지를 선택하지 않고 거절한다")
    void rejectsUnsupportedEvent() {
        var resolver = resolver();

        assertThatThrownBy(() -> resolver.resolve(event("UnknownEvent", "v1")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("계약 이벤트라도 정의되지 않은 스키마 버전이면 거절한다")
    void rejectsUnsupportedSchemaVersion() {
        var resolver = resolver();

        assertThatThrownBy(() -> resolver.resolve(event("StudyMemberJoined", "v2")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("발행을 켰는데 토픽 ARN이 비어 있으면 설정 검증에 실패한다")
    void rejectsEnabledWithoutTopic() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(new StudyOutboxProperties(true, ""))).isNotEmpty();
        assertThat(validator.validate(new StudyOutboxProperties(true, null))).isNotEmpty();
    }

    @Test
    @DisplayName("발행을 껐거나 토픽 ARN이 있으면 설정 검증을 통과한다")
    void acceptsDisabledOrConfiguredTopic() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(new StudyOutboxProperties(false, ""))).isEmpty();
        assertThat(validator.validate(new StudyOutboxProperties(true, TOPIC_ARN))).isEmpty();
    }

    @Test
    @DisplayName("발행을 끄면 Outbox Relay와 발행 설정을 등록하지 않는다")
    void skipsRelayWhenDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(StudyOutboxConfiguration.class)
            .withPropertyValues("study.events.publish-enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(StudyOutboxConfiguration.class);
                assertThat(context).doesNotHaveBean(OutboxRelay.class);
            });
    }

    /**
     * 예약 실행기를 Mock으로 대체해 컨텍스트 기동 중 DB 조회나 AWS 발행이 시작되지 않도록 한다.
     */
    @Test
    @DisplayName("발행을 켜고 토픽 ARN을 지정하면 Outbox Relay와 SNS 발행 설정을 등록한다")
    void registersRelayWhenEnabled() {
        var taskScheduler = mock(TaskScheduler.class);
        var configurer = mock(AwsClientBuilderConfigurer.class);
        given(taskScheduler.getClock()).willReturn(Clock.systemUTC());
        given(configurer.configure(any(SnsClientBuilder.class)))
            .willAnswer(
                invocation -> invocation
                    .<SnsClientBuilder>getArgument(0)
                    .region(Region.AP_NORTHEAST_2)
                    .credentialsProvider(AnonymousCredentialsProvider.create())
            );

        new ApplicationContextRunner()
            .withUserConfiguration(StudyOutboxConfiguration.class)
            .withPropertyValues("study.events.publish-enabled=true")
            .withBean(StudyOutboxProperties.class, () -> new StudyOutboxProperties(true, TOPIC_ARN))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(AwsClientBuilderConfigurer.class, () -> configurer)
            .withBean("taskScheduler", TaskScheduler.class, () -> taskScheduler)
            .withBean(SchedulingConfigurer.class, () -> tasks -> tasks.setTaskScheduler(taskScheduler))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(OutboxRelay.class);
                assertThat(context).hasSingleBean(SnsClient.class);
                assertThat(context).hasSingleBean(EventDestinationResolver.class);
            });
    }

    private EventDestinationResolver resolver() {
        return new StudyOutboxConfiguration().studyEventDestinationResolver(new StudyOutboxProperties(true, TOPIC_ARN));
    }

    private OutboxEvent event(String eventType, String schemaVersion) {
        var metadata
            = new EventMetadata(
                UUID.randomUUID(),
                eventType,
                schemaVersion,
                OffsetDateTime.parse("2026-11-17T00:00:00+09:00"),
                "7",
                "test"
            );
        return new OutboxEvent(1L, "STUDY_MEMBERSHIP", metadata, "{}", 0);
    }
}
