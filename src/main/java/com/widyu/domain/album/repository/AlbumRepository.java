package com.widyu.domain.album.repository;

import com.widyu.domain.album.entity.Album;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlbumRepository extends JpaRepository<Album, Long> {
}