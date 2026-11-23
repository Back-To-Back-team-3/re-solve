package com.backtoback.study.community.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.community.domain.Post;
import com.backtoback.study.community.domain.PostStatus;

import jakarta.persistence.LockModeType;

public interface PostRepository extends JpaRepository<Post, Long> {

    Page<Post> findByStudyIdAndStatus(Long studyId, PostStatus status, Pageable pageable);

    Page<Post> findByStudyIdAndStatusAndNotice(Long studyId, PostStatus status, boolean notice, Pageable pageable);

    Optional<Post> findByIdAndStudyId(Long id, Long studyId);

    /**
     * 수정·삭제·운영진 관리가 같은 게시글에 동시에 들어와도 한 요청씩 처리하도록 행을 {@code FOR UPDATE}로 잠근다.
     * 엔티티는 모든 컬럼을 UPDATE하므로 잠그지 않으면 늦게 커밋된 수정이 운영진의 숨김을 되돌릴 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Post p WHERE p.id = :id AND p.studyId = :studyId")
    Optional<Post> findByIdAndStudyIdWithLock(@Param("id") Long id, @Param("studyId") Long studyId);
}
