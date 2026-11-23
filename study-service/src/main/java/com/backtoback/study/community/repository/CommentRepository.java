package com.backtoback.study.community.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.community.domain.Comment;

public interface CommentRepository extends JpaRepository<Comment, Long> {

}
