package com.backtoback.contest.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
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
import org.springframework.jdbc.core.JdbcTemplate;

import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.contest.admin.dto.ExamRetryRequest;
import com.backtoback.contest.exam.client.ExamDependencyClient;
import com.backtoback.contest.exam.client.ExamDependencyClient.StudyMember;
import com.backtoback.contest.exam.client.ExamDependencyClient.StudyMembers;
import com.backtoback.contest.exam.client.ExamRetryClient;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.service.MemberReplicaService;
import com.backtoback.contest.global.error.ContestException;

import tools.jackson.databind.ObjectMapper;

/** 시험 운영 권한과 작업 소유자 검증을 확인한다. */
@ExtendWith(MockitoExtension.class)
class ExamRetryServiceTest {
    @Mock
    private ExamRepository exams;
    @Mock
    private ExamDependencyClient dependencies;
    @Mock
    private MemberReplicaService members;
    @Mock
    private ExamRetryStore store;
    @Mock
    private ExamRetryClient client;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private ObjectMapper mapper;
    @Mock
    private Exam exam;
    @InjectMocks
    private ExamRetryService service;

    @Test
    @DisplayName("현재 운영자는 같은 시험의 본인 재처리 작업을 조회한다")
    void operatorCanReadOwnOperation() {
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(exam.getStudyId()).willReturn(12L);
        given(members.refresh(7)).willReturn("ACTIVE");
        given(dependencies.study(12))
            .willReturn(new StudyMembers("12", "ACTIVE", List.of(new StudyMember("7", "1", "MANAGER"))));
        given(store.require("op"))
            .willReturn(
                new ExamRetryStore.Operation(
                    "op",
                    "EXAM_RETRY",
                    "EXAM",
                    55,
                    4001L,
                    "hash",
                    1,
                    null,
                    "PENDING",
                    7,
                    "재처리",
                    null,
                    LocalDateTime.now()
                )
            );

        var result = service.examOperation("55", "op", new GatewayUser("7", UserRole.USER));

        assertThat(result.operationId()).isEqualTo("op");
        assertThat(result.contextId()).isEqualTo("55");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("현재 운영자가 아니면 실패 접수 재처리 작업을 만들지 않는다")
    void deniesFormerOperator() {
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(exam.getStudyId()).willReturn(12L);
        given(members.refresh(7)).willReturn("ACTIVE");
        given(dependencies.study(12))
            .willReturn(new StudyMembers("12", "ACTIVE", List.of(new StudyMember("7", "1", "MEMBER"))));

        assertThatThrownBy(
            () -> service
                .request("55", "4001", new GatewayUser("7", UserRole.USER), new ExamRetryRequest("op", 1, "재처리"))
        ).isInstanceOf(ContestException.class);
        verifyNoInteractions(store, client);
    }

    @Test
    @DisplayName("현재 운영자라도 다른 사람이 요청한 작업을 조회하지 못한다")
    void deniesAnotherOperatorsOperation() {
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(exam.getStudyId()).willReturn(12L);
        given(members.refresh(7)).willReturn("ACTIVE");
        given(dependencies.study(12))
            .willReturn(new StudyMembers("12", "ACTIVE", List.of(new StudyMember("7", "1", "MANAGER"))));
        given(store.require("op"))
            .willReturn(
                new ExamRetryStore.Operation(
                    "op",
                    "EXAM_RETRY",
                    "EXAM",
                    55,
                    4001L,
                    "hash",
                    1,
                    null,
                    "PENDING",
                    8,
                    "재처리",
                    null,
                    LocalDateTime.now()
                )
            );

        assertThatThrownBy(() -> service.examOperation("55", "op", new GatewayUser("7", UserRole.USER)))
            .isInstanceOf(ContestException.class);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("현재 운영자는 다른 시험의 본인 작업을 조회하지 못한다")
    void deniesAnotherExamsOperation() {
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(exam.getStudyId()).willReturn(12L);
        given(members.refresh(7)).willReturn("ACTIVE");
        given(dependencies.study(12))
            .willReturn(new StudyMembers("12", "ACTIVE", List.of(new StudyMember("7", "1", "LEADER"))));
        given(store.require("op"))
            .willReturn(
                new ExamRetryStore.Operation(
                    "op",
                    "EXAM_RETRY",
                    "EXAM",
                    56,
                    4001L,
                    "hash",
                    1,
                    null,
                    "PENDING",
                    7,
                    "재처리",
                    null,
                    LocalDateTime.now()
                )
            );

        assertThatThrownBy(() -> service.examOperation("55", "op", new GatewayUser("7", UserRole.USER)))
            .isInstanceOf(ContestException.class);
        verifyNoInteractions(client);
    }
}
