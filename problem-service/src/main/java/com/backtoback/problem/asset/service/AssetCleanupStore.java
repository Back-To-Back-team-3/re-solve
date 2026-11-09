package com.backtoback.problem.asset.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;

import com.backtoback.problem.asset.domain.AssetKind;

import lombok.RequiredArgsConstructor;

/** 파일 한 행의 삭제 선점과 S3 삭제 이후 정리를 별도 트랜잭션에서 수행한다. */
@Service
@RequiredArgsConstructor
public class AssetCleanupStore {
    private static final String LOCK_NAME = "UnlinkedAssetCleanupScheduler";
    private static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * DB 시각을 사용하는 ShedLock을 회차별 고유 토큰으로 획득한다.
     *
     * @return 획득한 소유권 토큰 또는 빈 값
     */
    public Optional<String> acquireLease() {
        var owner = UUID.randomUUID().toString();
        var provider
            = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration
                    .builder()
                    .withJdbcTemplate(jdbc)
                    .usingDbTime()
                    .withLockedByValue(owner)
                    .build()
            );
        return provider
            .lock(new LockConfiguration(Instant.now(clock), LOCK_NAME, LOCK_DURATION, Duration.ZERO))
            .map(lock -> owner);
    }

    /**
     * 현재 회차가 획득한 잠금만 해제한다.
     *
     * @param owner 회차의 소유권 토큰
     */
    public void releaseLease(String owner) {
        jdbc.update("UPDATE shedlock SET lock_until=UTC_TIMESTAMP(3) WHERE name=? AND locked_by=?", LOCK_NAME, owner);
    }

    /**
     * 네 파일 종류에서 복구·종료 확인·정리 후보를 합계 500건까지 조회한다.
     *
     * @return 잠금 없는 후보
     */
    @Transactional(
        readOnly = true,
        propagation = Propagation.REQUIRES_NEW,
        timeout = 10
    )
    public List<Candidate> candidates() {
        var parts = new ArrayList<String>();
        var args = new ArrayList<Object>();
        var now = LocalDateTime.now(clock);
        for (var kind : AssetKind.values()) {
            parts
                .add(
                    "SELECT '" + kind.name() + "' kind,id,created_at,updated_at,"
                        + " CASE WHEN status='DELETING' THEN 0 "
                        + "WHEN upload_finished_at IS NOT NULL THEN 1 ELSE 2 END priority" + " FROM " + kind.table()
                        + " WHERE status='DELETING' OR "
                        + "(status IN ('PENDING','DISCARDED') AND upload_finished_at<=?) OR "
                        + "(status='PENDING' AND upload_finished_at IS NULL AND upload_expires_at<=?)"
                );
            args.add(now.minusHours(24));
            args.add(now);
        }
        return jdbc
            .query(
                "SELECT kind,id FROM (" + String.join(" UNION ALL ", parts)
                    + ") candidates ORDER BY updated_at,priority,created_at,kind,id LIMIT 500",
                (rs, row) -> new Candidate(AssetKind.valueOf(rs.getString("kind")), rs.getLong("id")),
                args.toArray()
            );
    }

    /**
     * 시도 시각을 별도 커밋해 반복 실패·종료 미확인 파일이 다음 후보를 막지 않게 한다.
     * 업로드 종료·유예 시각과 실패 분류는 변경하지 않는다.
     *
     * @param candidate 선정된 파일
     * @return 잠금을 획득하고 시도 순서를 갱신했는지 여부
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        timeout = 10
    )
    public boolean recordAttempt(Candidate candidate) {
        var rows = lock(candidate);
        if (rows.isEmpty() || "LINKED".equals(rows.getFirst().status())) {
            return false;
        }
        jdbc
            .update(
                "UPDATE " + candidate.kind().table() + " SET updated_at=? WHERE id=?",
                LocalDateTime.now(clock),
                candidate.id()
            );
        return true;
    }

    /**
     * 잠긴 후보는 건너뛰며 종료·유예·참조를 확인해 DELETING을 커밋한다.
     *
     * @param candidate 후보
     * @return 선점 결과와 불변 파일 정보
     * @throws IllegalStateException LINKED 이외 상태의 참조 불일치
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        timeout = 10
    )
    public Claim claim(Candidate candidate) {
        var rows = lock(candidate);
        if (rows.isEmpty()) {
            return new Claim("skipped", null);
        }
        var file = rows.getFirst();
        if ("LINKED".equals(file.status())) {
            return new Claim("skipped", null);
        }
        requireUnreferenced(candidate);
        if (file.finishedAt() == null) {
            return new Claim("unconfirmed", null);
        }
        if (file.finishedAt().isAfter(LocalDateTime.now(clock).minusHours(24))) {
            return new Claim("skipped", null);
        }
        if (!List.of("PENDING", "DISCARDED", "DELETING").contains(file.status())) {
            return new Claim("skipped", null);
        }
        jdbc
            .update(
                "UPDATE " + candidate.kind().table() + " SET status='DELETING',updated_at=? WHERE id=?",
                LocalDateTime.now(clock),
                candidate.id()
            );
        return new Claim("claimed", file);
    }

    /**
     * S3 삭제 뒤 동일 파일의 실제 참조를 다시 검사하고 파일 행만 삭제한다.
     *
     * @param expected 삭제한 파일의 불변 메타 정보
     * @param owner 정리 회차의 ShedLock 소유권 토큰
     * @return 실제 DB 삭제 여부
     * @throws IllegalStateException 상태·참조·객체 정보 불일치
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        timeout = 10
    )
    public boolean complete(File expected, String owner) {
        // 재획득과 최종 삭제를 같은 잠금 행으로 직렬화하고 커밋까지 소유권을 보호한다.
        var leases = jdbc.queryForList("""
            SELECT locked_by FROM shedlock WHERE name=? AND locked_by=?
                AND lock_until>UTC_TIMESTAMP(3) FOR UPDATE
            """, String.class, LOCK_NAME, owner);
        if (leases.isEmpty()) {
            return false;
        }
        var rows = lock(expected.candidate());
        if (rows.isEmpty()) {
            return false;
        }
        var current = rows.getFirst();
        if (!"DELETING".equals(current.status()) || !Objects.equals(expected.key(), current.key())
            || !Objects.equals(expected.version(), current.version())
            || !Objects.equals(expected.finishedAt(), current.finishedAt()) || current.finishedAt() == null
            || !Objects.equals(expected.sha256(), current.sha256()) || expected.sizeBytes() != current.sizeBytes()) {
            throw new IllegalStateException("삭제 파일의 상태·메타 정보가 변경되었습니다.");
        }
        requireUnreferenced(expected.candidate());
        return jdbc
            .update("DELETE FROM " + expected.candidate().kind().table() + " WHERE id=?", expected.candidate().id())
            == 1;
    }

    /**
     * 가장 오래 보존된 미연결·삭제 중 파일의 경과 초를 조회한다.
     *
     * @param deleting 삭제 중만 조회할지 여부
     * @return 경과 초, 대상이 없으면 0
     */
    @Transactional(
        readOnly = true,
        propagation = Propagation.REQUIRES_NEW,
        timeout = 10
    )
    public double oldestAge(boolean deleting) {
        LocalDateTime oldest = null;
        for (var kind : AssetKind.values()) {
            var value
                = jdbc
                    .queryForObject(
                        "SELECT MIN(created_at) FROM " + kind.table()
                            + (deleting ? " WHERE status='DELETING'" : " WHERE status IN ('PENDING','DISCARDED')"),
                        LocalDateTime.class
                    );
            if (value != null && (oldest == null || value.isBefore(oldest))) {
                oldest = value;
            }
        }
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, LocalDateTime.now(clock)).toSeconds());
    }

    /**
     * 같은 파일에 대한 연결과 삭제를 직렬화한다.
     *
     * @param candidate 대상 파일
     * @return 잠근 파일 또는 빈 목록
     */
    private List<File> lock(Candidate candidate) {
        return jdbc
            .query(
                "SELECT status,object_key,object_version,upload_finished_at,sha256,size_bytes FROM "
                    + candidate.kind().table() + " WHERE id=? FOR UPDATE SKIP LOCKED",
                (rs, row) -> new File(
                    candidate,
                    rs.getString("status"),
                    rs.getString("object_key"),
                    rs.getString("object_version"),
                    rs.getObject("upload_finished_at", LocalDateTime.class),
                    rs.getString("sha256"),
                    rs.getLong("size_bytes")
                ),
                candidate.id()
            );
    }

    /**
     * 과거 회차와 실패 검증 요청까지 모든 실제 참조를 확인한다.
     *
     * @param candidate 파일
     * @throws IllegalStateException 실제 참조 존재
     */
    private void requireUnreferenced(Candidate candidate) {
        var kind = candidate.kind();
        if (!jdbc.queryForList(kind.reference(), Long.class, candidate.id()).isEmpty()
            || (kind.additionalReference() != null
                && !jdbc.queryForList(kind.additionalReference(), Long.class, candidate.id()).isEmpty())) {
            throw new IllegalStateException("미연결 파일에 실제 참조가 존재합니다.");
        }
    }

    /** 종류와 ID로 구별한 후보 파일이다. */
    public record Candidate(AssetKind kind, long id) {
    }
    /** 삭제 전에 고정한 파일 정보다. */
    public record File(
        Candidate candidate,
        String status,
        String key,
        String version,
        LocalDateTime finishedAt,
        String sha256,
        long sizeBytes
    ) {
    }
    /** 건너뜀·종료 미확인·삭제 선점 결과다. */
    public record Claim(String outcome, File file) {
    }
}
