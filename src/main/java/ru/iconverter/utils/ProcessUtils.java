package ru.iconverter.utils;

import java.util.ArrayList;
import java.util.List;

public class ProcessUtils {

    private ProcessUtils() {
    }

    // Wraps a command with `ulimit -v` so a runaway external process (ffmpeg,
    // ebook-convert) fails on its own with ENOMEM once it exceeds the given
    // virtual-memory budget, instead of the kernel OOM killer picking a victim
    // inside the container's shared memory cgroup — which has, in production,
    // killed the JVM itself as collateral damage rather than the offending
    // process. `"$1"`/`"$@"` are passed as separate ProcessBuilder arguments
    // (not concatenated into the shell string), so filenames containing shell
    // metacharacters can't inject anything.
    public static List<String> withMemoryLimit(List<String> command, long limitMb) {
        List<String> wrapped = new ArrayList<>();
        wrapped.add("sh");
        wrapped.add("-c");
        wrapped.add("ulimit -v \"$1\"; shift; exec \"$@\"");
        wrapped.add("sh");
        wrapped.add(String.valueOf(limitMb * 1024L));
        wrapped.addAll(command);
        return wrapped;
    }
}
