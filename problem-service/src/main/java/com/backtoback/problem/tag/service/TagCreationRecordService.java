package com.backtoback.problem.tag.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.problem.tag.entity.TagCreationRecord;
import com.backtoback.problem.tag.repository.TagCreationRecordRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TagCreationRecordService {
    private static final int RETENTION_HOURS = 24;
    private static final int CLEANUP_BATCH_SIZE = 500;

    private final TagCreationRecordRepository creationRecords;
    private final Clock clock;

    /**
     * 태그 생성 요청을 잠그고 만료된 기록을 새 요청으로 갱신한다.
     * 태그 저장 트랜잭션에서 호출해야 한다. 새 요청은 24시간 동안 유효하고 재조회는 만료를 연장하지 않는다.
     *
     * @param adminId 요청한 관리자 ID
     * @param key 관리자별 중복 요청 식별 키
     * @param requestName 원본 요청의 태그 이름
     * @param requestDisplayOrder 원본 요청의 표시 순서
     * @return 현재 트랜잭션에서 변경할 수 있는 요청 기록
     * @throws org.springframework.transaction.IllegalTransactionStateException 호출 시 활성 트랜잭션이 없는 경우
     * @throws IllegalStateException 등록 처리 후 요청 기록을 찾지 못한 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TagCreationRecord lock(String adminId, String key, String requestName, int requestDisplayOrder) {
        LocalDateTime now = LocalDateTime.now(clock);
        creationRecords
            .insertIfAbsent(adminId, key, requestName, requestDisplayOrder, now.plusHours(RETENTION_HOURS), now);
        TagCreationRecord creationRecord
            = creationRecords
                .findForUpdate(adminId, key)
                .orElseThrow(() -> new IllegalStateException("Tag creation record is missing after insertion"));
        // 잠금 대기 중 만료될 수도 있으므로 획득 후 현재 시각을 다시 확인한다.
        now = LocalDateTime.now(clock);
        if (creationRecord.isExpiredAt(now)) {
            creationRecord.renew(requestName, requestDisplayOrder, now.plusHours(RETENTION_HOURS));
        }
        return creationRecord;
    }

    /**
     * 처리 중인 기록을 건너뛰고 만료된 태그 생성 요청을 최대 500개 삭제한다.
     * READ_COMMITTED 트랜잭션에서 대상 조회와 삭제를 함께 수행한다.
     *
     * @return 삭제한 요청 기록 수
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int deleteExpired() {
        List<Long> ids = creationRecords.findExpiredIdsForUpdate(LocalDateTime.now(clock), CLEANUP_BATCH_SIZE);
        if (ids.isEmpty()) {
            return 0;
        }
        creationRecords.deleteAllByIdInBatch(ids);
        return ids.size();
    }
}
