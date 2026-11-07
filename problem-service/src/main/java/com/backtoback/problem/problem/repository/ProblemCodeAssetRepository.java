package com.backtoback.problem.problem.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.problem.problem.domain.ProblemCodeAsset;

import jakarta.persistence.LockModeType;

/** 코드 파일의 불변 메타 정보와 업로드 상태를 저장한다. */
public interface ProblemCodeAssetRepository extends JpaRepository<ProblemCodeAsset, Long> {
    /**
     * 코드 파일을 잠그고 조회한다.
     *
     * @param id 파일 ID
     * @return 잠근 파일
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ProblemCodeAsset a where a.id = :id")
    Optional<ProblemCodeAsset> findByIdForUpdate(@Param("id") Long id);
}
