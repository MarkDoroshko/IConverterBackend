package ru.iconverter.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/convert/math")
public class MathConversionController {

    @GetMapping("/")
    public String convert() {
        return "Hello math";
    }

    // для теста. удалить по необходимости
    @PostMapping(value = "/process")
    public ResponseEntity<String> processData(@RequestBody String data) {
        return ResponseEntity.ok(data + " - pong");
    }
}
