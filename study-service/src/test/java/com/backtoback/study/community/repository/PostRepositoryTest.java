package com.backtoback.study.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
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
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 11, 23, 9, 0);
    // 서비스가 만드는 정렬과 같다: 고정 먼저 → 작성 시각 최신순 → ID 역순.
    private static final Pageable PINNED_FIRST
        = PageRequest
            .of(0, 20, Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

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

    @Test
    @DisplayName("목록은 고정 글을 먼저, 이후 작성 시각 최신순으로 조회한다")
    void findByStudyIdAndStatus_pinnedFirstThenLatest() {
        // given
        Long studyId = saveStudy();
        Long older = savePost(studyId, false, false, PostStatus.VISIBLE, at(1));
        Long newer = savePost(studyId, false, false, PostStatus.VISIBLE, at(2));
        Long pinnedOldest = savePost(studyId, true, false, PostStatus.VISIBLE, at(0));

        // when
        List<Long> ids
            = repository.findByStudyIdAndStatus(studyId, PostStatus.VISIBLE, PINNED_FIRST).map(Post::getId).toList();

        // then
        assertThat(ids).containsExactly(pinnedOldest, newer, older);
    }

    @Test
    @DisplayName("목록은 숨김·삭제 글과 다른 스터디 글을 제외한다")
    void findByStudyIdAndStatus_excludesHiddenDeletedAndOtherStudy() {
        // given
        Long studyId = saveStudy();
        Long visible = savePost(studyId, false, false, PostStatus.VISIBLE, at(0));
        savePost(studyId, false, false, PostStatus.HIDDEN, at(1));
        savePost(studyId, false, false, PostStatus.DELETED, at(2));
        savePost(saveStudy(), false, false, PostStatus.VISIBLE, at(3));

        // when
        var page = repository.findByStudyIdAndStatus(studyId, PostStatus.VISIBLE, PINNED_FIRST);

        // then
        assertThat(page.map(Post::getId).toList()).containsExactly(visible);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("공지만 조회하면 공지 글만 반환한다")
    void findByStudyIdAndStatusAndNotice_returnsNoticesOnly() {
        // given
        Long studyId = saveStudy();
        Long notice = savePost(studyId, false, true, PostStatus.VISIBLE, at(0));
        savePost(studyId, false, false, PostStatus.VISIBLE, at(1));

        // when
        List<Long> ids
            = repository
                .findByStudyIdAndStatusAndNotice(studyId, PostStatus.VISIBLE, true, PINNED_FIRST)
                .map(Post::getId)
                .toList();

        // then
        assertThat(ids).containsExactly(notice);
    }

    @Test
    @DisplayName("잠금 조회는 경로의 스터디 ID가 다르면 게시글을 찾지 않는다")
    void findByIdAndStudyIdWithLock_otherStudy_returnsEmpty() {
        // given
        Long studyId = saveStudy();
        Long postId = savePost(studyId, false, false, PostStatus.VISIBLE, at(0));

        // when & then
        assertThat(repository.findByIdAndStudyIdWithLock(postId, studyId)).isPresent();
        assertThat(repository.findByIdAndStudyIdWithLock(postId, saveStudy())).isEmpty();
    }

    private Long saveStudy() {
        Study study
            = Study.recruit("커뮤니티 테스트 스터디", null, "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.INSTANT, false);
        return studyRepository.saveAndFlush(study).getId();
    }

    // 작성 시각은 감사 기능이 저장 시점에 채우므로, 정렬을 확정하려고 저장 후 컬럼을 직접 바꾼다.
    private Long savePost(Long studyId, boolean pinned, boolean notice, PostStatus status, LocalDateTime createdAt) {
        Post post = Post.write(studyId, AUTHOR_ID, "제목", "본문", notice);
        ReflectionTestUtils.setField(post, "pinned", pinned);
        ReflectionTestUtils.setField(post, "status", status);
        Long postId = repository.saveAndFlush(post).getId();
        entityManager
            .createNativeQuery("UPDATE posts SET created_at = :createdAt WHERE id = :id")
            .setParameter("createdAt", createdAt)
            .setParameter("id", postId)
            .executeUpdate();
        entityManager.clear();
        return postId;
    }

    private static LocalDateTime at(int minutes) {
        return BASE_TIME.plusMinutes(minutes);
    }
}
