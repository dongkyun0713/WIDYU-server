package com.widyu.domain.album.repository;

import com.widyu.domain.album.entity.AlbumComment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlbumCommentRepository extends JpaRepository<AlbumComment, Long> {
}