package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.hibernate5.SpringBeanContainer;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in only: creates tables in a disposable loopback fcm_resilience_ database. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "FCM_MYSQL_URL", matches = ".+")
class FcmOutboxMySqlIntegrationTest extends FcmOutboxIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired ConfigurableListableBeanFactory beanFactory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("FCM_MYSQL_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/fcm_resilience_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Requires loopback and a disposable fcm_resilience_ database");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    @Test
    @DisplayName("기존 알림 스키마에 운영 SQL을 적용하면 Hibernate 검증과 VARCHAR 매핑을 통과한다")
    void 운영_마이그레이션은_MySQL_스키마_검증을_통과한다() throws Exception {
        // given: this test database contains synthetic rows only, removed after every test.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select database()", String.class)).startsWith("fcm_resilience_");
        assertThat(jdbc.queryForObject("select count(*) from fcm_outbox", Integer.class)).isZero();
        jdbc.execute("drop table fcm_outbox");
        var foreignKeys = jdbc.queryForList("""
                select constraint_name from information_schema.key_column_usage
                where table_schema=database() and table_name='fcm_notification'
                and column_name='recipient_member_id' and referenced_table_name is not null
                """, String.class);
        for (String name : foreignKeys) {
            if (!name.matches("[A-Za-z0-9_]+")) {
                throw new IllegalStateException("Unexpected synthetic foreign key name");
            }
            jdbc.execute("alter table fcm_notification drop foreign key `" + name + "`");
        }
        jdbc.execute("alter table fcm_notification drop column recipient_member_id");
        Path script = Path.of("scripts/mysql/create_fcm_outbox.sql");
        if (!Files.exists(script)) {
            script = Path.of("../../scripts/mysql/create_fcm_outbox.sql");
        }
        // when: use the shipped SQL, not a duplicate test DDL.
        try (var connection = dataSource.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource(script));
        }
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.widyu");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.dialect", "org.hibernate.dialect.MySQLDialect",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.resource.beans.container", new SpringBeanContainer(beanFactory)));
        try {
            factory.afterPropertiesSet();
            // then
            assertThat(factory.getObject()).isNotNull();
            assertThat(jdbc.queryForList("""
                    select data_type from information_schema.columns where table_schema=database()
                    and table_name='fcm_outbox' and column_name in ('state','fcm_category')
                    """, String.class)).containsExactlyInAnyOrder("varchar", "varchar");
        } finally {
            factory.destroy();
        }
    }
}
