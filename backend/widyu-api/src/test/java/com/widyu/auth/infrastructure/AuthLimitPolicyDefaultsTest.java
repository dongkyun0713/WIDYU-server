package com.widyu.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.global.properties.AuthLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AuthLimitPolicyDefaultsTest {
    @Test
    @DisplayName("설정이 없으면 승인된 인증 한도를 적용하고 전체 문자 한도는 미설정으로 둔다")
    void 승인된_인증_초기값을_적용한다() {
        // given / when
        var policy = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("auth.limits", Bindable.of(AuthLimitProperties.class));
        // then
        assertThat(policy.smsIntervalSeconds()).isEqualTo(60);
        assertThat(policy.smsPhoneHour()).isEqualTo(3);
        assertThat(policy.smsPhoneDay()).isEqualTo(10);
        assertThat(policy.smsIpHour()).isEqualTo(30);
        assertThat(policy.smsGlobalDay()).isNull();
        assertThat(policy.loginWindowSeconds()).isEqualTo(900);
        assertThat(policy.loginAccountFailures()).isEqualTo(10);
        assertThat(policy.loginIpFailures()).isEqualTo(100);
    }
}
