package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.StudyMembershipReplicaRepository;
import com.backtoback.contest.global.error.ContestException;

/** 시험 목록의 권한과 요청 검증을 확인한다. */
@ExtendWith(MockitoExtension.class)
class ExamQueryServiceTest {
    @Mock
    private ExamRepository exams;
    @Mock
    private ExamParticipantRepository participants;
    @Mock
    private ExamProblemRepository problems;
    @Mock
    private StudyMembershipReplicaRepository memberships;
    @InjectMocks
    private ExamQueryService service;

    /** 구성원은 필터와 페이지 정보를 유지해 전체 시험을 조회한다. */
    @Test
    @DisplayName("승인된 구성원은 상태와 정렬을 적용한 목록을 조회한다")
    void approvedMemberListsExams() {
        // given
        given(memberships.isActive(12, 7)).willReturn(true);
        given(exams.findVisible(eq(12L), eq(7L), eq(true), eq(ExamStatus.SCHEDULED), any(Pageable.class)))
            .willReturn(Page.empty());
        // when
        var response = service.list("12", "7", "SCHEDULED", 0, 20, "startsAt,desc");
        // then
        assertThat(response.content()).isEmpty();
        verify(exams).findVisible(eq(12L), eq(7L), eq(true), eq(ExamStatus.SCHEDULED), any(Pageable.class));
    }

    /** 취소 이력만 있을 때 활성 참가 조회는 비어 있으며 목록 상태도 null이다. */
    @Test
    @DisplayName("구성원의 취소된 참가만 있으면 목록 참가 상태는 null이다")
    void canceledParticipationIsNotShown() {
        // given
        Exam exam = mock(Exam.class);
        given(exam.getId()).willReturn(55L);
        given(exam.getStatus()).willReturn(ExamStatus.SCHEDULED);
        given(exam.getStartsAt()).willReturn(LocalDateTime.of(2026, 11, 7, 10, 0));
        given(exam.getEndsAt()).willReturn(LocalDateTime.of(2026, 11, 7, 11, 0));
        given(memberships.isActive(12, 7)).willReturn(true);
        given(exams.findVisible(eq(12L), eq(7L), eq(true), eq(null), any(Pageable.class)))
            .willReturn(new PageImpl<>(List.of(exam)));
        given(participants.findByExamIdAndMemberIdAndStatusNot(55L, 7L, ExamParticipantStatus.CANCELED))
            .willReturn(Optional.empty());
        // when
        var response = service.list("12", "7", null, 0, 20, "startsAt,desc");
        // then
        assertThat(response.content()).singleElement().extracting("myParticipantStatus").isNull();
    }

    /** 탈퇴자는 활성 참가가 있을 때 제한된 범위로 조회한다. */
    @Test
    @DisplayName("탈퇴한 활성 참가자는 본인 참가 시험으로 제한해 조회한다")
    void departedParticipantListsVisibleExams() {
        // given
        given(participants.hasActiveInStudy(12, 7)).willReturn(true);
        given(exams.findVisible(eq(12L), eq(7L), eq(false), eq(null), any(Pageable.class))).willReturn(Page.empty());
        // when
        service.list("12", "7", null, 1, 10, "createdAt,asc");
        // then
        verify(exams).findVisible(eq(12L), eq(7L), eq(false), eq(null), any(Pageable.class));
    }

    /** 참가가 없거나 취소만 남은 탈퇴자는 조회할 수 없다. */
    @Test
    @DisplayName("활성 참가가 없는 탈퇴자의 목록 조회를 거절한다")
    void deniesWithoutActiveParticipation() {
        // when & then
        assertThatThrownBy(() -> service.list("12", "7", null, 0, 20, "startsAt,desc"))
            .isInstanceOf(ContestException.class)
            .extracting("errorCode")
            .isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED);
        verifyNoInteractions(exams);
    }

    /** 허용되지 않은 목록 입력은 저장소 조회 전에 거절한다. */
    @Test
    @DisplayName("잘못된 식별자와 필터, 페이지와 정렬을 거절한다")
    void rejectsInvalidParameters() {
        // when & then
        assertThatThrownBy(() -> service.list("0", "7", null, 0, 20, "startsAt,desc"))
            .isInstanceOf(ContestException.class);
        assertThatThrownBy(() -> service.list("12", "7", "WRONG", 0, 20, "startsAt,desc"))
            .isInstanceOf(ContestException.class);
        assertThatThrownBy(() -> service.list("12", "7", null, -1, 20, "startsAt,desc"))
            .isInstanceOf(ContestException.class);
        assertThatThrownBy(() -> service.list("12", "7", null, 0, 101, "startsAt,desc"))
            .isInstanceOf(ContestException.class);
        assertThatThrownBy(() -> service.list("12", "7", null, 0, 20, "title,asc"))
            .isInstanceOf(ContestException.class);
        verifyNoInteractions(exams, memberships, participants, problems);
    }
}
