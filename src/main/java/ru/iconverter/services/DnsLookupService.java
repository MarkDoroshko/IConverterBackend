package ru.iconverter.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class DnsLookupService implements IDnsLookupService {

    private static final Logger log = LoggerFactory.getLogger(DnsLookupService.class);

    private static final List<Integer> QUERY_TYPES =
            List.of(Type.A, Type.AAAA, Type.MX, Type.TXT, Type.NS, Type.CNAME, Type.SOA);

    // Labels separated by dots, each 1-63 chars of letters/digits/hyphen (no
    // leading/trailing hyphen), matching the RFC 1035 hostname grammar closely
    // enough to reject junk before it reaches dnsjava.
    private static final Pattern HOSTNAME =
            Pattern.compile("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$");

    public static void validateHostname(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host is required");
        }
        String trimmed = host.trim();
        if (!HOSTNAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid hostname: " + trimmed);
        }
    }

    @Override
    public Map<String, List<String>> lookup(String host) {
        validateHostname(host);
        String normalized = host.trim();

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int type : QUERY_TYPES) {
            List<String> records = queryType(normalized, type);
            if (!records.isEmpty()) {
                result.put(Type.string(type), records);
            }
        }
        return result;
    }

    private List<String> queryType(String host, int type) {
        try {
            Lookup lookup = new Lookup(host, type);
            Record[] records = lookup.run();
            if (records == null) return List.of();
            return List.of(records).stream()
                    .map(this::formatRecord)
                    .toList();
        } catch (TextParseException e) {
            throw new IllegalArgumentException("Invalid hostname: " + host);
        } catch (RuntimeException e) {
            log.warn("DNS query failed for {} type {}: {}", host, Type.string(type), e.getMessage());
            return List.of();
        }
    }

    private String formatRecord(Record record) {
        if (record instanceof ARecord r) return r.getAddress().getHostAddress();
        if (record instanceof AAAARecord r) return r.getAddress().getHostAddress();
        if (record instanceof MXRecord r) return r.getPriority() + " " + r.getTarget();
        if (record instanceof TXTRecord r) return String.join(" ", r.getStrings());
        if (record instanceof NSRecord r) return r.getTarget().toString();
        if (record instanceof CNAMERecord r) return r.getTarget().toString();
        return record.rdataToString();
    }
}
