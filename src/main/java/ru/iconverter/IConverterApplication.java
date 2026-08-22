package ru.iconverter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableScheduling
public class IConverterApplication {

	// Must exist before the SQLite DataSource bean opens its first connection
	// (spring.datasource.url=jdbc:sqlite:./data/app.db) — the sqlite-jdbc
	// driver does not create missing parent directories itself. A static
	// block runs as soon as this class is loaded, ahead of context refresh,
	// so it covers both `main()` and @SpringBootTest bootstrapping.
	static {
		try {
			Files.createDirectories(Path.of("data"));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static void main(String[] args) {
		SpringApplication.run(IConverterApplication.class, args);
	}
}
