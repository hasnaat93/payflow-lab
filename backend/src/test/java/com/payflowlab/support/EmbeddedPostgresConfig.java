package com.payflowlab.support;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Starts a real PostgreSQL server in-process (no Docker) and points the
 * application at it. Integration tests therefore exercise the same engine,
 * Flyway migrations, constraints and triggers as production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmbeddedPostgresConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres(@Value("${payflow.embedded-postgres.port:0}") int port) throws IOException {
        EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder();
        if (port > 0) {
            builder.setPort(port);
        }
        return builder.start();
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(EmbeddedPostgres postgres) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl("postgres", "postgres"));
        dataSource.setUsername("postgres");
        dataSource.setMaximumPoolSize(12);
        return dataSource;
    }
}
