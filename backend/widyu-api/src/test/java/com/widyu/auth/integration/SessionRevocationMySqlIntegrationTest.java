package com.widyu.auth.integration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

/** 포트 13603의 일회용 로컬 MySQL 전용. 운영 DB 설정을 사용하지 않는다. */
@EnabledIfEnvironmentVariable(named = "WIDYU_SESSION603_MYSQL_TEST", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13603/session603_test?allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
})
class SessionRevocationMySqlIntegrationTest extends SessionRevocationIntegrationTest {
}
