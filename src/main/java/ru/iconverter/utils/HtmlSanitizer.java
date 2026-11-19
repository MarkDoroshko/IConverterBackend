package ru.iconverter.utils;

import org.apache.tika.Tika;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rewrites uploaded HTML before it is handed to LibreOffice or Calibre.
 *
 * <p>Both converters happily fetch resources referenced by a document. That
 * turned the HTML → PDF tool into a blind SSRF primitive: an
 * {@code <img src="https://attacker/...">} or a CSS {@code url(...)} made the
 * server issue an outbound request. The converter now receives only passive
 * markup: no scripts, no frames, no external links/images/fonts, no CSS that
 * can load a URL. Inline {@code data:image/*} payloads are kept so ordinary
 * pasted HTML with embedded images still renders.
 *
 * <p>This is deliberately an allow-list rewrite (keep known-safe attributes,
 * drop everything URL-like), not a best-effort blacklist.
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() {
    }

    // Elements that either execute code or load another document/resource.
    private static final Set<String> DROPPED_ELEMENTS = Set.of(
            "script", "iframe", "frame", "frameset", "object", "embed", "applet",
            "base", "link", "style", "meta", "template",
            "form", "input", "button", "textarea", "select", "option", "optgroup",
            "video", "audio", "source", "track", "picture", "canvas",
            "svg", "math"
    );

    // Attributes whose value is a URL. They are removed unless explicitly
    // allowed below (a fragment-only href, a mailto: link, or a data:image src).
    private static final Set<String> URL_ATTRIBUTES = Set.of(
            "src", "href", "xlink:href", "srcset", "imagesrcset", "poster",
            "background", "data", "action", "formaction", "cite", "longdesc",
            "usemap", "profile", "manifest", "ping", "codebase", "archive",
            "classid", "dynsrc", "lowsrc", "srcdoc"
    );

    private static final Pattern SAFE_DATA_IMAGE = Pattern.compile(
            "^data:image/(?:png|jpe?g|gif|webp|bmp|tiff);base64,[a-z0-9+/=\\s]+$",
            Pattern.CASE_INSENSITIVE);

    // Any of these in an inline style can make a renderer issue a request or
    // interpret a legacy scripting construct. Drop the whole declaration.
    private static final Pattern UNSAFE_CSS = Pattern.compile(
            "(?i)(?:url\\s*\\(|@import|image\\s*\\(|image-set\\s*\\(|-webkit-image-set\\s*\\(|"
                    + "\\bsrc\\s*\\(|expression\\s*\\(|behavior\\s*:|-moz-binding\\s*:|"
                    + "https?:|ftp:|//|\\\\)");

    /**
     * Content sniffing is needed because LibreOffice/Calibre detect the format
     * from the bytes, not only from the file extension. An HTML document named
     * {@code input.docx} would otherwise skip the sanitizer and still be
     * rendered as HTML.
     */
    public static boolean looksLikeHtml(InputStream input, String filename) {
        try {
            // Read a bounded prefix once and inspect it twice: once with Tika
            // (which may trust the extension too much) and once for literal
            // HTML markers. This catches HTML renamed to .docx/.txt.
            byte[] head = input.readNBytes(8192);
            String detected = new Tika().detect(new ByteArrayInputStream(head), filename);
            if ("text/html".equalsIgnoreCase(detected)
                    || "application/xhtml+xml".equalsIgnoreCase(detected)) {
                return true;
            }
            if (isBinaryContainer(head)) {
                return false;
            }
            String text = new String(head, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replace("\u0000", "");
            return text.contains("<html")
                    || text.contains("<!doctype html")
                    || text.contains("<head")
                    || text.contains("<body");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBinaryContainer(byte[] head) {
        if (head.length >= 4) {
            if (head[0] == 'P' && head[1] == 'K') return true;                     // zip/docx/xlsx/pptx/epub
            if (head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') return true;
            if ((head[0] & 0xff) == 0xD0 && (head[1] & 0xff) == 0xCF) return true; // OLE (doc/xls/ppt)
        }
        int controls = 0;
        for (byte value : head) {
            int b = value & 0xff;
            if (b == 0 || b < 9 || (b > 13 && b < 32)) {
                controls++;
            }
        }
        return controls > Math.max(8, head.length / 20);
    }

    public static String sanitize(String html) {
        return sanitize(Jsoup.parse(html == null ? "" : html));
    }

    public static byte[] sanitize(InputStream html) throws IOException {
        // Let jsoup detect the charset from <meta charset> / BOM, falling back
        // to UTF-8. The output is always UTF-8.
        Document document = Jsoup.parse(html, null, "");
        return sanitize(document).getBytes(StandardCharsets.UTF_8);
    }

    static String sanitize(Document document) {
        document.setBaseUri("");

        for (String tag : DROPPED_ELEMENTS) {
            document.select(tag).remove();
        }

        for (Element element : document.getAllElements()) {
            String tag = element.tagName().toLowerCase(Locale.ROOT);

            for (Attribute attribute : new ArrayList<>(element.attributes().asList())) {
                String key = attribute.getKey().toLowerCase(Locale.ROOT);
                String value = attribute.getValue();

                // Inline event handlers (onclick, onerror, …) never belong in a
                // document that is rendered headlessly.
                if (key.startsWith("on")) {
                    element.removeAttr(attribute.getKey());
                    continue;
                }

                if (key.equals("style")) {
                    if (UNSAFE_CSS.matcher(value).find()) {
                        element.removeAttr(attribute.getKey());
                    }
                    continue;
                }

                if (!URL_ATTRIBUTES.contains(key)) {
                    continue;
                }

                // Keep fragment links and mailto links (neither causes a
                // network fetch while rendering), and inline bitmap images.
                if (key.equals("href") && tag.equals("a")
                        && (value.startsWith("#") || value.toLowerCase(Locale.ROOT).startsWith("mailto:"))) {
                    continue;
                }
                if (key.equals("src") && tag.equals("img") && SAFE_DATA_IMAGE.matcher(value).matches()) {
                    continue;
                }

                element.removeAttr(attribute.getKey());
            }

            // An <img> without a usable source is just a broken-image icon.
            if (tag.equals("img") && !element.hasAttr("src")) {
                element.remove();
            }
        }

        // The original <meta charset> was dropped with the other meta tags.
        // Keep exactly one safe charset declaration so Cyrillic text is not
        // re-decoded as Latin-1 by the office renderer.
        document.head().appendElement("meta").attr("charset", "utf-8");

        document.outputSettings()
                .prettyPrint(false)
                .charset(StandardCharsets.UTF_8);
        return document.outerHtml();
    }
}
