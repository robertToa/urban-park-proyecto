package com.urbanpark.tickets.repository;

import com.urbanpark.tickets.model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByEstado(String estado);
    Optional<Ticket> findByPlacaAndEstado(String placa, String estado);
    Optional<Ticket> findByEspacioIdAndEstado(Long espacioId, String estado);
    List<Ticket> findByEspacioIdAndEstadoOrderByEntradaAsc(Long espacioId, String estado);
    List<Ticket> findByPlacaIgnoreCase(String placa);
    List<Ticket> findByUsuarioIgnoreCase(String usuario);
    List<Ticket> findByUsuarioIgnoreCaseAndEstado(String usuario, String estado);
    List<Ticket> findByLiberacionPendienteTrue();
    List<Ticket> findByUsuarioIgnoreCaseAndLiberacionPendienteTrue(String usuario);
    long countByLiberacionPendienteTrue();
}
