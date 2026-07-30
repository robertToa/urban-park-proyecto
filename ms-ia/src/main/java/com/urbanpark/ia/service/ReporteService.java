package com.urbanpark.ia.service;

import com.urbanpark.ia.client.EspaciosIaClient;
import com.urbanpark.ia.client.TicketsIaClient;
import com.urbanpark.ia.dto.ReporteTicketsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReporteService {

    private final TicketsIaClient ticketsClient;
    private final EspaciosIaClient espaciosClient;
    private final OllamaService ollama;

    public ReporteTicketsResponse generar(String authorization, LocalDate desde, LocalDate hasta) {
        if (desde == null) desde = LocalDate.now();
        if (hasta == null) hasta = LocalDate.now();
        if (hasta.isBefore(desde)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha hasta no puede ser anterior a la fecha desde");
        }

        // 1) Sincronizar plazas ↔ tickets abiertos para que cuadre con la pestaña Plazas
        Map<String, Object> sync = Map.of();
        try {
            sync = ticketsClient.reconciliarPlazas(authorization);
        } catch (Exception ignored) {
            // Si falla el sync, el reporte igual se genera con datos actuales
        }

        LocalDateTime inicio = desde.atStartOfDay();
        LocalDateTime fin = hasta.atTime(LocalTime.MAX);

        List<TicketsIaClient.TicketDto> todos;
        try {
            todos = ticketsClient.listar(authorization);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudieron leer los tickets para el reporte.");
        }
        if (todos == null) todos = List.of();

        List<TicketsIaClient.TicketDto> abiertosAhora = todos.stream()
                .filter(t -> "ABIERTO".equalsIgnoreCase(t.getEstado()))
                .collect(Collectors.toList());

        List<TicketsIaClient.TicketDto> tickets = todos.stream()
                .filter(t -> enRango(t.getEntrada(), inicio, fin))
                .collect(Collectors.toList());

        String periodo = desde.equals(hasta)
                ? "del día " + desde
                : "del " + desde + " al " + hasta;

        int abiertosPeriodo = (int) tickets.stream().filter(t -> "ABIERTO".equalsIgnoreCase(t.getEstado())).count();
        int cerrados = (int) tickets.stream().filter(t -> "CERRADO".equalsIgnoreCase(t.getEstado())).count();
        double recaudado = tickets.stream()
                .filter(t -> "CERRADO".equalsIgnoreCase(t.getEstado()) && t.getMontoTotal() != null)
                .mapToDouble(TicketsIaClient.TicketDto::getMontoTotal)
                .sum();
        double porCobrar = abiertosAhora.stream()
                .mapToDouble(this::estimadoAbierto)
                .sum();

        Map<String, Long> porZona = tickets.stream()
                .filter(t -> t.getZona() != null)
                .collect(Collectors.groupingBy(TicketsIaClient.TicketDto::getZona, Collectors.counting()));

        Map<String, Object> ocupacion = Map.of();
        try {
            ocupacion = espaciosClient.ocupacion(authorization);
        } catch (Exception ignored) {
            if (sync.get("ocupacion") instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                ocupacion = cast;
            }
        }

        int plazasTotal = toInt(ocupacion.get("total"));
        int plazasOcupadas = toInt(ocupacion.get("ocupados"));
        int plazasLibres = toInt(ocupacion.get("libres"));
        int plazasPct = toInt(ocupacion.get("porcentajeGlobal"));
        int ticketsAbiertosAhora = abiertosAhora.size();
        long plazasUnicasConTicket = abiertosAhora.stream()
                .map(TicketsIaClient.TicketDto::getEspacioId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        boolean cuadran = plazasOcupadas == plazasUnicasConTicket
                && plazasOcupadas == toInt(sync.getOrDefault("plazasOcupadasTrasSync", plazasOcupadas));

        List<Map<String, Object>> ocupadasDetalle = abiertosAhora.stream()
                .sorted(Comparator.comparing(t -> String.valueOf(t.getEspacioCodigo())))
                .map(t -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("plaza", t.getEspacioCodigo() != null ? t.getEspacioCodigo() : String.valueOf(t.getEspacioId()));
                    row.put("placa", t.getPlaca());
                    row.put("zona", t.getZona() != null ? t.getZona() : "-");
                    row.put("cliente", t.getUsuario() != null ? t.getUsuario() : "-");
                    row.put("ticket", t.getCodigo());
                    row.put("porCobrar", estimadoAbierto(t));
                    return row;
                })
                .collect(Collectors.toList());

        String plazasOcupadasTexto = ocupadasDetalle.isEmpty()
                ? "ninguna"
                : ocupadasDetalle.stream()
                .map(r -> r.get("plaza") + " (" + r.get("placa") + ")")
                .collect(Collectors.joining(", "));

        List<String> hallazgos = new ArrayList<>();
        hallazgos.add("Ahora hay " + plazasOcupadas + " plazas ocupadas de " + plazasTotal
                + " (" + plazasPct + "%). Libres: " + plazasLibres + ".");
        hallazgos.add(ocupadasDetalle.isEmpty()
                ? "No hay clientes estacionados en este momento."
                : "Plazas ocupadas por clientes: " + plazasOcupadasTexto + ".");
        hallazgos.add(cuadran
                ? "Los números cuadran: cada plaza ocupada tiene su ticket abierto."
                : "Revisar: plazas ocupadas (" + plazasOcupadas + ") no coinciden con tickets abiertos ("
                + plazasUnicasConTicket + ").");
        hallazgos.add("En el periodo " + periodo + " hubo " + tickets.size()
                + " tickets (" + cerrados + " cerrados, " + abiertosPeriodo + " aún abiertos).");
        hallazgos.add(String.format("Dinero cobrado en el periodo: $%.2f.", recaudado));
        hallazgos.add(String.format("Dinero pendiente por cobrar ahora: $%.2f.", porCobrar));
        if (!porZona.isEmpty()) {
            String zonas = porZona.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining(" · "));
            hallazgos.add("Movimiento por zona en el periodo: " + zonas + ".");
        }

        String resumenDatos = """
                Resume de forma clara para un gerente de estacionamiento.
                No mezcles ocupación actual con tickets del periodo.
                
                AHORA EN EL PARQUEADERO:
                - Total plazas: %d | Ocupadas: %d | Libres: %d | Ocupación: %d%%
                - Tickets abiertos ahora: %d | ¿Cuadra con plazas? %s
                - Plazas ocupadas (número + placa): %s
                
                PERIODO DE TICKETS (%s):
                - Tickets del periodo: %d (cerrados: %d, abiertos en periodo: %d)
                - Recaudado: $%.2f | Por cobrar ahora: $%.2f
                - Por zona: %s
                """.formatted(
                plazasTotal, plazasOcupadas, plazasLibres, plazasPct,
                ticketsAbiertosAhora, cuadran ? "SÍ" : "NO",
                plazasOcupadasTexto,
                periodo, tickets.size(), cerrados, abiertosPeriodo,
                recaudado, porCobrar, porZona
        );

        String[] ia = generarTextoOllama(resumenDatos, periodo);

        Map<String, Object> metricas = new LinkedHashMap<>();
        metricas.put("fechaDesde", desde.toString());
        metricas.put("fechaHasta", hasta.toString());
        metricas.put("periodo", periodo);
        metricas.put("plazasTotal", plazasTotal);
        metricas.put("plazasOcupadas", plazasOcupadas);
        metricas.put("plazasLibres", plazasLibres);
        metricas.put("plazasPorcentaje", plazasPct);
        metricas.put("ticketsAbiertosAhora", ticketsAbiertosAhora);
        metricas.put("cuadranConPlazas", cuadran);
        metricas.put("totalTickets", tickets.size());
        metricas.put("abiertosPeriodo", abiertosPeriodo);
        metricas.put("cerrados", cerrados);
        metricas.put("recaudado", Math.round(recaudado * 100.0) / 100.0);
        metricas.put("porCobrarEstimado", Math.round(porCobrar * 100.0) / 100.0);
        metricas.put("porZona", porZona);
        metricas.put("plazasOcupadasDetalle", ocupadasDetalle);
        metricas.put("ocupacion", ocupacion);
        metricas.put("sync", sync);
        metricas.put("generadoEn", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        return ReporteTicketsResponse.builder()
                .totalTickets(tickets.size())
                .abiertos(abiertosPeriodo)
                .cerrados(cerrados)
                .recaudado(Math.round(recaudado * 100.0) / 100.0)
                .porCobrarEstimado(Math.round(porCobrar * 100.0) / 100.0)
                .fechaDesde(desde.toString())
                .fechaHasta(hasta.toString())
                .plazasTotal(plazasTotal)
                .plazasOcupadas(plazasOcupadas)
                .plazasLibres(plazasLibres)
                .plazasPorcentaje(plazasPct)
                .ticketsAbiertosAhora(ticketsAbiertosAhora)
                .cuadranConPlazas(cuadran)
                .resumenIa(ia[0])
                .proveedorIa(ia[1])
                .hallazgos(hallazgos)
                .metricas(metricas)
                .build();
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return 0;
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(o)));
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean enRango(String entradaIso, LocalDateTime inicio, LocalDateTime fin) {
        LocalDateTime entrada = parseFecha(entradaIso);
        if (entrada == null) return false;
        return !entrada.isBefore(inicio) && !entrada.isAfter(fin);
    }

    private LocalDateTime parseFecha(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            String s = iso.length() >= 19 ? iso.substring(0, 19) : iso;
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            try {
                return LocalDate.parse(iso.substring(0, Math.min(10, iso.length()))).atStartOfDay();
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private double estimadoAbierto(TicketsIaClient.TicketDto t) {
        if (t.getEntrada() == null || t.getTarifaHora() == null) return 0;
        try {
            LocalDateTime ini = parseFecha(t.getEntrada());
            if (ini == null) return t.getTarifaHora();
            long minutos = Math.max(1, Duration.between(ini, LocalDateTime.now()).toMinutes());
            double horas = Math.ceil(minutos / 60.0);
            return Math.round(horas * t.getTarifaHora() * 100.0) / 100.0;
        } catch (Exception e) {
            return t.getTarifaHora();
        }
    }

    private String[] generarTextoOllama(String datos, String periodo) {
        String prompt = """
                Eres analista de Urban Park. Escribe un resumen MUY CLARO en español para el administrador.
                Periodo: %s.
                Reglas:
                1) Primero di cuántas plazas están ocupadas/libres AHORA y menciona números de plaza si hay.
                2) Luego di cuánto se cobró y cuánto falta por cobrar en el periodo.
                3) Cierra con UNA recomendación práctica (máximo 1 frase).
                Usa lenguaje simple. Máximo 4 oraciones cortas. Sin markdown, sin listas, sin jerga técnica.
                
                DATOS:
                %s
                """.formatted(periodo, datos);
        try {
            String raw = ollama.generateText(prompt);
            String texto = raw.replace("\\n", " ").trim();
            if (texto.isBlank()) throw new IllegalStateException("vacío");
            return new String[]{texto, "ollama/" + ollama.getModel()};
        } catch (Exception ex) {
            String local = "Ahora el parqueadero tiene plazas ocupadas que deben coincidir con tickets abiertos. "
                    + "En el periodo " + periodo + " revisa lo cobrado y lo pendiente. "
                    + "Prioriza cerrar tickets abiertos antiguos.";
            return new String[]{local, "heuristica-local"};
        }
    }

    private static String shortMsg(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
