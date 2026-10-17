package com.backtoback.problem.tag.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.problem.global.exception.custom.ProblemVersionConflictException;
import com.backtoback.problem.global.lock.TransactionLock;
import com.backtoback.problem.tag.entity.Tag;
import com.backtoback.problem.tag.repository.TagRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TagOrderService {
    private static final String ORDER_LOCK_KEY = "problem:tags:order:lock";

    private final TagRepository tagRepository;
    private final TransactionLock transactionLock;

    /**
     * 태그 변경을 직렬화할 공통 잠금을 현재 트랜잭션 종료까지 획득한다.
     * 태그 저장 트랜잭션에서 순번 조회·변경 전에 호출한다.
     *
     * @throws ProblemVersionConflictException 대기 시간 안에 잠금을 획득하지 못한 경우
     * @throws org.springframework.transaction.IllegalTransactionStateException 활성 트랜잭션이 없는 경우
     */
    public void lock() {
        if (!transactionLock.acquire(ORDER_LOCK_KEY)) {
            throw new ProblemVersionConflictException();
        }
    }

    /**
     * 새 태그를 삽입할 위치를 보정하고 기존 태그의 순번을 맞춘다.
     * 공통 태그 잠금을 먼저 획득해야 한다. 요청 위치는 0부터 기존 태그 개수까지로 보정한다.
     * 순번을 바꿀 태그는 임시 순번을 거쳐 UNIQUE 충돌 없이 변경한다.
     *
     * @param displayOrder 새 태그를 삽입할 요청 순번
     * @return 실제로 삽입할 순번
     * @throws org.springframework.transaction.IllegalTransactionStateException 활성 트랜잭션이 없는 경우
     */
    public int makeRoomFor(int displayOrder) {
        List<Tag> tags = tagRepository.findAllForUpdate();
        int position = Math.clamp(displayOrder, 0, tags.size());
        Map<Tag, Integer> targetOrders = new LinkedHashMap<>();
        for (int index = 0; index < tags.size(); index++) {
            Tag tag = tags.get(index);
            // 삽입 위치만 비우고 나머지 순번은 0부터 연속되게 맞춘다.
            int nextOrder = index < position ? index : index + 1;
            if (tag.getDisplayOrder() != nextOrder) {
                targetOrders.put(tag, nextOrder);
            }
        }
        applyOrders(targetOrders);
        return position;
    }

    /**
     * 기존 태그를 요청 위치로 이동하고 전체 순번을 0부터 연속되게 맞춘다.
     * 공통 태그 잠금을 먼저 획득해야 한다. 범위를 벗어난 위치는 첫 번째나 마지막으로 보정한다.
     *
     * @param tag 이동할 태그
     * @param displayOrder 이동할 요청 순번
     * @throws org.springframework.transaction.IllegalTransactionStateException 활성 트랜잭션이 없는 경우
     */
    public void move(Tag tag, int displayOrder) {
        List<Tag> tags = tagRepository.findAllForUpdate();
        tags.removeIf(existing -> existing.getId().equals(tag.getId()));
        int position = Math.clamp(displayOrder, 0, tags.size());
        tags.add(position, tag);
        Map<Tag, Integer> targetOrders = new LinkedHashMap<>();
        for (int index = 0; index < tags.size(); index++) {
            Tag current = tags.get(index);
            if (current.getDisplayOrder() != index) {
                targetOrders.put(current, index);
            }
        }
        applyOrders(targetOrders);
    }

    /**
     * 삭제 후 남은 태그의 순번을 0부터 연속되게 맞춘다.
     * 공통 태그 잠금을 보유한 트랜잭션에서 태그 삭제를 DB에 반영한 뒤 호출한다.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException 활성 트랜잭션이 없는 경우
     */
    public void closeGap() {
        List<Tag> tags = tagRepository.findAllForUpdate();
        Map<Tag, Integer> targetOrders = new LinkedHashMap<>();
        for (int index = 0; index < tags.size(); index++) {
            Tag tag = tags.get(index);
            if (tag.getDisplayOrder() != index) {
                targetOrders.put(tag, index);
            }
        }
        applyOrders(targetOrders);
    }

    /**
     * 순번 변경 대상을 임시 음수 순번에 저장한 뒤 최종 순번으로 변경한다.
     * 커밋된 순번은 0 이상이며, 변경하지 않는 태그는 대상에 포함하지 않는다.
     * 두 번의 flush로 UNIQUE 충돌을 피하고 각 저장 시 낙관적 락 버전과 수정 시각을 갱신한다.
     * 같은 트랜잭션에서 공통 태그 잠금과 대상 행의 쓰기 잠금을 보유해야 한다.
     *
     * @param targetOrders 변경할 태그와 최종 순번
     */
    private void applyOrders(Map<Tag, Integer> targetOrders) {
        if (targetOrders.isEmpty()) {
            return;
        }
        int temporaryOrder = -1;
        for (Tag tag : targetOrders.keySet()) {
            tag.update(null, temporaryOrder--);
        }
        // 모든 변경 대상이 기존 순번을 비운 후에 최종 순번을 배정한다.
        tagRepository.flush();
        targetOrders.forEach((tag, order) -> tag.update(null, order));
        tagRepository.flush();
    }
}
