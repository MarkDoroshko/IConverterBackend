package ru.iconverter.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

    private static final Pattern DOMAIN =
            Pattern.compile("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$");

    private static final Pattern REFER_LINE =
            Pattern.compile("(?im)^refer:\\s*(\\S+)\\s*$");

    private static final Pattern WHOIS_SERVER_LINE =
            Pattern.compile("(?im)^(?:whois server|registrar whois server|referralserver)\\s*:\\s*(?:whois://)?(\\S+)\\s*$");

    public static void validateDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("Domain is required");
        }
        if (!DOMAIN.matcher(domain.trim()).matches()) {
            throw new IllegalArgumentException("Invalid domain: " + domain.trim());
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
        validateDomain(domain);
        String normalized = domain.trim().toLowerCase();

        String ianaResponse = query(IANA_WHOIS, normalized);
        String registryServer = parseReferral(ianaResponse)
                .orElseThrow(() -> new IllegalArgumentException("No WHOIS registry found for: " + normalized));

        String registryResponse = query(registryServer, normalized);

        return parseWhoisServer(registryResponse)
                .filter(server -> !server.equalsIgnoreCase(registryServer))
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
