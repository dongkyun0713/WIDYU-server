package com.widyu.album.application;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import com.widyu.album.Album;
import com.widyu.album.AlbumView;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.fcm.event.album.dto.AlbumViewedEvent;
import com.widyu.member.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlbumViewService {

    private final AlbumViewRepository albumViewRepository;
    private final AlbumRepository albumRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = REQUIRES_NEW)
    public void recordView(Album album, Member member) {
        Optional<AlbumView> existingView = albumViewRepository.findByAlbumAndMember(album, member);
        
        if (existingView.isEmpty()) {
            AlbumView albumView = AlbumView.createView(album, member);
            albumViewRepository.save(albumView);

            // album은 호출부(상세 조회)의 읽기 전용 트랜잭션 엔티티라 이 트랜잭션에서 변경 감지되지 않는다.
            // DB는 원자 UPDATE로 올리고, 메모리 값은 같은 요청의 응답 viewCount에만 쓰인다.
            albumRepository.incrementViewCount(album.getId());
            album.incrementViewCount();

            // 앨범 조회 알림 이벤트 발행
            eventPublisher.publishEvent(new AlbumViewedEvent(
                    member.getId(),
                    album.getId()
            ));

            log.info("앨범 첫 조회 기록: albumId={}, memberId={}", album.getId(), member.getId());
        }
    }

    @Transactional(readOnly = true)
    public List<Member> getRecentViewers(Album album, int limit) {
        return albumViewRepository.findViewersByAlbum(album, limit);
    }

    @Transactional(readOnly = true)
    public Long getTotalViewCount(Album album) {
        return albumViewRepository.countViewsByAlbum(album);
    }
}
