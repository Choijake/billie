package com.nextdoor.nextdoor.domain.member.infrastructure.persistence.adapter;

import com.nextdoor.nextdoor.domain.member.domain.model.Member;
import com.nextdoor.nextdoor.domain.member.domain.repository.MemberRepository;
import com.nextdoor.nextdoor.domain.member.infrastructure.persistence.jpa.MemberJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Repository
public class MemberRepositoryAdapter implements MemberRepository {

    private final MemberJpaRepository memberJpaRepository;

    @Override
    public Member save(Member member) {
        return memberJpaRepository.save(member);
    }

    @Override
    public List<Member> saveAll(List<Member> members) {
        return memberJpaRepository.saveAll(members);
    }

    @Override
    public Optional<Member> findById(Long id) {
        return memberJpaRepository.findById(id);
    }

    @Override
    public Optional<Member> findByUserKey(String userKey) {
        return memberJpaRepository.findByUserKey(userKey);
    }

    @Override
    public long count() {
        return memberJpaRepository.count();
    }
}