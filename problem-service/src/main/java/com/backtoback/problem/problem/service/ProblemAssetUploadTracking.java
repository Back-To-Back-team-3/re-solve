package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.problem.problem.domain.ProblemBodyAsset;
import com.backtoback.problem.problem.domain.ProblemTestAsset;
import com.backtoback.problem.problem.repository.ProblemBodyAssetRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** 파일 업로드의 DB 추적을 외부 저장 호출과 분리한다(ERD 4.2.3). */
@Service
@RequiredArgsConstructor
public class ProblemAssetUploadTracking {
    private final ProblemBodyAssetRepository bodies;
    private final ProblemTestAssetRepository tests;
    private final Clock clock;
    private final EntityManager entityManager;

    /**
     * 새 키와 파일 추적 행을 별도 트랜잭션에서 저장한다.
     *
     * @param ownerId 작성 관리자 ID
     * @param content 저장할 바이트의 메타 정보
     * @param body 본문 여부
     * @return 추적 행의 식별 정보
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UploadTicket start(Long ownerId, ProblemAssetContent content, boolean body) {
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(content);
        String key = (body ? "problem-body/" : "test-cases/") + UUID.randomUUID() + ".json";
        LocalDateTime expiry = now().plusHours(24);
        Long id;
        if (body) {
            id
                = bodies
                    .saveAndFlush(
                        ProblemBodyAsset.create(ownerId, key, null, content.sha256(), content.sizeBytes(), expiry)
                    )
                    .getId();
        } else {
            id
                = tests
                    .saveAndFlush(
                        ProblemTestAsset.create(ownerId, key, null, content.sha256(), content.sizeBytes(), expiry)
                    )
                    .getId();
        }
        return new UploadTicket(id, key, expiry, body);
    }

    /**
     * 결과를 확정할 수 없을 때 쓰기 종료 시각 없이 오류 분류만 기록한다.
     *
     * @param ticket 기존 추적 정보
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnknown(UploadTicket ticket) {
        if (ticket.body()) {
            bodies
                .findByIdForUpdate(ticket.id())
                .orElseThrow()
                .recordUnknownUpload(ticket.expiry(), "UPLOAD_UNCONFIRMED");
        } else {
            tests
                .findByIdForUpdate(ticket.id())
                .orElseThrow()
                .recordUnknownUpload(ticket.expiry(), "UPLOAD_UNCONFIRMED");
        }
    }

    /**
     * 쓰기를 시작하지 않은 만료 파일을 잠그고 참조가 없을 때 폐기한다.
     *
     * @param ticket 기존 추적 정보
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discardBeforeWrite(UploadTicket ticket) {
        if (ticket.body()) {
            var asset = bodies.findByIdForUpdate(ticket.id()).orElseThrow();
            LocalDateTime finished = now();
            if (finished.isBefore(ticket.expiry())) {
                throw new IllegalStateException("업로드 보호 기간이 아직 유효합니다.");
            }
            requireUnreferenced(ticket);
            asset.discardFinishedUpload(ticket.expiry(), finished, "UPLOAD_EXPIRED");
        } else {
            var asset = tests.findByIdForUpdate(ticket.id()).orElseThrow();
            LocalDateTime finished = now();
            if (finished.isBefore(ticket.expiry())) {
                throw new IllegalStateException("업로드 보호 기간이 아직 유효합니다.");
            }
            requireUnreferenced(ticket);
            asset.discardFinishedUpload(ticket.expiry(), finished, "UPLOAD_EXPIRED");
        }
    }

    /**
     * 단일 쓰기 성공과 콘텐츠 확인 후 파일 상태를 기록한다.
     *
     * @param ticket 기존 추적 정보
     * @param version 저장된 객체 버전
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(UploadTicket ticket, String version) {
        if (ticket.body()) {
            var asset = bodies.findByIdForUpdate(ticket.id()).orElseThrow();
            LocalDateTime finished = now();
            if (!finished.isBefore(ticket.expiry())) {
                requireUnreferenced(ticket);
                asset.discardFinishedUpload(ticket.expiry(), finished, "UPLOAD_EXPIRED");
                return false;
            }
            asset.completeUpload(ticket.expiry(), finished, version);
        } else {
            var asset = tests.findByIdForUpdate(ticket.id()).orElseThrow();
            LocalDateTime finished = now();
            if (!finished.isBefore(ticket.expiry())) {
                requireUnreferenced(ticket);
                asset.discardFinishedUpload(ticket.expiry(), finished, "UPLOAD_EXPIRED");
                return false;
            }
            asset.completeUpload(ticket.expiry(), finished, version);
        }
        return true;
    }

    /** 파일 행 잠금 아래 모든 저장된 참조를 확인한다(ERD 4.2.3). */
    private void requireUnreferenced(UploadTicket ticket) {
        String query
            = ticket.body()
                ? "select (select count(*) from problem_revisions where body_asset_id = :id) + "
                    + "(select count(*) from problem_body_images where body_asset_id = :id)"
                : "select count(*) from problem_revision_tests where test_asset_id = :id";
        Number count = (Number)entityManager.createNativeQuery(query).setParameter("id", ticket.id()).getSingleResult();
        if (count.longValue() != 0) {
            throw new IllegalStateException("참조 중인 파일은 폐기할 수 없습니다.");
        }
    }

    /** DB의 마이크로초 정밀도와 비교할 수 있는 현재 시각. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** 업로드 추적 행과 기존 보호 만료 시각. */
    public record UploadTicket(Long id, String key, LocalDateTime expiry, boolean body) {
    }
}
