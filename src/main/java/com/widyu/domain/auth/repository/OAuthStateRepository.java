package com.widyu.domain.auth.repository;

import com.widyu.domain.auth.entity.OAuthState;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OAuthStateRepository extends CrudRepository<OAuthState, String> {
}
