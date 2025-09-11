package com.widyu.domain.auth.repository;

import com.widyu.domain.auth.entity.TemporaryMember;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TemporaryMemberRepository extends CrudRepository<TemporaryMember, String> {
}
