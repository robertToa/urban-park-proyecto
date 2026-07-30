package com.urbanpark.tickets.service;

import com.urbanpark.tickets.client.EspaciosClient;
import com.urbanpark.tickets.model.Ticket;
import com.urbanpark.tickets.repository.TicketRepository;
import com.urbanpark.tickets.security.AuthSupport;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository repository;
    private final EspaciosClient espaciosClient;

    public List<Ticket> listar(Jwt jwt) {
        if (AuthSupport.esClienteSolo(jwt)) {
            String user = AuthSupport.username(jwt);
            return repository.findByUsuarioIgnoreCase(user);
        }
        return repository.findAll();
    }

    public List<Ticket> abiertos(Jwt jwt) {
        if (AuthSupport.esClienteSolo(jwt)) {
            return repository.findByUsuarioIgnoreCaseAndEstado(AuthSupport.username(jwt), "ABIERTO");
        }
        return repository.findByEstado("ABIERTO");
    }

    public Ticket obtener(Long id, Jwt jwt) {
        Ticket t = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket no encontrado"));
        asegurarAcceso(t, jwt);
        return t;
    }

    public Ticket abrir(String placa, Long espacioId, String authorization, Jwt jwt) {
        String user = AuthSupport.username(jwt);
        String placaNorm = validarPlaca(placa);
        if (espacioId == null || espacioId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes indicar una plaza válida");
        }

        repository.findByPlacaAndEstado(placaNorm, "ABIERTO").ifPresent(t -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe un ticket abierto para la placa " + placaNorm
                            + " (ticket " + t.getCodigo() + "). Ciérralo antes de abrir otro.");
        });

        repository.findByEspacioIdAndEstado(espacioId, "ABIERTO").ifPresent(t -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La plaza ya tiene un ticket abierto (" + t.getCodigo() + " · " + t.getPlaca()
                            + "). Elige otra plaza libre o cierra ese ticket.");
        });

        EspaciosClient.EspacioDto espacio;
        try {
            espacio = espaciosClient.ocupar(espacioId, authorization);
        } catch (FeignException ex) {
            throw mapEspaciosError(ex, espacioId);
        }

        Ticket ticket = Ticket.builder()
                .codigo("TK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .placa(placaNorm)
                .espacioId(espacio.getId())
                .espacioCodigo(espacio.getCodigo())
                .zona(espacio.getZona())
                .entrada(LocalDateTime.now())
                .estado("ABIERTO")
                .tarifaHora(espacio.getTarifaHora())
                .usuario(user)
                .build();
        return repository.save(ticket);
    }

    /** Placa: 4–12 chars, letras/números y guion. */
    private String validarPlaca(String placa) {
        if (placa == null || placa.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La placa es obligatoria");
        }
        String p = placa.trim().toUpperCase().replace(" ", "");
        if (!p.matches("^[A-Z0-9]{2,8}(-?[A-Z0-9]{1,4})?$") || p.length() < 4 || p.length() > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Placa inválida. Usa formato tipo ABC-123 o ABC123 (4 a 12 caracteres).");
        }
        return p;
    }

    public Ticket cerrar(Long id, String authorization, Jwt jwt) {
        Ticket ticket = obtener(id, jwt);
        if ("CERRADO".equals(ticket.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El ticket ya está cerrado");
        }

        boolean liberacionPendiente = false;
        try {
            espaciosClient.liberar(ticket.getEspacioId(), authorization);
        } catch (FeignException ex) {
            if (esEspaciosNoDisponible(ex)) {
                // Pago/cierre no depende de ms-espacios: se cobra y se sincroniza después
                liberacionPendiente = true;
            } else if (ex.status() != 404) {
                throw mapEspaciosError(ex, ticket.getEspacioId());
            }
            // 404: plaza no existe → nada que liberar
        }

        ticket.setSalida(LocalDateTime.now());
        ticket.setEstado("CERRADO");
        ticket.setLiberacionPendiente(liberacionPendiente);
        long minutos = Math.max(1, Duration.between(ticket.getEntrada(), ticket.getSalida()).toMinutes());
        double horas = Math.ceil(minutos / 60.0);
        double tarifa = ticket.getTarifaHora() != null ? ticket.getTarifaHora() : 0;
        ticket.setMontoTotal(Math.round(horas * tarifa * 100.0) / 100.0);
        return repository.save(ticket);
    }

    /**
     * Reintenta liberar plazas de tickets ya cobrados mientras ms-espacios estaba caído.
     * Cualquier rol autenticado (CLIENTE solo los suyos).
     */
    public Map<String, Object> sincronizarLiberacionesPendientes(String authorization, Jwt jwt) {
        List<Ticket> pendientes;
        if (AuthSupport.esClienteSolo(jwt)) {
            pendientes = repository.findByUsuarioIgnoreCaseAndLiberacionPendienteTrue(
                    AuthSupport.username(jwt));
        } else {
            pendientes = repository.findByLiberacionPendienteTrue();
        }

        int liberadas = 0;
        int fallidas = 0;
        int sinCambio = 0;
        List<String> detalle = new ArrayList<>();

        if (pendientes.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("pendientes", 0);
            empty.put("liberadas", 0);
            empty.put("fallidas", 0);
            empty.put("mensaje", "No hay liberaciones pendientes");
            empty.put("detalle", List.of());
            return empty;
        }

        for (Ticket t : pendientes) {
            // Si aún hay un ticket ABIERTO en esa plaza, no liberar
            Optional<Ticket> abierto = repository.findByEspacioIdAndEstado(t.getEspacioId(), "ABIERTO");
            if (abierto.isPresent()) {
                t.setLiberacionPendiente(false);
                repository.save(t);
                sinCambio++;
                detalle.add(t.getCodigo() + ": hay ticket abierto, se cancela pendiente");
                continue;
            }
            try {
                espaciosClient.liberar(t.getEspacioId(), authorization);
                t.setLiberacionPendiente(false);
                repository.save(t);
                liberadas++;
                detalle.add(t.getCodigo() + " → plaza " + t.getEspacioCodigo() + " disponible");
            } catch (FeignException ex) {
                if (ex.status() == 404) {
                    t.setLiberacionPendiente(false);
                    repository.save(t);
                    liberadas++;
                    detalle.add(t.getCodigo() + ": plaza no existe, pendiente limpiado");
                } else if (esEspaciosNoDisponible(ex)) {
                    fallidas++;
                    detalle.add(t.getCodigo() + ": ms-espacios aún no disponible");
                } else {
                    fallidas++;
                    detalle.add(t.getCodigo() + ": error " + ex.status());
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pendientesIniciales", pendientes.size());
        out.put("liberadas", liberadas);
        out.put("fallidas", fallidas);
        out.put("sinCambio", sinCambio);
        out.put("pendientesRestantes", repository.countByLiberacionPendienteTrue());
        out.put("mensaje", liberadas > 0
                ? "Sincronizado: " + liberadas + " plaza(s) marcada(s) como disponible(s)"
                : (fallidas > 0
                    ? "Aún no se pudo sincronizar (¿ms-espacios sigue caído?)"
                    : "Sin cambios"));
        out.put("detalle", detalle);
        return out;
    }

    /**
     * Alinea plazas con tickets ABIERTO: cierra duplicados en la misma plaza,
     * ocupa plazas con ticket y libera plazas sin ticket.
     */
    public Map<String, Object> reconciliarPlazas(String authorization) {
        int duplicadosCerrados = cerrarDuplicadosMismaPlaza();

        List<Ticket> abiertos = repository.findByEstado("ABIERTO");
        Set<Long> plazasConTicket = abiertos.stream()
                .map(Ticket::getEspacioId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<EspaciosClient.EspacioDto> plazas;
        try {
            plazas = espaciosClient.listar(authorization);
        } catch (FeignException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudieron listar plazas para reconciliar");
        }
        if (plazas == null) plazas = List.of();

        int ocupadas = 0;
        int liberadas = 0;
        int yaOk = 0;
        List<String> detalle = new ArrayList<>();
        if (duplicadosCerrados > 0) {
            detalle.add("Cerrados " + duplicadosCerrados + " ticket(s) duplicado(s) en la misma plaza");
        }

        for (EspaciosClient.EspacioDto p : plazas) {
            boolean debeEstarOcupada = plazasConTicket.contains(p.getId());
            boolean estaOcupada = Boolean.TRUE.equals(p.getOcupado());
            if (debeEstarOcupada && !estaOcupada) {
                try {
                    espaciosClient.ocupar(p.getId(), authorization);
                    ocupadas++;
                    detalle.add("Ocupada " + p.getCodigo() + " (ticket abierto)");
                } catch (FeignException ex) {
                    if (ex.status() != 409) {
                        detalle.add("Error ocupando " + p.getCodigo() + ": " + ex.status());
                    } else {
                        yaOk++;
                    }
                }
            } else if (!debeEstarOcupada && estaOcupada) {
                try {
                    espaciosClient.liberar(p.getId(), authorization);
                    liberadas++;
                    detalle.add("Liberada " + p.getCodigo() + " (sin ticket abierto)");
                    limpiarPendientesDePlaza(p.getId());
                } catch (FeignException ex) {
                    detalle.add("Error liberando " + p.getCodigo() + ": " + ex.status());
                }
            } else {
                if (!debeEstarOcupada) {
                    limpiarPendientesDePlaza(p.getId());
                }
                yaOk++;
            }
        }

        abiertos = repository.findByEstado("ABIERTO");
        plazasConTicket = abiertos.stream()
                .map(Ticket::getEspacioId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, Object> ocupacion;
        try {
            ocupacion = espaciosClient.ocupacion(authorization);
        } catch (FeignException ex) {
            ocupacion = Map.of();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        long ocupadosNum = toLong(ocupacion.get("ocupados"));
        long libresNum = toLong(ocupacion.get("libres"));
        long totalNum = toLong(ocupacion.get("total"));
        boolean cuadran = ocupadosNum == plazasConTicket.size()
                && abiertos.size() == plazasConTicket.size();
        out.put("ticketsAbiertos", abiertos.size());
        out.put("plazasConTicketAbierto", plazasConTicket.size());
        out.put("plazasOcupadasTrasSync", ocupadosNum);
        out.put("plazasLibresTrasSync", libresNum);
        out.put("plazasTotal", totalNum >= 0 ? totalNum : plazas.size());
        out.put("porcentajeGlobal", ocupacion.getOrDefault("porcentajeGlobal", null));
        out.put("ocupacion", ocupacion);
        out.put("ajustesOcupar", ocupadas);
        out.put("ajustesLiberar", liberadas);
        out.put("duplicadosCerrados", duplicadosCerrados);
        out.put("sinCambio", yaOk);
        out.put("cuadran", cuadran);
        out.put("detalle", detalle);
        out.put("mensaje", cuadran
                ? "Plazas ocupadas (" + ocupadosNum + ") = tickets abiertos (" + abiertos.size() + ")"
                : "Aún no cuadran: ocupadas=" + ocupadosNum + " vs abiertos=" + abiertos.size());
        return out;
    }

    /** Si hay varios ABIERTO en la misma plaza, deja el más antiguo y cierra el resto (sin liberar plaza). */
    private int cerrarDuplicadosMismaPlaza() {
        List<Ticket> abiertos = repository.findByEstado("ABIERTO");
        Map<Long, List<Ticket>> porPlaza = abiertos.stream()
                .filter(t -> t.getEspacioId() != null)
                .collect(Collectors.groupingBy(Ticket::getEspacioId));
        int cerrados = 0;
        LocalDateTime ahora = LocalDateTime.now();
        for (List<Ticket> grupo : porPlaza.values()) {
            if (grupo.size() <= 1) continue;
            grupo.sort((a, b) -> {
                if (a.getEntrada() == null) return 1;
                if (b.getEntrada() == null) return -1;
                return a.getEntrada().compareTo(b.getEntrada());
            });
            for (int i = 1; i < grupo.size(); i++) {
                Ticket dup = grupo.get(i);
                dup.setSalida(ahora);
                dup.setEstado("CERRADO");
                long minutos = Math.max(1, Duration.between(
                        dup.getEntrada() != null ? dup.getEntrada() : ahora, ahora).toMinutes());
                double tarifa = dup.getTarifaHora() != null ? dup.getTarifaHora() : 0;
                double horas = Math.ceil(minutos / 60.0);
                dup.setMontoTotal(Math.round(horas * tarifa * 100.0) / 100.0);
                repository.save(dup);
                cerrados++;
            }
        }
        return cerrados;
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return -1;
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return -1;
        }
    }

    private void asegurarAcceso(Ticket ticket, Jwt jwt) {
        if (!AuthSupport.esClienteSolo(jwt)) return;
        String user = AuthSupport.username(jwt);
        if (user == null || ticket.getUsuario() == null || !user.equalsIgnoreCase(ticket.getUsuario())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo puedes ver o cerrar tus propios tickets");
        }
    }

    /** Demuestra Circuit Breaker + Retry hacia ms-espacios */
    @CircuitBreaker(name = "espaciosClient", fallbackMethod = "ocupacionFallback")
    @Retry(name = "espaciosClient")
    public Map<String, Object> ocupacionRemota(String authorization) {
        Map<String, Object> remote = espaciosClient.ocupacion(authorization);
        Map<String, Object> data = new HashMap<>(remote);
        data.put("origen", "ms-espacios (live)");
        data.put("resilience", "Circuit Breaker + Retry OK");
        return data;
    }

    @SuppressWarnings("unused")
    private Map<String, Object> ocupacionFallback(String authorization, Throwable ex) {
        return Map.of(
                "fallback", true,
                "origen", "ms-tickets fallback",
                "mensaje", "Resilience4j activó fallback: " + ex.getMessage(),
                "total", 0,
                "ocupados", 0,
                "libres", 0
        );
    }

    private void limpiarPendientesDePlaza(Long espacioId) {
        if (espacioId == null) return;
        for (Ticket t : repository.findByLiberacionPendienteTrue()) {
            if (espacioId.equals(t.getEspacioId())) {
                t.setLiberacionPendiente(false);
                repository.save(t);
            }
        }
    }

    /** ms-espacios caído, timeout, circuit o 5xx. */
    private boolean esEspaciosNoDisponible(FeignException ex) {
        int status = ex.status();
        return status < 0 || status == 502 || status == 503 || status == 504 || status >= 500;
    }

    private ResponseStatusException mapEspaciosError(FeignException ex, Long espacioId) {
        int status = ex.status();
        String body = ex.contentUTF8() != null ? ex.contentUTF8() : ex.getMessage();
        if (status == 409) {
            return new ResponseStatusException(HttpStatus.CONFLICT,
                    "La plaza ID " + espacioId + " ya está ocupada. Elige una plaza LIBRE en la pestaña Plazas.");
        }
        if (status == 404) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No existe la plaza ID " + espacioId + ". Revisa el ID en Plazas.");
        }
        if (status >= 500 || status < 0) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ms-espacios no disponible (Resilience/Feign). ¿Está caído el servicio? Detalle: " + body);
        }
        return new ResponseStatusException(HttpStatus.valueOf(Math.max(status, 400)), body);
    }
}
