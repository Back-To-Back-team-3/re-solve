package com.backtoback.judge.judging.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;

/** 스냅샷 해시의 안정성과 필드 경계·NULL 표식 보존을 검증한다. */
class ExecutionRuntimeTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-27T09:00:00");

    /** 같은 속성은 생성 시각과 관계없이 같은 버전 1 해시를 가진다. */
    @Test
    @DisplayName("실행 환경 해시는 명세의 직렬화 순서와 SHA-256 결과를 유지한다")
    void hashesStableSnapshot() {
        var runtime = new JudgeRuntime("java", 91, "Java (OpenJDK 17.0.6)", "17.0.6", null);
        String hash = ExecutionRuntime.snapshot(runtime, NOW).getSnapshotHash();
        assertThat(hash).isEqualTo("fbc2a5e3e4cc2495a37b2e73b4824478ee5103e910cbd21ec1e109defd598c9c");
        assertThat(ExecutionRuntime.snapshot(runtime, NOW.plusDays(1)).getSnapshotHash()).isEqualTo(hash);
    }

    /** 옵션의 없음과 빈 문자열을 서로 다른 스냅샷으로 구분한다. */
    @Test
    @DisplayName("스냅샷 해시는 NULL·빈 문자열·실제 옵션을 구분한다")
    void distinguishesNullAndEmptyOptions() {
        var absent = new JudgeRuntime("java", 91, "Java", "17", null);
        var empty = new JudgeRuntime("java", 91, "Java", "17", "");
        var options = new JudgeRuntime("java", 91, "Java", "17", "-g");
        assertThat(ExecutionRuntime.snapshot(absent, NOW).getSnapshotHash())
            .isNotEqualTo(ExecutionRuntime.snapshot(empty, NOW).getSnapshotHash())
            .isNotEqualTo(ExecutionRuntime.snapshot(options, NOW).getSnapshotHash());
    }

    /** 단순 문자열 연결 결과가 같아도 필드별 길이를 포함해 구분한다. */
    @Test
    @DisplayName("이름과 버전의 문자열 연결 결과가 같아도 필드 경계가 다르면 다른 해시를 만든다")
    void preservesFieldBoundaries() {
        var first = new JudgeRuntime("java", 91, "Java1", "7.0.6", null);
        var second = new JudgeRuntime("java", 91, "Java", "17.0.6", null);
        assertThat(first.runtimeName() + first.runtimeVersion())
            .isEqualTo(second.runtimeName() + second.runtimeVersion());
        assertThat(ExecutionRuntime.snapshot(first, NOW).getSnapshotHash())
            .isNotEqualTo(ExecutionRuntime.snapshot(second, NOW).getSnapshotHash());
    }
}
