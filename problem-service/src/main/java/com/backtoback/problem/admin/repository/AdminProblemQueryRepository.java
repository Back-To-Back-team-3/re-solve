package com.backtoback.problem.admin.repository;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Repository;

import com.backtoback.problem.admin.dto.AdminProblemSearchRequest;
import com.backtoback.problem.problem.domain.ProblemRevision;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** 최신 회차를 먼저 선택한 뒤 필터·페이지를 DB에서 적용한다. */
@Repository
@RequiredArgsConstructor
public class AdminProblemQueryRepository {
    private final EntityManager entityManager;

    /**
     * API 명세서 §2.3.2의 회차 목록을 조회한다.
     *
     * @param filter 관리자 검색 조건
     * @return 고정 회차 페이지
     */
    public Page<ProblemRevision> search(AdminProblemSearchRequest filter) {
        var pageable = filter.pageable();
        var where = new StringBuilder(" from ProblemRevision r, Problem p where r.problemId=p.id");
        Map<String, Object> values = new HashMap<>();
        if (!Boolean.FALSE.equals(filter.latestOnly())) {
            where
                .append(
                    " and r.revisionNumber=(select max(latest.revisionNumber) from ProblemRevision latest"
                        + " where latest.problemId=p.id)"
                );
        }
        if (filter.visibility() == null) {
            where.append(" and p.visibility<>com.backtoback.problem.problem.domain.ProblemVisibility.ARCHIVED");
        } else {
            where.append(" and p.visibility=:visibility");
            values.put("visibility", filter.visibility());
        }
        if (filter.scope() != null) {
            where.append(" and p.scope=:scope");
            values.put("scope", filter.scope());
        }
        if (filter.difficulty() != null) {
            where.append(" and r.difficulty=:difficulty");
            values.put("difficulty", filter.difficulty());
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            where.append(" and locate(:keyword, r.title)>0");
            values.put("keyword", filter.keyword());
        }
        if (filter.tagIds() != null && !filter.tagIds().isEmpty()) {
            where
                .append(
                    " and exists (select tag.id from ProblemRevisionTag tag"
                        + " where tag.problemRevisionId=r.id and tag.tagId in :tags)"
                );
            values.put("tags", filter.tagIds());
        }
        var order
            = pageable
                .getSort()
                .stream()
                .map(item -> "r." + item.getProperty() + " " + item.getDirection().name())
                .collect(Collectors.joining(","));
        var data = entityManager.createQuery("select r" + where + " order by " + order, ProblemRevision.class);
        var count = entityManager.createQuery("select count(r)" + where, Long.class);
        values.forEach((key, value) -> {
            data.setParameter(key, value);
            count.setParameter(key, value);
        });
        return new PageImpl<>(
            data.setFirstResult((int)pageable.getOffset()).setMaxResults(pageable.getPageSize()).getResultList(),
            pageable,
            count.getSingleResult()
        );
    }
}
