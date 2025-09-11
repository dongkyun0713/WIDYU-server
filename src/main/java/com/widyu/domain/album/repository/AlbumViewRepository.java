package com.widyu.domain.album.repository;

import com.widyu.domain.album.entity.AlbumView;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlbumViewRepository extends JpaRepository<AlbumView, Long> {
}