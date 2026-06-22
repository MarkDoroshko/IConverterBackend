package ru.iconverter.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    @Test
    void allowsUpToLimitThenBlocks() {
        FixedWindowRateLimiter rl = new FixedWindowRateLimiter(3, 60_000);
        long t = 1_000_000;
        assertThat(rl.allow("ip", t)).isTrue();
        assertThat(rl.allow("ip", t)).isTrue();
        assertThat(rl.allow("ip", t)).isTrue();
        assertThat(rl.allow("ip", t)).isFalse(); // 4th over the limit of 3
    }

    @Test
    void resetsAfterWindow() {
        FixedWindowRateLimiter rl = new FixedWindowRateLimiter(2, 60_000);
        long t = 0;
        assertThat(rl.allow("ip", t)).isTrue();
        assertThat(rl.allow("ip", t)).isTrue();
        assertThat(rl.allow("ip", t)).isFalse();
        // After the window elapses the counter resets.
        assertThat(rl.allow("ip", t + 60_000)).isTrue();
    }

    @Test
    void keysAreIndependent() {
        FixedWindowRateLimiter rl = new FixedWindowRateLimiter(1, 60_000);
        long t = 0;
        assertThat(rl.allow("a", t)).isTrue();
        assertThat(rl.allow("a", t)).isFalse();
        assertThat(rl.allow("b", t)).isTrue(); // different key, own quota
    }
}
