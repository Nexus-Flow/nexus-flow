package com.nexus_flow.core.configurations.flyway;

import org.flywaydb.core.Flyway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
/**
 * Remember to set flyway to false in application yml and start migration scripts from version 2
 */
public class FlywayConfiguration implements CommandLineRunner {


    private final DataSource dataSource;

    public FlywayConfiguration(DataSource dataSource) {
        this.dataSource = dataSource;
    }


    @Override
    public void run(String... args) throws Exception {
        Flyway.configure().baselineOnMigrate(true).outOfOrder(true).dataSource(dataSource).load().migrate();
    }
}


