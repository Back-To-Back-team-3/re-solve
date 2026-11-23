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

import com.backtoback.study.community.domain.Post;
import com.backtoback.study.community.domain.PostStatus;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.repository.StudyRepository;

import jakarta.persistence.EntityManager;

/**
 * 게시글 테이블 매핑·제약 통합 테스트 (ERD 4.5.12 posts).
 * 마이그레이션과 엔티티가 맞는지, 스터디 외래 키가 동작하는지 실제 MySQL로 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class PostRepositoryTest {

    private static final Long AUTHOR_ID = 7L;

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
    private PostRepository repository;

    @Autowired
    private StudyRepository studyRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("게시글을 저장하면 공지 여부를 그대로 저장하고, 고정 해제·VISIBLE 상태와 생성·수정 시각이 기록된다")
    void save_post_readsBackInitialState() {
        // given
        Long studyId = saveStudy();
        Post post = Post.write(studyId, AUTHOR_ID, "이번 주 공지", "BFS 문제를 풉니다.", true);

        // when
        Long postId = repository.saveAndFlush(post).getId();
        // 영속성 컨텍스트를 비워 DB에 저장된 값을 다시 읽는다.
        entityManager.clear();

        // then
        Post saved = repository.findById(postId).orElseThrow();
        assertThat(saved.getStudyId()).isEqualTo(studyId);
        assertThat(saved.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(saved.getTitle()).isEqualTo("이번 주 공지");
        assertThat(saved.getContent()).isEqualTo("BFS 문제를 풉니다.");
        assertThat(saved.isNotice()).isTrue();
        assertThat(saved.isPinned()).isFalse();
        assertThat(saved.getStatus()).isEqualTo(PostStatus.VISIBLE);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 스터디 ID로 게시글을 저장하면 FK 위반으로 실패한다")
    void save_unknownStudy_violatesForeignKey() {
        // when & then
        assertThatThrownBy(() -> repository.saveAndFlush(Post.write(999_999L, AUTHOR_ID, "제목", "본문", false)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long saveStudy() {
        Study study
            = Study.recruit("커뮤니티 테스트 스터디", null, "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.INSTANT, false);
        return studyRepository.saveAndFlush(study).getId();
    }
}
