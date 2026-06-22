package ru.iconverter.services.conversions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.iconverter.services.conversions.AudioConversionService.*;

// Pure-logic tests for the ffmpeg command builder and validation.
// No Spring context, no ffmpeg required.
class AudioConversionServiceTest {

    @Test
    void normalize_lowercasesStripsDot() {
        assertThat(normalize("  .MP3 ")).isEqualTo("mp3");
        assertThat(normalize(null)).isEqualTo("");
    }

    @Test
    void buildCommand_mp4ToMp3() {
        assertThat(buildCommand("/in.mp4", "/out.mp3", "mp3"))
                .containsExactly("ffmpeg", "-y", "-i", "/in.mp4", "-vn",
                        "-codec:a", "libmp3lame", "-q:a", "2", "/out.mp3");
    }

    @Test
    void buildCommand_aacAndOggAndFlacAndWav() {
        assertThat(buildCommand("/i", "/o.m4a", "m4a")).contains("-codec:a", "aac", "-b:a", "192k");
        assertThat(buildCommand("/i", "/o.ogg", "ogg")).contains("-codec:a", "libvorbis");
        assertThat(buildCommand("/i", "/o.flac", "flac")).contains("-codec:a", "flac");
        // wav: no explicit codec, just -vn then output
        assertThat(buildCommand("/i", "/o.wav", "wav"))
                .containsExactly("ffmpeg", "-y", "-i", "/i", "-vn", "/o.wav");
    }

    @Test
    void buildCommand_rejectsUnsupportedTarget() {
        assertThatThrownBy(() -> buildCommand("/i", "/o", "xyz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_acceptsVideoSourceToMp3() {
        validate("mp4", "mp3"); // no throw — extract audio from video
        validate("wav", "mp3");
    }

    @Test
    void validate_rejectsUnsupported() {
        assertThatThrownBy(() -> validate("mp4", "xyz")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate("exe", "mp3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noAudioStream_detectsFfmpegMessages() {
        assertThat(noAudioStream("Output file #0 does not contain any stream")).isTrue();
        assertThat(noAudioStream("Stream map '0:a' matches no streams.")).isTrue();
        assertThat(noAudioStream("some other ffmpeg error")).isFalse();
        assertThat(noAudioStream(null)).isFalse();
    }
}
