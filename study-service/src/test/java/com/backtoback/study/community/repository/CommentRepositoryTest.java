package com.backtoback.study.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.community.domain.Comment;
import com.backtoback.study.community.domain.CommentStatus;
import com.backtoback.study.community.domain.Post;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.repository.StudyRepository;

import jakarta.persistence.EntityManager;

/**
 * 댓글 테이블 매핑·제약 통합 테스트 (ERD 4.5.13 comments).
 * 마이그레이션과 엔티티가 맞는지, 게시글 외래 키가 동작하는지 실제 MySQL로 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class CommentRepositoryTest {

    private static final Long AUTHOR_ID = 8L;

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("study_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private CommentRepository repository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private StudyRepository studyRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("댓글을 저장하면 게시글 ID·작성자·본문과 VISIBLE 상태가 기록된다")
    void save_comment_readsBackInitialState() {
        // given
        Long postId = savePost();
        Comment comment = Comment.create(postId, AUTHOR_ID, "좋네요");

        // when
        Long commentId = repository.saveAndFlush(comment).getId();
        // 영속성 컨텍스트를 비워 DB에 저장된 값을 다시 읽는다.
        entityManager.clear();

        // then
        Comment saved = repository.findById(commentId).orElseThrow();
        assertThat(saved.getPostId()).isEqualTo(postId);
        assertThat(saved.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(saved.getContent()).isEqualTo("좋네요");
        assertThat(saved.getStatus()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 게시글 ID로 댓글을 저장하면 FK 위반으로 실패한다")
    void save_unknownPost_violatesForeignKey() {
        // when & then
        assertThatThrownBy(() -> repository.saveAndFlush(Comment.create(999_999L, AUTHOR_ID, "좋네요")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long savePost() {
        Study study
            = Study.recruit("커뮤니티 테스트 스터디", null, "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.INSTANT, false);
        Long studyId = studyRepository.saveAndFlush(study).getId();
        return postRepository.saveAndFlush(Post.create(studyId, 7L, "제목", "본문", false)).getId();
    }
}
