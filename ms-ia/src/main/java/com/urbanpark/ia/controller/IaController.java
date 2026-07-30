package com.urbanpark.ia.controller;

import com.urbanpark.ia.dto.AsignacionResponse;
import com.urbanpark.ia.dto.RecomendacionResponse;
import com.urbanpark.ia.dto.ReporteTicketsResponse;
import com.urbanpark.ia.service.RecomendacionService;
import com.urbanpark.ia.service.ReporteService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ia")
@RequiredArgsConstructor
@CrossOrigin
public class IaController {

    private final RecomendacionService service;
    private final ReporteService reporteService;

    /** Ollama elige la mejor plaza disponible (sin ocupar). */
    @PostMapping("/recomendar")
    @PreAuthorize("hasAnyRole('CLIENTE','ADMIN')")
    public RecomendacionResponse recomendar(@RequestBody RecomendarRequest req,
                                            @RequestHeader("Authorization") String authorization) {
        return service.recomendar(req.getTipoVehiculo(), req.getPreferencia(), authorization);
    }

    /** Ollama elige plaza y abre el ticket (asignación real de parqueo). */
    @PostMapping("/asignar")
    @PreAuthorize("hasAnyRole('CLIENTE','ADMIN')")
    public AsignacionResponse asignar(@RequestBody AsignarRequest req,
                                      @RequestHeader("Authorization") String authorization) {
        return service.asignar(req.getPlaca(), req.getTipoVehiculo(), req.getPreferencia(), authorization);
    }

    /** Reporte operativo por rango de fechas: solo ADMIN. */
    @PostMapping("/reporte-tickets")
    @PreAuthorize("hasRole('ADMIN')")
    public ReporteTicketsResponse reporteTickets(@RequestBody(required = false) ReporteRequest req,
                                                 @RequestHeader("Authorization") String authorization) {
        if (req == null) req = new ReporteRequest();
        return reporteService.generar(authorization, req.getFechaDesde(), req.getFechaHasta());
    }

    @GetMapping("/ollama")
    public Map<String, Object> ollama() {
        return service.estadoOllama();
    }

    @GetMapping("/salud")
    public MapSalud salud() {
        return new MapSalud("ms-ia OK", "Urban Park AI + Ollama");
    }

    @Data
    public static class RecomendarRequest {
        private String tipoVehiculo;
        private String preferencia;
    }

    @Data
    public static class AsignarRequest {
        private String placa;
        private String tipoVehiculo;
        private String preferencia;
    }

    @Data
    public static class ReporteRequest {
        /** yyyy-MM-dd */
        private java.time.LocalDate fechaDesde;
        /** yyyy-MM-dd */
        private java.time.LocalDate fechaHasta;
    }

    public record MapSalud(String status, String servicio) {}
}
