package com.urbanpark.vehiculos.service;

import com.urbanpark.vehiculos.model.Vehiculo;
import com.urbanpark.vehiculos.repository.VehiculoRepository;
import com.urbanpark.vehiculos.security.AuthSupport;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VehiculoService {

    private final VehiculoRepository repository;

    @PostConstruct
    void seed() {
        if (repository.count() == 0) {
            repository.saveAll(List.of(
                    Vehiculo.builder().placa("ABC-123").tipo("AUTO").marca("Toyota").color("Gris")
                            .propietario("Ana Cliente").propietarioEmail("cliente1@urbanpark.local")
                            .propietarioUsername("cliente1").build(),
                    Vehiculo.builder().placa("XYZ-789").tipo("MOTO").marca("Yamaha").color("Rojo")
                            .propietario("Luis Operador").propietarioEmail("operador1@urbanpark.local")
                            .propietarioUsername("operador1").build()
            ));
        }
        // Backfill dueños para datos previos
        repository.findAll().forEach(v -> {
            if (v.getPropietarioUsername() == null || v.getPropietarioUsername().isBlank()) {
                if (v.getPropietarioEmail() != null && v.getPropietarioEmail().startsWith("cliente1")) {
                    v.setPropietarioUsername("cliente1");
                    repository.save(v);
                } else if (v.getPropietarioEmail() != null && v.getPropietarioEmail().startsWith("operador1")) {
                    v.setPropietarioUsername("operador1");
                    repository.save(v);
                } else if ("ABC-123".equalsIgnoreCase(v.getPlaca())) {
                    v.setPropietarioUsername("cliente1");
                    repository.save(v);
                } else if ("XYZ-789".equalsIgnoreCase(v.getPlaca())) {
                    v.setPropietarioUsername("operador1");
                    repository.save(v);
                }
            }
        });
        asegurarVehiculo("DEF-456", "AUTO", "Chevrolet", "Azul", "Pedro Cliente", "cliente2@urbanpark.local", "cliente2");
        asegurarVehiculo("GHI-789", "CAMIONETA", "Ford", "Blanco", "María Cliente", "cliente3@urbanpark.local", "cliente3");
    }

    private void asegurarVehiculo(String placa, String tipo, String marca, String color,
                                  String propietario, String email, String username) {
        boolean existe = repository.findAll().stream()
                .anyMatch(v -> placa.equalsIgnoreCase(v.getPlaca()));
        if (!existe) {
            repository.save(Vehiculo.builder()
                    .placa(placa).tipo(tipo).marca(marca).color(color)
                    .propietario(propietario).propietarioEmail(email)
                    .propietarioUsername(username).build());
        }
    }

    public List<Vehiculo> listar(Jwt jwt) {
        if (AuthSupport.esClienteSolo(jwt)) {
            return repository.findByPropietarioUsernameIgnoreCase(AuthSupport.username(jwt));
        }
        return repository.findAll();
    }

    public Vehiculo obtener(Long id, Jwt jwt) {
        Vehiculo v = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehículo no encontrado"));
        asegurarAcceso(v, jwt);
        return v;
    }

    public Vehiculo porPlaca(String placa, Jwt jwt) {
        Vehiculo v = repository.findByPlacaIgnoreCase(placa)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Placa no registrada"));
        asegurarAcceso(v, jwt);
        return v;
    }

    public Vehiculo crear(Vehiculo v, Jwt jwt) {
        if (v.getPlaca() == null || v.getPlaca().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La placa es obligatoria");
        }
        String placa = v.getPlaca().trim().toUpperCase().replace(" ", "");
        if (placa.length() < 4 || placa.length() > 12 || !placa.matches("^[A-Z0-9]{2,8}(-?[A-Z0-9]{1,4})?$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Placa inválida. Usa formato tipo ABC-123");
        }
        v.setPlaca(placa);
        if (v.getPropietario() == null || v.getPropietario().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El propietario es obligatorio");
        }
        repository.findByPlacaIgnoreCase(placa).ifPresent(x -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La placa ya está registrada");
        });
        v.setId(null);
        v.setTipo(v.getTipo() != null ? v.getTipo().toUpperCase() : "AUTO");
        if (AuthSupport.esClienteSolo(jwt)) {
            v.setPropietarioUsername(AuthSupport.username(jwt));
            if (v.getPropietario() == null || v.getPropietario().isBlank()) {
                v.setPropietario(AuthSupport.username(jwt));
            }
        } else if (v.getPropietarioUsername() == null || v.getPropietarioUsername().isBlank()) {
            v.setPropietarioUsername(AuthSupport.username(jwt));
        }
        return repository.save(v);
    }

    public Vehiculo actualizar(Long id, Vehiculo data, Jwt jwt) {
        Vehiculo v = obtener(id, jwt);
        v.setMarca(data.getMarca());
        v.setColor(data.getColor());
        v.setTipo(data.getTipo());
        v.setPropietario(data.getPropietario());
        v.setPropietarioEmail(data.getPropietarioEmail());
        if (!AuthSupport.esClienteSolo(jwt) && data.getPropietarioUsername() != null) {
            v.setPropietarioUsername(data.getPropietarioUsername());
        }
        return repository.save(v);
    }

    public void eliminar(Long id, Jwt jwt) {
        Vehiculo v = obtener(id, jwt);
        if (AuthSupport.esClienteSolo(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El cliente no puede eliminar vehículos; contacta al operador");
        }
        repository.delete(v);
    }

    private void asegurarAcceso(Vehiculo v, Jwt jwt) {
        if (!AuthSupport.esClienteSolo(jwt)) return;
        String user = AuthSupport.username(jwt);
        if (user == null || v.getPropietarioUsername() == null || !user.equalsIgnoreCase(v.getPropietarioUsername())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo puedes gestionar tus propios vehículos");
        }
    }
}
