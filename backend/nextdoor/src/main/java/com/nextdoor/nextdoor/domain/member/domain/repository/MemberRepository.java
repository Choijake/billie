package com.nextdoor.nextdoor.domain.member.domain.repository;

import com.nextdoor.nextdoor.domain.member.domain.model.Member;
import java.util.List;
import java.util.Optional;

public interface MemberRepository {

    Member save(Member member);

    List<Member> saveAll(List<Member> members);

    Optional<Member> findById(Long id);

    Optional<Member> findByUserKey(String userKey);

    long count();
}