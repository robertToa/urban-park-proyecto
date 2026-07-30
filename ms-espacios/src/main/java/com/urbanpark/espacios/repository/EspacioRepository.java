package com.urbanpark.espacios.repository;

import com.urbanpark.espacios.model.Espacio;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EspacioRepository extends JpaRepository<Espacio, Long> {
    Optional<Espacio> findByCodigo(String codigo);
    List<Espacio> findByOcupadoFalse();
    List<Espacio> findByZonaAndOcupadoFalse(String zona);
    List<Espacio> findByTipoVehiculoAndOcupadoFalse(String tipoVehiculo);
    long countByOcupadoTrue();
    long countByZona(String zona);
    long countByZonaAndOcupadoTrue(String zona);
}
