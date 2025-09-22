package ru.iconverter.services.conversions;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("integration")
public class CalibreBookConversionIntegrationTest {

    private static Path tempDir;

    @DynamicPropertySource
    static void setTempDirProperty(DynamicPropertyRegistry registry) throws IOException {
        tempDir = Files.createTempDirectory("calibre-integration-test-");
        registry.add("app.temp-dir", () -> tempDir.toAbsolutePath().toString());
    }

    @AfterAll
    static void cleanup() {
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                FileUtils.deleteDirectory(tempDir.toFile());
            } catch (IOException e) {
                System.err.println("Не удалось удалить: " + tempDir);
            }
        }
    }

    @Autowired
    private CalibreBookConversionService conversionService;

    private MockMultipartFile testFile;

    @BeforeEach
    void setUp() {
        testFile = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "Test content for conversion.".getBytes(StandardCharsets.UTF_8)
        );
    }

    @Test
    @Order(1)
    void shouldHaveEbookConvertAvailable() {
        try {
            Process process = new ProcessBuilder("ebook-convert", "--version").start();
            int exitCode = process.waitFor();
            assertThat(exitCode).isEqualTo(0);
            System.out.println("ebook-convert доступен. Версия:");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                reader.lines().forEach(System.out::println);
            }
        } catch (IOException | InterruptedException e) {
            fail("ebook-convert не доступен: " + e.getMessage());
        }
    }

    //@Test
    @Order(2)
    void shouldConvertBetweenAllSupportedFormats() {
        int successCount = 0;
        int totalCount = 0;

        for (String sourceFormat : CalibreBookConversionService.SUPPORTED_SOURCE_FORMATS) {
            for (String targetFormat : CalibreBookConversionService.SUPPORTED_TARGET_FORMATS) {
                if (sourceFormat.equals(targetFormat)) continue;

                totalCount++;

                try {
                    System.out.printf("Конвертируем из %s в %s... ", sourceFormat, targetFormat);

                    // Подготовить файл с нужным расширением
                    MockMultipartFile file = createTestFileWithExtension("test." + sourceFormat);

                    // Выполнить конвертацию
                    Resource result = conversionService.convertBook(file, sourceFormat, targetFormat);

                    // Проверить результат
                    assertThat(result).isNotNull();
                    byte[] content = result.getInputStream().readAllBytes();
                    assertThat(content).isNotEmpty();

                    // Сохранить временно для отладки (опционально)
                    Path outputPath = tempDir.resolve("test." + targetFormat);
                    Files.write(outputPath, content);
                    System.out.printf("✅ Успешно. Результат: %d байт, сохранён в %s%n", content.length, outputPath);

                    successCount++;

                } catch (Exception e) {
                    System.err.printf("❌ Ошибка при конвертации %s → %s", sourceFormat, targetFormat);
                    // Не падаем — хотим протестировать все комбинации
                }
            }
        }

        // Вывод итогов
        System.out.printf("Результат: %d из %d конвертаций прошли успешно.%n", successCount, totalCount);

        // Можно ослабить, если часть форматов заведомо не поддерживаются Calibre для ввода/вывода
        // Но желательно, чтобы большинство работало
        assertThat(successCount).as("Количество успешных конвертаций").isGreaterThan((int) (totalCount * 0.7)); // например, 70%
    }

    // Вспомогательный метод: создаёт тестовый файл с нужным расширением
    private MockMultipartFile createTestFileWithExtension(String filename) {
        String content = "Test content for " + filename;
        byte[] bytes;

        // Некоторые форматы требуют особого содержимого
        if (List.of("epub", "fb2", "html", "htmlz", "azw3").contains(getFileExtension(filename))) {
            // Простой HTML как база для многих форматов
            content = """
                    <html>
                    <head><title>Test</title></head>
                    <body><p>""" + content +
                    """ 
                    </p></body>
                    </html>
                    """;
            bytes = content.getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = content.getBytes(StandardCharsets.UTF_8);
        }

        return new MockMultipartFile(
                "file",
                filename,
                MediaType.TEXT_PLAIN_VALUE,
                bytes
        );
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }
}
