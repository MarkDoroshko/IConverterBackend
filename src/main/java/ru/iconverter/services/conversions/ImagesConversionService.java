package ru.iconverter.services.conversions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.concurrent.TimeUnit;

@Service
public class ImagesConversionService implements IImagesConversionService {

    private static final Logger logger = LoggerFactory.getLogger(ImagesConversionService.class);

    @Override
    public ByteArrayResource convertImage(MultipartFile file, String format) throws IOException {
        logger.info("Starting image conversion to format: {}", format);
        logger.info("Input file size: {} bytes", file.getSize());
        logger.info("Input file type: {}", file.getContentType());

        byte[] fileBytes = file.getBytes();
        InputStream inputStream = new ByteArrayInputStream(fileBytes);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        ProcessBuilder processBuilder = new ProcessBuilder("magick", "-", format + ":-");
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        try (OutputStream processOutputStream = process.getOutputStream();
             InputStream processInputStream = process.getInputStream();
             InputStream errorStream = process.getErrorStream()) {

            byte[] buffer = new byte[1024];
            int bytesRead;

            // Запись входных данных в процесс
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                processOutputStream.write(buffer, 0, bytesRead);
            }
            processOutputStream.close(); // Закрываем поток записи

            // Чтение ошибок из процесса
            StringBuilder errorOutput = new StringBuilder();
            while ((bytesRead = errorStream.read(buffer)) != -1) {
                errorOutput.append(new String(buffer, 0, bytesRead));
            }

            // Чтение выходных данных из процесса
            while ((bytesRead = processInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            // Ожидание завершения процесса с таймаутом
            if (!process.waitFor(30, TimeUnit.SECONDS)) { // Таймаут 30 секунд
                process.destroy();
                throw new IOException("Conversion timed out");
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("ImageMagick conversion failed. Error output: {}", errorOutput.toString());
                throw new IOException("Conversion failed with exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            logger.error("Conversion interrupted", e);
            Thread.currentThread().interrupt(); // Восстановление статуса прерывания
            throw new IOException("Conversion interrupted", e);
        } catch (IOException e) {
            logger.error("Conversion error", e);
            throw new IOException("Conversion failed", e);
        }

        logger.info("Conversion completed. Output size: {} bytes", outputStream.size());
        return new ByteArrayResource(outputStream.toByteArray());
    }
}
