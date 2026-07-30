package com.urbanpark.ia.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ReporteTicketsResponse {
    private int totalTickets;
    private int abiertos;
    private int cerrados;
    private double recaudado;
    private double porCobrarEstimado;
    private String fechaDesde;
    private String fechaHasta;
    /** Plazas actuales (misma fuente que pestaña Plazas). */
    private int plazasTotal;
    private int plazasOcupadas;
    private int plazasLibres;
    private int plazasPorcentaje;
    private int ticketsAbiertosAhora;
    private boolean cuadranConPlazas;
    private String resumenIa;
    private String proveedorIa;
    private List<String> hallazgos;
    private Map<String, Object> metricas;
}
