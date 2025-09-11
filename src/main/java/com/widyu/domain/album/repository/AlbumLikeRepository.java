package com.widyu.domain.album.repository;

import com.widyu.domain.album.entity.AlbumLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlbumLikeRepository extends JpaRepository<AlbumLike, Long> {
}