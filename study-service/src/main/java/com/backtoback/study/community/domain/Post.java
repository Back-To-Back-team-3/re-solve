package com.backtoback.study.community.domain;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스터디 게시글(ERD 4.5.12 posts). 삭제·숨김은 상태로만 표시하는 소프트 삭제다.
 * {@code studyId}는 서비스 내부 FK만 두고 연관관계로 매핑하지 않는다.
 */
@Getter
@Entity
@Table(
    name = "posts",
    indexes = {
        @Index(
            name = "idx_posts_study_status_pinned_created_at",
            columnList = "study_id, status, is_pinned, created_at"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "study_id",
        nullable = false
    )
    private Long studyId;

    @Column(
        name = "author_id",
        nullable = false
    )
    private Long authorId;

    @Column(
        nullable = false,
        length = 200
    )
    private String title;

    @Column(
        nullable = false,
        columnDefinition = "TEXT"
    )
    private String content;

    @Column(
        name = "is_pinned",
        nullable = false
    )
    private boolean pinned;

    @Column(
        name = "is_notice",
        nullable = false
    )
    private boolean notice;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private PostStatus status;

    private Post(Long studyId, Long authorId, String title, String content, boolean notice) {
        this.studyId = studyId;
        this.authorId = authorId;
        this.title = title;
        this.content = content;
        this.pinned = false;
        this.notice = notice;
        this.status = PostStatus.VISIBLE;
    }

    /**
     * 공지 지정 권한({@code LEADER}·{@code MANAGER})은 확인하지 않으므로 호출하는 쪽에서 먼저 확인한다(API 명세서 5.5.2).
     */
    public static Post write(Long studyId, Long authorId, String title, String content, boolean notice) {
        return new Post(studyId, authorId, title, content, notice);
    }

    /** 작성자 본인 여부와 공개 상태는 호출하는 쪽에서 먼저 확인한다(API 명세서 5.5.4). */
    public void edit(String title, String content) {
        this.title = title;
        this.content = content;
    }

    /** 행을 지우지 않고 작성자 삭제 상태로 바꾼다(ERD 4.5.12 {@code status}). */
    public void delete() {
        this.status = PostStatus.DELETED;
    }

    /**
     * 운영진이 고정·공지·숨김을 한 번에 정한다. 숨김을 풀면 다시 공개된다(API 명세서 5.5.5).
     * 운영진 권한과 작성자 삭제 여부는 호출하는 쪽에서 먼저 확인한다.
     */
    public void moderate(boolean pinned, boolean notice, boolean hidden) {
        this.pinned = pinned;
        this.notice = notice;
        this.status = hidden ? PostStatus.HIDDEN : PostStatus.VISIBLE;
    }
}
