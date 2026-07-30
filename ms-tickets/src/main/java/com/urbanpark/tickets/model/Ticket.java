package com.urbanpark.tickets.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tickets")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String codigo;

    @Column(nullable = false)
    private String placa;

    @Column(nullable = false)
    private Long espacioId;

    private String espacioCodigo;
    private String zona;

    @Column(nullable = false)
    private LocalDateTime entrada;

    private LocalDateTime salida;

    @Column(nullable = false)
    private String estado; // ABIERTO, CERRADO

    private Double tarifaHora;
    private Double montoTotal;

    /** Usuario Keycloak (preferred_username) dueño del ticket. */
    private String usuario;

    /**
     * true si el ticket ya se cobró/cerró pero no se pudo liberar la plaza
     * (p. ej. ms-espacios caído). Se limpia al sincronizar cuando el servicio vuelve.
     */
    @Builder.Default
    @Column(nullable = false)
    private Boolean liberacionPendiente = false;
}
