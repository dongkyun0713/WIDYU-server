package com.widyu.authtest.repository;

import com.widyu.authtest.domain.RefreshToken;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenTestRepository extends CrudRepository<RefreshToken, Long> {
}
