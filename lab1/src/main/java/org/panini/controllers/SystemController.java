package org.panini.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @GetMapping("/readFile")
    public ResponseEntity<String> postSysCall() {
        try {
            Path path = Path.of("/tmp/secret.txt");
            String content = Files.readString(path);
            return ResponseEntity.ok("Success. File content: " + content);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).body("Operation not permitted: " + e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("I/O error: " + e.getMessage());
        }
    }

    @GetMapping("/sysCall")
    public ResponseEntity<String> seccompTest() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 8080), 2000);
            return ResponseEntity.ok("Success");
        } catch (IOException e) {
            return ResponseEntity.status(403).body("Operation not permitted: " + e.getMessage());
        }
    }
}
