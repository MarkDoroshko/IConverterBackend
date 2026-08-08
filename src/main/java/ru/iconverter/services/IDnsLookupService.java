package ru.iconverter.services;

import java.util.List;
import java.util.Map;

public interface IDnsLookupService {

    // Queries A, AAAA, MX, TXT, NS, CNAME and SOA records for a hostname.
    // Keys with no records for that type are omitted.
    Map<String, List<String>> lookup(String host);
}
