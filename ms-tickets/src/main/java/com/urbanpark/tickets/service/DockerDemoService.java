package com.urbanpark.tickets.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Controla el contenedor ms-espacios vía Docker CLI (demo Resilience).
 * Requiere montar /var/run/docker.sock en ms-tickets.
 */
@Service
public class DockerDemoService {

    private static final String FILTER = "name=ms-espacios";

    public Map<String, Object> stopEspacios() {
        String id = resolveContainerId(true);
        ExecResult r = exec("docker", "stop", id);
        if (r.exitCode != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo detener ms-espacios: " + r.output);
        }
        return result("stop", id, "ms-espacios detenido. Prueba ahora Resilience (debería activar fallback).");
    }

    public Map<String, Object> startEspacios() {
        String id = resolveContainerId(false);
        ExecResult r = exec("docker", "start", id);
        if (r.exitCode != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo iniciar ms-espacios: " + r.output);
        }
        return result("start", id, "ms-espacios iniciado. Espera unos segundos y prueba de nuevo.");
    }

    public Map<String, Object> statusEspacios() {
        ExecResult running = exec("docker", "ps", "-q", "-f", FILTER);
        boolean up = running.exitCode == 0 && !running.output.isBlank();
        String id = up ? running.output.trim().lines().findFirst().orElse("")
                : resolveContainerIdQuiet();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("servicio", "ms-espacios");
        out.put("running", up);
        out.put("containerId", id);
        out.put("mensaje", up ? "ms-espacios está en ejecución" : "ms-espacios está detenido");
        return out;
    }

    private Map<String, Object> result(String action, String id, String mensaje) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("servicio", "ms-espacios");
        out.put("containerId", id);
        out.put("mensaje", mensaje);
        out.put("ok", true);
        return out;
    }

    private String resolveContainerId(boolean preferRunning) {
        if (preferRunning) {
            ExecResult running = exec("docker", "ps", "-q", "-f", FILTER);
            String id = firstLine(running.output);
            if (!id.isBlank()) return id;
        }
        ExecResult all = exec("docker", "ps", "-aq", "-f", FILTER);
        String id = firstLine(all.output);
        if (id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No se encontró el contenedor ms-espacios. ¿Está el stack con docker compose?");
        }
        return id;
    }

    private String resolveContainerIdQuiet() {
        try {
            return resolveContainerId(false);
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) return "";
        return text.trim().lines().findFirst().orElse("").trim();
    }

    private ExecResult exec(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = br.lines().collect(Collectors.joining("\n")).trim();
            }
            boolean finished = p.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new ExecResult(124, "Timeout ejecutando: " + String.join(" ", command));
            }
            return new ExecResult(p.exitValue(), output);
        } catch (Exception e) {
            List<String> hint = new ArrayList<>();
            hint.add(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            hint.add("¿ms-tickets tiene docker.sock y docker-cli?");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, String.join(" — ", hint));
        }
    }

    private record ExecResult(int exitCode, String output) {}
}
