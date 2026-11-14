package com.backtoback.integration.github.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitHub Access Token AES-GCM 256비트 암·복호화기 (erd.md > 4.8.1, policy-and-state.md > 9.3).
 * Base64로 인코딩된 256비트(32바이트) 키를 사용하여 키 버전(1B) + 12바이트 IV(Nonce) + 128비트 인증 태그를 포함한 암호문을 생성한다.
 */
@Component
public class TokenEncryptor {

    private static final String ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte KEY_VERSION_V1 = 0x01;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int REQUIRED_KEY_LENGTH_BYTES = 32;
    private static final int MIN_CIPHER_DATA_LENGTH = 1 + GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;

    public TokenEncryptor(@Value("${integration.crypto.secret-key}") String secretKeyBase64) {
        if (secretKeyBase64 == null || secretKeyBase64.isBlank()) {
            throw new IllegalArgumentException("암호화 비밀키는 필수이며 비어 있을 수 없습니다.");
        }

        byte[] decodedKey;
        try {
            decodedKey = Base64.getDecoder().decode(secretKeyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("암호화 비밀키는 올바른 Base64 문자열이어야 합니다.", e);
        }

        if (decodedKey.length != REQUIRED_KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                "암호화 비밀키는 Base64 디코딩 후 정확히 32바이트(256비트)여야 합니다. 현재: " + decodedKey.length + "바이트"
            );
        }

        this.secretKey = new SecretKeySpec(decodedKey, ALGORITHM);
        this.secureRandom = new SecureRandom();
    }

    /**
     * 평문 토큰을 AES-GCM으로 암호화하여 키 버전(1B) + IV(12B) + 암호문(Auth Tag 포함) 바이트 배열을 반환한다.
     *
     * @param plainText 평문 문자열
     * @return 암호화된 바이트 배열 (입력이 null인 경우 null)
     */
    public byte[] encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(1 + iv.length + cipherText.length);
            byteBuffer.put(KEY_VERSION_V1);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);
            return byteBuffer.array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("토큰 암호화 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * 암호화된 바이트 배열(키 버전 + IV + 암호문)을 복호화하여 평문 문자열로 복원한다.
     *
     * @param cipherData 암호화된 바이트 배열
     * @return 복호화된 평문 문자열 (입력이 null인 경우 null)
     * @throws IllegalArgumentException 길이가 부족하거나 지원하지 않는 키 버전인 경우
     * @throws IllegalStateException 복호화 실패 또는 변조된 데이터인 경우
     */
    public String decrypt(byte[] cipherData) {
        if (cipherData == null) {
            return null;
        }

        if (cipherData.length < MIN_CIPHER_DATA_LENGTH) {
            throw new IllegalArgumentException("유효하지 않은 암호문 데이터입니다 (최소 길이 미달).");
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(cipherData);
        byte keyVersion = byteBuffer.get();
        if (keyVersion != KEY_VERSION_V1) {
            throw new IllegalArgumentException("지원하지 않는 암호화 키 버전입니다: " + keyVersion);
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("토큰 복호화 중 오류가 발생했습니다.", e);
        }
    }
}
