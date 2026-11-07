package com.backtoback.problem.problem.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.response.ExecutionLimitsResponse;
import com.backtoback.problem.problem.repository.ProblemCodeAssetRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionTestEntryRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;
import com.backtoback.problem.problem.repository.ProblemValidationRepository;

import lombok.RequiredArgsConstructor;

/** 원격 파일 조회 전에 고정 요청·회차·파일 메타 정보를 읽는다. */
@Service
@RequiredArgsConstructor
public class ProblemValidationDataStore {
    private final ProblemValidationRepository validations;
    private final ProblemRevisionRepository revisions;
    private final ProblemCodeAssetRepository codes;
    private final ProblemRevisionTestEntryRepository entries;
    private final ProblemTestAssetRepository tests;

    /**
     * 요청 ID가 가리키는 불변 자료 참조를 조회한다.
     *
     * @param validationId 원래 검증 요청
     * @return DB 연결 밖에서 사용할 고정 자료
     * @throws ProblemException 요청이 없으면 {@code PROBLEM_VALIDATION_NOT_FOUND}
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        readOnly = true
    )
    public Snapshot read(long validationId) {
        var validation
            = validations
                .findById(validationId)
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_VALIDATION_NOT_FOUND));
        var revision = revisions.findById(validation.getProblemRevisionId()).orElseThrow();
        var code = codes.findById(validation.getCodeAssetId()).orElseThrow();
        var cases = entries.findByProblemRevisionIdOrderByTestOrder(revision.getId()).stream().map(entry -> {
            var asset = tests.findById(entry.getTestAssetId()).orElseThrow();
            return new TestFile(
                entry.getTestAssetId(),
                entry.isSample(),
                new File(asset.getObjectKey(), asset.getObjectVersion(), asset.getSha256(), asset.getSizeBytes())
            );
        }).toList();
        return new Snapshot(
            validation.getId(),
            revision.getId(),
            validation.getLanguage(),
            validation.getRequestHash(),
            new File(code.getObjectKey(), code.getObjectVersion(), code.getSha256(), code.getSizeBytes()),
            revision.getFunctionSpecJson(),
            ExecutionLimitsResponse.from(revision),
            cases
        );
    }

    /** 원격 저장소에서 확인할 불변 객체 메타 정보. */
    public record File(String key, String version, String sha256, long size) {
    }
    /** 고정 순서로 읽은 테스트 참조. */
    public record TestFile(long id, boolean sample, File file) {
    }
    /** 원래 요청의 불변 자료 묶음. */
    public record Snapshot(
        long id,
        long revisionId,
        String language,
        String hash,
        File code,
        String function,
        ExecutionLimitsResponse limits,
        List<TestFile> tests
    ) {
    }
}
