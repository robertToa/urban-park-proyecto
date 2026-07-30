package com.urbanpark.ia.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

@FeignClient(name = "ticketsIaClient", url = "${tickets.url}")
public interface TicketsIaClient {

    @GetMapping("/api/tickets")
    List<TicketDto> listar(@RequestHeader("Authorization") String authorization);

    @GetMapping("/api/tickets/abiertos")
    List<TicketDto> abiertos(@RequestHeader("Authorization") String authorization);

    @PostMapping("/api/tickets/abrir")
    TicketDto abrir(@RequestBody AbrirRequest request,
                    @RequestHeader("Authorization") String authorization);

    @PostMapping("/api/tickets/reconciliar-plazas")
    Map<String, Object> reconciliarPlazas(@RequestHeader("Authorization") String authorization);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class AbrirRequest {
        private String placa;
        private Long espacioId;
    }

    @Data
    class TicketDto {
        private Long id;
        private String codigo;
        private String placa;
        private Long espacioId;
        private String espacioCodigo;
        private String zona;
        private String estado;
        private String entrada;
        private String salida;
        private Double tarifaHora;
        private Double montoTotal;
        private String usuario;
    }
}
