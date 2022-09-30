package net.aggregat4.quicksand;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.aggregat4.dblib.SchemaMigrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.sql.SQLException;

@SpringBootApplication
public class QuicksandApplication {

	@Bean
	public DataSource dataSource(
			@Value("${quicksand_jdbc_url}") String jdbcUrl,
			@Value("${quicksand_jdbc_username}") String jdbcUsername,
			@Value("${quicksand_jdbc_password}") String jdbcPassword) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(jdbcUrl);
		config.setUsername(jdbcUsername);
		config.setPassword(jdbcPassword);
		DataSource ds = new HikariDataSource(config);
		try (var con = ds.getConnection()) {
			SchemaMigrator.migrate(con, new QuicksandMigrations());
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		return ds;
	}

	public static void main(String[] args) {
		SpringApplication.run(QuicksandApplication.class, args);
	}

}
