package com.backtoback.problem.problem.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.problem.problem.domain.ProblemImage;

/** 이미지 파일을 저장하고, 객체 키로 조회한다. */
public interface ProblemImageRepository extends JpaRepository<ProblemImage, Long> {
    /**
     * 객체 키가 일치하는 이미지 파일을 조회한다.
     *
     * @param objectKey S3 객체 키
     * @return 조건에 맞는 이미지 파일
     */
    Optional<ProblemImage> findByObjectKey(String objectKey);
}
