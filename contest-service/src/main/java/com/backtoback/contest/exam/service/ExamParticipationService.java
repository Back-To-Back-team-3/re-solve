package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantHistory;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.ExamCancellationResponse;
import com.backtoback.contest.exam.dto.ExamEntryResponse;
import com.backtoback.contest.exam.dto.ExamParticipationResponse;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.exam.repository.ExamParticipantHistoryRepository;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/**
 * 시험 참가의 등록·취소·입장과 이력을 처리하며 등록·취소 이벤트를 같은 트랜잭션에 저장한다.
 * 원격 회원 상태 조회는 DB 잠금을 유지하지 않는 동안 수행한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §4.2.1 시험 참가 등록·§4.2.2 시험 입장·§4.2.10 시험 참가 취소: 권한과 응답</li>
 * <li>도메인 및 데이터베이스 v1.0 / §6.4.1 접수·마감·자동 제출: 자원·시험·참가자 잠금 순서</li>
 * <li>이벤트 계약서 v1.0 / §4.4 contest: 참가 등록·취소 이벤트와 Outbox 원자성</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ExamParticipationService {
    private final MemberReplicaService memberRefresh;
    private final MemberReplicaRepository members;
    private final ContestResourceGuardService guards;
    private final ExamEligibilityService eligibility;
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final ExamParticipantHistoryRepository histories;
    private final OutboxEventWriter outbox;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 원격 회원 조회는 자원 잠금 전에 수행한다 (ERD 6.4.1).
     *
     * @param examId 시험 식별자 문자열
     * @param memberId 인증된 회원 식별자 문자열
     * @return 참가 응답과 신규 생성 여부
     * @throws ContestException 인증·자격·상태 또는 시간 조건이 유효하지 않을 때
     */
    public Registration register(String examId, String memberId) {
        long exam = id(examId);
        long member = id(memberId);
        memberRefresh.refresh(member);
        return transactions.execute(status -> registerLocked(exam, member));
    }

    /**
     * 회원 가드와 시험 잠금 아래 새 참가만 이력·이벤트와 함께 저장한다.
     *
     * @param examId 시험 식별자
     * @param member 참가 회원 식별자
     * @return 생성 여부와 참가 응답
     */
    private Registration registerLocked(long examId, long member) {
        guards.lockForEnrollment(List.of(new Resource("MEMBER", member)));
        requireActive(member);
        Exam exam = lockExam(examId);
        var rows = participants.findForUpdate(examId, member);
        var now = now();
        if (exam.getStatus() == ExamStatus.CANCELED) {
            throw new ContestException(ContestErrorCode.EXAM_CANCELED);
        }
        if (!now.isBefore(exam.getEndsAt())) {
            throw new ContestException(ContestErrorCode.EXAM_CLOSED);
        }
        eligibility.requireApprovedMembership(exam.getStudyId(), member);
        var active = rows.stream().filter(row -> row.getStatus() != ExamParticipantStatus.CANCELED).findFirst();
        if (active.isPresent()) {
            return new Registration(ExamParticipationResponse.from(active.get(), now), false);
        }
        var participant = participants.saveAndFlush(ExamParticipant.register(exam, member, now));
        history(participant, null, ExamParticipantStatus.REGISTERED, member, now);
        var problemIds
            = jdbc
                .queryForList(
                    "SELECT problem_id FROM exam_problems WHERE exam_id = ? " + "ORDER BY display_order",
                    Long.class,
                    examId
                )
                .stream()
                .map(String::valueOf)
                .toList();
        append(
            "ExamParticipantRegistered",
            participant,
            new RegisteredPayload(
                "EXAM",
                Long.toString(examId),
                Long.toString(examId),
                participant.getId().toString(),
                Long.toString(member),
                Long.toString(participant.getParticipantRevision()),
                Integer.toString(exam.getExamRevision()),
                problemIds,
                format(exam.getStartsAt()),
                format(exam.getEndsAt())
            )
        );
        return new Registration(ExamParticipationResponse.from(participant, now), true);
    }

    /**
     * 취소 재요청을 먼저 확인하고 신규 취소의 회원 조회는 잠금 밖에서 수행한다 (API 명세서 4.2.10).
     *
     * @param examId 시험 식별자 문자열
     * @param memberId 인증된 회원 식별자 문자열
     * @return 취소된 참가의 상태와 버전
     * @throws ContestException 참가가 없거나 취소할 수 없는 상태일 때
     */
    public ExamCancellationResponse cancel(String examId, String memberId) {
        long exam = id(examId);
        long member = id(memberId);
        var existing = transactions.execute(status -> canceledResult(exam, member));
        if (existing != null) {
            return existing;
        }
        memberRefresh.refresh(member);
        return transactions.execute(status -> {
            var parent = lockExam(exam);
            var rows = participants.findForUpdate(exam, member);
            var participant
                = rows
                    .stream()
                    .filter(row -> row.getStatus() != ExamParticipantStatus.CANCELED)
                    .findFirst()
                    .orElseGet(
                        () -> rows
                            .stream()
                            .findFirst()
                            .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED))
                    );
            if (participant.getStatus() == ExamParticipantStatus.CANCELED) {
                return ExamCancellationResponse.from(participant);
            }
            requireNotSuspended(member);
            var now = now();
            if (!now.isBefore(parent.getStartsAt()) || participant.getStatus() != ExamParticipantStatus.REGISTERED) {
                throw new ContestException(ContestErrorCode.EXAM_PARTICIPATION_CANCELLATION_CLOSED);
            }
            participant.cancel();
            history(participant, ExamParticipantStatus.REGISTERED, ExamParticipantStatus.CANCELED, member, now);
            append(
                "ExamParticipantCanceled",
                participant,
                new CanceledPayload(
                    "EXAM",
                    Long.toString(exam),
                    Long.toString(exam),
                    participant.getId().toString(),
                    Long.toString(member),
                    Long.toString(participant.getParticipantRevision()),
                    Integer.toString(parent.getExamRevision()),
                    format(now)
                )
            );
            return ExamCancellationResponse.from(participant);
        });
    }

    /**
     * 취소 재요청은 회원 조회 장애와 무관하게 기존 결과를 반환한다.
     *
     * @param examId 시험 식별자
     * @param member 인증된 회원 식별자
     * @return 취소된 마지막 참가의 응답, 활성 참가가 있으면 null
     * @throws ContestException 시험 또는 참가가 존재하지 않을 때
     */
    private ExamCancellationResponse canceledResult(long examId, long member) {
        lockExam(examId);
        var rows = participants.findForUpdate(examId, member);
        if (rows.stream().anyMatch(row -> row.getStatus() != ExamParticipantStatus.CANCELED)) {
            return null;
        }
        return ExamCancellationResponse
            .from(
                rows.stream().findFirst().orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED))
            );
    }

    /**
     * 원격 조회가 갱신한 복제본에서 정지 여부를 확인한다.
     *
     * @param member 회원 식별자
     * @throws ContestException 복제본이 없거나 정지 상태일 때
     */
    private void requireNotSuspended(long member) {
        String state
            = members
                .findStatus(member)
                .orElseThrow(() -> new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));
        if ("SUSPENDED".equals(state)) {
            throw new ContestException(ContestErrorCode.MEMBER_SUSPENDED);
        }
    }

    /**
     * 원격 회원 조회를 잠금 전에 끝내고 최초 입장과 탈퇴 준비를 직렬화한다 (ERD 4.4.16).
     *
     * @param examId 시험 식별자 문자열
     * @param memberId 인증된 회원 식별자 문자열
     * @return 최초 입장 시각과 개인 마감
     * @throws ContestException 회원·참가 상태 또는 시간 조건이 유효하지 않을 때
     */
    public ExamEntryResponse enter(String examId, String memberId) {
        long exam = id(examId);
        long member = id(memberId);
        memberRefresh.refresh(member);
        return transactions.execute(status -> {
            guards.lockForEnrollment(List.of(new Resource("MEMBER", member)));
            requireActive(member);
            var parent = lockExam(exam);
            var participant
                = participants
                    .findForUpdate(exam, member)
                    .stream()
                    .filter(row -> row.getStatus() != ExamParticipantStatus.CANCELED)
                    .findFirst()
                    .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
            var now = now();
            if (now.isBefore(parent.getStartsAt())) {
                throw new ContestException(ContestErrorCode.EXAM_NOT_STARTED);
            }
            if (participant.getStatus() == ExamParticipantStatus.FINISHED) {
                throw new ContestException(ContestErrorCode.EXAM_ALREADY_FINISHED);
            }
            if (now.isAfter(participant.getPersonalEndsAt()) || parent.getStatus() == ExamStatus.CANCELED
                || participant.getStatus() == ExamParticipantStatus.ABSENT) {
                throw new ContestException(ContestErrorCode.EXAM_CLOSED);
            }
            if (participant.getStatus() == ExamParticipantStatus.REGISTERED) {
                participant.enter(now);
                history(participant, ExamParticipantStatus.REGISTERED, ExamParticipantStatus.STARTED, member, now);
            }
            return ExamEntryResponse.from(participant, now);
        });
    }

    /**
     * 복제된 회원 상태로 정지와 탈퇴 상태를 구분해 차단한다.
     *
     * @param member 회원 식별자
     * @throws ContestException 정지·탈퇴 상태이거나 회원 복제본이 없을 때
     */
    private void requireActive(long member) {
        String state
            = members
                .findStatus(member)
                .orElseThrow(() -> new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));
        if ("SUSPENDED".equals(state)) {
            throw new ContestException(ContestErrorCode.MEMBER_SUSPENDED);
        }
        if (!"ACTIVE".equals(state)) {
            throw new ContestException(ContestErrorCode.CONTEST_OPERATION_BLOCKED);
        }
    }

    /**
     * 참가 행보다 먼저 부모 시험을 잠근다 (ERD 6.4.1).
     *
     * @param id 시험 식별자
     * @return 잠긴 시험
     * @throws ContestException 시험이 존재하지 않을 때
     */
    private Exam lockExam(long id) {
        return exams.findForUpdate(id).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
    }

    /**
     * 상태 전이 이력을 현재 업무 트랜잭션에 저장한다.
     *
     * @param participant 전이한 참가
     * @param from 이전 상태, 최초 등록이면 null
     * @param to 전이 후 상태
     * @param member 처리 회원 식별자
     * @param now 전이 시각
     */
    private void history(
        ExamParticipant participant,
        ExamParticipantStatus from,
        ExamParticipantStatus to,
        long member,
        LocalDateTime now
    ) {
        histories.save(ExamParticipantHistory.transition(participant, from, to, member, now));
    }

    /**
     * 참가 이벤트를 현재 트랜잭션의 Outbox에 기록한다.
     *
     * @param type 이벤트 종류
     * @param participant 이벤트의 참가 애그리게이트
     * @param payload 공통 헤더를 제외한 이벤트 데이터
     */
    private void append(String type, ExamParticipant participant, Object payload) {
        String correlation = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlation == null) {
            correlation = "contest-" + UUID.randomUUID();
        }
        outbox.append("EXAM_PARTICIPANT", type, "v1", participant.getId().toString(), correlation, payload);
    }

    /**
     * DB 시각 정밀도에 맞춘 서비스 시간대의 현재 시각을 반환한다.
     *
     * @return 마이크로초 단위 서버 시각
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
    }

    /**
     * 이벤트 시각을 마이크로초와 오프셋이 있는 문자열로 변환한다.
     *
     * @param time 서비스 시간대 시각
     * @return 이벤트 계약의 시각 문자열
     */
    private String format(LocalDateTime time) {
        return DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx")
            .format(time.atZone(ClockConfiguration.SERVICE_ZONE));
    }

    /**
     * 양의 BIGINT 식별자만 업무 요청으로 받는다.
     *
     * @param value 경로 또는 인증에서 전달된 문자열
     * @return 저장 가능한 양의 식별자
     * @throws ContestException 식별자 형식이나 범위가 잘못됐을 때
     */
    private long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /** 등록 응답과 생성 여부다. */
    public record Registration(ExamParticipationResponse response, boolean created) {
    }

    /** 등록 시점의 시험 전체 설정 스냅샷이다 (이벤트 계약서 4.4 C3). */
    public record RegisteredPayload(
        String context,
        String contextId,
        String examId,
        String participantId,
        String memberId,
        String participantRevision,
        String contextRevision,
        List<String> problemIds,
        String startsAt,
        String endsAt
    ) {
    }

    /** 취소한 참가와 설정 버전이다 (이벤트 계약서 4.4 C5). */
    public record CanceledPayload(
        String context,
        String contextId,
        String examId,
        String participantId,
        String memberId,
        String participantRevision,
        String contextRevision,
        String canceledAt
    ) {
    }
}
