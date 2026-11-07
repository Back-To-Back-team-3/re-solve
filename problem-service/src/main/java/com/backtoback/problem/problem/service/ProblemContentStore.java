package com.backtoback.problem.problem.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemAssetStatus;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.domain.ProblemVisibility;
import com.backtoback.problem.problem.repository.ProblemBodyAssetRepository;
import com.backtoback.problem.problem.repository.ProblemBodyImageRepository;
import com.backtoback.problem.problem.repository.ProblemImageRepository;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;
import com.backtoback.problem.problem.service.ProblemValidationDataStore.File;

import lombok.RequiredArgsConstructor;

/** 외부 공개 권한과 내부 문맥을 구분하고 원격 읽기 전에 자료 참조를 고정한다. */
@Service
@RequiredArgsConstructor
public class ProblemContentStore {
    private final ProblemReadStore reads;
    private final ProblemJudgingStore judging;
    private final ProblemBodyAssetRepository bodies;
    private final ProblemTestAssetRepository tests;
    private final ProblemImageRepository images;
    private final ProblemBodyImageRepository links;
    private final ProblemRepository problems;
    private final ProblemRevisionRepository revisions;

    /**
     * 일반 사용 회차 또는 내부 고정 회차의 공개 원문 참조를 조회한다.
     *
     * @param id 문제
     * @param revisionId 내부 고정 회차 또는 일반 조회 시 null
     * @param context 내부 열람 문맥 또는 일반 조회 시 null
     * @return 본문·공개 테스트 참조
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        readOnly = true
    )
    public Snapshot content(long id, Long revisionId, String context) {
        long selected;
        if (revisionId == null) {
            var current = reads.summaries(List.of(id), "ASSIGNMENT").getFirst();
            if (!current.available()) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND);
            }
            selected = current.revision().getId();
        } else {
            context(context);
            selected = revisionId;
        }
        var fixed = judging.read(id, selected);
        var body = bodies.findById(fixed.revision().getBodyAssetId()).orElseThrow();
        var samples = fixed.tests().stream().filter(ProblemJudgingStore.Test::sample).map(test -> {
            var asset = tests.findById(test.id()).orElseThrow();
            return new Sample(
                test.id(),
                new File(asset.getObjectKey(), asset.getObjectVersion(), asset.getSha256(), asset.getSizeBytes())
            );
        }).toList();
        return new Snapshot(
            fixed,
            new File(body.getObjectKey(), body.getObjectVersion(), body.getSha256(), body.getSizeBytes()),
            samples
        );
    }

    /**
     * 지정 회차 본문에 실제 연결된 이미지 참조를 조회한다.
     *
     * @param id 문제
     * @param revisionId 지정 회차
     * @param imageId 이미지
     * @param context 내부 문맥 또는 일반 조회 시 null
     * @return MIME과 불변 파일 참조
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        readOnly = true
    )
    public Image image(long id, long revisionId, long imageId, String context) {
        if (id <= 0 || revisionId <= 0 || imageId <= 0) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        if (context != null) {
            context(context);
        }
        var problem = problems.findById(id).orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
        if (context == null
            && (problem.getScope() != ProblemScope.GENERAL || problem.getVisibility() != ProblemVisibility.PUBLIC)) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND);
        }
        var revision
            = revisions
                .findById(revisionId)
                .filter(value -> value.getProblemId() == id)
                .orElseThrow(
                    () -> new ProblemException(
                        context == null ? ProblemErrorCode.PROBLEM_NOT_FOUND
                            : ProblemErrorCode.PROBLEM_REVISION_NOT_FOUND
                    )
                );
        if (links
            .findByBodyAssetId(revision.getBodyAssetId())
            .stream()
            .noneMatch(link -> link.getImageId() == imageId)) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND);
        }
        var image
            = images
                .findById(imageId)
                .filter(
                    value -> value.getStatus() == ProblemAssetStatus.LINKED && value.getProblemId() != null
                        && value.getProblemId() == id
                )
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
        return new Image(
            image.getContentType(),
            new File(image.getObjectKey(), image.getObjectVersion(), image.getSha256(), image.getSizeBytes())
        );
    }

    /**
     * 호출 서비스가 권한을 확인한 문맥의 형식을 검사한다.
     *
     * @param context 내부 문맥
     * @throws ProblemException 허용하지 않는 형식 또는 ID 범위
     */
    private void context(String context) {
        try {
            if (context == null || !context.matches("(EXAM|CONTEST|DIAGNOSIS):[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            Long.parseLong(context.substring(context.indexOf(':') + 1));
        } catch (NumberFormatException invalid) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /** 공개 예제 파일 참조. */
    public record Sample(long id, File file) {
    }
    /** 본문 응답을 만들 때 사용할 자료. */
    public record Snapshot(ProblemJudgingStore.Snapshot fixed, File body, List<Sample> samples) {
    }
    /** 이미지 MIME과 불변 참조. */
    public record Image(String contentType, File file) {
    }
}
