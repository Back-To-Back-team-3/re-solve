package com.backtoback.problem.problem.domain;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 공개 상태 요청의 식별자와 최초 종결 결과를 영속 보존한다. */
@Entity
@Table(name = "problem_state_operations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemStateOperation extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String operationId;
    private Long problemId;
    private Long actorId;
    private String operationType;
    private String idempotencyKey;
    private String requestHash;
    private int expectedSourceVersion;
    private Integer resultSourceVersion;
    private String beforeVisibility;
    private String targetVisibility;
    private String state;
    private String reason;
    private Integer responseStatus;
    @Column(columnDefinition = "json")
    private String responseJson;

    /**
     * 문제 잠금 아래 공개 작업의 원래 입력을 기록한다.
     *
     * @param operationId 새 작업 ID
     * @param problem 잠근 문제
     * @param key 멱등키
     * @param hash 정규화 요청 해시
     * @param expectedVersion 원래 기대 버전
     * @return 아직 종결하지 않은 공개 작업
     */
    public static ProblemStateOperation publication(
        String operationId,
        Problem problem,
        String key,
        String hash,
        int expectedVersion
    ) {
        return new ProblemStateOperation(operationId, problem, key, hash, expectedVersion);
    }

    /**
     * 공개 작업의 필수 입력과 초기 상태를 함께 설정한다.
     *
     * @param operationId 새 작업 ID
     * @param problem 잠근 문제
     * @param key 멱등키
     * @param hash 정규화 요청 해시
     * @param expectedVersion 원래 기대 버전
     */
    private ProblemStateOperation(String operationId, Problem problem, String key, String hash, int expectedVersion) {
        this.operationId = operationId;
        this.problemId = problem.getId();
        this.actorId = problem.getAuthorId();
        this.operationType = "PUBLICATION";
        this.idempotencyKey = key;
        this.requestHash = hash;
        this.expectedSourceVersion = expectedVersion;
        this.beforeVisibility = problem.getVisibility().name();
        this.targetVisibility = "PUBLIC";
        this.state = "PENDING";
        this.reason = "PUBLICATION";
    }

    /**
     * 공개 성공 결과와 최초 HTTP 응답을 확정한다.
     *
     * @param version 증가한 문제 버전
     * @param response 최초 응답 JSON
     */
    public void commit(int version, String response) {
        state = "COMMITTED";
        resultSourceVersion = version;
        responseStatus = 200;
        responseJson = response;
    }

    /**
     * 상태를 변경하지 않은 확정 실패와 최초 HTTP 응답을 보존한다.
     *
     * @param status 최초 오류 HTTP 상태
     * @param response 최초 오류 JSON
     */
    public void abort(int status, String response) {
        state = "ABORTED";
        responseStatus = status;
        responseJson = response;
    }
}
