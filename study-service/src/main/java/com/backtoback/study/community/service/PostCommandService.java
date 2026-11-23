package com.backtoback.study.community.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.community.domain.Post;
import com.backtoback.study.community.dto.PostCreateRequest;
import com.backtoback.study.community.repository.PostRepository;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyRepository;

import lombok.RequiredArgsConstructor;

/**
 * 구성원의 게시글 작성을 처리한다(API 명세서 5.5.2). 정지 회원 확인은 member 원격 조회가 있을 수 있어
 * 이 서비스의 트랜잭션 밖에서 호출하는 쪽이 먼저 한다.
 */
@Service
@RequiredArgsConstructor
public class PostCommandService {

    private final StudyRepository studyRepository;
    private final PostRepository postRepository;
    private final CommunityAccessChecker accessChecker;
    private final Clock clock;

    /**
     * 게시글을 저장하고 같은 트랜잭션에서 스터디 최근 활동 시각을 갱신한다.
     *
     * @return 저장한 게시글 ID
     * @throws StudyException {@code STUDY_NOT_FOUND}, {@code STUDY_ALREADY_CLOSED},
     *     {@code AUTH_ACCESS_DENIED}: {@code APPROVED} 구성원이 아니거나 운영진이 아닌데 공지로 작성
     */
    @Transactional
    public Long write(Long studyId, Long memberId, PostCreateRequest request) {
        // 회원 제거 경로와 같은 순서(studies → study_memberships)로 잠가 작성 중 구성원 제거와 직렬화한다(scheduler.md §5.0).
        Study study
            = studyRepository
                .findByIdWithLock(studyId)
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
        if (study.isClosed()) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
        }
        StudyMembership author = accessChecker.requireApprovedMember(studyId, memberId);
        if (request.notice() && !accessChecker.isOperator(author)) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        Post post
            = postRepository.save(Post.write(studyId, memberId, request.title(), request.content(), request.notice()));
        study.recordActivity(now());
        return post.getId();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
    }
}
