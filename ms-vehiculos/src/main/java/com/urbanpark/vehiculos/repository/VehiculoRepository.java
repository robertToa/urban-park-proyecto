package com.urbanpark.vehiculos.repository;

import com.urbanpark.vehiculos.model.Vehiculo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VehiculoRepository extends JpaRepository<Vehiculo, Long> {
    Optional<Vehiculo> findByPlacaIgnoreCase(String placa);
    List<Vehiculo> findByPropietarioContainingIgnoreCase(String propietario);
    List<Vehiculo> findByPropietarioUsernameIgnoreCase(String propietarioUsername);
}
