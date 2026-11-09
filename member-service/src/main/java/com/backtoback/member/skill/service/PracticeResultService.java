package com.backtoback.member.skill.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.skill.store.PracticeResultStore;
import com.backtoback.member.skill.store.PracticeResultStore.Previous;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PracticeResultService {

    private final MemberRepository memberRepository;
    private final PracticeResultStore store;
    private final Clock clock;

    /**
     * 연습 채점 결과를 반영한다 (정책 및 상태 8.3 처리 순서 ②~④). 공통 소비기의 트랜잭션({@code processed_events} 기록)
     * 안에서 호출된다. 같은 회원의 결과가 동시에 와도 성취도를 덮어쓰지 않도록 회원 행을 먼저 잠근다.
     *
     * @return 반영했으면 {@code true}, 저장된 회차보다 작거나 같아 무시했으면 {@code false}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean apply(PracticeResult result) {
        if (memberRepository.findForUpdateById(result.memberId()).isEmpty()) {
            // 재전달로 해결되지 않으므로 DLQ로 보내 확인한다.
            throw new IllegalArgumentException("Unknown member for practice result");
        }
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        Optional<Previous> replaced = store.replace(result, now);
        if (replaced.isEmpty()) {
            return false;
        }
        Previous previous = replaced.get();
        // 재채점으로 태그·난이도 스냅샷이 바뀌었으면 이전 조합도 다시 계산한다.
        previous.tagIds().forEach(tagId -> store.recalculate(result.memberId(), tagId, previous.difficulty(), now));
        result.tagIds().forEach(tagId -> store.recalculate(result.memberId(), tagId, result.difficulty(), now));
        return true;
    }
}
