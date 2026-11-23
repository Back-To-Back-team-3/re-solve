package com.backtoback.study.community.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.community.domain.Post;
import com.backtoback.study.community.domain.PostStatus;

public interface PostRepository extends JpaRepository<Post, Long> {

    Page<Post> findByStudyIdAndStatus(Long studyId, PostStatus status, Pageable pageable);

    Page<Post> findByStudyIdAndStatusAndNotice(Long studyId, PostStatus status, boolean notice, Pageable pageable);

    Optional<Post> findByIdAndStudyId(Long id, Long studyId);
}
