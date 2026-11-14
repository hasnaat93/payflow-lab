package com.payflowlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// repositories are grouped per module as nested interfaces (e.g. LedgerRepositories.AccountRepository)
@EnableJpaRepositories(considerNestedRepositories = true)
public class PayflowLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayflowLabApplication.class, args);
    }
}
