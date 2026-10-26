package com.backtoback.problem.tag.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.problem.tag.domain.TagCreationRecord;
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
     * 만료 정리 회차에서 잠금 없이 조회할 후보 ID를 최대 500개 반환한다.
     * 후보 조회 트랜잭션은 건별 삭제 트랜잭션과 분리해 종료한다.
     *
     * @return 만료 시각과 ID 순으로 조회한 삭제 후보 ID 목록
     */
    @Transactional(
        readOnly = true,
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRES_NEW
    )
    public List<Long> findExpiredIdsForCleanup() {
        return creationRecords.findExpiredIds(LocalDateTime.now(clock), CLEANUP_BATCH_SIZE);
    }

    /**
     * 만료 후보 하나를 별도 트랜잭션에서 다시 확인하고, 아직 만료된 경우에만 삭제한다.
     *
     * @param id 만료 후보 요청 기록 ID
     * @return 잠금 획득 및 재확인 후 삭제했으면 {@code true}
     */
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRES_NEW
    )
    public boolean deleteExpiredById(Long id) {
        TagCreationRecord record = creationRecords.findByIdForUpdateSkipLocked(id).orElse(null);
        if (record == null || !record.isExpiredAt(LocalDateTime.now(clock))) {
            return false;
        }
        creationRecords.delete(record);
        return true;
    }
}
