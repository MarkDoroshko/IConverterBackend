package ru.iconverter.services.conversions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.iconverter.services.conversions.VideoConversionService.*;

// Pure-logic tests for the ffmpeg command builder and validation.
// No Spring context, no ffmpeg required.
class VideoConversionServiceTest {

    @Test
    void normalize_lowercasesStripsDot() {
        assertThat(normalize("  .MP4 ")).isEqualTo("mp4");
        assertThat(normalize(null)).isEqualTo("");
    }

    @Test
    void buildCommand_mp4MovMkvUseH264Aac() {
        assertThat(buildCommand("/i", "/o.mp4", "mp4"))
                .containsExactly("ffmpeg", "-y", "-i", "/i",
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                        "-c:a", "aac", "-b:a", "192k", "/o.mp4");
        assertThat(buildCommand("/i", "/o.mov", "mov")).contains("-c:v", "libx264");
        assertThat(buildCommand("/i", "/o.mkv", "mkv")).contains("-c:v", "libx264");
    }

    @Test
    void buildCommand_avi() {
        assertThat(buildCommand("/i", "/o.avi", "avi"))
                .containsExactly("ffmpeg", "-y", "-i", "/i",
                        "-c:v", "mpeg4", "-q:v", "5",
                        "-c:a", "libmp3lame", "-q:a", "4", "/o.avi");
    }

    @Test
    void buildCommand_webm() {
        assertThat(buildCommand("/i", "/o.webm", "webm"))
                .containsExactly("ffmpeg", "-y", "-i", "/i",
                        "-c:v", "libvpx-vp9", "-crf", "30", "-b:v", "0",
                        "-c:a", "libopus", "-b:a", "128k", "/o.webm");
    }

    @Test
    void buildCommand_rejectsUnsupportedTarget() {
        assertThatThrownBy(() -> buildCommand("/i", "/o", "xyz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_acceptsSupportedSourceAndTarget() {
        validate("mov", "mp4");
        validate("webm", "avi");
    }

    @Test
    void validate_rejectsUnsupported() {
        assertThatThrownBy(() -> validate("mp4", "xyz")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate("mp3", "mp4")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate("exe", "mp4")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveHeight_acceptsPresetsWithOrWithoutP() {
        assertThat(resolveHeight("1080")).isEqualTo(1080);
        assertThat(resolveHeight("1080p")).isEqualTo(1080);
        assertThat(resolveHeight("2160")).isEqualTo(2160);
        assertThat(resolveHeight("720")).isEqualTo(720);
        assertThat(resolveHeight("480")).isEqualTo(480);
    }

    @Test
    void resolveHeight_rejectsUnknown() {
        assertThatThrownBy(() -> resolveHeight("360")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolveHeight(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildResizeCommand_scalesToHeightPreservingAspect() {
        assertThat(buildResizeCommand("/i.mp4", "/o.mp4", 720))
                .containsExactly("ffmpeg", "-y", "-i", "/i.mp4", "-vf", "scale=-2:720", "-c:a", "copy", "/o.mp4");
    }

    @Test
    void validateTimestamp_acceptsSecondsAndHmsAndDefaults() {
        assertThat(validateTimestamp("12.5", "startTime", null)).isEqualTo("12.5");
        assertThat(validateTimestamp("00:01:30", "endTime", null)).isEqualTo("00:01:30");
        assertThat(validateTimestamp(null, "startTime", "0")).isEqualTo("0");
    }

    @Test
    void validateTimestamp_rejectsInvalidAndMissingRequired() {
        assertThatThrownBy(() -> validateTimestamp("abc", "startTime", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateTimestamp(null, "endTime", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildTrimCommand_usesStreamCopy() {
        assertThat(buildTrimCommand("/i.mp4", "/o.mp4", "0", "10"))
                .containsExactly("ffmpeg", "-y", "-i", "/i.mp4", "-ss", "0", "-to", "10", "-c", "copy", "/o.mp4");
    }

    @Test
    void validateGifDuration_acceptsWithinRangeRejectsOutOfRange() {
        assertThat(validateGifDuration("5")).isEqualTo("5");
        assertThatThrownBy(() -> validateGifDuration(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateGifDuration("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateGifDuration("16")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateGifDuration("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateGifFps_defaultsAndClamps() {
        assertThat(validateGifFps(null)).isEqualTo(10);
        assertThat(validateGifFps(20)).isEqualTo(20);
        assertThatThrownBy(() -> validateGifFps(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateGifFps(31)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateGifWidth_defaultsAndClamps() {
        assertThat(validateGifWidth(null)).isEqualTo(480);
        assertThat(validateGifWidth(640)).isEqualTo(640);
        assertThatThrownBy(() -> validateGifWidth(50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateGifWidth(2000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildGifPaletteCommand_buildsPalettegenFilter() {
        assertThat(buildGifPaletteCommand("/i.mp4", "/p.png", "0", "5", 10, 480))
                .containsExactly("ffmpeg", "-y", "-ss", "0", "-t", "5", "-i", "/i.mp4",
                        "-vf", "fps=10,scale=480:-1:flags=lanczos,palettegen", "/p.png");
    }

    @Test
    void buildGifRenderCommand_buildsPaletteuseFilterComplex() {
        assertThat(buildGifRenderCommand("/i.mp4", "/p.png", "/o.gif", "0", "5", 10, 480))
                .containsExactly("ffmpeg", "-y", "-ss", "0", "-t", "5", "-i", "/i.mp4", "-i", "/p.png",
                        "-filter_complex", "fps=10,scale=480:-1:flags=lanczos[x];[x][1:v]paletteuse", "/o.gif");
    }
}
