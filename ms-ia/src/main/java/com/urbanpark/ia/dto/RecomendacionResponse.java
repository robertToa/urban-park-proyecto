package com.urbanpark.ia.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class RecomendacionResponse {
    private String zonaRecomendada;
    private Long espacioId;
    private String espacioCodigo;
    private Double tarifaHora;
    private Integer nivel;
    private String motivo;
    private String explicacionIa;
    private String proveedorIa;
    private List<String> alternativas;
}
