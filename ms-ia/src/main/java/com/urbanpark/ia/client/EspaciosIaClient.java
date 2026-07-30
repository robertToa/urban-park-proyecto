package com.urbanpark.ia.client;

import lombok.Data;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@FeignClient(name = "espaciosIaClient", url = "${espacios.url}")
public interface EspaciosIaClient {

    @GetMapping("/api/espacios/disponibles")
    List<EspacioDto> disponibles(@RequestParam(value = "tipo", required = false) String tipo,
                                 @RequestHeader("Authorization") String authorization);

    @GetMapping("/api/espacios/ocupacion")
    Map<String, Object> ocupacion(@RequestHeader("Authorization") String authorization);

    @Data
    class EspacioDto {
        private Long id;
        private String codigo;
        private String zona;
        private String tipoVehiculo;
        private Boolean ocupado;
        private Double tarifaHora;
        private Integer nivel;
        private String descripcion;
    }
}
