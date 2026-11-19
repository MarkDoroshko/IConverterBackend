package ru.iconverter.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlSanitizerTest {

    @Test
    void removesExternalImagesLinksScriptsAndFrames() {
        String html = """
                <html><body>
                  <img src="https://python.org/logo.png" alt="x">
                  <img src="//evil.example/pixel.gif">
                  <a href="https://evil.example/">link</a>
                  <script src="https://evil.example/x.js"></script>
                  <iframe src="https://evil.example/"></iframe>
                  <link rel="stylesheet" href="https://evil.example/a.css">
                  <object data="https://evil.example/x"></object>
                </body></html>
                """;

        String sanitized = HtmlSanitizer.sanitize(html);

        assertThat(sanitized).doesNotContain("python.org", "evil.example", "<script", "<iframe", "<link", "<object");
        assertThat(sanitized).contains(">link<");
    }

    @Test
    void keepsInlineDataImages() {
        String dataImage = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB";
        String sanitized = HtmlSanitizer.sanitize("<p><img src=\"" + dataImage + "\" alt=\"dot\"></p>");

        assertThat(sanitized).contains(dataImage);
    }

    @Test
    void stripsNetworkCssAndMetaRefresh() {
        String html = """
                <html><head>
                  <meta http-equiv="refresh" content="0;url=https://evil.example/">
                  <style>body { background: url(https://evil.example/a.png); }</style>
                </head><body>
                  <p style="color: red; background-image: url(https://evil.example/b.png)">x</p>
                  <p style="color: blue">ok</p>
                </body></html>
                """;

        String sanitized = HtmlSanitizer.sanitize(html);

        assertThat(sanitized).doesNotContain("evil.example", "http-equiv", "<style");
        assertThat(sanitized).doesNotContain("background-image");
        assertThat(sanitized).contains("color: blue");
    }

    @Test
    void removesInlineEventHandlersAndJavascriptUrls() {
        String html = """
                <body onload="alert(1)">
                  <img src="data:image/png;base64,AAAA" onerror="alert(2)">
                  <a href="javascript:alert(3)">x</a>
                  <a href="#section">fragment</a>
                  <a href="mailto:test@example.com">mail</a>
                </body>
                """;

        String sanitized = HtmlSanitizer.sanitize(html);

        assertThat(sanitized).doesNotContain("onload", "onerror", "javascript:");
        assertThat(sanitized).contains("href=\"#section\"");
        assertThat(sanitized).contains("mailto:test@example.com");
    }

    @Test
    void detectsHtmlEvenWhenTheExtensionLies() throws Exception {
        byte[] html = "<!doctype html><html><body><img src=\"https://evil.example/x\"></body></html>"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(HtmlSanitizer.looksLikeHtml(new ByteArrayInputStream(html), "invoice.docx")).isTrue();

        byte[] zip = new byte[]{'P', 'K', 3, 4, 20, 0, 6, 0};
        assertThat(HtmlSanitizer.looksLikeHtml(new ByteArrayInputStream(zip), "invoice.docx")).isFalse();
    }

    @Test
    void sanitizeInputStreamProducesUtf8() throws Exception {
        byte[] input = "<p>Привет, мир</p>".getBytes(StandardCharsets.UTF_8);
        byte[] output = HtmlSanitizer.sanitize(new ByteArrayInputStream(input));

        assertThat(new String(output, StandardCharsets.UTF_8)).contains("Привет, мир");
    }
}
