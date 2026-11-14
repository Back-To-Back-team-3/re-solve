package com.backtoback.study.membership.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;

/**
 * PENDING 신청 1건 만료 처리 서비스의 정상·스킵 시나리오를 검증한다(스케줄러 명세서 §5.1).
 */
@ExtendWith(MockitoExtension.class)
class StudyMembershipExpiryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final Instant NOW_INSTANT = NOW.toInstant(ZoneOffset.of("+09:00"));
    private static final Long MEMBERSHIP_ID = 1L;
    private static final Long STUDY_ID = 10L;
    private static final Long MEMBER_ID = 20L;
    private static final String CORR_ID = "sched-StudyApplicationExpiryScheduler-test-uuid";

    @Mock
    private StudyMembershipRepository membershipRepository;
    @Mock
    private StudyMembershipHistoryRepository historyRepository;
    @Mock
    private StudyRepository studyRepository;
    @Mock
    private OutboxEventWriter outboxEventWriter;
    @Mock
    private Clock clock;

    @InjectMocks
    private StudyMembershipExpiryService service;

    private StudyMembership membership;
    private Study study;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(NOW_INSTANT);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.of("+09:00"));

        membership
            = StudyMembership
                .apply(
                    STUDY_ID,
                    MEMBER_ID,
                    MembershipRole.MEMBER,
                    MembershipStatus.PENDING,
                    NOW.minusDays(8),
                    NOW.minusDays(1),
                    null
                );
        ReflectionTestUtils.setField(membership, "id", MEMBERSHIP_ID);

        study = Study.recruit("테스트 스터디", "설명", "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.APPROVAL, false);
        ReflectionTestUtils.setField(study, "id", STUDY_ID);
    }

    @Test
    @DisplayName("만료 시각이 지난 PENDING 신청은 EXPIRED로 전이하고 이력·이벤트를 기록한다")
    void expire_pendingMembership_createsHistoryAndOutbox() {
        given(membershipRepository.expireIfPending(MEMBERSHIP_ID)).willReturn(1);
        given(membershipRepository.findById(MEMBERSHIP_ID)).willReturn(Optional.of(membership));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));

        service.expire(MEMBERSHIP_ID, CORR_ID);

        verify(historyRepository).save(any());
        verify(outboxEventWriter)
            .append(
                eq("study_membership"),
                eq("StudyApplicationDecided"),
                eq("v1"),
                eq(String.valueOf(MEMBERSHIP_ID)),
                eq(CORR_ID),
                any()
            );
    }

    @Test
    @DisplayName("expireIfPending이 0을 반환하면 이력·이벤트 없이 즉시 반환한다")
    void expire_alreadyChanged_skipsHistoryAndOutbox() {
        given(membershipRepository.expireIfPending(MEMBERSHIP_ID)).willReturn(0);

        service.expire(MEMBERSHIP_ID, CORR_ID);

        verify(historyRepository, never()).save(any());
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }
}
