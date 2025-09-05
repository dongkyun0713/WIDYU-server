package com.widyu.authtest.repository;

import com.widyu.authtest.domain.OAuthState;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OAuthStateTestRepository extends CrudRepository<OAuthState, String> {
}
