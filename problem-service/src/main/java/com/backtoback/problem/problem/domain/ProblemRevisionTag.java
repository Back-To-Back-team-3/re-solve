package com.backtoback.problem.problem.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 문제 수정 회차와 태그의 불변 연결을 기록한다(ERD 4.2.9). */
@Getter
@Entity
@Table(
    name = "problem_revision_tags",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_problem_revision_tags_revision_tag",
        columnNames = {
            "problem_revision_id",
            "tag_id"
        }
    ),
    indexes = @Index(
        name = "idx_problem_revision_tags_tag",
        columnList = "tag_id"
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemRevisionTag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "problem_revision_id",
        nullable = false
    )
    private Long problemRevisionId;

    @Column(
        name = "tag_id",
        nullable = false
    )
    private Long tagId;

    @Column(
        name = "created_at",
        nullable = false
    )
    private LocalDateTime createdAt;

    private ProblemRevisionTag(Long problemRevisionId, Long tagId, LocalDateTime createdAt) {
        this.problemRevisionId = problemRevisionId;
        this.tagId = tagId;
        this.createdAt = createdAt;
    }

    /** 회차와 태그의 연결을 기록한다. */
    public static ProblemRevisionTag create(Long problemRevisionId, Long tagId, LocalDateTime createdAt) {
        return new ProblemRevisionTag(problemRevisionId, tagId, createdAt);
    }
}
