package com.urbanpark.vehiculos.controller;

import com.urbanpark.vehiculos.model.Vehiculo;
import com.urbanpark.vehiculos.service.VehiculoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vehiculos")
@RequiredArgsConstructor
@CrossOrigin
public class VehiculoController {

    private final VehiculoService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public List<Vehiculo> listar(@AuthenticationPrincipal Jwt jwt) {
        return service.listar(jwt);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public Vehiculo obtener(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return service.obtener(id, jwt);
    }

    @GetMapping("/placa/{placa}")
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public Vehiculo porPlaca(@PathVariable String placa, @AuthenticationPrincipal Jwt jwt) {
        return service.porPlaca(placa, jwt);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public Vehiculo crear(@RequestBody Vehiculo vehiculo, @AuthenticationPrincipal Jwt jwt) {
        return service.crear(vehiculo, jwt);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
    public Vehiculo actualizar(@PathVariable Long id, @RequestBody Vehiculo vehiculo,
                               @AuthenticationPrincipal Jwt jwt) {
        return service.actualizar(id, vehiculo, jwt);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
    public void eliminar(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        service.eliminar(id, jwt);
    }
}
