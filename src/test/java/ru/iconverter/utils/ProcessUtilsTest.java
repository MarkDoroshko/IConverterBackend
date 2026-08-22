package ru.iconverter.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessUtilsTest {

    @Test
    void wrapsCommandWithUlimitViaShellArgv() {
        List<String> wrapped = ProcessUtils.withMemoryLimit(List.of("ffmpeg", "-y", "-i", "in.mp4", "out.mp4"), 1200);

        assertThat(wrapped).containsExactly(
                "sh", "-c", "ulimit -v \"$1\"; shift; exec \"$@\"", "sh",
                String.valueOf(1200L * 1024L),
                "ffmpeg", "-y", "-i", "in.mp4", "out.mp4");
    }

    @Test
    void originalCommandArgumentsStayAsSeparateArgvEntries() {
        // A filename with shell metacharacters must not be able to inject
        // anything — it has to survive as one opaque argv entry.
        List<String> wrapped = ProcessUtils.withMemoryLimit(
                List.of("ebook-convert", "in;rm -rf /.epub", "out.pdf"), 500);

        assertThat(wrapped).contains("in;rm -rf /.epub");
        assertThat(wrapped.get(wrapped.size() - 2)).isEqualTo("in;rm -rf /.epub");
    }
}
