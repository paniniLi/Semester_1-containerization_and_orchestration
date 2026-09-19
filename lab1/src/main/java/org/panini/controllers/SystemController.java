package org.panini.controllers;

import org.panini.model.CommandRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

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

    @GetMapping("/changeTime")
    public ResponseEntity<String> changeTime() {
        try {
            Process process = new ProcessBuilder(
                    "date", "-s", "2026-09-19 12:00:00"
            ).redirectErrorStream(true).start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return ResponseEntity.ok("System time changed successfully:\n" + output);
            }
            return ResponseEntity.status(403).body(
                    "Failed to change system time.\n" + "Exit code: " + exitCode + "\n" + output
            );
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/socket/open")
    public ResponseEntity<String> seccompTest() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 8080), 2000);
            return ResponseEntity.ok("Success");
        } catch (IOException e) {
            return ResponseEntity.status(403).body("Operation not permitted: " + e.getMessage());
        }
    }

    @PostMapping("/system/execute")
    public ResponseEntity<String> executeCommand(@RequestBody CommandRequest request) {
        if (request == null || request.command() == null || request.command().isBlank()) {
            return ResponseEntity.badRequest().body("Поле command не должно быть пустым");
        }

        try {
            Process process = new ProcessBuilder("sh", "-c", request.command())
                    .redirectErrorStream(true)
                    .start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                return ResponseEntity.internalServerError()
                        .body("Код ошибки: " + exitCode);
            }

            return ResponseEntity.ok(output);
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body("Ошибка запуска команды: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError()
                    .body("Выполнение команды было прервано");
        }
    }
}
