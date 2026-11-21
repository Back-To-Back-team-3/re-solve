package com.backtoback.study.replica.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.event.dto.LearningProfileUpdatedPayload;
import com.backtoback.study.replica.event.dto.LearningProfileUpdatedPayload.TagLevel;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * 학습 프로필 변경 이벤트를 소비해 학습 프로필 복제본을 교체한다(event-contract.md §M4, ERD 4.5.10).
 * 추천 재계산은 하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningProfileReplicaConsumerService {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final JdbcProcessedEventStore processedEventStore;
    private final MemberReplicaRepository memberReplicaRepository;
    private final MemberLearningProfileReplicaRepository learningProfileReplicaRepository;

    /**
     * 학습 프로필 스냅샷을 복제본에 반영한다. 같은 이벤트는 한 번만 처리하고, 순서 값이 같거나 낮은 이벤트는
     * 저장소 조건부 쿼리가 무시한다. 탈퇴한 회원의 이벤트는 소비 기록만 남긴다.
     * 태그 레벨은 {@code {tagId: level}} 맵으로, 빈 목록은 미입력({@code null})으로 저장한다(ERD 4.5.10).
     *
     * @throws RuntimeException 페이로드가 계약과 다를 때(검증 실패는 {@code IllegalArgumentException}, 헤더 형식 오류는
     *     페이로드의 {@code toMetadata()} 참고). 롤백 후 재수신을 거쳐 DLQ로 격리된다.
     */
    // 회원 복제본이 아직 없을 때 탈퇴 반영과의 직렬화는 FOR SHARE의 갭 잠금에 기대므로, 갭 잠금이 없는
    // READ COMMITTED로 운영 기본값이 바뀌어도 보호되도록 격리 수준을 고정한다.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void consume(LearningProfileUpdatedPayload payload) {
        // 소비 기록을 첫 문장으로 남긴다. 이후 예외로 롤백되면 기록도 함께 롤백되어 재수신 때 다시 처리한다(event-contract.md §5.1).
        processedEventStore.insert(payload.toMetadata(), now());
        payload.validate();
        Long memberId = payload.memberIdAsLong();

        // 탈퇴 시 복제본을 물리 삭제하므로 늦게 도착한 이벤트가 행을 되살리지 않게 회원 복제본 상태로 거른다(정책 및 상태 §9.4).
        // 탈퇴 소비와 같은 회원 복제본 행을 잠금 조회해 둘 중 먼저 커밋한 쪽의 결과를 보고 진행한다.
        boolean withdrawn
            = memberReplicaRepository
                .findByMemberIdWithForShare(memberId)
                .map(replica -> replica.getMemberStatus() == MemberStatus.WITHDRAWN)
                .orElse(false);
        if (withdrawn) {
            log
                .info(
                    "LearningProfileUpdated 탈퇴 회원 이벤트 무시 memberId={} profileVersion={}",
                    memberId,
                    payload.profileVersion()
                );
            return;
        }

        learningProfileReplicaRepository
            .upsertFromLearningProfileUpdated(
                memberId,
                payload.goal(),
                toTagLevelsJson(payload.tagLevels()),
                toJsonArrayOrNull(payload.preferredLanguages()),
                toJsonArrayOrNull(payload.activeTimeSlots()),
                payload.profileVersion(),
                now()
            );
    }

    private String toTagLevelsJson(List<TagLevel> tagLevels) {
        if (tagLevels.isEmpty()) {
            return null;
        }
        // 중복 태그 ID는 validate()가 거부한다. 입력 순서를 유지해 저장한다.
        Map<String, String> levelByTagId = new LinkedHashMap<>();
        tagLevels.forEach(tagLevel -> levelByTagId.put(tagLevel.tagId(), tagLevel.level()));
        return objectMapper.writeValueAsString(levelByTagId);
    }

    private String toJsonArrayOrNull(List<String> values) {
        return values.isEmpty() ? null : objectMapper.writeValueAsString(values);
    }

    private LocalDateTime now() {
        // DATETIME(6)은 초과 자릿수를 반올림하므로 저장 전에 마이크로초로 자른다.
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
