package dev.proppilot.units;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SeedDataIT extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seedHasExpectedShape() {
        assertThat(count("cities")).isEqualTo(3);
        assertThat(count("buildings")).isEqualTo(6);
        assertThat(count("units")).isEqualTo(150);
        assertThat(count("tenants")).isEqualTo(count("leases"));
        assertThat(count("tenants")).isBetween(100, 130);
        assertThat(count("payments")).isEqualTo(count("leases") * 12);
    }

    @Test
    void someRentIsUnpaid() {
        assertThat(jdbc.queryForObject("select count(*) from payments where paid_on is null", Integer.class))
                .isPositive();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
