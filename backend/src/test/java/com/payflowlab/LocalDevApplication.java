package com.payflowlab;

import com.payflowlab.support.EmbeddedPostgresConfig;
import org.springframework.boot.SpringApplication;

/**
 * Docker-free development launcher: runs the real application against an
 * embedded PostgreSQL on port 54329 and seeds the demo data.
 *
 * <pre>./mvnw spring-boot:test-run</pre>
 *
 * The database lives in a temporary directory and is recreated on every start.
 * It lives under src/test so the embedded database never ships in the production jar.
 */
public class LocalDevApplication {

    public static void main(String[] args) {
        SpringApplication.from(PayflowLabApplication::main)
                .with(EmbeddedPostgresConfig.class)
                .run(prepend(args, "--payflow.embedded-postgres.port=54329"));
    }

    private static String[] prepend(String[] args, String first) {
        String[] all = new String[args.length + 1];
        all[0] = first;
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }
}
