package com.urbanpark.tickets.controller;

import com.urbanpark.tickets.model.Ticket;
import com.urbanpark.tickets.service.DockerDemoService;
import com.urbanpark.tickets.service.TicketService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
@CrossOrigin
public class TicketController {

    private final TicketService service;
    private final DockerDemoService dockerDemoService;

    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public List<Ticket> listar(@AuthenticationPrincipal Jwt jwt) {
        return service.listar(jwt);
    }

    @GetMapping("/abiertos")
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public List<Ticket> abiertos(@AuthenticationPrincipal Jwt jwt) {
        return service.abiertos(jwt);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public Ticket obtener(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return service.obtener(id, jwt);
    }

    /** Demo Resilience4j: solo ADMIN. */
    @GetMapping("/ocupacion")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> ocupacion(@RequestHeader("Authorization") String authorization) {
        return service.ocupacionRemota(authorization);
    }

    /** Demo: estado del contenedor ms-espacios (docker). Solo ADMIN. */
    @GetMapping("/demo/espacios/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> espaciosStatus() {
        return dockerDemoService.statusEspacios();
    }

    /** Demo: docker stop ms-espacios. Solo ADMIN. */
    @PostMapping("/demo/espacios/stop")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> espaciosStop() {
        return dockerDemoService.stopEspacios();
    }

    /** Demo: docker start ms-espacios. Solo ADMIN. */
    @PostMapping("/demo/espacios/start")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> espaciosStart() {
        return dockerDemoService.startEspacios();
    }

    /** Sincroniza estado de plazas con tickets ABIERTO (ADMIN). */
    @PostMapping("/reconciliar-plazas")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reconciliarPlazas(@RequestHeader("Authorization") String authorization) {
        return service.reconciliarPlazas(authorization);
    }

    /**
     * Tras pagar con ms-espacios caído: libera plazas pendientes cuando el servicio vuelve.
     * CLIENTE solo las suyas; OPERADOR/ADMIN todas.
     */
    @PostMapping("/sincronizar-liberaciones")
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public Map<String, Object> sincronizarLiberaciones(
            @RequestHeader("Authorization") String authorization,
            @AuthenticationPrincipal Jwt jwt) {
        return service.sincronizarLiberacionesPendientes(authorization, jwt);
    }

    @PostMapping("/abrir")
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public Ticket abrir(@RequestBody AbrirRequest req,
                        @RequestHeader("Authorization") String authorization,
                        @AuthenticationPrincipal Jwt jwt) {
        return service.abrir(req.getPlaca(), req.getEspacioId(), authorization, jwt);
    }

    @PostMapping("/{id}/cerrar")
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public Ticket cerrar(@PathVariable Long id,
                         @RequestHeader("Authorization") String authorization,
                         @AuthenticationPrincipal Jwt jwt) {
        return service.cerrar(id, authorization, jwt);
    }

    @Data
    public static class AbrirRequest {
        private String placa;
        private Long espacioId;
    }
}
