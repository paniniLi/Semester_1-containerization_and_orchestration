package org.panini.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {

    @GetMapping("/health")
    public ResponseEntity<String> getHealth() {
        return ResponseEntity.ok("Приложение работает");
    }

    @GetMapping("/eat")
    public ResponseEntity<String> getEat(@RequestParam("mb") int mb) {
        if (mb < 0) return ResponseEntity.badRequest().body("Query-параметр mb должен быть строго положительным");

        byte[] block = new byte[mb];
        return ResponseEntity.ok(String.format("Выделен %d байт", mb));
    }

    @GetMapping("/burn")
    public ResponseEntity<Void> getBurn() {
        while (true) { Thread.onSpinWait(); }
    }

}
