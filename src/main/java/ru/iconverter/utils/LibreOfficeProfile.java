package ru.iconverter.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the per-conversion LibreOffice profile with safe defaults.
 *
 * <p>LibreOffice reads {@code user/registrymodifications.xcu} on startup. The
 * profile is unique per call (so concurrent soffice processes do not fight
 * over a lock), and this file disables macro execution and external-link
 * updates before the document is opened. That is defense in depth on top of
 * {@link HtmlSanitizer}: a DOCX/ODT/PPTX with a linked image must not make the
 * server fetch a URL.
 */
public final class LibreOfficeProfile {

    private LibreOfficeProfile() {
    }

    public static void writeSecureProfile(Path profileDir) throws IOException {
        Path userDir = profileDir.resolve("user");
        Files.createDirectories(userDir);
        String settings = """
                <?xml version="1.0" encoding="UTF-8"?>
                <oor:items xmlns:oor="http://openoffice.org/2001/registry"
                           xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <item oor:path="/org.openoffice.Office.Common/Security/Scripting">
                    <prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop>
                  </item>
                  <item oor:path="/org.openoffice.Office.Common/Load">
                    <prop oor:name="LinkUpdateMode" oor:op="fuse"><value>0</value></prop>
                  </item>
                </oor:items>
                """;
        Files.writeString(userDir.resolve("registrymodifications.xcu"), settings, StandardCharsets.UTF_8);
    }
}
