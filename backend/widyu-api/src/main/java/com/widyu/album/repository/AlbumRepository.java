package com.widyu.album.repository;

import com.widyu.album.Album;
import com.widyu.member.Member;
import com.widyu.global.entity.Status;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlbumRepository extends JpaRepository<Album, Long> {

    @Query("SELECT a FROM Album a WHERE a.id = :id AND a.status = :status")
    Optional<Album> findByIdAndStatus(@Param("id") Long id, @Param("status") Status status);

    @Query("SELECT DISTINCT a FROM Album a JOIN FETCH a.member LEFT JOIN FETCH a.mediaUrls LEFT JOIN FETCH a.thumbnailUrls LEFT JOIN FETCH a.durations WHERE a.id = :id AND a.status = :status")
    Optional<Album> findByIdAndStatusWithCollections(@Param("id") Long id, @Param("status") Status status);

    @Query("SELECT a FROM Album a JOIN FETCH a.member WHERE a.id = :id")
    Optional<Album> findByIdWithMember(@Param("id") Long id);

    // 조회수는 DB에서 원자적으로 올린다. 읽은 값을 고쳐 저장하면 동시 조회 증가가 유실된다.
    @Modifying
    @Query("UPDATE Album a SET a.viewCount = a.viewCount + 1 WHERE a.id = :albumId")
    void incrementViewCount(@Param("albumId") Long albumId);

    @Query("SELECT a.id FROM Album a WHERE a.status = 'ACTIVE' AND a.member.id IN :memberIds ORDER BY a.createdAt DESC, a.id DESC")
    org.springframework.data.domain.Slice<Long> findLatestAlbumIdsByMemberIds(@Param("memberIds") List<Long> memberIds,
                                                                                Pageable pageable);

    @Query("SELECT DISTINCT a FROM Album a LEFT JOIN FETCH a.mediaUrls LEFT JOIN FETCH a.thumbnailUrls LEFT JOIN FETCH a.durations WHERE a.id IN :albumIds ORDER BY a.createdAt DESC, a.id DESC")
    List<Album> findAlbumsWithCollectionsByIds(@Param("albumIds") List<Long> albumIds);

    @Query("""
            SELECT a.id FROM Album a
            WHERE a.status = 'ACTIVE'
              AND a.member.id IN :memberIds
              AND (a.createdAt < :lastCreatedAt
                   OR (a.createdAt = :lastCreatedAt AND a.id < :lastId))
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    org.springframework.data.domain.Slice<Long> findAlbumIdsAfterPostIdAndMemberIds(
            @Param("lastCreatedAt") LocalDateTime lastCreatedAt,
            @Param("lastId") Long lastId,
            @Param("memberIds") List<Long> memberIds,
            Pageable pageable
    );

    @Query("SELECT a.createdAt FROM Album a WHERE a.member = :member AND a.status = :status ORDER BY a.createdAt DESC LIMIT 1")
    Optional<LocalDateTime> findLastUploadDateByMember(@Param("member") Member member, @Param("status") Status status);

    long countByMemberId(Long id);

    @Query("SELECT COUNT(a) FROM Album a WHERE a.status = 'ACTIVE' AND a.createdAt >= :since")
    long countNewAlbums(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(a) FROM Album a WHERE a.status = 'ACTIVE' AND a.member != :member AND a.id NOT IN (SELECT au.album.id FROM AlbumUnlock au WHERE au.member = :member)")
    long countLockedAlbumsForMember(@Param("member") Member member);

    @Query("SELECT a FROM Album a JOIN FETCH a.member WHERE a.status != 'DELETED' ORDER BY a.id DESC")
    Page<Album> findAllForAdmin(Pageable pageable);

    long countByStatus(Status status);

    List<Album> findTop3ByMemberIdAndStatusNotOrderByIdDesc(Long memberId, Status status);

    @Query("SELECT COUNT(a) FROM Album a WHERE a.status = 'ACTIVE' AND a.createdAt >= :start AND a.createdAt < :end")
    long countActiveAlbumsCreatedBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    
    @Query("SELECT a.id FROM Album a WHERE a.status = 'ACTIVE' AND a.member.id IN :memberIds AND a.createdAt >= :startOfDay AND a.createdAt < :startOfNextDay ORDER BY a.createdAt DESC, a.id DESC")
    org.springframework.data.domain.Slice<Long> findLatestAlbumIdsByDateAndMemberIds(
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("startOfNextDay") LocalDateTime startOfNextDay,
            @Param("memberIds") List<Long> memberIds,
            Pageable pageable);

    @Query("""
            SELECT a.id FROM Album a
            WHERE a.status = 'ACTIVE'
              AND a.member.id IN :memberIds
              AND a.createdAt >= :startOfDay
              AND a.createdAt < :startOfNextDay
              AND (a.createdAt < :lastCreatedAt
                   OR (a.createdAt = :lastCreatedAt AND a.id < :lastId))
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    org.springframework.data.domain.Slice<Long> findAlbumIdsAfterPostIdByDateAndMemberIds(
            @Param("lastCreatedAt") LocalDateTime lastCreatedAt,
            @Param("lastId") Long lastId,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("startOfNextDay") LocalDateTime startOfNextDay,
            @Param("memberIds") List<Long> memberIds,
            Pageable pageable
    );

    @Query("SELECT a.id FROM Album a WHERE a.status = 'ACTIVE' ORDER BY (a.likeCount * 3 + a.commentCount * 2) DESC, a.createdAt DESC")
    org.springframework.data.domain.Slice<Long> findTopScoredAlbumIds(Pageable pageable);

    @Query("SELECT a.id FROM Album a WHERE a.status = 'ACTIVE' AND a.member.id IN :memberIds ORDER BY (a.likeCount * 3 + a.commentCount * 2) DESC, a.createdAt DESC")
    org.springframework.data.domain.Slice<Long> findTopScoredAlbumIdsByMemberIds(@Param("memberIds") List<Long> memberIds, Pageable pageable);
}
