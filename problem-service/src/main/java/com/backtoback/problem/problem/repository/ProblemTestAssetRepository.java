package com.backtoback.problem.problem.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.problem.problem.domain.ProblemTestAsset;

import jakarta.persistence.LockModeType;

/** 테스트 파일을 저장하고, 객체 키로 조회한다. */
public interface ProblemTestAssetRepository extends JpaRepository<ProblemTestAsset, Long> {
    /** 같은 문제와 작성자의 연결된 파일 중 내용 메타 정보가 일치하는 후보를 조회한다. */
    List<ProblemTestAsset> findByProblemIdAndOwnerIdAndStatusAndSha256AndSizeBytesOrderByIdAsc(
        Long problemId,
        Long ownerId,
        com.backtoback.problem.problem.domain.ProblemAssetStatus status,
        String sha256,
        long sizeBytes
    );

    /** 파일 연결과 정리 선점을 직렬화하기 위해 테스트 파일 행을 잠근다(ERD 4.2.3). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ProblemTestAsset a where a.id = :assetId")
    Optional<ProblemTestAsset> findByIdForUpdate(@Param("assetId") Long assetId);

    /**
     * 객체 키가 일치하는 테스트 파일을 조회한다.
     *
     * @param objectKey S3 객체 키
     * @return 조건에 맞는 테스트 파일
     */
    Optional<ProblemTestAsset> findByObjectKey(String objectKey);
}
