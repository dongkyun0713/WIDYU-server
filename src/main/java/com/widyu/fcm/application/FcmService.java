package com.widyu.fcm.application;

import com.widyu.fcm.api.dto.FcmSendDto;
import com.widyu.member.domain.Member;

import java.io.IOException;

public interface FcmService {
    int sendMessageTo(FcmSendDto fcmSendDto) throws IOException;
}
