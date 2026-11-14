package com.backtoback.problem.problem.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 불변 파일에 저장할 바이트와 그 바이트의 체크섬·크기. */
public final class ProblemAssetContent {
    private final byte[] bytes;
    private final String sha256;

    /**
     * 바이트를 복사한 뒤 같은 내용의 해시를 계산한다.
     *
     * @param bytes 저장할 바이트
     * @throws NullPointerException 바이트가 null인 경우
     * @throws IllegalStateException SHA-256을 사용할 수 없는 경우
     */
    private ProblemAssetContent(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes).clone();
        try {
            this.sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this.bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    /**
     * 전달한 바이트를 외부 변경과 분리한다.
     *
     * @param bytes 저장할 바이트
     * @return 복사된 바이트와 메타 정보
     */
    public static ProblemAssetContent fromBytes(byte[] bytes) {
        return new ProblemAssetContent(bytes);
    }

    /**
     * 원본 보호를 위해 별도 배열을 반환한다.
     *
     * @return 저장할 바이트의 복사본
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    public String sha256() {
        return sha256;
    }

    public long sizeBytes() {
        return bytes.length;
    }
}
