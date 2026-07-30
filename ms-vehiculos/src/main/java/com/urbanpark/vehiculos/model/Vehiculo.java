package com.urbanpark.vehiculos.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "vehiculos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Vehiculo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String placa;

    @Column(nullable = false)
    private String tipo; // AUTO, MOTO, CAMIONETA

    private String marca;
    private String color;

    @Column(nullable = false)
    private String propietario;

    private String propietarioEmail;

    /** Usuario Keycloak dueño del vehículo. */
    private String propietarioUsername;
}
