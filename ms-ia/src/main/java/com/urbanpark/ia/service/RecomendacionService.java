package com.urbanpark.ia.service;

import com.urbanpark.ia.client.EspaciosIaClient;
import com.urbanpark.ia.client.TicketsIaClient;
import com.urbanpark.ia.dto.AsignacionResponse;
import com.urbanpark.ia.dto.RecomendacionResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecomendacionService {

    private final EspaciosIaClient espaciosClient;
    private final TicketsIaClient ticketsClient;
    private final OllamaService ollama;

    public RecomendacionResponse recomendar(String tipoVehiculo, String preferencia, String authorization) {
        Seleccion sel = seleccionarConOllama(tipoVehiculo, preferencia, authorization);
        EspaciosIaClient.EspacioDto best = sel.espacio();

        List<String> alternativas = sel.candidatos().stream()
                .filter(e -> !e.getId().equals(best.getId()))
                .limit(3)
                .map(e -> e.getCodigo() + " (" + e.getZona() + ")")
                .collect(Collectors.toList());

        return RecomendacionResponse.builder()
                .zonaRecomendada(best.getZona())
                .espacioId(best.getId())
                .espacioCodigo(best.getCodigo())
                .tarifaHora(best.getTarifaHora())
                .nivel(best.getNivel())
                .motivo(sel.motivo())
                .explicacionIa(sel.explicacion())
                .proveedorIa(sel.proveedor())
                .alternativas(alternativas)
                .build();
    }

    /**
     * Ollama elige la plaza disponible y abre el ticket (asignación real).
     */
    public AsignacionResponse asignar(String placa, String tipoVehiculo, String preferencia, String authorization) {
        if (placa == null || placa.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Debes indicar la placa del vehículo (ej. ABC-123).");
        }
        String placaNorm = placa.trim().toUpperCase();

        Seleccion sel = seleccionarConOllama(tipoVehiculo, preferencia, authorization);
        EspaciosIaClient.EspacioDto best = sel.espacio();

        try {
            TicketsIaClient.TicketDto ticket = ticketsClient.abrir(
                    new TicketsIaClient.AbrirRequest(placaNorm, best.getId()),
                    authorization);

            return AsignacionResponse.builder()
                    .espacioId(best.getId())
                    .espacioCodigo(best.getCodigo())
                    .zona(best.getZona())
                    .tarifaHora(best.getTarifaHora())
                    .nivel(best.getNivel())
                    .placa(ticket.getPlaca())
                    .ticketId(ticket.getId())
                    .ticketCodigo(ticket.getCodigo())
                    .motivo(sel.motivo())
                    .explicacionIa(sel.explicacion())
                    .proveedorIa(sel.proveedor())
                    .asignado(true)
                    .build();
        } catch (FeignException ex) {
            throw mapTicketError(ex, placaNorm, best.getCodigo());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Ollama eligió la plaza " + best.getCodigo()
                            + ", pero no se pudo crear el ticket. Intenta de nuevo o abre el ticket manualmente en Tickets. Detalle: "
                            + ex.getMessage());
        }
    }

    public Map<String, Object> estadoOllama() {
        boolean ok = ollama.isReachable();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disponible", ok);
        m.put("baseUrl", ollama.getBaseUrl());
        m.put("modelo", ollama.getModel());
        m.put("mensaje", ok
                ? "Ollama listo para asignar plazas"
                : "Ollama no responde. Ejecuta: docker exec ollama ollama pull " + ollama.getModel());
        return m;
    }

    private Seleccion seleccionarConOllama(String tipoVehiculo, String preferencia, String authorization) {
        String tipo = tipoVehiculo != null ? tipoVehiculo.toUpperCase() : "AUTO";
        List<EspaciosIaClient.EspacioDto> libres;
        try {
            libres = espaciosClient.disponibles(tipo, authorization);
        } catch (FeignException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo consultar plazas libres. Verifica que el servicio de espacios esté activo.");
        }
        if (libres == null || libres.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No hay plazas LIBRES para vehículos tipo " + tipo
                            + ". Cierra algún ticket abierto o elige otro tipo.");
        }

        Map<String, Object> ocupacion;
        try {
            ocupacion = espaciosClient.ocupacion(authorization);
        } catch (Exception e) {
            ocupacion = Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> zonas = ocupacion.get("zonas") instanceof Map
                ? (Map<String, Object>) ocupacion.get("zonas") : Map.of();

        String listaPlazas = libres.stream()
                .map(e -> String.format(
                        "{\"id\":%d,\"codigo\":\"%s\",\"zona\":\"%s\",\"tarifa\":%.2f,\"nivel\":%s,\"descripcion\":\"%s\"}",
                        e.getId(), e.getCodigo(), e.getZona(),
                        e.getTarifaHora() != null ? e.getTarifaHora() : 0,
                        e.getNivel() != null ? e.getNivel() : "null",
                        safe(e.getDescripcion())))
                .collect(Collectors.joining(",\n"));

        String prompt = """
                Eres el motor de asignación de Urban Park (estacionamiento).
                Elige UNA plaza disponible para un vehículo tipo %s.
                Preferencia del conductor: %s
                Ocupación por zona (%%): %s

                Plazas libres (JSON):
                [%s]

                Responde SOLO un JSON válido con esta forma exacta:
                {"codigo":"CODIGO_PLAZA","motivo":"explicacion breve en espanol"}

                Reglas:
                - codigo debe existir en la lista de plazas libres.
                - Prioriza menor ocupacion de zona, luego tarifa y preferencia del conductor.
                - No inventes plazas.
                """.formatted(
                tipo,
                preferencia != null && !preferencia.isBlank() ? preferencia : "sin preferencia",
                zonas,
                listaPlazas);

        try {
            String raw = ollama.generate(prompt);
            String json = OllamaService.extractJsonObject(raw);
            if (json == null) {
                throw new IllegalStateException("Ollama no devolvió JSON: " + raw);
            }
            String codigo = OllamaService.readJsonString(json, "codigo");
            String motivo = OllamaService.readJsonString(json, "motivo");
            if (codigo == null || codigo.isBlank()) {
                throw new IllegalStateException("JSON sin codigo: " + json);
            }

            EspaciosIaClient.EspacioDto elegido = libres.stream()
                    .filter(e -> codigo.equalsIgnoreCase(e.getCodigo()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Ollama eligió codigo inválido '" + codigo + "'"));

            String explicacion = motivo != null ? motivo
                    : "Ollama asignó la plaza " + elegido.getCodigo() + " en " + elegido.getZona();

            return new Seleccion(
                    elegido,
                    libres,
                    "Asignación por Ollama (" + ollama.getModel() + "): plaza " + elegido.getCodigo(),
                    explicacion,
                    "ollama/" + ollama.getModel());
        } catch (Exception ex) {
            EspaciosIaClient.EspacioDto best = fallbackHeuristico(libres, zonas, preferencia);
            return new Seleccion(
                    best,
                    libres,
                    "Se usó asignación automática local (Ollama no respondió bien): " + shortMsg(ex.getMessage()),
                    String.format("Se eligió %s en %s por menor ocupación / tarifa.",
                            best.getCodigo(), best.getZona()),
                    "heuristica-local");
        }
    }

    private EspaciosIaClient.EspacioDto fallbackHeuristico(
            List<EspaciosIaClient.EspacioDto> libres,
            Map<String, Object> zonas,
            String preferencia) {
        return libres.stream()
                .sorted(Comparator
                        .comparingDouble((EspaciosIaClient.EspacioDto e) -> zonaOcupacion(zonas, e.getZona()))
                        .thenComparingDouble(e -> e.getTarifaHora() != null ? e.getTarifaHora() : 99)
                        .thenComparing(e -> preferenciaNivel(e, preferencia)))
                .findFirst()
                .orElse(libres.get(0));
    }

    private double zonaOcupacion(Map<String, Object> zonas, String zona) {
        Object z = zonas.get(zona);
        if (z instanceof Map<?, ?> m && m.get("porcentaje") instanceof Number n) {
            return n.doubleValue();
        }
        return 50;
    }

    private int preferenciaNivel(EspaciosIaClient.EspacioDto e, String preferencia) {
        if (preferencia == null) return 0;
        String p = preferencia.toLowerCase();
        int nivel = e.getNivel() != null ? e.getNivel() : 0;
        if (p.contains("cerca") || p.contains("entrada") || p.contains("rapido")) {
            return Math.abs(nivel);
        }
        if (p.contains("barato") || p.contains("econom")) {
            return (int) Math.round((e.getTarifaHora() != null ? e.getTarifaHora() : 5) * 10);
        }
        return 0;
    }

    private ResponseStatusException mapTicketError(FeignException ex, String placa, String plazaCodigo) {
        int status = ex.status();
        String body = ex.contentUTF8() != null ? ex.contentUTF8() : "";
        if (status == 409) {
            if (body.toLowerCase().contains("placa") || body.toLowerCase().contains("ticket abierto")) {
                return new ResponseStatusException(HttpStatus.CONFLICT,
                        "La placa " + placa + " ya tiene un ticket ABIERTO. "
                                + "Ve a Tickets y pulsa «Cerrar / cobrar» antes de asignar otra plaza.");
            }
            return new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se pudo asignar la plaza " + plazaCodigo + " porque está ocupada o en conflicto. "
                            + "Elige otra preferencia o cierra tickets abiertos.");
        }
        if (status == 404) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No se encontró la plaza " + plazaCodigo + " para crear el ticket.");
        }
        if (status >= 500 || status < 0) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio de tickets no responde. Espera unos segundos e intenta de nuevo.");
        }
        String msg = extractJsonMessage(body);
        return new ResponseStatusException(HttpStatus.valueOf(Math.max(status, 400)),
                msg != null ? msg : "No se pudo crear el ticket para " + placa + ".");
    }

    private static String extractJsonMessage(String body) {
        if (body == null || body.isBlank()) return null;
        int i = body.indexOf("\"message\"");
        if (i < 0) return null;
        int start = body.indexOf('"', i + 9);
        if (start < 0) return null;
        start++;
        int end = body.indexOf('"', start);
        if (end < 0) return null;
        return body.substring(start, end);
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\"", "'").replace("\n", " ");
    }

    private static String shortMsg(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private record Seleccion(
            EspaciosIaClient.EspacioDto espacio,
            List<EspaciosIaClient.EspacioDto> candidatos,
            String motivo,
            String explicacion,
            String proveedor) {}
}
