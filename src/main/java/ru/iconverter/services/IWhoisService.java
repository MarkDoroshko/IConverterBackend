package ru.iconverter.services;

public interface IWhoisService {

    // Raw WHOIS response text for a domain.
    String lookup(String domain);
}
