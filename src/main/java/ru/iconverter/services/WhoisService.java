package ru.iconverter.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.iconverter.utils.NetworkUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.IDN;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Plain WHOIS (RFC 3912, port 43) client. There's no single WHOIS server for
// all domains, so this asks IANA which registry is authoritative for the TLD,
// then follows one further "Whois Server:" referral if the registry response
// names a more specific one (e.g. gTLD registry → the registrar's own server).
@Service
public class WhoisService implements IWhoisService {

    private static final Logger log = LoggerFactory.getLogger(WhoisService.class);

    private static final String IANA_WHOIS = "whois.iana.org";
    private static final int WHOIS_PORT = 43;
    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_CHARS = 20_000;

    // TLD may be an ordinary ASCII label or an IDN A-label (xn--p1ai).
    private static final Pattern DOMAIN =
            Pattern.compile("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+"
                    + "(?:[a-zA-Z]{2,63}|xn--[a-zA-Z0-9-]{2,59})$");

    private static final Pattern REFER_LINE =
            Pattern.compile("(?im)^refer:\\s*(\\S+)\\s*$");

    private static final Pattern WHOIS_SERVER_LINE =
            Pattern.compile("(?im)^(?:whois server|registrar whois server|referralserver)\\s*:\\s*(?:whois://)?(\\S+)\\s*$");

    // Same hostname grammar as the public API's input validation: no IP
    // literals, no ports, no paths — a WHOIS "refer:" line is remote input.
    private static final Pattern HOSTNAME =
            Pattern.compile("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+"
                    + "(?:[a-zA-Z]{2,63}|xn--[a-zA-Z0-9-]{2,59})$");

    public static void validateDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("Domain is required");
        }
        if (!DOMAIN.matcher(domain.trim()).matches()) {
            throw new IllegalArgumentException("Invalid domain: " + domain.trim());
        }
    }

    // Users often paste a full URL ("https://example.ru/page") or a trailing
    // FQDN dot; reduce that to the bare host before validation.
    public static String normalizeDomain(String input) {
        if (input == null) return null;
        String host = input.trim().replaceFirst("(?i)^[a-z][a-z0-9+.-]*://", "");
        int end = host.length();
        for (char c : new char[]{'/', '?', '#', ':'}) {
            int i = host.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        host = host.substring(0, end);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return host;

        // WHOIS servers speak ASCII. Users paste Unicode domains (пример.рф)
        // and expect them to work; convert to the A-label (punycode) form
        // instead of rejecting them. Already-punycode input is unchanged.
        try {
            return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid domain: " + input.trim());
        }
    }

    public static Optional<String> parseReferral(String ianaResponse) {
        Matcher m = REFER_LINE.matcher(ianaResponse);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public static Optional<String> parseWhoisServer(String response) {
        Matcher m = WHOIS_SERVER_LINE.matcher(response);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    @Override
    public String lookup(String domain) {
        String normalized = normalizeDomain(domain);
        validateDomain(normalized);

        String ianaResponse = query(IANA_WHOIS, normalized);
        // The referral comes from a remote server over plaintext TCP — treat it
        // as untrusted input: only follow it if it is a hostname that resolves
        // exclusively to public addresses (otherwise the API could be steered
        // at localhost / the internal network).
        String registryServer = parseReferral(ianaResponse)
                .filter(WhoisService::isPublicHost)
                .orElseThrow(() -> new IllegalArgumentException("No WHOIS registry found for: " + normalized));

        String registryResponse = query(registryServer, normalized);

        return parseWhoisServer(registryResponse)
                .filter(server -> !server.equalsIgnoreCase(registryServer))
                .filter(WhoisService::isPublicHost)
                .map(server -> {
                    try {
                        return query(server, normalized);
                    } catch (RuntimeException e) {
                        log.warn("Referral WHOIS query to {} failed, falling back to registry response: {}",
                                server, e.getMessage());
                        return registryResponse;
                    }
                })
                .orElse(registryResponse);
    }

    /**
     * True when {@code host} is a syntactically valid hostname that resolves to
     * public addresses only. {@code getAllByName} is safe here: the input has
     * already been constrained to a hostname literal (no IP, no port, no path).
     */
    static boolean isPublicHost(String host) {
        if (host == null || host.length() > 253 || !HOSTNAME.matcher(host).matches()) {
            return false;
        }
        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (java.net.InetAddress address : addresses) {
                if (!NetworkUtils.isPublicAddress(address)) {
                    return false;
                }
            }
            return true;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    private String query(String server, String domain) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(server, WHOIS_PORT), SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                out.print(domain + "\r\n");
                out.flush();

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null && sb.length() < MAX_RESPONSE_CHARS) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        } catch (IOException e) {
            throw new RuntimeException("WHOIS query to " + server + " failed: " + e.getMessage(), e);
        }
    }
}
