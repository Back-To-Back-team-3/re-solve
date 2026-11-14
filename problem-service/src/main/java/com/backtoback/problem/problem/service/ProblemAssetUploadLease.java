package com.backtoback.problem.problem.service;

import java.time.Duration;

import com.backtoback.problem.problem.service.ProblemAssetUploadTracking.UploadTicket;

/**
 * 외부 저장 작업이 끝날 때까지 같은 추적 행의 보호 기간을 갱신한다.
 * 종료와 갱신을 직렬화해 마지막 만료값을 완료 처리에 전달한다.
 * 기준 문서: 정책 및 상태 v1.0 / §4 문제·테스트케이스 정책: 업로드 보호·갱신.
 */
final class ProblemAssetUploadLease implements AutoCloseable {
    private final ProblemAssetUploadTracking tracking;
    private final Duration initialDelay;
    private final Thread worker;
    private UploadTicket ticket;
    private RuntimeException failure;
    private boolean closed;

    /**
     * 보호 기간의 절반마다 갱신할 작업을 시작한다.
     *
     * @param tracking 별도 트랜잭션으로 파일을 갱신하는 추적 서비스
     * @param ticket 최초 추적 정보
     * @param initialDelay 첫 갱신까지의 시간
     */
    ProblemAssetUploadLease(ProblemAssetUploadTracking tracking, UploadTicket ticket, Duration initialDelay) {
        this.tracking = tracking;
        this.ticket = ticket;
        this.initialDelay = initialDelay;
        this.worker = Thread.ofVirtual().name("problem-asset-upload-renewal").start(this::run);
    }

    /** 작업이 종료되거나 갱신이 실패할 때까지 대기와 갱신을 반복한다. */
    private void run() {
        try {
            Duration delay = initialDelay;
            while (true) {
                Thread.sleep(delay);
                if (!renew()) {
                    return;
                }
                delay = Duration.ofHours(12);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 종료와 갱신을 직렬화한다. 갱신 실패 시 마지막으로 확정된 만료값을 보존한다.
     *
     * @return 다음 갱신을 시도할 수 있으면 true
     */
    synchronized boolean renew() {
        if (closed || failure != null) {
            return false;
        }
        try {
            ticket = tracking.renew(ticket);
            return true;
        } catch (RuntimeException exception) {
            failure = exception;
            return false;
        }
    }

    /**
     * 갱신 작업을 멈춘 후 마지막으로 확인된 추적 정보를 반환한다.
     *
     * @return 마지막으로 확인된 파일과 만료 시각
     */
    synchronized UploadTicket ticket() {
        return ticket;
    }

    /** 갱신 실패가 있었다면 업로드 완료를 확정하지 않도록 전달한다. */
    synchronized void requireHealthy() {
        if (failure != null) {
            throw new IllegalStateException("업로드 보호 기간을 갱신하지 못했습니다.", failure);
        }
    }

    /** 진행 중인 갱신을 기다린 후 추가 갱신을 중단한다. */
    @Override
    public synchronized void close() {
        closed = true;
        worker.interrupt();
    }
}
