package com.urbanpark.tickets.client;

import lombok.Data;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "espaciosClient", url = "${espacios.url}")
public interface EspaciosClient {

    @GetMapping("/api/espacios")
    java.util.List<EspacioDto> listar(@RequestHeader("Authorization") String authorization);

    @GetMapping("/api/espacios/{id}")
    EspacioDto obtener(@PathVariable("id") Long id,
                       @RequestHeader("Authorization") String authorization);

    @PostMapping("/api/espacios/{id}/ocupar")
    EspacioDto ocupar(@PathVariable("id") Long id,
                      @RequestHeader("Authorization") String authorization);

    @PostMapping("/api/espacios/{id}/liberar")
    EspacioDto liberar(@PathVariable("id") Long id,
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
