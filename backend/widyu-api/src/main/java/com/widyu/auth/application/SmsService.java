package com.widyu.auth.application;

import com.widyu.auth.VerificationCode;
import com.widyu.auth.repository.VerificationCodeRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.CoolsmsProperties;
import com.widyu.global.util.PiiMaskingUtil;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.nurigo.sdk.NurigoApp;
import net.nurigo.sdk.message.exception.NurigoMessageNotReceivedException;
import net.nurigo.sdk.message.model.Message;
import net.nurigo.sdk.message.service.DefaultMessageService;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SmsService {

    private final CoolsmsProperties coolsmsProperties;
    private final DefaultMessageService messageService;
    private final SecureRandom secureRandom;
    private final VerificationCodeRepository verificationCodeRepository;

    private static final Pattern PHONE_PATTERN = Pattern.compile("^01[016789]\\d{7,8}$");

    public SmsService(final CoolsmsProperties coolsmsProperties,
                      final VerificationCodeRepository verificationCodeRepository) {
        this.coolsmsProperties = coolsmsProperties;
        this.verificationCodeRepository = verificationCodeRepository;
        this.messageService = NurigoApp.INSTANCE.initialize(
                coolsmsProperties.apiKey(),
                coolsmsProperties.apiSecret(),
                coolsmsProperties.apiUrl()
        );
        this.secureRandom = new SecureRandom();
    }

    public void sendVerificationSms(final String toPhoneNumber, final String name) {
        validatePhoneNumber(toPhoneNumber);
        String code = generateVerificationCode();

        saveVerificationCode(toPhoneNumber, code, name);
        String messageText = createMessageText(code);

        sendMessageWithLogging(toPhoneNumber, name, messageText);
    }

    private void sendMessageWithLogging(final String toPhoneNumber,
                                        final String name,
                                        final String messageText) {
        try {
            Message message = createMessage(toPhoneNumber, messageText);
            messageService.send(message);

            log.info("SMS 전송 성공 - 수신번호: {}", PiiMaskingUtil.maskPhoneNumber(toPhoneNumber));

        } catch (NurigoMessageNotReceivedException exception) {
            log.error("SMS 전송 실패 - 수신 불가: 수신번호={}, 실패목록={}",
                    PiiMaskingUtil.maskPhoneNumber(toPhoneNumber), exception.getFailedMessageList());
            throw new BusinessException(ErrorCode.SMS_SEND_FAILED);

        } catch (Exception exception) {
            log.error("SMS 전송 중 알 수 없는 오류 발생: 수신번호={}, 오류={}",
                    PiiMaskingUtil.maskPhoneNumber(toPhoneNumber), exception.getMessage(), exception);
            throw new BusinessException(ErrorCode.SMS_SEND_FAILED);
        }
    }


    private void saveVerificationCode(final String phoneNumber, final String code, final String name) {
        VerificationCode verificationCode = VerificationCode.builder()
                .phoneNumber(phoneNumber)
                .code(code)
                .name(name)
                .ttl(coolsmsProperties.verificationCodeTtl())
                .build();

        verificationCodeRepository.save(verificationCode);
    }

    private void validatePhoneNumber(final String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PHONE_NUMBER_REQUIRED);
        }

        if (!PHONE_PATTERN.matcher(phoneNumber).matches()) {
            throw new BusinessException(ErrorCode.INVALID_PHONE_NUMBER);
        }
    }

    private String generateVerificationCode() {
        StringBuilder code = new StringBuilder();
        int length = coolsmsProperties.verificationCodeLength();

        for (int i = 0; i < length; i++) {
            code.append(secureRandom.nextInt(10));
        }

        return code.toString();
    }

    private String createMessageText(final String verificationCode) {
        return coolsmsProperties.messageTemplate()
                .replace("{code}", verificationCode);
    }

    private Message createMessage(final String toPhoneNumber, final String messageText) {
        Message message = new Message();
        message.setFrom(coolsmsProperties.fromPhoneNumber());
        message.setTo(toPhoneNumber);
        message.setText(messageText);
        return message;
    }

}
