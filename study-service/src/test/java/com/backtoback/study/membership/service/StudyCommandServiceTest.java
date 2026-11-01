package com.backtoback.study.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.dto.StudyCreateRequest;
import com.backtoback.study.membership.repository.StudyMemberQuotaRepository;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

/**
 * 스터디 생성 정상·예외 시나리오를 검증한다(API 명세서 §5.2.1).
 */
@ExtendWith(MockitoExtension.class)
class StudyCommandServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final Instant NOW_INSTANT = NOW.toInstant(ZoneOffset.of("+09:00"));

    @Mock
    private MemberReplicaRepository memberReplicaRepository;
    @Mock
    private StudyMemberQuotaRepository quotaRepository;
    @Mock
    private StudyRepository studyRepository;
    @Mock
    private StudyMembershipRepository membershipRepository;
    @Mock
    private StudyMembershipHistoryRepository historyRepository;
    @Mock
    private OutboxEventWriter outboxEventWriter;
    @Mock
    private Clock clock;

    @InjectMocks
    private StudyCommandService studyCommandService;

    private StudyCreateRequest request;

    @BeforeEach
    void setUp() {
        request
            = new StudyCreateRequest(
                "코딩테스트 스터디",
                "설명",
                "EMPLOYMENT",
                "LV2",
                "java",
                "EVENING",
                5,
                JoinType.INSTANT,
                false
            );
    }

    @Test
    @DisplayName("정상 요청이면 스터디를 생성하고 studyId를 반환한다")
    void createStudy_success() {
        // given
        given(memberReplicaRepository.findByMemberId(1L)).willReturn(Optional.empty());
        given(quotaRepository.upsertActiveCount(1L)).willReturn(1);
        given(clock.instant()).willReturn(NOW_INSTANT);

        given(studyRepository.save(any(Study.class))).willAnswer(inv -> {
            ReflectionTestUtils.setField((Study)inv.getArgument(0), "id", 10L);
            return inv.getArgument(0);
        });
        given(membershipRepository.save(any(StudyMembership.class))).willAnswer(inv -> {
            ReflectionTestUtils.setField((StudyMembership)inv.getArgument(0), "id", 20L);
            return inv.getArgument(0);
        });
        given(historyRepository.save(any())).willReturn(null);

        // when
        Long result = studyCommandService.createStudy(1L, request, "corr-1");

        // then
        assertThat(result).isEqualTo(10L);
        verify(outboxEventWriter)
            .append(anyString(), eq("StudyMemberJoined"), anyString(), eq("20"), eq("corr-1"), any());
    }

    @Test
    @DisplayName("SUSPENDED 회원이면 MEMBER_SUSPENDED 예외를 던진다")
    void createStudy_suspended_throwsMemberSuspended() {
        // given
        MemberReplica replica = MemberReplica.sync(1L, "user1", null, MemberStatus.SUSPENDED, 1L, NOW);
        given(memberReplicaRepository.findByMemberId(1L)).willReturn(Optional.of(replica));

        // when & then
        assertThatThrownBy(() -> studyCommandService.createStudy(1L, request, "corr-1"))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.MEMBER_SUSPENDED)
            );
    }

    @Test
    @DisplayName("참여 상한 초과이면 STUDY_JOIN_LIMIT_EXCEEDED 예외를 던진다")
    void createStudy_quotaExceeded_throwsLimitExceeded() {
        // given
        given(memberReplicaRepository.findByMemberId(1L)).willReturn(Optional.empty());
        given(quotaRepository.upsertActiveCount(1L)).willReturn(0);

        // when & then
        assertThatThrownBy(() -> studyCommandService.createStudy(1L, request, "corr-1"))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_JOIN_LIMIT_EXCEEDED)
            );
    }
}
