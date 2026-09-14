package com.widyu.global.properties;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("auth.limits")
public record AuthLimitProperties(
        @DefaultValue("60") @Min(1) int smsIntervalSeconds,
        @DefaultValue("3") @Min(1) int smsPhoneHour,
        @DefaultValue("10") @Min(1) int smsPhoneDay,
        @DefaultValue("30") @Min(1) int smsIpHour,
        @Min(1) Integer smsGlobalDay,
        @DefaultValue("900") @Min(1) int loginWindowSeconds,
        @DefaultValue("10") @Min(1) int loginAccountFailures,
        @DefaultValue("100") @Min(1) int loginIpFailures
) {
}
