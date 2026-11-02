package com.backtoback.problem.problem.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.glavo.webp.WebPImageReader;
import org.springframework.stereotype.Component;

import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

/** 원본 바이트의 형식·정적 여부·해상도와 복호화를 검증한다(API 명세서 2.4.1). */
@Component
public class ProblemImageValidator {
    private static final long MAX_PIXELS = 20_000_000L;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final byte[] PNG
        = {
            (byte)137,
            80,
            78,
            71,
            13,
            10,
            26,
            10
        };

    public String validate(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_IMAGE_TOO_LARGE);
        }
        try {
            if (bytes.length >= 12 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WEBP")) {
                try (WebPImageReader reader = WebPImageReader.open(new ByteArrayInputStream(bytes))) {
                    requireDimensions(reader.getSourceWidth(), reader.getSourceHeight());
                    if (reader.isAnimated() || reader.getFrameCount() != 1 || reader.readNextFrame() == null) {
                        throw invalid();
                    }
                }
                return "image/webp";
            }
            boolean png = bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), PNG);
            boolean jpeg
                = bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
            if (!png && !jpeg) {
                throw invalid();
            }
            if (png) {
                rejectAnimatedPng(bytes);
            }
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw invalid();
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input);
                    requireDimensions(reader.getWidth(0), reader.getHeight(0));
                    if (reader.read(0) == null) {
                        throw invalid();
                    }
                } finally {
                    reader.dispose();
                }
            }
            return png ? "image/png" : "image/jpeg";
        } catch (ProblemException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid();
        }
    }

    private void rejectAnimatedPng(byte[] bytes) {
        int offset = 8;
        while (offset <= bytes.length - 12) {
            long length
                = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt());
            long next = offset + 12L + length;
            if (next > bytes.length) {
                throw invalid();
            }
            String type = ascii(bytes, offset + 4, 4);
            if (type.equals("acTL") || type.equals("fcTL") || type.equals("fdAT")) {
                throw invalid();
            }
            offset = (int)next;
            if (type.equals("IEND")) {
                return;
            }
        }
        throw invalid();
    }

    private void requireDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || (long)width * height > MAX_PIXELS) {
            throw invalid();
        }
    }

    private String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private ProblemException invalid() {
        return new ProblemException(ProblemErrorCode.PROBLEM_IMAGE_INVALID);
    }
}
