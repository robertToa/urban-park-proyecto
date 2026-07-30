# Urban Park

Sistema de estacionamiento urbano (tipo Urban Park) con arquitectura de microservicios, según el Proyecto Integrador.

## Arranque

```bash
docker compose up --build
```

Abre: [http://localhost](http://localhost)

Keycloak admin: [http://localhost:8080](http://localhost:8080) → `admin` / `admin`

### Usuarios demo

| Usuario | Password | Roles |
|---------|----------|-------|
| cliente1 | cliente123 | CLIENTE |
| cliente2 | cliente123 | CLIENTE |
| cliente3 | cliente123 | CLIENTE |
| operador1 | operador123 | OPERADOR |
| admin1 | admin123 | ADMIN, OPERADOR |

## Arquitectura

```
Navegador → Nginx (:80)
              ├─ /                 frontend HTML
              ├─ /api/auth/**      ms-auth (:8085) → Keycloak Admin API
              ├─ /api/admin/**     ms-auth (:8085) → usuarios (ADMIN)
              ├─ /api/perfil/**    ms-auth (:8085) → perfil propio
              ├─ /api/espacios/**  ms-espacios (:8081) + PostgreSQL
              ├─ /api/vehiculos/** ms-vehiculos (:8082) + PostgreSQL
              ├─ /api/tickets/**   ms-tickets (:8083) + PostgreSQL
              ├─ /api/ia/**        ms-ia (:8084)
              └─ Keycloak (:8080)  JWT / roles (fuente de verdad de usuarios)
```

### Tabs por rol

| Rol | Pantallas |
|-----|-----------|
| CLIENTE | Plazas · Mis vehículos · Mis tickets · IA · Mi perfil · Sesión JWT |
| OPERADOR | Plazas · Vehículos · Tickets · Mi perfil · Sesión JWT |
| ADMIN | Todo lo anterior + Reporte IA · Resilience · **Usuarios** |

### ms-auth (usuarios / perfiles)

Sin base de datos propia: usa el client `urbanpark-backend` (service account) contra Keycloak Admin API.

| Método | Ruta | Quién |
|--------|------|-------|
| POST | `/api/auth/register` | Público (alta CLIENTE / OPERADOR / ADMIN) |
| GET/POST | `/api/admin/users` | ADMIN |
| PUT | `/api/admin/users/{id}` | ADMIN (datos / enabled) |
| PUT | `/api/admin/users/{id}/roles` | ADMIN |
| PUT | `/api/admin/users/{id}/password` | ADMIN |
| GET/PUT | `/api/perfil/me` | Autenticado |
| PUT | `/api/perfil/me/password` | Autenticado |

Si el realm ya existía antes de añadir roles al service account, recrea Keycloak (`docker compose down` y vuelve a subir) o asígnalos en Admin Console → Clients → `urbanpark-backend` → Service account roles → `realm-management`.

### Mapeo modelo gimnasio → Urban Park

| Gimnasio (referencia) | Urban Park |
|-----------------------|------------|
| Miembros / actividades | Vehículos / plazas |
| Check-in membresía | Ticket entrada/salida |
| Recomendación rutina (IA) | Recomendación de plaza (IA) |
| auth-service + Keycloak | ms-auth + Keycloak |

## Resilience4j

`ms-tickets` llama a `ms-espacios` con **OpenFeign + Circuit Breaker + Retry**.

Demo en UI: pestaña **Resilience** (solo ADMIN).

Para forzar fallback usa los botones **Detener ms-espacios** / **Iniciar ms-espacios** en esa pestaña
(o manualmente: `docker compose stop ms-espacios` / `docker compose start ms-espacios`).

**Pago con ms-espacios caído:** puedes **Cerrar / cobrar** igual; el ticket queda pagado y marca
`liberacionPendiente`. Al **Iniciar ms-espacios** (o con **Sincronizar plazas** en Tickets) se verifica
el pago y se libera la plaza.

## IA (Ollama)

`ms-ia` usa **Ollama** para elegir la plaza disponible y asignarla.

| Endpoint | Qué hace |
|----------|----------|
| `POST /api/ia/recomendar` | Ollama elige plaza (no ocupa) |
| `POST /api/ia/asignar` | Ollama elige plaza **y abre ticket** |
| `GET /api/ia/ollama` | Estado del modelo |

Modelo por defecto: `llama3.2:3b` (el que ya tienes en Ollama).

El compose **reutiliza el Ollama del host** (`localhost:11434`) para evitar conflicto de puerto.
Si aún no tienes el modelo:

```bash
ollama pull llama3.2:3b
# o, si Ollama está en Docker aparte:
docker exec ollama ollama pull llama3.2:3b
```

Luego:

```bash
docker compose up --build
```

Solo si quieres Ollama **dentro** del compose (sin ocupar 11434 en el host):

```bash
docker compose --profile ollama-docker up --build
```
(y cambia `OLLAMA_BASE_URL` a `http://ollama:11434`).

## Ficha

Ver [FICHA.md](./FICHA.md).
