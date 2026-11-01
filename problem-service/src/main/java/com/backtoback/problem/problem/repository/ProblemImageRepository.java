package com.backtoback.problem.problem.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.problem.problem.domain.ProblemImage;

import jakarta.persistence.LockModeType;

/** 이미지 파일을 저장하고, 객체 키로 조회한다. */
public interface ProblemImageRepository extends JpaRepository<ProblemImage, Long> {
    /**
     * 이미지 ID 오름차순으로 잠가 같은 종류 파일의 잠금 순서를 고정한다(ERD 6.2).
     *
     * @param imageIds 잠글 이미지 ID
     * @return 잠근 이미지 목록
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ProblemImage i where i.id in :imageIds order by i.id")
    List<ProblemImage> findAllByIdForUpdate(@Param("imageIds") Collection<Long> imageIds);

    /**
     * 객체 키가 일치하는 이미지 파일을 조회한다.
     *
     * @param objectKey S3 객체 키
     * @return 조건에 맞는 이미지 파일
     */
    Optional<ProblemImage> findByObjectKey(String objectKey);
}
