package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.hint.exception.HintDailyLimitExceededException;
import com.backtoback.study.hint.repository.HintDailyUsageRepository;

import lombok.RequiredArgsConstructor;

/**
 * 일일 힌트 횟수를 원자적으로 확보한다 (api-spec §6.1.1 step⑤, 한도 20회).
 * UPDATE → INSERT IGNORE → retry 패턴으로 경합 없이 한도를 보장한다.
 *
 * @Transactional 없음 — HintTransactionalService의 트랜잭션에 참여한다.
 */
@Service
@RequiredArgsConstructor
public class HintDailyLimitService {

    public static final int DAILY_LIMIT = 20;

    private final Clock clock;
    private final HintDailyUsageRepository hintDailyUsageRepository;

    public int getTodayUsedCount(Long memberId) {
        LocalDate today = LocalDate.now(clock.withZone(ClockConfiguration.SERVICE_ZONE));
        return hintDailyUsageRepository.findUsedCountByMemberIdAndUsageDate(memberId, today).orElse(0);
    }

    public int reserve(Long memberId) {
        LocalDate today = LocalDate.now(clock.withZone(ClockConfiguration.SERVICE_ZONE));

        int updated = hintDailyUsageRepository.incrementIfBelowLimit(memberId, today, DAILY_LIMIT);
        if (updated == 0) {
            hintDailyUsageRepository.insertIgnore(memberId, today);
            updated = hintDailyUsageRepository.incrementIfBelowLimit(memberId, today, DAILY_LIMIT);
            if (updated == 0) {
                throw new HintDailyLimitExceededException();
            }
        }

        return hintDailyUsageRepository.findUsedCountByMemberIdAndUsageDate(memberId, today).orElse(DAILY_LIMIT);
    }
}
