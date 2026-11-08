package com.backtoback.study.operation.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.repository.StudyMemberQuotaRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.operation.domain.AuditLog;
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
     * 잠금 순서: quota → study (가입 승인과 동일, 교착 방지).
     */
    @Transactional
    public void doCloseStudy(Long studyId, String correlationId, LocalDateTime now) {
        // ① quota 먼저 (잠금 순서: quota → study)
        decrementApprovedMemberQuotas(studyId);
        // ② study
        int rows = studyRepository.closeStudyIfNotClosed(studyId);
        if (rows == 0) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
        }
        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
        applyCloseEffects(studyId, study.getVersion(), correlationId, now);
    }

    /**
     * 관리자 강제 종료 연쇄 효과를 트랜잭션 안에서 원자적으로 적용한다(API 명세서 §5.6.1).
     * 잠금 순서: quota → study (가입 승인과 동일, 교착 방지).
     */
    @Transactional
    public void doForceCloseStudy(
        Long studyId,
        Long actorId,
        String beforeStateJson,
        String reason,
        String clientIp,
        String correlationId,
        LocalDateTime now
    ) {
        // ① quota 먼저 (잠금 순서: quota → study)
        decrementApprovedMemberQuotas(studyId);
        // ② study
        int rows = studyRepository.closeStudyIfNotClosed(studyId);
        if (rows == 0) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
        }
        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
        applyCloseEffects(studyId, study.getVersion(), correlationId, now);
        auditLogRepository.save(AuditLog.forceStudyClosed(actorId, studyId, beforeStateJson, reason, clientIp, now));
    }

    private void decrementApprovedMemberQuotas(Long studyId) {
        List<Long> memberIds
            = studyMembershipRepository.findMemberIdsByStudyIdAndStatus(studyId, MembershipStatus.APPROVED);
        if (!memberIds.isEmpty()) {
            studyMemberQuotaRepository.decrementActiveCountBatch(memberIds);
        }
    }

    private void applyCloseEffects(Long studyId, Long studyVersion, String correlationId, LocalDateTime now) {
        studyMembershipRepository.cancelPendingMemberships(studyId);
        assignmentRepository.closeActiveAssignments(studyId);
        outboxEventWriter
            .append(
                "study",
                "StudyClosed",
                "v1",
                String.valueOf(studyId),
                correlationId,
                new StudyClosedPayload(studyId, studyVersion, now.atOffset(ZoneOffset.of("+09:00")))
            );
    }
}
