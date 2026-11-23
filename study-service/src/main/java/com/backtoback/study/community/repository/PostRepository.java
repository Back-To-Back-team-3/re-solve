package com.backtoback.study.community.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.community.domain.Post;

public interface PostRepository extends JpaRepository<Post, Long> {

}
