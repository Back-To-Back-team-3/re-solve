package com.backtoback.study.operation.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.repository.StudyMemberQuotaRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.operation.domain.AuditLog;
import com.backtoback.study.operation.dto.StudyCloseResponse;
import com.backtoback.study.operation.repository.AuditLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 종료 트랜잭션 담당 서비스 (API 명세서 §5.3.4·§5.6.1).
 * HTTP 외부 호출 이후 트랜잭션을 열기 위해 {@link StudyOperationService}에서 분리했다.
 */
@Service
@RequiredArgsConstructor
public class StudyClosePersistService {

    private final StudyRepository studyRepository;
    private final StudyMembershipRepository studyMembershipRepository;
    private final AssignmentRepository assignmentRepository;
    private final StudyMemberQuotaRepository studyMemberQuotaRepository;
    private final AuditLogRepository auditLogRepository;
    private final OutboxEventWriter outboxEventWriter;

    /**
     * 스터디 종료 연쇄 효과를 트랜잭션 안에서 원자적으로 적용한다(API 명세서 §5.3.4).
     */
    @Transactional
    public StudyCloseResponse doCloseStudy(Long studyId, Long studyVersion, String correlationId, LocalDateTime now) {
        int rows = studyRepository.closeStudyIfNotClosed(studyId);
        if (rows == 0) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
        }
        applyCloseEffects(studyId, studyVersion, correlationId, now);
        return new StudyCloseResponse(String.valueOf(studyId));
    }

    /**
     * 관리자 강제 종료 연쇄 효과를 트랜잭션 안에서 원자적으로 적용한다(API 명세서 §5.6.1).
     */
    @Transactional
    public StudyCloseResponse doForceCloseStudy(
        Long studyId,
        Long actorId,
        Long studyVersion,
        String beforeStateJson,
        String reason,
        String clientIp,
        String correlationId,
        LocalDateTime now
    ) {
        int rows = studyRepository.closeStudyIfNotClosed(studyId);
        if (rows == 0) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
        }
        applyCloseEffects(studyId, studyVersion, correlationId, now);
        auditLogRepository.save(AuditLog.forceStudyClosed(actorId, studyId, beforeStateJson, reason, clientIp, now));
        return new StudyCloseResponse(String.valueOf(studyId));
    }

    private void applyCloseEffects(Long studyId, Long studyVersion, String correlationId, LocalDateTime now) {
        studyMembershipRepository.cancelPendingMemberships(studyId);
        assignmentRepository.closeActiveAssignments(studyId);
        List<Long> memberIds
            = studyMembershipRepository.findMemberIdsByStudyIdAndStatus(studyId, MembershipStatus.APPROVED);
        if (!memberIds.isEmpty()) {
            studyMemberQuotaRepository.decrementActiveCountBatch(memberIds);
        }
        outboxEventWriter
            .append(
                "study",
                "StudyClosed",
                "v1",
                String.valueOf(studyId),
                correlationId,
                new StudyClosedPayload(studyId, studyVersion + 1, now)
            );
    }
}
