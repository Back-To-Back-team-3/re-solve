package com.backtoback.study.community.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.community.domain.Post;
import com.backtoback.study.community.domain.PostStatus;
import com.backtoback.study.community.dto.PostModerationRequest;
import com.backtoback.study.community.dto.PostModerationResponse;
import com.backtoback.study.community.repository.PostRepository;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyRepository;

import lombok.RequiredArgsConstructor;

/**
 * 운영진의 게시글 상단 고정·공지·숨김 변경을 처리한다(API 명세서 5.5.5). 정지 회원 확인은 트랜잭션 밖에서 호출하는 쪽이 먼저 한다.
 */
@Service
@RequiredArgsConstructor
public class PostModerationService {

    private final StudyRepository studyRepository;
    private final PostRepository postRepository;
    private final CommunityAccessChecker accessChecker;

    /**
     * 숨긴 글도 대상으로 삼아 숨김을 풀 수 있다. 작성자가 삭제한 글은 되살리지 않는다.
     *
     * @throws StudyException {@code STUDY_NOT_FOUND}, {@code STUDY_ALREADY_CLOSED},
     *     {@code AUTH_ACCESS_DENIED}: 운영진이 아님, {@code STUDY_POST_NOT_FOUND}: 다른 스터디 글이거나 삭제 상태
     */
    @Transactional
    public PostModerationResponse moderate(Long studyId, Long postId, Long memberId, PostModerationRequest request) {
        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
        if (study.isClosed()) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
        }
        StudyMembership operator = accessChecker.requireApprovedMember(studyId, memberId);
        if (!accessChecker.isOperator(operator)) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        // 작성자의 수정·삭제와 같은 행 잠금을 써서, 잠금 뒤의 상태로 삭제 여부를 판단한다.
        Post post
            = postRepository
                .findByIdAndStudyIdWithLock(postId, studyId)
                .filter(found -> found.getStatus() != PostStatus.DELETED)
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_POST_NOT_FOUND));
        post.moderate(request.isPinned(), request.isNotice(), request.isHidden());
        return PostModerationResponse.from(post);
    }
}
