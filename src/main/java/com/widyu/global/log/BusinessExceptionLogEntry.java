package com.widyu.global.log;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BusinessExceptionLogEntry {

    private String timestamp;
    private String exceptionType;
    private String message;
    private String requestUri;

    public String toLogString() {
        return String.format("""
            
            [BUSINESS-EXCEPTION]
              Time          : %s
              ExceptionType : %s
              Message       : %s
              Request URI   : %s
            ----------------------------------------------------
            """,
                timestamp,
                exceptionType,
                message,
                requestUri
        );
    }
}
