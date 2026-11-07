package com.backtoback.problem.problem.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.stereotype.Component;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.request.ProblemContentRequest;
import com.backtoback.problem.problem.dto.request.ProblemCreateRequest;
import com.backtoback.problem.problem.dto.request.ProblemRevisionRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 전체 HTTP 본문을 제한된 크기로 읽고 외부 입력 오류를 공통 업무 오류로 변환한다. */
@Component
public class ProblemCreateBodyReader extends AbstractHttpMessageConverter<ProblemContentRequest> {
    private final ObjectMapper mapper;

    /**
     * 최초·새 회차 등록 JSON을 읽는 변환기를 생성한다.
     *
     * @param mapper 애플리케이션 JSON 매퍼
     */
    public ProblemCreateBodyReader(ObjectMapper mapper) {
        super(MediaType.APPLICATION_JSON);
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    protected boolean supports(Class<?> type) {
        return type == ProblemCreateRequest.class || type == ProblemRevisionRequest.class;
    }

    /** {@inheritDoc} */
    @Override
    public boolean canWrite(Class<?> type, MediaType mediaType) {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    protected ProblemContentRequest readInternal(Class<? extends ProblemContentRequest> type, HttpInputMessage message)
        throws IOException {
        return read(message.getBody(), type);
    }

    /** {@inheritDoc} */
    @Override
    protected void writeInternal(ProblemContentRequest request, HttpOutputMessage message) {
        throw new UnsupportedOperationException("등록 요청은 응답으로 쓰지 않습니다.");
    }

    /**
     * Content-Length 유무와 관계없이 실제 본문을 8MiB까지 허용한다.
     *
     * @param stream HTTP 본문 스트림
     * @return 전체 등록 입력
     * @throws IOException 요청 스트림 읽기 실패
     * @throws ProblemException 본문이 너무 크거나 JSON 형식이 올바르지 않은 경우
     */
    public ProblemCreateRequest read(InputStream stream) throws IOException {
        return read(stream, ProblemCreateRequest.class);
    }

    /**
     * 등록 종류에 맞춰 크기 제한과 엄격한 JSON 변환을 적용한다.
     *
     * @param stream HTTP 본문
     * @param type 최초 또는 새 회차 요청 타입
     * @param <T> 입력 타입
     * @return 변환된 등록 입력
     * @throws IOException 스트림 읽기에 실패한 경우
     * @throws ProblemException 크기 초과는 {@code PROBLEM_REQUEST_TOO_LARGE}, 잘못된 JSON은 {@code COMMON_INVALID_REQUEST}
     */
    private <T extends ProblemContentRequest> T read(InputStream stream, Class<T> type) throws IOException {
        byte[] bytes = stream.readNBytes(8388609);
        if (bytes.length > 8388608) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_REQUEST_TOO_LARGE);
        }
        try {
            return mapper
                .readerFor(type)
                .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .without(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .readValue(bytes);
        } catch (JacksonException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /**
     * 인증 주체의 ID를 검증한다.
     *
     * @param value Gateway 회원 ID
     * @return 작성 관리자 ID
     * @throws ProblemException 양수 ID가 아닌 경우 AUTH_TOKEN_INVALID
     */
    public long authorId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException failure) {
            throw new ProblemException(CommonErrorCode.AUTH_TOKEN_INVALID);
        }
    }
}
