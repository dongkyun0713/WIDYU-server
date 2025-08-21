package com.widyu.auth.repository;

import com.widyu.auth.domain.TemporaryMember;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TemporaryMemberRepository extends CrudRepository<TemporaryMember, String> {
}
