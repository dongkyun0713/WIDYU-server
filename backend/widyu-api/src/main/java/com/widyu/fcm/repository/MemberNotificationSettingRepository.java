package com.widyu.fcm.repository;

import com.widyu.fcm.MemberNotificationSetting;
import com.widyu.fcm.PushSettingGroup;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberNotificationSettingRepository extends
        JpaRepository<MemberNotificationSetting, Long> {

    Optional<MemberNotificationSetting> findByMemberIdAndCategory(Long memberId, PushSettingGroup category);

    List<MemberNotificationSetting> findAllByMemberId(Long memberId);
}
