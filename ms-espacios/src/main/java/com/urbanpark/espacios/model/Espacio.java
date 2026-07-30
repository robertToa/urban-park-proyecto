package com.urbanpark.espacios.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "espacios")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Espacio {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String codigo;

    @Column(nullable = false)
    private String zona;

    @Column(nullable = false)
    private String tipoVehiculo; // AUTO, MOTO, CAMIONETA, DISCAPACIDAD

    @Column(nullable = false)
    private Boolean ocupado = false;

    @Column(nullable = false)
    private Double tarifaHora;

    private Integer nivel;
    private String descripcion;
}
