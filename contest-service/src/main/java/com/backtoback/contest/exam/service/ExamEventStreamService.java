package com.backtoback.contest.exam.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.global.error.ContestException;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/** 커밋된 시험 상태를 구독자에게 전달하고 주기적으로 연결을 갱신한다. */
@Service
@RequiredArgsConstructor
public class ExamEventStreamService {
    private final ExamEventSnapshotService snapshots;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final ExecutorService reads = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 최초 조회로 권한을 확인하고 재연결에는 현재 공개 상태를 다시 전송한다.
     * 상태와 결과 이벤트가 같은 버전 ID를 공유하므로 마지막 ID만으로 결과 수신 여부를 판별하지 않는다.
     *
     * @param examId 시험 식별자
     * @param memberId 인증된 회원 식별자
     * @param lastEventId 클라이언트가 전달한 마지막 이벤트 ID; 현재 상태로 복구한다
     * @return 60초 후 정상 종료하는 이벤트 스트림
     */
    public SseEmitter subscribe(String examId, String memberId, String lastEventId) {
        var first = snapshots.read(examId, memberId);
        validateCursor(lastEventId, first);
        var emitter = new SseEmitter(60000L);
        var closed = new AtomicBoolean();
        var last = new AtomicReference<>(first);
        var pending = new AtomicReference<Future<?>>();
        try {
            sendState(emitter, first);
            if (first.terminalCount() != null) {
                sendResult(emitter, first);
            }
        } catch (IOException failure) {
            emitter.completeWithError(failure);
            return emitter;
        }
        var future = executor.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            var previousRead = pending.get();
            if (previousRead != null && !previousRead.isDone()) {
                return;
            }
            pending.set(reads.submit(() -> {
                try {
                    var current = snapshots.read(examId, memberId);
                    var previous = last.getAndSet(current);
                    if (!current.status().equals(previous.status())) {
                        sendState(emitter, current);
                    }
                    if (current.terminalCount() != null
                        && (current.revision() != previous.revision() || !current.status().equals(previous.status()))) {
                        sendResult(emitter, current);
                    }
                } catch (Exception failure) {
                    closed.set(true);
                    emitter.completeWithError(failure);
                }
            }));
        }, 1, 1, TimeUnit.SECONDS);
        var heartbeat = executor.scheduleAtFixedRate(() -> {
            if (!closed.get()) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException failure) {
                    closed.set(true);
                    emitter.completeWithError(failure);
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
        Runnable cleanup = () -> {
            closed.set(true);
            future.cancel(false);
            heartbeat.cancel(false);
            var read = pending.get();
            if (read != null) {
                read.cancel(true);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(failure -> cleanup.run());
        return emitter;
    }

    /** 서로 다른 시험이나 미래 버전은 현재 시험의 유효한 재연결 위치가 아니다. */
    static void validateCursor(String value, ExamEventSnapshotService.Snapshot snapshot) {
        if (value == null) {
            return;
        }
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 3 || !"EXAM".equals(parts[0]) || !snapshot.examId().equals(parts[1])
                || !parts[2].matches("0|[1-9][0-9]*") || Long.parseLong(parts[2]) > snapshot.revision()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /** 상태 변경은 공개 결과가 없는 시점에도 전송한다. */
    private void sendState(SseEmitter emitter, ExamEventSnapshotService.Snapshot snapshot) throws IOException {
        emitter
            .send(
                SseEmitter
                    .event()
                    .name("exam-state-updated")
                    .id(eventId(snapshot))
                    .data(
                        Map
                            .of(
                                "examId",
                                snapshot.examId(),
                                "status",
                                snapshot.status(),
                                "serverNow",
                                snapshot.serverNow()
                            )
                    )
            );
    }

    private void sendResult(SseEmitter emitter, ExamEventSnapshotService.Snapshot snapshot) throws IOException {
        String status = "FINALIZED".equals(snapshot.status()) ? "FINALIZED" : "PROVISIONAL";
        emitter
            .send(
                SseEmitter
                    .event()
                    .name("exam-result-updated")
                    .id(eventId(snapshot))
                    .data(
                        Map
                            .of(
                                "examId",
                                snapshot.examId(),
                                "resultStatus",
                                status,
                                "resultRevision",
                                Long.toString(snapshot.revision()),
                                "terminalCount",
                                snapshot.terminalCount(),
                                "totalSubmissionCount",
                                snapshot.totalSubmissionCount()
                            )
                    )
            );
    }

    /** 문맥과 DB 버전을 이벤트 식별자로 만든다. */
    private String eventId(ExamEventSnapshotService.Snapshot snapshot) {
        return "EXAM:" + snapshot.examId() + ":" + snapshot.revision();
    }

    /** 애플리케이션 종료 시 연결별 작업 실행기를 닫는다. */
    @PreDestroy
    public void close() {
        executor.shutdownNow();
        reads.shutdownNow();
    }
}
