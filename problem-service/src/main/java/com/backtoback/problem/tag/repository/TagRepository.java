package com.backtoback.problem.tag.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.backtoback.problem.tag.domain.Tag;

import jakarta.persistence.LockModeType;

/** 태그를 저장·검색하고, 순번 변경에 필요한 전체 태그의 비관적 락 조회를 제공한다. */
public interface TagRepository extends JpaRepository<Tag, Long> {
    /**
     * 이름에 검색어가 포함된 태그를 대소문자 구분 없이 조회한다.
     *
     * @param keyword 태그 이름에 포함된 검색어
     * @param pageable 페이지 범위와 정렬 조건
     * @return 조건에 맞는 태그 페이지
     */
    Page<Tag> findByNameContainingIgnoreCase(String keyword, Pageable pageable);

    /**
     * 전체 태그를 표시 순번·ID 순으로 조회하고 쓰기 잠금을 획득한다.
     * 활성 쓰기 트랜잭션에서 호출하며 트랜잭션 종료까지 잠금을 유지한다.
     *
     * @return 표시 순번·ID 순서로 조회한 잠금 대상 태그 목록
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tag t order by t.displayOrder, t.id")
    List<Tag> findAllForUpdate();
}
