package com.urbanpark.espacios.controller;

import com.urbanpark.espacios.model.Espacio;
import com.urbanpark.espacios.service.EspacioService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/espacios")
@RequiredArgsConstructor
@CrossOrigin
public class EspacioController {

    private final EspacioService service;

    @GetMapping
    public List<Espacio> listar() {
        return service.listar();
    }

    @GetMapping("/disponibles")
    public List<Espacio> disponibles(@RequestParam(required = false) String tipo,
                                     @RequestParam(required = false) String zona) {
        return service.disponibles(tipo, zona);
    }

    @GetMapping("/ocupacion")
    public Map<String, Object> ocupacion() {
        return service.ocupacion();
    }

    @GetMapping("/{id}")
    public Espacio obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
    public Espacio crear(@RequestBody Espacio espacio) {
        return service.crear(espacio);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
    public Espacio actualizar(@PathVariable Long id, @RequestBody Espacio espacio) {
        return service.actualizar(id, espacio);
    }

    /** Necesario para abrir ticket (CLIENTE también, vía ms-tickets). */
    @PostMapping("/{id}/ocupar")
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public Espacio ocupar(@PathVariable Long id) {
        return service.ocupar(id);
    }

    /** Cierre de ticket (CLIENTE) o liberación manual (OPERADOR/ADMIN en UI). */
    @PostMapping("/{id}/liberar")
    @PreAuthorize("hasAnyRole('CLIENTE','OPERADOR','ADMIN')")
    public Espacio liberar(@PathVariable Long id) {
        return service.liberar(id);
    }
}
