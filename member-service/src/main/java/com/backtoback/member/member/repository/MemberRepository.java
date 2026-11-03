package com.backtoback.member.member.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberRole;
import com.backtoback.member.member.domain.MemberStatus;

import jakarta.persistence.LockModeType;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByGithubId(Long githubId);

    /**
     * 관리자 회원 목록 (API 명세서 1.6.1). 조건이 {@code null}이면 거르지 않는다.
     * {@code keyword}는 LIKE 특수 문자({@code %}·{@code _}·{@code !})를 {@code !}로 이스케이프한 값이어야 한다.
     * MySQL 문자열에서 역슬래시가 이스케이프 문자로 해석되므로 이스케이프 문자는 {@code !}를 쓴다.
     */
    @Query("""
        select m from Member m
        where (:status is null or m.status = :status)
          and (:role is null or m.role = :role)
          and (:keyword is null
            or m.nickname like concat('%', :keyword, '%') escape '!'
            or m.githubLogin like concat('%', :keyword, '%') escape '!')
        """)
    Page<Member> search(String keyword, MemberStatus status, MemberRole role, Pageable pageable);

    /**
     * {@code profile_version}을 올리는 변경용 조회. 같은 회원의 동시 변경이 같은 버전을 저장하지 않도록 행을 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findForUpdateById(Long id);
}
