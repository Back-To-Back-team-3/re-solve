package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

class ProblemImageValidatorTest {
    private final ProblemImageValidator validator = new ProblemImageValidator();

    @Test
    @DisplayName("정적 WebP를 복호화하고 동적 WebP는 거절한다")
    void detectsStaticAndAnimatedWebp() {
        byte[] still = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAAAcQ/Y/+ByKi/wEA");
        byte[] animated
            = Base64
                .getDecoder()
                .decode(
                    "UklGRsQAAABXRUJQVlA4WAoAAAACAAAAAQAAAQAAQU5JTQYAAAAAAAAAAABBTk1GSgAAAAAAAAAAAAEAAAEAAGQAAAJWUDgg"
                        + "MgAAADABAJ0BKgIAAgABQCYloAADcAD+8ut///mwP/bz/wR6Af//0uD//pcH//S4P/SkAAAAQU5NRkYAAAAAAAAAAAAB"
                        + "AAABAABkAAAAVlA4IC4AAAA0AQCdASoCAAIAAAAmJaAAA3AA/vtV4///S4P/+lwf/9Lg/9Lg//rV5Vesq6AA"
                );
        assertThat(validator.validate(still)).isEqualTo("image/webp");
        assertThatThrownBy(() -> validator.validate(animated)).isInstanceOf(ProblemException.class);
    }

    @Test
    @DisplayName("정적 PNG와 JPEG의 실제 형식을 반환한다")
    void detectsStaticImages() throws Exception {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        ImageIO.write(image, "jpeg", jpeg);
        assertThat(validator.validate(png.toByteArray())).isEqualTo("image/png");
        assertThat(validator.validate(jpeg.toByteArray())).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("PNG 애니메이션 청크가 있으면 정적 이미지로 취급하지 않는다")
    void rejectsAnimatedPng() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] png = output.toByteArray();
        byte[] animated = new byte[png.length + 12];
        System.arraycopy(png, 0, animated, 0, 33);
        System
            .arraycopy(
                new byte[] {
                    0,
                    0,
                    0,
                    0,
                    'a',
                    'c',
                    'T',
                    'L',
                    0,
                    0,
                    0,
                    0
                },
                0,
                animated,
                33,
                12
            );
        System.arraycopy(png, 33, animated, 45, png.length - 33);
        assertThatThrownBy(() -> validator.validate(animated)).isInstanceOf(ProblemException.class);
    }

    @Test
    @DisplayName("허용하지 않는 형식과 잘린 파일을 거절한다")
    void rejectsInvalidImages() {
        assertThatThrownBy(() -> validator.validate("GIF89a".getBytes()))
            .isInstanceOf(ProblemException.class)
            .extracting(error -> ((ProblemException)error).getErrorCode())
            .isEqualTo(ProblemErrorCode.PROBLEM_IMAGE_INVALID);
        assertThatThrownBy(
            () -> validator
                .validate(
                    new byte[] {
                        (byte)137,
                        80,
                        78,
                        71,
                        13,
                        10,
                        26,
                        10
                    }
                )
        ).isInstanceOf(ProblemException.class);
    }

    @Test
    @DisplayName("5MiB를 초과하면 형식 검증보다 크기 오류를 반환한다")
    void rejectsOversizedImage() {
        assertThatThrownBy(() -> validator.validate(new byte[5 * 1024 * 1024 + 1]))
            .isInstanceOf(ProblemException.class)
            .extracting(error -> ((ProblemException)error).getErrorCode())
            .isEqualTo(ProblemErrorCode.PROBLEM_IMAGE_TOO_LARGE);
    }
}
