# Urban Park — Ficha descriptiva del proyecto

## Nombre del sistema
**Urban Park** — Plataforma de gestión de estacionamiento urbano tipo Urban Park (zonas, plazas, tickets de entrada/salida y recomendaciones inteligentes).

## Problema que resuelve
Facilita a conductores y operadores la reserva/ocupación de plazas, el control de entrada-salida, el cobro por tiempo y la sugerencia de la mejor zona según ocupación y tipo de vehículo.

## Microservicios (mínimo 3)

| Servicio | Puerto | Responsabilidad | Base de datos |
|----------|--------|-----------------|---------------|
| **ms-espacios** | 8081 | Catálogo de zonas y plazas (CRUD, disponibilidad) | PostgreSQL `db_espacios` |
| **ms-vehiculos** | 8082 | Registro de vehículos y conductores | PostgreSQL `db_vehiculos` |
| **ms-tickets** | 8083 | Tickets de entrada/salida y facturación; consume ms-espacios | PostgreSQL `db_tickets` |
| **ms-ia** | 8084 | Asignación de plaza con **Ollama** | — (stateless) |

## Roles Keycloak
- `CLIENTE` — registrar vehículo, consultar plazas, crear ticket, pedir recomendación IA
- `OPERADOR` — gestionar plazas, cerrar tickets, ver ocupación
- `ADMIN` — administración completa

## Resilience4j
Aplicado en **ms-tickets → ms-espacios** (OpenFeign):
1. **Circuit Breaker** + fallback si espacios no responde
2. **Retry** ante fallos transitorios

Endpoint demostrable: `GET /api/tickets/ocupacion` (usa Feign + CB + Retry).

## Frontend
HTML + CSS + JavaScript vanilla (servido por Nginx).

## IA
**Ollama** (`llama3.2:3b` por defecto) elige la plaza disponible según ocupación, tipo de vehículo y preferencia del conductor.

- `POST /api/ia/recomendar` — solo sugerencia  
- `POST /api/ia/asignar` — asignación real (elige plaza + abre ticket en ms-tickets)  
- Si Ollama no responde, hay fallback heurístico local.
