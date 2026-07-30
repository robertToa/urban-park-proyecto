package com.urbanpark.espacios.service;

import com.urbanpark.espacios.model.Espacio;
import com.urbanpark.espacios.repository.EspacioRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EspacioService {

    private final EspacioRepository repository;

    @PostConstruct
    void seed() {
        List<Espacio> plantilla = List.of(
                Espacio.builder().codigo("A-01").zona("Zona A - Centro").tipoVehiculo("AUTO").ocupado(false).tarifaHora(3.5).nivel(1).descripcion("Plaza techada cerca de ascensor").build(),
                Espacio.builder().codigo("A-02").zona("Zona A - Centro").tipoVehiculo("AUTO").ocupado(false).tarifaHora(3.5).nivel(1).descripcion("Plaza estándar").build(),
                Espacio.builder().codigo("A-03").zona("Zona A - Centro").tipoVehiculo("DISCAPACIDAD").ocupado(false).tarifaHora(2.0).nivel(1).descripcion("Plaza preferencial").build(),
                Espacio.builder().codigo("A-04").zona("Zona A - Centro").tipoVehiculo("AUTO").ocupado(false).tarifaHora(3.5).nivel(1).descripcion("Plaza cerca de salida").build(),
                Espacio.builder().codigo("A-05").zona("Zona A - Centro").tipoVehiculo("CAMIONETA").ocupado(false).tarifaHora(4.0).nivel(1).descripcion("Plaza amplia planta baja").build(),
                Espacio.builder().codigo("B-01").zona("Zona B - Norte").tipoVehiculo("AUTO").ocupado(false).tarifaHora(2.8).nivel(2).descripcion("Vista exterior").build(),
                Espacio.builder().codigo("B-02").zona("Zona B - Norte").tipoVehiculo("CAMIONETA").ocupado(false).tarifaHora(4.0).nivel(2).descripcion("Plaza amplia").build(),
                Espacio.builder().codigo("B-03").zona("Zona B - Norte").tipoVehiculo("AUTO").ocupado(false).tarifaHora(2.8).nivel(2).descripcion("Plaza estándar N2").build(),
                Espacio.builder().codigo("B-04").zona("Zona B - Norte").tipoVehiculo("DISCAPACIDAD").ocupado(false).tarifaHora(2.0).nivel(2).descripcion("Plaza preferencial N2").build(),
                Espacio.builder().codigo("B-05").zona("Zona B - Norte").tipoVehiculo("MOTO").ocupado(false).tarifaHora(1.5).nivel(2).descripcion("Motos N2").build(),
                Espacio.builder().codigo("C-01").zona("Zona C - Sur").tipoVehiculo("MOTO").ocupado(false).tarifaHora(1.5).nivel(-1).descripcion("Motos sótano").build(),
                Espacio.builder().codigo("C-02").zona("Zona C - Sur").tipoVehiculo("MOTO").ocupado(false).tarifaHora(1.5).nivel(-1).descripcion("Motos sótano").build(),
                Espacio.builder().codigo("C-03").zona("Zona C - Sur").tipoVehiculo("AUTO").ocupado(false).tarifaHora(2.5).nivel(-1).descripcion("Plaza sótano").build(),
                Espacio.builder().codigo("C-04").zona("Zona C - Sur").tipoVehiculo("CAMIONETA").ocupado(false).tarifaHora(3.8).nivel(-1).descripcion("Camionetas sótano").build(),
                Espacio.builder().codigo("C-05").zona("Zona C - Sur").tipoVehiculo("AUTO").ocupado(false).tarifaHora(2.5).nivel(-1).descripcion("Plaza sótano").build(),
                Espacio.builder().codigo("D-01").zona("Zona D - Este").tipoVehiculo("AUTO").ocupado(false).tarifaHora(3.0).nivel(1).descripcion("Plaza zona este").build(),
                Espacio.builder().codigo("D-02").zona("Zona D - Este").tipoVehiculo("AUTO").ocupado(false).tarifaHora(3.0).nivel(1).descripcion("Plaza zona este").build(),
                Espacio.builder().codigo("D-03").zona("Zona D - Este").tipoVehiculo("MOTO").ocupado(false).tarifaHora(1.5).nivel(1).descripcion("Motos este").build(),
                Espacio.builder().codigo("D-04").zona("Zona D - Este").tipoVehiculo("CAMIONETA").ocupado(false).tarifaHora(4.2).nivel(1).descripcion("Camionetas este").build(),
                Espacio.builder().codigo("D-05").zona("Zona D - Este").tipoVehiculo("DISCAPACIDAD").ocupado(false).tarifaHora(2.0).nivel(1).descripcion("Plaza preferencial este").build()
        );
        List<Espacio> faltantes = plantilla.stream()
                .filter(p -> repository.findByCodigo(p.getCodigo()).isEmpty())
                .toList();
        if (!faltantes.isEmpty()) {
            repository.saveAll(faltantes);
        }
    }

    public List<Espacio> listar() {
        return repository.findAll();
    }

    public List<Espacio> disponibles(String tipo, String zona) {
        if (tipo != null && !tipo.isBlank()) {
            return repository.findByTipoVehiculoAndOcupadoFalse(tipo.toUpperCase());
        }
        if (zona != null && !zona.isBlank()) {
            return repository.findByZonaAndOcupadoFalse(zona);
        }
        return repository.findByOcupadoFalse();
    }

    public Espacio obtener(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Espacio no encontrado"));
    }

    public Espacio crear(Espacio e) {
        e.setId(null);
        if (e.getOcupado() == null) e.setOcupado(false);
        return repository.save(e);
    }

    public Espacio actualizar(Long id, Espacio data) {
        Espacio e = obtener(id);
        e.setCodigo(data.getCodigo());
        e.setZona(data.getZona());
        e.setTipoVehiculo(data.getTipoVehiculo());
        e.setTarifaHora(data.getTarifaHora());
        e.setNivel(data.getNivel());
        e.setDescripcion(data.getDescripcion());
        return repository.save(e);
    }

    public Espacio ocupar(Long id) {
        Espacio e = obtener(id);
        if (Boolean.TRUE.equals(e.getOcupado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La plaza ya está ocupada");
        }
        e.setOcupado(true);
        return repository.save(e);
    }

    public Espacio liberar(Long id) {
        Espacio e = obtener(id);
        e.setOcupado(false);
        return repository.save(e);
    }

    public Map<String, Object> ocupacion() {
        long total = repository.count();
        long ocupados = repository.countByOcupadoTrue();
        Map<String, Object> zonas = repository.findAll().stream()
                .collect(Collectors.groupingBy(Espacio::getZona)).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, en -> {
                    long t = en.getValue().size();
                    long o = en.getValue().stream().filter(Espacio::getOcupado).count();
                    return Map.of("total", t, "ocupados", o, "libres", t - o,
                            "porcentaje", t == 0 ? 0 : Math.round(o * 100.0 / t));
                }));
        return Map.of(
                "total", total,
                "ocupados", ocupados,
                "libres", total - ocupados,
                "porcentajeGlobal", total == 0 ? 0 : Math.round(ocupados * 100.0 / total),
                "zonas", zonas
        );
    }
}
