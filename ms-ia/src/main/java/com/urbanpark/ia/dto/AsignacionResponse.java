package com.urbanpark.ia.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AsignacionResponse {
    private Long espacioId;
    private String espacioCodigo;
    private String zona;
    private Double tarifaHora;
    private Integer nivel;
    private String placa;
    private Long ticketId;
    private String ticketCodigo;
    private String motivo;
    private String explicacionIa;
    private String proveedorIa;
    private boolean asignado;
}
