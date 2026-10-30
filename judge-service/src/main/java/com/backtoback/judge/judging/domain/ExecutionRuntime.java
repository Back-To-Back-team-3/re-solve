package com.backtoback.judge.judging.domain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

import org.hibernate.annotations.Immutable;
import org.springframework.util.Assert;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 여러 제출에서 공유하며 수정하지 않는 실행 환경 스냅샷이다. */
@Entity
@Table(name = "execution_runtimes")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionRuntime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(
        nullable = false,
        length = 64,
        columnDefinition = "CHAR(64)",
        updatable = false
    )
    private String snapshotHash;
    @Column(
        nullable = false,
        length = 30,
        updatable = false
    )
    private String language;
    @Column(
        nullable = false,
        updatable = false
    )
    private int judge0LanguageId;
    @Column(
        nullable = false,
        length = 255,
        updatable = false
    )
    private String runtimeName;
    @Column(
        nullable = false,
        length = 100,
        updatable = false
    )
    private String runtimeVersion;
    @Column(
        columnDefinition = "TEXT",
        updatable = false
    )
    private String compilerOptions;
    @Column(
        nullable = false,
        columnDefinition = "DATETIME(6)",
        updatable = false
    )
    private LocalDateTime createdAt;

    /**
     * 검증한 실행 환경과 기록 시각으로 스냅샷을 생성한다.
     *
     * @param runtime 선택한 실행 환경
     * @param createdAt 스냅샷 기록 시각
     */
    private ExecutionRuntime(JudgeRuntime runtime, LocalDateTime createdAt) {
        Assert.notNull(runtime, "실행 환경은 필수입니다.");
        JudgeLanguage.fromCode(runtime.language());
        Assert.isTrue(runtime.judge0LanguageId() > 0, "Judge0 실행 환경 ID는 양수여야 합니다.");
        Assert.hasText(runtime.runtimeName(), "실행 환경 이름은 필수입니다.");
        Assert.hasText(runtime.runtimeVersion(), "실행 환경 버전은 필수입니다.");
        Assert.isTrue(runtime.runtimeName().length() <= 255, "실행 환경 이름은 255자 이하여야 합니다.");
        Assert.isTrue(runtime.runtimeVersion().length() <= 100, "실행 환경 버전은 100자 이하여야 합니다.");
        Assert.notNull(createdAt, "스냅샷 기록 시각은 필수입니다.");
        this.snapshotHash = hash(runtime);
        this.language = runtime.language();
        this.judge0LanguageId = runtime.judge0LanguageId();
        this.runtimeName = runtime.runtimeName();
        this.runtimeVersion = runtime.runtimeVersion();
        this.compilerOptions = runtime.compilerOptions();
        this.createdAt = createdAt;
    }

    /**
     * 선택한 실행 환경을 불변 스냅샷으로 만든다.
     *
     * @param runtime 선택한 실행 환경
     * @param createdAt 주입한 {@code Clock} 기준 기록 시각
     * @return 저장할 스냅샷
     * @throws JudgeException {@code UNSUPPORTED_LANGUAGE}: 지원하지 않는 언어인 경우
     * @throws IllegalArgumentException 필수 값이나 길이·ID가 잘못된 경우
     */
    public static ExecutionRuntime snapshot(JudgeRuntime runtime, LocalDateTime createdAt) {
        return new ExecutionRuntime(runtime, createdAt);
    }

    /**
     * 해시가 일치해도 이름·버전·옵션을 포함한 원본 속성의 동일성을 확인한다.
     *
     * @param runtime 비교할 실행 환경
     * @return 모든 속성이 같은지 여부
     */
    public boolean matches(JudgeRuntime runtime) {
        return runtime != null && Objects.equals(language, runtime.language())
            && judge0LanguageId == runtime.judge0LanguageId() && Objects.equals(runtimeName, runtime.runtimeName())
            && Objects.equals(runtimeVersion, runtime.runtimeVersion())
            && Objects.equals(compilerOptions, runtime.compilerOptions());
    }

    /**
     * 저장한 스냅샷을 기존 Judge0 요청 계약으로 복원한다.
     *
     * @return 고정 실행 환경
     */
    public JudgeRuntime toRuntime() {
        return new JudgeRuntime(language, judge0LanguageId, runtimeName, runtimeVersion, compilerOptions);
    }

    /**
     * 버전 1 형식의 전체 속성을 길이·NULL 표식을 포함해 SHA-256으로 식별한다.
     * 문자열은 UTF-8 바이트 길이와 원문, 외부 환경 ID는 32비트 정수로 기록한다.
     * (정책 및 상태 §3.2 채점 실행)
     *
     * @param runtime 해시를 계산할 검증된 실행 환경
     * @return 소문자 16진수 64자 해시
     */
    private static String hash(JudgeRuntime runtime) {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            writeText(output, runtime.language());
            output.writeInt(runtime.judge0LanguageId());
            writeText(output, runtime.runtimeName());
            writeText(output, runtime.runtimeVersion());
            writeText(output, runtime.compilerOptions());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("실행 환경 스냅샷 해시를 계산할 수 없습니다.", exception);
        }
    }

    /**
     * 문자열 경계와 NULL·빈 문자열을 구분할 수 있는 바이트열을 기록한다.
     *
     * @param output 빅엔디언 바이트 출력
     * @param text 원본 문자열; {@code null}이면 길이 -1만 기록
     * @throws IOException 출력 기록에 실패한 경우
     */
    private static void writeText(DataOutputStream output, String text) throws IOException {
        if (text == null) {
            output.writeInt(-1);
            return;
        }
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

}
