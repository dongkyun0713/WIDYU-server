package com.widyu.album.repository;

import com.widyu.album.Album;
import com.widyu.album.AlbumUnlock;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AlbumUnlockRepository extends JpaRepository<AlbumUnlock, Long> {
    boolean existsByAlbumAndMember(Album album, Member member);
    
    @Query("SELECT au.album.id FROM AlbumUnlock au WHERE au.member = :member ORDER BY au.unlockedAt DESC")
    List<Long> findUnlockedAlbumIdsByMember(@Param("member") Member member);

    @Query("SELECT a.id FROM Album a WHERE a.status = 'ACTIVE' "
            + "AND a.member.id IN :memberIds AND a.member.type = :memberType "
            + "ORDER BY a.createdAt DESC, a.id DESC")
    List<Long> findActiveAlbumIdsByMemberIdsAndMemberType(
            @Param("memberIds") List<Long> memberIds,
            @Param("memberType") MemberType memberType
    );
    
    @Query("SELECT au.album.id FROM AlbumUnlock au WHERE au.member = :member AND au.album.id IN :albumIds")
    List<Long> findUnlockedAlbumIdsByMemberAndAlbumIds(@Param("member") Member member,
                                                       @Param("albumIds") List<Long> albumIds);

    @Query("SELECT COUNT(au) * " + Album.UNLOCK_PRICE + " FROM AlbumUnlock au WHERE au.member = :member")
    Long getTotalUnlockPriceByMember(@Param("member") Member member);

    @Query("""
            SELECT COUNT(a) FROM Album a
            WHERE a.member.id = :writerId
              AND a.status = com.widyu.global.entity.Status.ACTIVE
              AND a.id <> :justUnlockedAlbumId
              AND NOT EXISTS (
                  SELECT au.id FROM AlbumUnlock au
                  WHERE au.album = a AND au.member.id = :seniorId
              )
            """)
    long countRemainingLockedByWriterAndSenior(
            @Param("writerId") Long writerId,
            @Param("seniorId") Long seniorId,
            @Param("justUnlockedAlbumId") Long justUnlockedAlbumId);
}
