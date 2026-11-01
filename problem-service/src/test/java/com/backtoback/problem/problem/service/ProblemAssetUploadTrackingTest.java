package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.problem.problem.domain.ProblemAssetStatus;
import com.backtoback.problem.problem.domain.ProblemBodyAsset;
import com.backtoback.problem.problem.domain.ProblemTestAsset;
import com.backtoback.problem.problem.repository.ProblemBodyAssetRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/** 추적 시작, 결과 불명과 잠금 후 만료 처리를 확인한다. */
@ExtendWith(MockitoExtension.class)
class ProblemAssetUploadTrackingTest {
    @Mock
    private ProblemBodyAssetRepository bodies;
    @Mock
    private ProblemTestAssetRepository tests;

    /** 추적 파일의 키와 종료 미확인 상태가 유지되는지 확인한다. */
    @Test
    @DisplayName("본문 추적 시작 후 결과가 불명확하면 종료 시각을 기록하지 않는다")
    void preservesUnknownBody() {
        // given
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
        given(bodies.saveAndFlush(any(ProblemBodyAsset.class))).willAnswer(invocation -> {
            ProblemBodyAsset asset = invocation.getArgument(0);
            ReflectionTestUtils.setField(asset, "id", 1L);
            return asset;
        });
        var tracking = new ProblemAssetUploadTracking(bodies, tests, clock, null);
        var content
            = ProblemAssetContent
                .fromBytes(
                    new byte[] {
                        1
                    }
                );

        // when
        var ticket = tracking.start(1L, content, true);
        var asset
            = ProblemBodyAsset.create(1L, ticket.key(), null, content.sha256(), content.sizeBytes(), ticket.expiry());
        given(bodies.findByIdForUpdate(1L)).willReturn(Optional.of(asset));
        tracking.recordUnknown(ticket);

        // then
        assertThat(ticket.id()).isEqualTo(1L);
        assertThat(ticket.key()).startsWith("problem-body/");
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
        assertThat(asset.getUploadFinishedAt()).isNull();
        assertThat(asset.getFailureCode()).isEqualTo("UPLOAD_UNCONFIRMED");
    }

    /** 본문 행 잠금 획득 전후의 시각이 달라지는 상황을 확인한다. */
    @Test
    @DisplayName("본문 파일 잠금을 기다리는 동안 만료되면 완료 대신 폐기한다")
    void bodyExpiresWhileWaitingForLock() {
        // given
        verifyExpiryAfterLock(true);
    }

    /** 테스트 행도 잠금 이후의 시각으로 만료를 판단하는지 확인한다. */
    @Test
    @DisplayName("테스트 파일 잠금을 기다리는 동안 만료되면 완료 대신 폐기한다")
    void testExpiresWhileWaitingForLock() {
        // given
        verifyExpiryAfterLock(false);
    }

    /** 잠금 조회가 반환되기 직전에 시계를 만료 시각으로 전진시킨다. */
    private void verifyExpiryAfterLock(boolean body) {
        Clock clock = mock(Clock.class);
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        Instant expiryInstant = Instant.parse("2026-11-02T00:00:00Z");
        LocalDateTime expiry = LocalDateTime.ofInstant(expiryInstant, ZoneOffset.UTC);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        var current = new AtomicReference<>(expiryInstant.minusSeconds(1));
        given(clock.instant()).willAnswer(invocation -> current.get());
        given(entityManager.createNativeQuery(any(String.class))).willReturn(query);
        given(query.setParameter("id", 1L)).willReturn(query);
        given(query.getSingleResult()).willReturn(0L);
        var tracking = new ProblemAssetUploadTracking(bodies, tests, clock, entityManager);
        var ticket = new ProblemAssetUploadTracking.UploadTicket(1L, "key", expiry, body);
        var bodyAsset = ProblemBodyAsset.create(1L, "key", null, "a".repeat(64), 1L, expiry);
        var testAsset = ProblemTestAsset.create(1L, "key", null, "a".repeat(64), 1L, expiry);
        if (body) {
            given(bodies.findByIdForUpdate(1L)).willAnswer(invocation -> {
                current.set(expiryInstant);
                return Optional.of(bodyAsset);
            });
        } else {
            given(tests.findByIdForUpdate(1L)).willAnswer(invocation -> {
                current.set(expiryInstant);
                return Optional.of(testAsset);
            });
        }

        // when
        boolean completed = tracking.complete(ticket, null);

        // then
        assertThat(completed).isFalse();
        if (body) {
            assertThat(bodyAsset.getStatus()).isEqualTo(ProblemAssetStatus.DISCARDED);
            assertThat(bodyAsset.getUploadFinishedAt()).isEqualTo(expiry);
        } else {
            assertThat(testAsset.getStatus()).isEqualTo(ProblemAssetStatus.DISCARDED);
            assertThat(testAsset.getUploadFinishedAt()).isEqualTo(expiry);
        }
    }

    /** 쓰기 전 만료 파일도 파일 잠금과 참조 검사를 거쳐 폐기한다. */
    @Test
    @DisplayName("쓰기 전 만료된 본문 파일은 종료 시각과 함께 폐기한다")
    void discardsExpiredBeforeWrite() {
        // given
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 0, 0);
        var asset = ProblemBodyAsset.create(1L, "key", null, "a".repeat(64), 1L, expiry);
        var ticket = new ProblemAssetUploadTracking.UploadTicket(1L, "key", expiry, true);
        given(bodies.findByIdForUpdate(1L)).willReturn(Optional.of(asset));
        given(entityManager.createNativeQuery(any(String.class))).willReturn(query);
        given(query.setParameter("id", 1L)).willReturn(query);
        given(query.getSingleResult()).willReturn(0L);
        var tracking = new ProblemAssetUploadTracking(bodies, tests, clock, entityManager);

        // when
        tracking.discardBeforeWrite(ticket);

        // then
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.DISCARDED);
        assertThat(asset.getUploadedAt()).isNull();
        assertThat(asset.getUploadFinishedAt()).isEqualTo(expiry);
        assertThat(asset.getFailureCode()).isEqualTo("UPLOAD_EXPIRED");
    }

    /** 테스트 파일의 미시작 만료도 동일한 보호 조건을 적용한다. */
    @Test
    @DisplayName("쓰기 전 만료된 테스트 파일은 종료 시각과 함께 폐기한다")
    void discardsExpiredTestBeforeWrite() {
        // given
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 0, 0);
        var asset = ProblemTestAsset.create(1L, "key", null, "a".repeat(64), 1L, expiry);
        var ticket = new ProblemAssetUploadTracking.UploadTicket(1L, "key", expiry, false);
        given(tests.findByIdForUpdate(1L)).willReturn(Optional.of(asset));
        given(entityManager.createNativeQuery(any(String.class))).willReturn(query);
        given(query.setParameter("id", 1L)).willReturn(query);
        given(query.getSingleResult()).willReturn(0L);
        var tracking = new ProblemAssetUploadTracking(bodies, tests, clock, entityManager);

        // when
        tracking.discardBeforeWrite(ticket);

        // then
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.DISCARDED);
        assertThat(asset.getUploadedAt()).isNull();
        assertThat(asset.getUploadFinishedAt()).isEqualTo(expiry);
    }

    /** 본문에 회차나 이미지 참조가 남아 있으면 폐기하지 않는다. */
    @Test
    @DisplayName("만료된 본문 파일에 참조가 있으면 폐기를 거절한다")
    void rejectsReferencedBody() {
        // given
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 0, 0);
        var asset = ProblemBodyAsset.create(1L, "key", null, "a".repeat(64), 1L, expiry);
        var ticket = new ProblemAssetUploadTracking.UploadTicket(1L, "key", expiry, true);
        given(bodies.findByIdForUpdate(1L)).willReturn(Optional.of(asset));
        given(entityManager.createNativeQuery(any(String.class))).willReturn(query);
        given(query.setParameter("id", 1L)).willReturn(query);
        given(query.getSingleResult()).willReturn(1L);
        var tracking = new ProblemAssetUploadTracking(bodies, tests, clock, entityManager);

        // when & then
        assertThatThrownBy(() -> tracking.discardBeforeWrite(ticket)).isInstanceOf(IllegalStateException.class);
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
        assertThat(asset.getUploadFinishedAt()).isNull();
    }

    /** 테스트 파일에 회차 참조가 남아 있으면 폐기하지 않는다. */
    @Test
    @DisplayName("만료된 테스트 파일에 참조가 있으면 폐기를 거절한다")
    void rejectsReferencedTest() {
        // given
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 0, 0);
        var asset = ProblemTestAsset.create(1L, "key", null, "a".repeat(64), 1L, expiry);
        var ticket = new ProblemAssetUploadTracking.UploadTicket(1L, "key", expiry, false);
        given(tests.findByIdForUpdate(1L)).willReturn(Optional.of(asset));
        given(entityManager.createNativeQuery(any(String.class))).willReturn(query);
        given(query.setParameter("id", 1L)).willReturn(query);
        given(query.getSingleResult()).willReturn(1L);
        var tracking = new ProblemAssetUploadTracking(bodies, tests, clock, entityManager);

        // when & then
        assertThatThrownBy(() -> tracking.discardBeforeWrite(ticket)).isInstanceOf(IllegalStateException.class);
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
        assertThat(asset.getUploadFinishedAt()).isNull();
    }

}
