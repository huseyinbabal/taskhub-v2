package com.huseyin.taskhub;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TaskhubApiApplicationIT {

    @Autowired
    DataSource dataSource;

    @Test
    void contextLoadsAgainstPostgres17() {
        String version = new JdbcTemplate(dataSource).queryForObject("SHOW server_version", String.class);

        assertThat(version).startsWith("17.");
    }
}
