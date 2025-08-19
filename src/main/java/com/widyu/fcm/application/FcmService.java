package com.widyu.fcm.application;

import com.widyu.fcm.api.dto.FcmSendDto;
import java.io.IOException;
import org.springframework.stereotype.Service;

@Service
public interface FcmService {

    int sendMessageTo(FcmSendDto fcmSendDto) throws IOException;

}