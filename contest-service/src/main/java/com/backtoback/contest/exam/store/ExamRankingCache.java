package com.backtoback.contest.exam.store;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.backtoback.contest.exam.config.ExamRankingCacheProperties;
import com.backtoback.contest.exam.service.ExamResultQueryService.Standing;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 전체 시험 순위를 결과 버전 단위로 교체하는 조회 전용 캐시다. */
@Component
@RequiredArgsConstructor
public class ExamRankingCache {
    private static final Logger log = LoggerFactory.getLogger(ExamRankingCache.class);
    private static final DefaultRedisScript<Long> REPLACE = new DefaultRedisScript<>("""
        local old = redis.call('GET', KEYS[1])
        if old and string.sub(old, 1, 19) > ARGV[1] then return 0 end
        redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
        redis.call('SET', KEYS[1], ARGV[1] .. KEYS[2], 'EX', ARGV[3])
        if old then
            local oldkey = string.sub(old, 20)
            if oldkey ~= KEYS[2] then redis.call('DEL', oldkey) end
        end
        return 1
        """, Long.class);
    private static final DefaultRedisScript<String> READ = new DefaultRedisScript<>("""
        local pointer = redis.call('GET', KEYS[1])
        if not pointer or string.sub(pointer, 1, 19) ~= ARGV[1] then return nil end
        return redis.call('GET', string.sub(pointer, 20))
        """, String.class);
    private static final DefaultRedisScript<Long> EVICT = new DefaultRedisScript<>("""
        local old = redis.call('GET', KEYS[1])
        if not old or string.sub(old, 1, 19) > ARGV[1] then return 0 end
        redis.call('DEL', string.sub(old, 20))
        return redis.call('DEL', KEYS[1])
        """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ExamRankingCacheProperties properties;

    /** 버전이 일치하지 않거나 Redis에 장애가 있으면 DB 조회를 선택한다. */
    public List<Standing> read(long examId, long revision) {
        try {
            String json = redis.execute(READ, List.of(prefix(examId) + ":current"), version(revision));
            return json == null ? null : Arrays.asList(mapper.readValue(json, Standing[].class));
        } catch (RuntimeException failure) {
            log.warn("시험 순위 캐시 조회 실패: examId={}, type={}", examId, failure.getClass().getSimpleName());
            return null;
        }
    }

    /** 버전 역행을 거절하며 전체 데이터와 포인터를 한 번에 교체한다. */
    public void replace(long examId, long revision, List<Standing> items) {
        String prefix = prefix(examId);
        var stable
            = items
                .stream()
                .map(
                    row -> new Standing(
                        row.participantId(),
                        row.memberId(),
                        null,
                        row.score(),
                        row.rank(),
                        row.pending()
                    )
                )
                .toList();
        redis
            .execute(
                REPLACE,
                List.of(prefix + ":current", prefix + ":v:" + revision),
                version(revision),
                mapper.writeValueAsString(stable),
                Long.toString(properties.ttlSeconds())
            );
    }

    /** 관측 버전 이하만 제거해 동시에 갱신된 새 결과 캐시는 보존한다. */
    public void evict(long examId, long revision) {
        redis.execute(EVICT, List.of(prefix(examId) + ":current"), version(revision));
    }

    private String prefix(long examId) {
        return "exam:ranking:{" + examId + "}";
    }

    /** Lua 부동소수점 변환 없이 BIGINT 결과 버전을 비교한다. */
    private String version(long revision) {
        return String.format(java.util.Locale.ROOT, "%019d", revision);
    }
}
