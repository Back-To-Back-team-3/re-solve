package com.backtoback.contest.exam.repository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;

import lombok.RequiredArgsConstructor;

/**
 * 신규 편입과 자원 작업이 같은 행에서 직렬화되도록 가드를 확보하고 잠근다.
 * 잠금은 호출자의 트랜잭션이 끝날 때까지 유지한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.16 자원 작업 가드·§6.4.1 접수·마감·자동 제출: 유일 행과 잠금 순서</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class ContestResourceGuardRepository {
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /** 자원 종류와 소유 서비스의 식별자다. */
    public record Resource(String type, long id) {
        /** 지원하는 자원 종류와 양의 식별자만 받는다. */
        public Resource {
            if (!("MEMBER".equals(type) || "PROBLEM".equals(type) || "STUDY".equals(type)) || id <= 0) {
                throw new IllegalArgumentException("Invalid resource");
            }
        }
    }

    /** 잠긴 자원과 가드 상태다. */
    public record LockedGuard(Resource resource, String state) {
    }

    /**
     * 자원별 유일 행을 확보하고 종류와 식별자 순으로 잠근다.
     * 부모 시험과 참가자는 이 메서드가 반환된 뒤 잠가야 한다.
     *
     * @param resources 잠글 자원 목록
     * @return 중복을 제거하고 잠금 순서로 정렬한 가드
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LockedGuard> lockAll(Collection<Resource> resources) {
        var ordered
            = resources
                .stream()
                .distinct()
                .sorted(Comparator.comparing(Resource::type).thenComparingLong(Resource::id))
                .toList();
        var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        return ordered.stream().map(resource -> lock(resource, now)).toList();
    }

    /** 유일 키 충돌을 이용해 최초 생성 경합을 직렬화한 뒤 행을 잠근다. */
    private LockedGuard lock(Resource resource, LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO contest_resource_guards (resource_type, resource_id, state, created_at, updated_at)
            VALUES (?, ?, 'OPEN', ?, ?)
            ON DUPLICATE KEY UPDATE id = id
            """, resource.type(), resource.id(), now, now);
        String state = jdbcTemplate.queryForObject("""
            SELECT state FROM contest_resource_guards
            WHERE resource_type = ? AND resource_id = ? FOR UPDATE
            """, String.class, resource.type(), resource.id());
        return new LockedGuard(resource, state);
    }
}
