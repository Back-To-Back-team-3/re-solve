package com.backtoback.problem.problem.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.problem.problem.domain.ProblemAssetStatus;
import com.backtoback.problem.problem.repository.ProblemBodyAssetRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;

import lombok.RequiredArgsConstructor;

/**
 * 같은 문제에 연결된 파일의 메타 정보와 실제 원문을 대조한다.
 * 기준 문서: API 명세서 v1.1 / §2.3.0 문제·테스트 공통 데이터 형식: 원문과 메타 정보 재사용.
 */
@Service
@RequiredArgsConstructor
public class ProblemAssetReuseService {
    private final ProblemBodyAssetRepository bodies;
    private final ProblemTestAssetRepository tests;
    private final ProblemAssetObjectReader reader;

    /**
     * 후보를 확인한다. 최종 참조 생성 시 호출자가 파일을 잠그고 상태와 소속을 다시 검사한다.
     *
     * @param bucket 원문 버킷
     * @param problemId 문제 ID
     * @param ownerId 작성 관리자 ID
     * @param content 비교할 파일 바이트
     * @param body 본문 여부
     * @return 일치한 파일 ID 또는 빈 결과
     */
    @Transactional(propagation = Propagation.NEVER)
    public Optional<Long> find(String bucket, Long problemId, Long ownerId, ProblemAssetContent content, boolean body) {
        if (body) {
            for (var asset : bodies
                .findByProblemIdAndOwnerIdAndStatusAndSha256AndSizeBytesOrderByIdAsc(
                    problemId,
                    ownerId,
                    ProblemAssetStatus.LINKED,
                    content.sha256(),
                    content.sizeBytes()
                )) {
                if (reader.matches(bucket, asset.getObjectKey(), asset.getObjectVersion(), content)) {
                    return Optional.of(asset.getId());
                }
            }
        } else {
            for (var asset : tests
                .findByProblemIdAndOwnerIdAndStatusAndSha256AndSizeBytesOrderByIdAsc(
                    problemId,
                    ownerId,
                    ProblemAssetStatus.LINKED,
                    content.sha256(),
                    content.sizeBytes()
                )) {
                if (reader.matches(bucket, asset.getObjectKey(), asset.getObjectVersion(), content)) {
                    return Optional.of(asset.getId());
                }
            }
        }
        return Optional.empty();
    }
}
