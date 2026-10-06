package ru.iconverter.services.conversions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class CalibreBookConversionServiceTest {

    private final CalibreBookConversionService service = new CalibreBookConversionService();

    @Test
    void recognizesCannotAllocateMemoryAsMemoryFailure() {
        String output = "1% Converting input to HTML...\ncannot allocate memory for thread-local data: ABORT";
        assertThat(service.isMemoryFailure(output)).isTrue();
    }

    @Test
    void recognizesPythonMemoryErrorAsMemoryFailure() {
        String output = "Flattening CSS and remapping font sizes...\nMemoryError\nDuring handling of the above exception...";
        assertThat(service.isMemoryFailure(output)).isTrue();
    }

    @Test
    void recognizesPthreadCreateFailureAsMemoryFailure() {
        String output = "[114:114:ERROR:platform_thread_posix.cc(135)] pthread_create: Resource temporarily unavailable (11)";
        assertThat(service.isMemoryFailure(output)).isTrue();
    }

    @Test
    void recognizesFatalProcessOutOfMemoryAsMemoryFailure() {
        String output = "# Fatal error\n# Fatal process out of memory: Failed to reserve memory";
        assertThat(service.isMemoryFailure(output)).isTrue();
    }

    @Test
    void doesNotFlagUnrelatedFailureAsMemoryFailure() {
        String output = "lxml.etree.XMLSyntaxError: Document is empty, line 1, column 1";
        assertThat(service.isMemoryFailure(output)).isFalse();
    }
}
