package com.widyu.domain.album.repository;

import com.widyu.domain.album.entity.AlbumCommentLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlbumCommentLikeRepository extends JpaRepository<AlbumCommentLike, Long> {
}