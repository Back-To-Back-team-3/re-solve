package com.backtoback.notification.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.backtoback.notification.global.response.ErrorResponse;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("BusinessException 발생 시 해당 에러코드의 상태와 응답을 반환한다")
    void handleBusinessException() {
        BusinessException exception = new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);

        ResponseEntity<ErrorResponse> response = handler.handleBusinessException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND.name());
    }

    @Test
    @DisplayName("MethodArgumentNotValidException 발생 시 400과 필드 오류 상세를 반환한다")
    void handleMethodArgumentNotValidException() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "title", "제목은 필수입니다."));
        MethodParameter parameter
            = new MethodParameter(this.getClass().getDeclaredMethod("handleMethodArgumentNotValidException"), -1);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentNotValidException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE.name());
        assertThat(response.getBody().error().details()).hasSize(1);
        assertThat(response.getBody().error().details().get(0).field()).isEqualTo("title");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException 발생 시 400을 반환한다")
    void handleHttpMessageNotReadableException() {
        HttpMessageNotReadableException exception
            = new HttpMessageNotReadableException("JSON parse error", (org.springframework.http.HttpInputMessage)null);

        ResponseEntity<ErrorResponse> response = handler.handleHttpMessageNotReadableException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE.name());
    }

    @Test
    @DisplayName("MissingServletRequestParameterException 발생 시 400과 파라미터 필드 오류를 반환한다")
    void handleMissingServletRequestParameterException() {
        MissingServletRequestParameterException exception = new MissingServletRequestParameterException("page", "int");

        ResponseEntity<ErrorResponse> response = handler.handleMissingServletRequestParameterException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().details()).hasSize(1);
        assertThat(response.getBody().error().details().get(0).field()).isEqualTo("page");
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException 발생 시 400과 필드 오류를 반환한다")
    void handleMethodArgumentTypeMismatchException() {
        MethodArgumentTypeMismatchException exception
            = new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, null);

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentTypeMismatchException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().details()).hasSize(1);
        assertThat(response.getBody().error().details().get(0).field()).isEqualTo("id");
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException 발생 시 405 및 Allow 헤더를 반환한다")
    void handleHttpRequestMethodNotSupportedException() {
        HttpRequestMethodNotSupportedException exception
            = new HttpRequestMethodNotSupportedException("POST", Set.of("GET", "DELETE"));

        ResponseEntity<ErrorResponse> response = handler.handleHttpRequestMethodNotSupportedException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.DELETE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE.name());
    }

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException 발생 시 415 및 Accept 헤더를 반환한다")
    void handleHttpMediaTypeNotSupportedException() {
        HttpMediaTypeNotSupportedException exception
            = new HttpMediaTypeNotSupportedException(MediaType.APPLICATION_XML, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> response = handler.handleHttpMediaTypeNotSupportedException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE.name());
    }

    @Test
    @DisplayName("일반 Exception 발생 시 500 INTERNAL_SERVER_ERROR를 반환한다")
    void handleGeneralException() {
        RuntimeException exception = new RuntimeException("Unexpected error");

        ResponseEntity<ErrorResponse> response = handler.handleException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.name());
    }
}
