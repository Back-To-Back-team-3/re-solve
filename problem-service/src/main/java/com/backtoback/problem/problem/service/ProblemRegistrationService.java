package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemAssetStatus;
import com.backtoback.problem.problem.domain.ProblemBodyAsset;
import com.backtoback.problem.problem.domain.ProblemBodyImage;
import com.backtoback.problem.problem.domain.ProblemImage;
import com.backtoback.problem.problem.domain.ProblemRevision;
import com.backtoback.problem.problem.domain.ProblemRevisionTag;
import com.backtoback.problem.problem.repository.ProblemBodyAssetRepository;
import com.backtoback.problem.problem.repository.ProblemBodyImageRepository;
import com.backtoback.problem.problem.repository.ProblemImageRepository;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionTagRepository;
import com.backtoback.problem.tag.repository.TagRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 문제·회차와 본문·태그·이미지 참조를 호출자의 등록 트랜잭션에 저장한다. */
@Service
@RequiredArgsConstructor
public class ProblemRegistrationService {
    private final ProblemRepository problems;
    private final ProblemRevisionRepository revisions;
    private final ProblemBodyAssetRepository bodyAssets;
    private final ProblemImageRepository images;
    private final ProblemBodyImageRepository bodyImages;
    private final ProblemRevisionTagRepository revisionTags;
    private final TagRepository tags;
    private final TestCaseValidator testCaseValidator;
    private final ProblemImageReferenceParser imageReferences;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final EntityManager entityManager;

    /**
     * 업로드 완료된 파일과 검증한 입력을 저장한다. 호출자는 같은 트랜잭션에 회차 테스트 구성과 감사 기록 등
     * 전체 등록에 필요한 나머지 작업을 반영해야 한다. 호출자는 재사용 본문의 원문·체크섬·크기를 저장 객체와
     * 대조하며, 이 메서드만으로 등록이 완료되지 않는다(API 명세서 2.3.0·2.3.1).
     * 요청 본문과 테스트의 바이트·개수 제한은 호출자가 검사한다(정책 및 상태 §4).
     * 멱등키 UNIQUE 경합의 패배는 호출자가 트랜잭션을 롤백한 뒤 최초 결과를 다시 조회한다.
     * 테스트 파일을 함께 연결하는 흐름은 본문 → 테스트 → 이미지 순서로 잠그도록 이 메서드의 잠금 경계를 구성해야 한다
     * (ERD 4.2.3).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SaveResult save(ProblemRegistrationCommand command) {
        validateIdentity(command);
        var existing = revisions.findByAuthorIdAndIdempotencyKey(command.authorId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(command, existing.get());
        }
        validateContent(command);
        testCaseValidator.validate(command.functionSpec(), command.testCases());
        List<Long> imageIds = imageReferences.parse(command.description(), command.constraints(), command.examples());
        validateTags(command.tagIds());

        Problem problem;
        int revisionNumber;
        if (command.problemId() == null) {
            if (command.scope() == null || command.expectedRowVersion() != null) {
                throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
            }
            problem = problems.saveAndFlush(Problem.create(command.authorId(), command.scope()));
            revisionNumber = 1;
        } else {
            if (command.expectedRowVersion() == null || command.scope() != null) {
                throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
            }
            problem
                = problems
                    .findByIdForUpdate(command.problemId())
                    .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
            existing = revisions.findByAuthorIdAndIdempotencyKeyForUpdate(command.authorId(), command.idempotencyKey());
            if (existing.isPresent()) {
                return replay(command, existing.get());
            }
            // 이미 관리 중인 엔티티는 잠금 조회로 값이 갱신되지 않을 수 있으므로 DB 버전과 대조한다.
            Integer lockedVersion
                = entityManager
                    .createQuery("select p.rowVersion from Problem p where p.id = :problemId", Integer.class)
                    .setParameter("problemId", command.problemId())
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getSingleResult();
            if (!lockedVersion.equals(problem.getRowVersion())) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
            }
            problem.prepareNewRevision(command.authorId(), command.expectedRowVersion());
            revisionNumber
                = revisions
                    .findFirstByProblemIdOrderByRevisionNumberDesc(problem.getId())
                    .map(revision -> Math.addExact(revision.getRevisionNumber(), 1))
                    .orElseThrow(() -> new IllegalStateException("문제에 저장된 회차가 없습니다."));
        }

        ProblemBodyAsset body
            = bodyAssets
                .findByIdForUpdate(command.bodyAssetId())
                .orElseThrow(() -> new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST));
        if (!Objects.equals(body.getSha256(), command.bodySha256()) || body.getSizeBytes() != command.bodySizeBytes()) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        boolean reusedBody = body.getStatus() == ProblemAssetStatus.LINKED;
        List<Long> existingImages
            = entityManager
                .createQuery(
                    "select bi from ProblemBodyImage bi where bi.bodyAssetId = :bodyId order by bi.imageId",
                    ProblemBodyImage.class
                )
                .setParameter("bodyId", body.getId())
                .setLockMode(LockModeType.PESSIMISTIC_READ)
                .getResultList()
                .stream()
                .map(ProblemBodyImage::getImageId)
                .toList();
        if ((reusedBody && !existingImages.equals(imageIds)) || (!reusedBody && !existingImages.isEmpty())) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_IMAGE_INVALID);
        }
        try {
            body.linkToProblem(problem.getId(), command.authorId());
        } catch (IllegalStateException exception) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        for (Long imageId : imageIds) {
            List<ProblemImage> lockedImages = images.findAllByIdForUpdate(List.of(imageId));
            if (lockedImages.size() != 1) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_IMAGE_INVALID);
            }
            try {
                lockedImages.getFirst().linkToProblem(problem.getId(), command.authorId());
            } catch (IllegalStateException exception) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_IMAGE_INVALID);
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        ProblemRevision revision
            = revisions
                .saveAndFlush(
                    ProblemRevision
                        .create(
                            problem.getId(),
                            revisionNumber,
                            body.getId(),
                            command.title(),
                            command.difficulty(),
                            mapper.writeValueAsString(command.functionSpec()),
                            command.timeLimitMs(),
                            command.memoryLimitKb(),
                            command.wallTimeLimitMs(),
                            command.outputLimitKb(),
                            command.authorId(),
                            command.idempotencyKey(),
                            command.requestHash(),
                            problem.getRowVersion(),
                            now
                        )
                );
        revisionTags
            .saveAll(
                command.tagIds().stream().map(tagId -> ProblemRevisionTag.create(revision.getId(), tagId, now)).toList()
            );
        if (!reusedBody) {
            bodyImages
                .saveAll(
                    imageIds.stream().map(imageId -> ProblemBodyImage.create(body.getId(), imageId, now)).toList()
                );
        }
        return result(revision, false);
    }

    private void validateIdentity(ProblemRegistrationCommand command) {
        if (command == null || command.authorId() == null || command.authorId() <= 0 || command.idempotencyKey() == null
            || command.idempotencyKey().isBlank() || command.idempotencyKey().length() > 100
            || !command.idempotencyKey().matches("\\p{ASCII}+") || command.requestHash() == null
            || command.requestHash().length() != 64) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    private void validateContent(ProblemRegistrationCommand command) {
        if (command.bodyAssetId() == null || command.bodyAssetId() <= 0 || command.bodySha256() == null
            || command.bodySha256().length() != 64 || command.bodySizeBytes() < 0 || command.title() == null
            || command.title().isBlank() || command.title().length() > 200 || command.difficulty() < 1
            || command.difficulty() > 3 || command.timeLimitMs() < 1000 || command.outputLimitKb() < 1
            || command.outputLimitKb() > 256 || command.description() == null || command.constraints() == null
            || command.examples() == null) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    private void validateTags(List<Long> tagIds) {
        if (tagIds == null || tagIds.stream().anyMatch(id -> id == null || id <= 0)
            || new HashSet<>(tagIds).size() != tagIds.size()) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        // 여러 태그를 같은 순서로 잠가 등록 요청끼리 잠금 순서가 달라지지 않도록 한다.
        for (Long tagId : tagIds.stream().sorted().toList()) {
            if (tags.findByIdForShare(tagId).isEmpty()) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_TAG_NOT_FOUND);
            }
        }
    }

    private SaveResult replay(ProblemRegistrationCommand command, ProblemRevision revision) {
        if (!Objects.equals(command.requestHash(), revision.getRequestHash())) {
            throw new ProblemException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return result(revision, true);
    }

    private SaveResult result(ProblemRevision revision, boolean replayed) {
        return new SaveResult(
            revision.getProblemId(),
            revision.getId(),
            revision.getRevisionNumber(),
            revision.getResponseRowVersion(),
            replayed
        );
    }

    /** 최초 응답에 필요한 식별자·버전과 재요청 여부. */
    public record SaveResult(
        Long problemId,
        Long problemRevisionId,
        int revisionNumber,
        int rowVersion,
        boolean replayed
    ) {
    }
}
