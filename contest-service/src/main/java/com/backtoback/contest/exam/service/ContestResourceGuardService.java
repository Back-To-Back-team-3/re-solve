package com.backtoback.contest.exam.service;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.LockedGuard;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/**
 * 신규 편입 트랜잭션에서 필요한 가드를 잠그고 차단 상태를 확인한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.16 자원 작업 가드: 준비·차단 상태의 신규 편입 제한</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ContestResourceGuardService {
    private final ContestResourceGuardRepository guards;

    /**
     * 모든 가드를 정해진 순서로 잠근 후 열린 자원만 신규 편입할 수 있게 한다.
     *
     * @param resources 신규 편입에 필요한 자원
     * @return 트랜잭션이 끝날 때까지 잠긴 가드
     * @throws ContestException 준비 중이거나 차단된 자원이 있는 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LockedGuard> lockForEnrollment(Collection<Resource> resources) {
        var locked = guards.lockAll(resources);
        if (locked.stream().anyMatch(guard -> !"OPEN".equals(guard.state()))) {
            throw new ContestException(ContestErrorCode.CONTEST_OPERATION_BLOCKED);
        }
        return locked;
    }
}
