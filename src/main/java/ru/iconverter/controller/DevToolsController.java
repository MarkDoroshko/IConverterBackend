package ru.iconverter.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.iconverter.services.IDnsLookupService;
import ru.iconverter.services.IWhoisService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devtools")
public class DevToolsController {

    private final IDnsLookupService dnsLookupService;
    private final IWhoisService whoisService;

    public DevToolsController(IDnsLookupService dnsLookupService, IWhoisService whoisService) {
        this.dnsLookupService = dnsLookupService;
        this.whoisService = whoisService;
    }

    @GetMapping("/dns")
    public ResponseEntity<Map<String, List<String>>> dns(@RequestParam("host") String host) {
        return ResponseEntity.ok(dnsLookupService.lookup(host));
    }

    @GetMapping("/whois")
    public ResponseEntity<Map<String, String>> whois(@RequestParam("domain") String domain) {
        return ResponseEntity.ok(Map.of("raw", whoisService.lookup(domain)));
    }
}
