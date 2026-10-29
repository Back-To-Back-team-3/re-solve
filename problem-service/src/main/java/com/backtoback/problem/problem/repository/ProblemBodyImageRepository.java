package com.backtoback.problem.problem.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.problem.problem.domain.ProblemBodyImage;

/** 본문 파일과 이미지의 연결을 저장하고, 본문 파일에 연결된 이미지 목록을 조회한다. */
public interface ProblemBodyImageRepository extends JpaRepository<ProblemBodyImage, Long> {
    /**
     * 본문 파일에 연결된 이미지 목록을 조회한다.
     *
     * @param bodyAssetId 본문 파일 ID
     * @return 연결된 이미지 목록
     */
    List<ProblemBodyImage> findByBodyAssetId(Long bodyAssetId);
}
