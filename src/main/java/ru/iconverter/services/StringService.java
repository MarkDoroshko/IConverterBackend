package ru.iconverter.services;

import org.springframework.stereotype.Service;

@Service
public class StringService implements IStringService {

    public String getFileExtension(String filename) {
        if (filename == null) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }
}
