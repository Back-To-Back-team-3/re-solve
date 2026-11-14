package com.backtoback.integration.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenEncryptorTest {

    // 32바이트 바이트배열을 Base64 인코딩한 테스트용 키
    private static final String TEST_SECRET_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final TokenEncryptor encryptor = new TokenEncryptor(TEST_SECRET_KEY_BASE64);

    @Test
    @DisplayName("평문 문자열을 암호화한 뒤 복호화하면 원래 문자열로 복원된다")
    void encryptAndDecryptSuccess() {
        String token = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";

        byte[] cipherData = encryptor.encrypt(token);
        String decrypted = encryptor.decrypt(cipherData);

        assertThat(cipherData).isNotNull();
        // 첫 바이트는 키 버전 0x01
        assertThat(cipherData[0]).isEqualTo((byte)0x01);
        assertThat(decrypted).isEqualTo(token);
    }

    @Test
    @DisplayName("동일한 평문이라도 매 암호화마다 랜덤 IV가 적용되어 암호문이 달라진다")
    void randomIvPerEncryption() {
        String token = "ghp_secure_github_token";

        byte[] cipher1 = encryptor.encrypt(token);
        byte[] cipher2 = encryptor.encrypt(token);

        assertThat(cipher1).isNotEqualTo(cipher2);
        assertThat(encryptor.decrypt(cipher1)).isEqualTo(token);
        assertThat(encryptor.decrypt(cipher2)).isEqualTo(token);
    }

    @Test
    @DisplayName("null 입력 시 암·복호화 결과도 null을 반환한다")
    void nullInputReturnsNull() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("길이가 부족한 암호문 입력 시 IllegalArgumentException이 발생한다")
    void shortCipherDataThrowsException() {
        assertThatThrownBy(() -> encryptor.decrypt(new byte[10]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("최소 길이 미달");
    }

    @Test
    @DisplayName("지원하지 않는 키 버전이 포함된 암호문 입력 시 IllegalArgumentException이 발생한다")
    void invalidKeyVersionThrowsException() {
        byte[] validCipher = encryptor.encrypt("test-token");
        validCipher[0] = 0x02; // 지원하지 않는 버전으로 변조

        assertThatThrownBy(() -> encryptor.decrypt(validCipher))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("지원하지 않는 암호화 키 버전");
    }

    @Test
    @DisplayName("암호문 본문이나 인증 태그가 변조되면 GCM 인증 실패로 IllegalStateException이 발생한다")
    void tamperedCipherDataThrowsException() {
        String token = "ghp_token_to_tamper";
        byte[] cipherData = encryptor.encrypt(token);

        // 마지막 1바이트 변조 (Auth Tag 변조)
        cipherData[cipherData.length - 1] ^= 0x01;

        assertThatThrownBy(() -> encryptor.decrypt(cipherData))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("토큰 복호화 중 오류가 발생했습니다.");
    }

    @Test
    @DisplayName("비밀키가 Base64 형식이 아니거나 디코딩 후 32바이트가 아니면 초기화 시 IllegalArgumentException이 발생한다")
    void invalidSecretKeyThrowsException() {
        // Base64 형식 오류
        assertThatThrownBy(() -> new TokenEncryptor("not-base-64!!!")).isInstanceOf(IllegalArgumentException.class);

        // 32바이트 미만 (16바이트)
        String shortKeyBase64 = "MDEyMzQ1Njc4OWFiY2RlZg==";
        assertThatThrownBy(() -> new TokenEncryptor(shortKeyBase64))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("정확히 32바이트");

        // 빈 값
        assertThatThrownBy(() -> new TokenEncryptor(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("비어 있을 수 없습니다");
    }
}
