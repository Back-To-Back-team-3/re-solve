package com.backtoback.integration.github.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenEncryptorTest {

    private static final String TEST_SECRET_KEY = "0123456789abcdef0123456789abcdef"; // 32 bytes

    private final TokenEncryptor encryptor = new TokenEncryptor(TEST_SECRET_KEY);

    @Test
    @DisplayName("평문 문자열을 암호화한 뒤 복호화하면 원래 문자열로 복원된다")
    void encryptAndDecryptSuccess() {
        String token = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";

        byte[] cipherData = encryptor.encrypt(token);
        String decrypted = encryptor.decrypt(cipherData);

        assertThat(cipherData).isNotNull();
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
        assertThat(encryptor.decrypt(new byte[10])).isNull(); // IV(12B)보다 짧은 경우
    }

    @Test
    @DisplayName("암호문 데이터가 변조되면 GCM 인증 태그 불일치로 복호화 예외가 발생한다")
    void tamperedCipherDataThrowsException() {
        String token = "ghp_token_to_tamper";
        byte[] cipherData = encryptor.encrypt(token);

        // 데이터 1바이트 변조
        cipherData[cipherData.length - 1] ^= 0x01;

        assertThatThrownBy(() -> encryptor.decrypt(cipherData))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("토큰 복호화 중 오류가 발생했습니다.");
    }

    @Test
    @DisplayName("비밀키가 32바이트 미만이면 초기화 시 예외가 발생한다")
    void shortSecretKeyThrowsException() {
        assertThatThrownBy(() -> new TokenEncryptor("short-key"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("최소 32바이트 이상");
    }
}
