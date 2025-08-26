package com.widyu.fcm.application;

import com.widyu.fcm.api.dto.FcmSendDto;
import com.widyu.fcm.api.dto.response.FcmSendResponse;
import com.widyu.member.domain.Member;

import java.io.IOException;

public interface FcmService {
    FcmSendResponse sendMessageTo(FcmSendDto fcmSendDto) throws IOException;
}
