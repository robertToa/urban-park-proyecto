const KEYCLOAK_TOKEN =
  "/realms/urbanpark/protocol/openid-connect/token";
const KEYCLOAK_TOKEN_FALLBACK =
  "http://localhost:8080/realms/urbanpark/protocol/openid-connect/token";
const CLIENT_ID = "urbanpark-web";
const STORAGE_KEY = "urbanpark.session";
const API_BASE = "";

const state = {
  accessToken: null,
  refreshToken: null,
  expiresAt: 0,
  profile: null,
  plazasCache: [],
  ticketsCache: [],
  vehiculosCache: [],
  filtroTickets: "TODOS",
  pendingConfirm: null,
  resilienceLog: [],
  resilienceOk: 0,
  resilienceFail: 0,
  usuariosCache: [],
};

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => [...document.querySelectorAll(sel)];

function saveSession() {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      accessToken: state.accessToken,
      refreshToken: state.refreshToken,
      expiresAt: state.expiresAt,
    })
  );
}

function loadSession() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return false;
    const data = JSON.parse(raw);
    state.accessToken = data.accessToken;
    state.refreshToken = data.refreshToken;
    state.expiresAt = data.expiresAt;
    state.profile = parseJwt(state.accessToken);
    return !!state.accessToken;
  } catch {
    return false;
  }
}

function clearSession() {
  state.accessToken = null;
  state.refreshToken = null;
  state.expiresAt = 0;
  state.profile = null;
  localStorage.removeItem(STORAGE_KEY);
}

function parseJwt(token) {
  if (!token) return null;
  const payload = token.split(".")[1];
  const b64 = payload.replace(/-/g, "+").replace(/_/g, "/");
  const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
  const json = new TextDecoder().decode(
    Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))
  );
  return JSON.parse(json);
}

function rolesFrom(profile) {
  return profile?.realm_access?.roles || [];
}

function hasAnyRole(...roles) {
  const mine = rolesFrom(state.profile);
  return roles.some((r) => mine.includes(r));
}

/** Rol efectivo: ADMIN > OPERADOR > CLIENTE (evita que operador vea pantallas de cliente/admin). */
function rolPrincipal() {
  const r = rolesFrom(state.profile);
  if (r.includes("ADMIN")) return "ADMIN";
  if (r.includes("OPERADOR")) return "OPERADOR";
  if (r.includes("CLIENTE")) return "CLIENTE";
  return null;
}

/** Pantallas permitidas por perfil. */
const TABS_POR_ROL = {
  CLIENTE: ["plazas", "vehiculos", "tickets", "ia", "perfil", "token"],
  OPERADOR: ["plazas", "vehiculos", "tickets", "perfil", "token"],
  ADMIN: ["plazas", "vehiculos", "tickets", "ia", "reporte", "resilience", "usuarios", "perfil", "token"],
};

const TAB_IDS = [
  "plazas",
  "vehiculos",
  "tickets",
  "ia",
  "reporte",
  "resilience",
  "usuarios",
  "perfil",
  "token",
];

function puedeVerTab(tabId) {
  const rol = rolPrincipal();
  return !!(rol && (TABS_POR_ROL[rol] || []).includes(tabId));
}

/** Liberar plaza / gestión operativa: OPERADOR o ADMIN. */
function esPersonal() {
  return ["OPERADOR", "ADMIN"].includes(rolPrincipal());
}

function aplicarPermisosUi() {
  const rol = rolPrincipal();
  const permitidas = TABS_POR_ROL[rol] || [];
  const esCliente = rol === "CLIENTE";

  $$(".tab").forEach((tab) => {
    const id = tab.dataset.tab;
    const ok = permitidas.includes(id);
    tab.hidden = !ok;
    if (!ok && tab.classList.contains("active")) {
      const primera = permitidas[0] || "plazas";
      irATab(primera);
    }
  });

  TAB_IDS.forEach((name) => {
    const panel = $("#tab-" + name);
    if (!panel) return;
    if (!permitidas.includes(name)) {
      panel.hidden = true;
    }
  });

  $$("[data-roles]").forEach((el) => {
    if (el.classList.contains("tab")) return;
    const needed = (el.dataset.roles || "")
      .split(",")
      .map((r) => r.trim())
      .filter(Boolean);
    el.hidden = needed.length > 0 && !needed.includes(rol);
  });

  const tTitle = $("#tickets-title");
  const tHint = $("#tickets-scope-hint");
  if (tTitle) {
    tTitle.textContent = esCliente ? "Mis tickets" : "Tickets entrada / salida";
  }
  if (tHint) {
    if (esCliente) {
      tHint.hidden = false;
      tHint.textContent =
        "Perfil CLIENTE: ves tu resumen (usados, pagado y por pagar) y solo tus propios tickets/placas.";
    } else if (rol === "OPERADOR") {
      tHint.hidden = false;
      tHint.textContent = "Perfil OPERADOR: gestionas todos los tickets del parqueadero.";
    } else {
      tHint.hidden = true;
    }
  }

  const vTitle = $("#vehiculos-title");
  const vHint = $("#vehiculos-scope-hint");
  if (vTitle) {
    vTitle.textContent = esCliente ? "Mis vehículos" : "Vehículos";
  }
  if (vHint) {
    if (esCliente) {
      vHint.hidden = false;
      vHint.textContent = "Solo se listan los vehículos registrados a tu usuario.";
    } else {
      vHint.hidden = true;
    }
  }
}

async function login(username, password) {
  const body = new URLSearchParams({
    grant_type: "password",
    client_id: CLIENT_ID,
    username,
    password,
  });
  const urls = [KEYCLOAK_TOKEN, KEYCLOAK_TOKEN_FALLBACK];
  let res = null;
  let lastErr = null;
  for (const url of urls) {
    try {
      res = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body,
      });
      if (res) break;
    } catch (e) {
      lastErr = e;
    }
  }
  if (!res) {
    throw new Error(
      "No se pudo conectar con Keycloak. Usa http://localhost — " +
        (lastErr && lastErr.message ? lastErr.message : "Failed to fetch")
    );
  }
  if (!res.ok) {
    const err = await res.text();
    throw new Error("Login fallido (" + res.status + "): " + err);
  }
  const data = await res.json();
  state.accessToken = data.access_token;
  state.refreshToken = data.refresh_token;
  state.expiresAt = Date.now() + data.expires_in * 1000;
  state.profile = parseJwt(data.access_token);
  saveSession();
}

async function refreshIfNeeded() {
  if (!state.accessToken) throw new Error("Sin sesión");
  if (Date.now() < state.expiresAt - 15000) return;
  if (!state.refreshToken) throw new Error("Sesión expirada");
  const body = new URLSearchParams({
    grant_type: "refresh_token",
    client_id: CLIENT_ID,
    refresh_token: state.refreshToken,
  });
  const res = await fetch(KEYCLOAK_TOKEN, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!res.ok) {
    clearSession();
    showLogin();
    throw new Error("Token expirado. Vuelve a iniciar sesión.");
  }
  const data = await res.json();
  state.accessToken = data.access_token;
  state.refreshToken = data.refresh_token;
  state.expiresAt = Date.now() + data.expires_in * 1000;
  state.profile = parseJwt(data.access_token);
  saveSession();
  renderTokenPanel();
}

async function api(path, options = {}) {
  await refreshIfNeeded();
  const url = path.startsWith("http") ? path : API_BASE + path;
  const res = await fetch(url, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + state.accessToken,
      ...(options.headers || {}),
    },
  });
  if (res.status === 401 || res.status === 403) {
    const raw = await res.text();
    clearSession();
    showLogin();
    throw new Error(mensajeAmigable(res.status, raw, path));
  }
  if (!res.ok) {
    const raw = await res.text();
    throw new Error(mensajeAmigable(res.status, raw, path));
  }
  if (res.status === 204) return null;
  return res.json();
}

/** Convierte errores técnicos HTTP/JSON en texto claro para el usuario. */
function mensajeAmigable(status, raw, path) {
  let msg = "";
  try {
    const j = JSON.parse(raw);
    msg = j.message || j.detail || j.error || "";
  } catch {
    msg = (raw || "").trim();
  }
  if (msg && !msg.startsWith("{") && msg.length < 400) {
    return msg;
  }

  const ruta = path || "";
  if (status === 409) {
    if (ruta.includes("/ia/asignar") || ruta.includes("/tickets")) {
      return "No se pudo asignar: esa placa ya tiene un ticket abierto, o la plaza está ocupada. Ve a Tickets → Cerrar / cobrar e intenta de nuevo.";
    }
    return "Conflicto: el recurso ya está en uso. Revisa tickets abiertos o plazas ocupadas.";
  }
  if (status === 404) {
    return "No se encontró lo solicitado (plaza, ticket o vehículo). Revisa los datos e intenta de nuevo.";
  }
  if (status === 400) {
    return msg || "Datos incompletos o inválidos. Revisa el formulario.";
  }
  if (status === 502 || status === 503) {
    if (ruta.includes("/ia/")) {
      return "No se pudo completar la asignación con IA. Puede que la placa ya tenga un ticket abierto o que un servicio no responda. Cierra tickets abiertos en la pestaña Tickets e intenta otra vez.";
    }
    return "Un servicio no está disponible temporalmente. Espera unos segundos e intenta de nuevo.";
  }
  if (status === 401) {
    return "Tu sesión expiró. Vuelve a iniciar sesión.";
  }
  if (status === 403) {
    return "No tienes permiso con tu rol actual. Revisa el perfil: cliente / operador / admin tienen pantallas distintas.";
  }
  return msg || ("Error " + status + ". Intenta de nuevo.");
}

function toast(mensaje, tipo = "info") {
  const root = $("#toast-root");
  if (!root) {
    alert(mensaje);
    return;
  }
  const el = document.createElement("div");
  el.className = "toast " + tipo;
  el.textContent = mensaje;
  root.appendChild(el);
  setTimeout(() => {
    el.style.opacity = "0";
    el.style.transition = "opacity 0.3s";
    setTimeout(() => el.remove(), 300);
  }, 3200);
}

function confirmar(titulo, mensaje) {
  return new Promise((resolve) => {
    const modal = $("#modal-confirm");
    $("#modal-title").textContent = titulo;
    $("#modal-msg").textContent = mensaje;
    modal.hidden = false;
    state.pendingConfirm = resolve;
  });
}

function cerrarModal(ok) {
  const modal = $("#modal-confirm");
  modal.hidden = true;
  if (typeof state.pendingConfirm === "function") {
    state.pendingConfirm(ok);
    state.pendingConfirm = null;
  }
}

function irATab(id) {
  $$(".tab").forEach((t) => t.classList.toggle("active", t.dataset.tab === id));
  TAB_IDS.forEach((name) => {
    const el = $("#tab-" + name);
    if (el) el.hidden = name !== id;
  });
  if (id === "usuarios" && puedeVerTab("usuarios")) {
    loadUsuarios().catch((e) => toast(e.message, "err"));
  }
  if (id === "perfil" && puedeVerTab("perfil")) {
    loadPerfil().catch((e) => toast(e.message, "err"));
  }
  if (id === "resilience" && puedeVerTab("resilience")) {
    refreshEspaciosDemoStatus().catch(() => {});
  }
}

function llenarSelectPlazas(plazas, selectedCodigo) {
  const sel = $("#select-plaza");
  if (!sel) return;
  const libres = (plazas || []).filter((p) => !p.ocupado);
  const prev = selectedCodigo || sel.value;
  sel.innerHTML =
    `<option value="">Elige plaza libre…</option>` +
    libres
      .map(
        (p) =>
          `<option value="${p.codigo}" ${String(p.codigo).toUpperCase() === String(prev).toUpperCase() ? "selected" : ""}>` +
          `${p.codigo} · ${normalizarTipo(p.tipoVehiculo)} · ${p.zona} · $${p.tarifaHora}/h` +
          `</option>`
      )
      .join("");
  if (!libres.length) {
    sel.innerHTML = `<option value="">No hay plazas libres</option>`;
  }
}

function llenarPlacas(vehiculos) {
  const dl = $("#lista-placas");
  const input = $("#input-placa-ticket");
  if (!dl || !input) return;
  const list = vehiculos || [];
  dl.innerHTML = list.map((v) => `<option value="${v.placa}"></option>`).join("");

  // CLIENTE: solo puede elegir sus placas (select), no escribir placas ajenas
  if (rolPrincipal() === "CLIENTE") {
    const prev = input.value;
    const sel = document.createElement("select");
    sel.name = "placa";
    sel.id = "input-placa-ticket";
    sel.required = true;
    sel.innerHTML =
      `<option value="">Elige tu placa…</option>` +
      list
        .map(
          (v) =>
            `<option value="${v.placa}" ${String(v.placa).toUpperCase() === String(prev).toUpperCase() ? "selected" : ""}>${v.placa} · ${v.tipo}</option>`
        )
        .join("");
    if (!list.length) {
      sel.innerHTML = `<option value="">Registra primero un vehículo</option>`;
    }
    input.replaceWith(sel);
  } else if (input.tagName === "SELECT") {
    const text = document.createElement("input");
    text.name = "placa";
    text.id = "input-placa-ticket";
    text.setAttribute("list", "lista-placas");
    text.placeholder = "Placa";
    text.required = true;
    text.autocomplete = "off";
    input.replaceWith(text);
  }
}

function mostrarOkIa(html) {
  const box = $("#ia-result");
  box.hidden = false;
  box.classList.remove("ia-error");
  box.innerHTML = html;
}

function mostrarErrorIa(texto) {
  const box = $("#ia-result");
  box.hidden = false;
  box.classList.add("ia-error");
  box.innerHTML = `
    <h3>No se pudo asignar</h3>
    <p>${texto}</p>
    <p><small>Consejo: en Tickets cierra el ticket abierto de esa placa, o usa otra placa.</small></p>
  `;
}

function showLogin() {
  const login = $("#view-login");
  const app = $("#view-app");
  login.hidden = false;
  app.hidden = true;
  login.removeAttribute("hidden");
  app.setAttribute("hidden", "");
}

function showApp() {
  const login = $("#view-login");
  const app = $("#view-app");
  login.hidden = true;
  app.hidden = false;
  login.setAttribute("hidden", "");
  app.removeAttribute("hidden");
  $("#session-user").textContent =
    (state.profile?.preferred_username || "?") +
    " · " +
    (rolPrincipal() || rolesFrom(state.profile).filter((r) => ["CLIENTE", "OPERADOR", "ADMIN"].includes(r)).join(", "));
  aplicarPermisosUi();
  renderTokenPanel();
  (async () => {
    try {
      const prev = [loadTickets()];
      if (puedeVerTab("vehiculos")) prev.push(loadVehiculos());
      await Promise.all(prev);
      await loadPlazas();
      const mio = miTicketAbierto();
      if (mio) {
        toast(
          `Estás estacionado en la plaza ${mio.espacioCodigo || mio.espacioId}`,
          "info"
        );
      }
    } catch (e) {
      console.error(e);
    }
  })();
}

function renderTokenPanel() {
  const p = state.profile || {};
  const exp = p.exp ? new Date(p.exp * 1000).toLocaleString() : "-";
  $("#token-meta").innerHTML = `
    <div><strong>Usuario:</strong> ${p.preferred_username || "-"}</div>
    <div><strong>Roles:</strong> ${rolesFrom(p).join(", ") || "-"}</div>
    <div><strong>Expira:</strong> ${exp}</div>
    <div><strong>Issuer:</strong> ${p.iss || "-"}</div>
  `;
  $("#token-raw").textContent = state.accessToken || "";
}

async function loadPlazas() {
  const [plazas, ocup] = await Promise.all([
    api("/api/espacios"),
    api("/api/espacios/ocupacion"),
  ]);
  state.plazasCache = plazas;
  $("#ocupacion-box").innerHTML = `
    <div class="stat"><span>Total</span><strong>${ocup.total}</strong></div>
    <div class="stat"><span>Ocupadas</span><strong>${ocup.ocupados}</strong></div>
    <div class="stat"><span>Libres</span><strong>${ocup.libres}</strong></div>
    <div class="stat"><span>% ocupación</span><strong>${ocup.porcentajeGlobal}%</strong></div>
  `;

  informarPlazaCliente();

  const mio = miTicketAbierto();
  const miPlazaCodigo = String(mio?.espacioCodigo || "").toUpperCase();
  const miPlazaId = mio?.espacioId != null ? String(mio.espacioId) : "";

  $("#plazas-grid").innerHTML = plazas
    .map((p) => {
      const ticket = ticketAbiertoDePlaza(p);
      const esMia =
        !!mio &&
        (String(p.codigo || "").toUpperCase() === miPlazaCodigo ||
          (miPlazaId && String(p.id) === miPlazaId));
      const ocupadaInfo = esMia
        ? `<div class="plaza-ocupante plaza-mia-msg">
             <strong>Tu plaza ${p.codigo}</strong>
             · placa ${mio.placa}
             · ticket ${mio.codigo || ""}
           </div>`
        : ticket
          ? `<div class="plaza-ocupante">
             <strong>Plaza ${ticket.espacioCodigo || p.codigo}</strong>
             · placa ${ticket.placa}
             ${ticket.usuario ? ` · cliente ${ticket.usuario}` : ""}
           </div>`
          : p.ocupado
            ? `<div class="plaza-ocupante"><strong>Plaza ${p.codigo}</strong> · ocupada</div>`
            : "";
      return `
    <article class="plaza ${p.ocupado ? "ocupada" : "libre"}${esMia ? " plaza-mia" : ""}"${
      esMia ? ` id="plaza-mia"` : ""
    }>
      <span class="badge">${esMia ? "TU PLAZA" : p.ocupado ? "OCUPADA" : "LIBRE"}</span>
      <div class="code">${p.codigo}</div>
      <div class="meta">
        <div class="plaza-zona">ID ${p.id} · ${p.zona}</div>
        <div class="plaza-tipo-row">
          ${iconoTipoVehiculo(p.tipoVehiculo)}
          <span class="plaza-tarifa">$${p.tarifaHora}/h · N${p.nivel ?? "-"}</span>
        </div>
        ${ocupadaInfo}
      </div>
      <div class="plaza-actions">
      ${
        esMia
          ? `<p class="hint plaza-hint plaza-mia-hint">Aquí estás estacionado · cierra el ticket al salir</p>`
          : !p.ocupado
            ? `<button type="button" class="btn primary btn-usar-plaza" data-codigo="${p.codigo}">Usar esta plaza</button>`
            : esPersonal()
              ? `<button type="button" class="btn secondary btn-liberar-plaza" data-id="${p.id}" data-codigo="${p.codigo}">Liberar plaza</button>`
              : `<p class="hint plaza-hint">Ocupada · plaza <strong>${p.codigo}</strong>${
                  ticket ? ` · ${ticket.placa}` : ""
                }</p>`
      }
      </div>
    </article>`;
    })
    .join("");

  const cardMia = $("#plaza-mia");
  if (cardMia) {
    requestAnimationFrame(() => {
      cardMia.scrollIntoView({ behavior: "smooth", block: "nearest" });
    });
  }

  llenarSelectPlazas(plazas);

  $$(".btn-usar-plaza").forEach((btn) => {
    btn.addEventListener("click", () => {
      irATab("tickets");
      const sel = $("#select-plaza");
      if (sel) sel.value = btn.dataset.codigo;
      toast("Plaza " + btn.dataset.codigo + " lista para abrir ticket", "info");
      $("#form-ticket [name=placa]")?.focus();
    });
  });

  $$(".btn-liberar-plaza").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const ok = await confirmar(
        "Liberar plaza",
        `¿Marcar ${btn.dataset.codigo} como libre? Úsalo si no hay ticket abierto (plaza atascada).`
      );
      if (!ok) return;
      try {
        await api("/api/espacios/" + btn.dataset.id + "/liberar", { method: "POST" });
        toast("Plaza " + btn.dataset.codigo + " liberada", "ok");
        await loadPlazas();
      } catch (e) {
        toast(e.message, "err");
      }
    });
  });
  if ((state.ticketsCache || []).length) renderTickets();
}

function ticketAbiertoDePlaza(p) {
  if (!p) return null;
  return (state.ticketsCache || []).find(
    (t) =>
      t.estado === "ABIERTO" &&
      (String(t.espacioId) === String(p.id) ||
        String(t.espacioCodigo || "").toUpperCase() === String(p.codigo || "").toUpperCase())
  );
}

function miTicketAbierto() {
  const user = String(state.profile?.preferred_username || "").toLowerCase();
  if (!user) return null;
  return (state.ticketsCache || []).find(
    (t) => t.estado === "ABIERTO" && String(t.usuario || "").toLowerCase() === user
  );
}

/** Avisa el número de estacionamiento si el cliente tiene plaza ocupada. */
function informarPlazaCliente() {
  const box = $("#plaza-cliente-info");
  const mark = document.querySelector(".stall-mark");
  if (!box) return;

  const mio = miTicketAbierto();
  if (mio) {
    const num = mio.espacioCodigo || mio.espacioId || "?";
    box.hidden = false;
    box.innerHTML = `
      <strong>Estás estacionado en la plaza ${num}</strong>
      <span>Placa ${mio.placa} · ticket ${mio.codigo || ""} · cierra el ticket al salir</span>`;
    if (mark) mark.textContent = String(num);
    return;
  }

  // Personal: resumen de plazas ocupadas con número
  if (esPersonal()) {
    const ocupadas = (state.ticketsCache || []).filter((t) => t.estado === "ABIERTO");
    if (ocupadas.length) {
      const lista = ocupadas
        .map((t) => `${t.espacioCodigo || t.espacioId} (${t.placa})`)
        .join(" · ");
      box.hidden = false;
      box.innerHTML = `
        <strong>${ocupadas.length} plaza(s) ocupada(s) por clientes</strong>
        <span>${lista}</span>`;
      if (mark) mark.textContent = ocupadas[0].espacioCodigo || "A-01";
      return;
    }
  }

  box.hidden = true;
  box.innerHTML = "";
  if (mark) mark.textContent = "A-01";
}

/** Acepta ID numérico (5) o código (B-02). */
async function resolverEspacioId(plazaRef) {
  const ref = String(plazaRef || "").trim().toUpperCase();
  if (!ref) throw new Error("Indica la plaza (ej. B-02)");
  if (/^\d+$/.test(ref)) return Number(ref);

  let plazas = state.plazasCache;
  if (!plazas || !plazas.length) {
    plazas = await api("/api/espacios");
    state.plazasCache = plazas;
  }
  const found = plazas.find((p) => String(p.codigo).toUpperCase() === ref);
  if (!found) {
    throw new Error("No existe la plaza '" + ref + "'. Revisa el código en Plazas (ej. A-01, B-02).");
  }
  if (found.ocupado) {
    throw new Error("La plaza " + ref + " está OCUPADA. Elige una LIBRE.");
  }
  return found.id;
}

async function loadVehiculos() {
  const list = await api("/api/vehiculos");
  state.vehiculosCache = list;
  llenarPlacas(list);
  $("#vehiculos-list").innerHTML = list.length
    ? list
        .map(
          (v) => `
    <div class="row">
      <div>
        <strong>${v.placa}</strong> · ${iconoTipoVehiculo(v.tipo)} · ${v.marca || "-"} ${v.color || ""}
        <br/><small>${v.propietario}</small>
      </div>
      <div class="actions">
        <button type="button" class="btn secondary btn-usar-placa" data-placa="${v.placa}">Usar placa</button>
      </div>
    </div>`
        )
        .join("")
    : `<div class="empty-state">No hay vehículos registrados.</div>`;

  $$(".btn-usar-placa").forEach((btn) => {
    btn.addEventListener("click", () => {
      irATab("tickets");
      const input = $("#form-ticket [name=placa]");
      if (input) input.value = btn.dataset.placa;
      toast("Placa " + btn.dataset.placa + " cargada", "info");
    });
  });
  if ((state.ticketsCache || []).length) renderTickets();
}

function formatearFecha(iso) {
  if (!iso) return "-";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  return d.toLocaleString("es-EC", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

/** Iconos SVG por tipo de vehículo / plaza. */
function normalizarTipo(tipo) {
  const t = String(tipo || "").trim().toUpperCase();
  if (t.includes("DISCAP") || t === "PMR" || t === "ACCESIBLE") return "DISCAPACIDAD";
  if (t.includes("MOTO")) return "MOTO";
  if (t.includes("CAMION") || t.includes("SUV") || t.includes("PICK")) return "CAMIONETA";
  if (t.includes("AUTO") || t.includes("CAR") || t === "VEHICULO") return "AUTO";
  return t || "AUTO";
}

function iconoTipoVehiculo(tipo, opts = {}) {
  const kind = normalizarTipo(tipo);
  const cls = "tipo-icon tipo-" + kind.toLowerCase() + (opts.sm ? " sm" : "");
  const label = kind === "DISCAPACIDAD" ? "Discapacidad" : kind;
  const svgs = {
    AUTO: `<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M5 11l1.5-4.5A2 2 0 0 1 8.4 5h7.2a2 2 0 0 1 1.9 1.5L19 11h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1.1a2.5 2.5 0 0 1-4.8 0H9.9a2.5 2.5 0 0 1-4.8 0H4a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1zm2.5 5a1 1 0 1 0 0-2 1 1 0 0 0 0 2zm9 0a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM8.4 7l-1 3h9.2l-1-3H8.4z"/></svg>`,
    MOTO: `<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M19 13a3 3 0 1 1-2.8 4H14l-1.5-3H9.8A3 3 0 1 1 5 13c1.2 0 2.2.7 2.7 1.7L9 13h3.2l1.2-2.4L11 9h2.5l2.2 2.2.8-.7V8h2v3.5l-1.2 1.1A3 3 0 0 1 19 13zM5 15a1 1 0 1 0 0 2 1 1 0 0 0 0-2zm14 0a1 1 0 1 0 0 2 1 1 0 0 0 0-2z"/></svg>`,
    CAMIONETA: `<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M3 10h9V6h2l4 4h3v5h-1.1a2.5 2.5 0 0 1-4.8 0H8.9a2.5 2.5 0 0 1-4.8 0H3v-5zm2 6.5a1 1 0 1 0 0-2 1 1 0 0 0 0 2zm12 0a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM14 8.5V10h2.5L14 8.5z"/></svg>`,
    DISCAPACIDAD: `<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 2a2.2 2.2 0 1 1 0 4.4A2.2 2.2 0 0 1 12 2zm-1.2 5.5h2.4v4.2l3.2 1.6-.9 1.8-3.5-1.7H9.5A3.5 3.5 0 1 0 13 17.2v-2.1a1.5 1.5 0 1 1-1.5-1.5h.8V7.5z"/></svg>`,
  };
  const svg = svgs[kind] || svgs.AUTO;
  return `<span class="${cls}" title="${label}">${svg}<span class="tipo-label">${label}</span></span>`;
}

function tipoDeTicket(t) {
  const veh = (state.vehiculosCache || []).find(
    (v) => String(v.placa).toUpperCase() === String(t.placa || "").toUpperCase()
  );
  if (veh?.tipo) return veh.tipo;
  const plaza = (state.plazasCache || []).find(
    (p) =>
      String(p.codigo).toUpperCase() === String(t.espacioCodigo || "").toUpperCase() ||
      String(p.id) === String(t.espacioId)
  );
  return plaza?.tipoVehiculo || "AUTO";
}

/** Misma lógica que el backend: mínimo 1 min, horas redondeadas hacia arriba. */
function calcularMonto(entradaIso, salidaIso, tarifaHora) {
  const tarifa = Number(tarifaHora) || 0;
  const ini = new Date(entradaIso);
  const fin = salidaIso ? new Date(salidaIso) : new Date();
  if (Number.isNaN(ini.getTime()) || Number.isNaN(fin.getTime())) return 0;
  const minutos = Math.max(1, Math.floor((fin - ini) / 60000));
  const horas = Math.ceil(minutos / 60);
  return Math.round(horas * tarifa * 100) / 100;
}

function detalleTicket(t) {
  const ingreso = formatearFecha(t.entrada);
  if (t.estado === "CERRADO") {
    const salida = formatearFecha(t.salida);
    const cobrado =
      t.montoTotal != null ? Number(t.montoTotal) : calcularMonto(t.entrada, t.salida, t.tarifaHora);
    return `
      <small>
        <strong>Ingreso:</strong> ${ingreso}<br/>
        <strong>Salida:</strong> ${salida}<br/>
        <strong>Valor cobrado:</strong> $${cobrado.toFixed(2)}
        <span class="muted"> ($${Number(t.tarifaHora || 0).toFixed(2)}/h)</span>
      </small>`;
  }
  const porCobrar = calcularMonto(t.entrada, null, t.tarifaHora);
  return `
    <small>
      <strong>Ingreso:</strong> ${ingreso}<br/>
      <strong>Valor por cobrar (ahora):</strong> $${porCobrar.toFixed(2)}
      <span class="muted"> · se actualiza al cerrar · $${Number(t.tarifaHora || 0).toFixed(2)}/h</span>
    </small>`;
}

async function loadTickets() {
  const list = await api("/api/tickets");
  state.ticketsCache = list;
  renderTickets();
  informarPlazaCliente();
}

function renderTicketsResumen(list) {
  const box = $("#tickets-resumen");
  if (!box) return;

  const tickets = list || [];
  const total = tickets.length;
  const abiertos = tickets.filter((t) => t.estado === "ABIERTO");
  const cerrados = tickets.filter((t) => t.estado === "CERRADO");
  const pagado = cerrados.reduce((sum, t) => {
    const m =
      t.montoTotal != null
        ? Number(t.montoTotal)
        : calcularMonto(t.entrada, t.salida, t.tarifaHora);
    return sum + (Number.isFinite(m) ? m : 0);
  }, 0);
  const porPagar = abiertos.reduce(
    (sum, t) => sum + calcularMonto(t.entrada, null, t.tarifaHora),
    0
  );

  const esCliente = rolPrincipal() === "CLIENTE";
  const titulo = esCliente ? "Tu resumen de consumo" : "Resumen de tickets";
  const sub = esCliente
    ? "Solo tus tickets: usados, pagados y pendientes."
    : "Totales según los tickets visibles en esta sesión.";

  box.hidden = false;
  box.innerHTML = `
    <div class="tickets-resumen-head">
      <h3>${titulo}</h3>
      <p>${sub}</p>
    </div>
    <div class="tickets-resumen-grid">
      <div class="tr-card">
        <span>Tickets usados</span>
        <strong>${total}</strong>
        <small>${cerrados.length} cerrados · ${abiertos.length} abiertos</small>
      </div>
      <div class="tr-card tr-pagado">
        <span>He pagado</span>
        <strong>$${pagado.toFixed(2)}</strong>
        <small>Suma de tickets cerrados</small>
      </div>
      <div class="tr-card tr-pendiente">
        <span>Me falta por pagar</span>
        <strong>$${porPagar.toFixed(2)}</strong>
        <small>${abiertos.length ? "Estimado de tickets abiertos" : "Sin cargos pendientes"}</small>
      </div>
    </div>`;
}

function renderTickets() {
  const list = state.ticketsCache || [];
  renderTicketsResumen(list);

  const filtro = state.filtroTickets || "TODOS";
  let filtrados = list;
  if (filtro === "ABIERTO" || filtro === "CERRADO") {
    filtrados = list.filter((t) => t.estado === filtro);
  }
  filtrados = [...filtrados].sort((a, b) => {
    if (a.estado !== b.estado) return a.estado === "ABIERTO" ? -1 : 1;
    return String(b.entrada || "").localeCompare(String(a.entrada || ""));
  });

  if (!filtrados.length) {
    const msg =
      rolPrincipal() === "CLIENTE"
        ? "No tienes tickets propios para mostrar. Abre uno con tu placa."
        : `No hay tickets ${filtro === "TODOS" ? "" : filtro.toLowerCase() + "s "}para mostrar.`;
    $("#tickets-list").innerHTML = `<div class="empty-state">${msg}</div>`;
    return;
  }

  const pendientesSync = filtrados.filter((t) => t.liberacionPendiente).length;
  const syncHint = $("#tickets-sync-hint");
  if (syncHint) {
    if (pendientesSync > 0) {
      syncHint.hidden = false;
      syncHint.textContent =
        pendientesSync +
        " pago(s) cobrado(s) con plaza aún por liberar (ms-espacios estaba caído). Pulsa «Sincronizar plazas» cuando el servicio esté UP.";
    } else {
      syncHint.hidden = true;
    }
  }

  $("#tickets-list").innerHTML = filtrados
    .map(
      (t) => `
    <div class="row ticket-${(t.estado || "").toLowerCase()}">
      <div>
        <strong>${t.codigo}</strong> · ${t.placa} · plaza ${t.espacioCodigo || t.espacioId}
        ${iconoTipoVehiculo(tipoDeTicket(t), { sm: true })}
        <span class="badge-estado">${t.estado}</span>
        ${
          t.liberacionPendiente
            ? `<span class="badge-estado badge-warn">Plaza pendiente sync</span>`
            : ""
        }
        <br/>
        ${detalleTicket(t)}
      </div>
      <div class="actions">
        ${
          t.estado === "ABIERTO"
            ? `<button type="button" class="btn ok" data-cerrar="${t.id}">Cerrar / cobrar</button>`
            : ""
        }
      </div>
    </div>`
    )
    .join("");

  $$("[data-cerrar]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const ticket = state.ticketsCache.find((x) => String(x.id) === String(btn.dataset.cerrar));
      const estimado = ticket
        ? calcularMonto(ticket.entrada, null, ticket.tarifaHora).toFixed(2)
        : "?";
      const ok = await confirmar(
        "Cerrar y cobrar",
        `¿Cerrar el ticket ${ticket?.codigo || ""} de ${ticket?.placa || ""}? Valor estimado a cobrar: $${estimado}`
      );
      if (!ok) {
        toast("Cobro cancelado", "info");
        return;
      }
      try {
        const cerrado = await api("/api/tickets/" + btn.dataset.cerrar + "/cerrar", {
          method: "POST",
        });
        if (cerrado.liberacionPendiente) {
          toast(
            `Cobrado $${Number(cerrado.montoTotal || estimado).toFixed(2)}. Plaza quedará libre al sincronizar (ms-espacios estaba caído).`,
            "info"
          );
        } else {
          toast(
            `Ticket cerrado. Cobrado: $${Number(cerrado.montoTotal || estimado).toFixed(2)}`,
            "ok"
          );
        }
        await loadTickets();
        await loadPlazas().catch(() => {});
      } catch (e) {
        toast(e.message, "err");
      }
    });
  });
}

async function sincronizarLiberacionesPendientes(mostrarToast = true) {
  try {
    const r = await api("/api/tickets/sincronizar-liberaciones", { method: "POST" });
    if (mostrarToast) {
      toast(r.mensaje || "Sincronización lista", r.liberadas > 0 ? "ok" : "info");
    }
    await loadTickets().catch(() => {});
    await loadPlazas().catch(() => {});
    return r;
  } catch (e) {
    if (mostrarToast) toast(e.message, "err");
    throw e;
  }
}

// Events
$("#btn-perfiles").addEventListener("click", () => {
  const panel = $("#panel-perfiles");
  const open = panel.hidden;
  panel.hidden = !open;
  $("#btn-perfiles").textContent = open
    ? "Ocultar perfiles y permisos"
    : "Ver perfiles y permisos";
});

$$(".btn-usar").forEach((btn) => {
  btn.addEventListener("click", () => {
    $("#username").value = btn.dataset.user;
    $("#password").value = btn.dataset.pass;
    $("#login-error").hidden = true;
  });
});

$("#login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const err = $("#login-error");
  err.hidden = true;
  try {
    await login($("#username").value.trim(), $("#password").value);
    showApp();
  } catch (ex) {
    err.textContent = ex.message;
    err.hidden = false;
  }
});

$("#btn-logout").addEventListener("click", () => {
  clearSession();
  showLogin();
});

function mostrarRegistro(show) {
  $("#login-form").hidden = !!show;
  $("#register-form").hidden = !show;
  $("#login-error").hidden = true;
  $("#register-error").hidden = true;
  $("#register-ok").hidden = true;
}

$("#btn-mostrar-registro")?.addEventListener("click", () => mostrarRegistro(true));
$("#btn-cancelar-registro")?.addEventListener("click", () => mostrarRegistro(false));

$("#register-form")?.addEventListener("submit", async (e) => {
  e.preventDefault();
  const err = $("#register-error");
  const ok = $("#register-ok");
  err.hidden = true;
  ok.hidden = true;
  const fd = new FormData(e.target);
  try {
    const res = await fetch("/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: String(fd.get("username") || "").trim(),
        email: String(fd.get("email") || "").trim(),
        firstName: String(fd.get("firstName") || "").trim(),
        lastName: String(fd.get("lastName") || "").trim(),
        password: fd.get("password"),
        role: fd.get("role") || "CLIENTE",
      }),
    });
    if (!res.ok) {
      const raw = await res.text();
      throw new Error(mensajeAmigable(res.status, raw, "/api/auth/register"));
    }
    const data = await res.json().catch(() => ({}));
    ok.textContent =
      "Cuenta creada como " + (data.role || fd.get("role") || "CLIENTE") + ". Ya puedes iniciar sesión.";
    ok.hidden = false;
    $("#username").value = String(fd.get("username") || "").trim();
    $("#password").value = "";
    e.target.reset();
  } catch (ex) {
    err.textContent = ex.message;
    err.hidden = false;
  }
});

$("#btn-refresh-usuarios")?.addEventListener("click", () =>
  loadUsuarios().catch((e) => toast(e.message, "err"))
);

$("#form-usuario-crear")?.addEventListener("submit", async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  try {
    await api("/api/admin/users", {
      method: "POST",
      body: JSON.stringify({
        username: String(fd.get("username") || "").trim(),
        email: String(fd.get("email") || "").trim(),
        firstName: String(fd.get("firstName") || "").trim(),
        lastName: String(fd.get("lastName") || "").trim(),
        password: fd.get("password"),
        role: fd.get("role"),
      }),
    });
    toast("Usuario creado", "ok");
    e.target.reset();
    await loadUsuarios();
  } catch (ex) {
    toast(ex.message, "err");
  }
});

$("#modal-user-cancel")?.addEventListener("click", cerrarModalUser);
$("#modal-user")?.addEventListener("click", (e) => {
  if (e.target.id === "modal-user") cerrarModalUser();
});

$("#form-user-edit")?.addEventListener("submit", async (e) => {
  e.preventDefault();
  const id = $("#edit-user-id").value;
  const role = $("#edit-role").value;
  try {
    await api("/api/admin/users/" + id, {
      method: "PUT",
      body: JSON.stringify({
        email: $("#edit-email").value.trim(),
        firstName: $("#edit-firstName").value.trim(),
        lastName: $("#edit-lastName").value.trim(),
        enabled: $("#edit-enabled").checked,
      }),
    });
    await api("/api/admin/users/" + id + "/roles", {
      method: "PUT",
      body: JSON.stringify({ role }),
    });
    toast("Usuario actualizado", "ok");
    cerrarModalUser();
    await loadUsuarios();
  } catch (ex) {
    toast(ex.message, "err");
  }
});

$("#modal-password-cancel")?.addEventListener("click", cerrarModalPassword);
$("#modal-password")?.addEventListener("click", (e) => {
  if (e.target.id === "modal-password") cerrarModalPassword();
});

$("#form-user-password")?.addEventListener("submit", async (e) => {
  e.preventDefault();
  const id = $("#pwd-user-id").value;
  try {
    await api("/api/admin/users/" + id + "/password", {
      method: "PUT",
      body: JSON.stringify({ password: $("#pwd-new").value }),
    });
    toast("Contraseña actualizada", "ok");
    cerrarModalPassword();
  } catch (ex) {
    toast(ex.message, "err");
  }
});

$("#btn-refresh-perfil")?.addEventListener("click", () =>
  loadPerfil().catch((e) => toast(e.message, "err"))
);

$("#form-perfil")?.addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    await api("/api/perfil/me", {
      method: "PUT",
      body: JSON.stringify({
        firstName: $("#perfil-firstName").value.trim(),
        lastName: $("#perfil-lastName").value.trim(),
        email: $("#perfil-email").value.trim(),
      }),
    });
    toast("Perfil actualizado", "ok");
    await loadPerfil();
  } catch (ex) {
    toast(ex.message, "err");
  }
});

$("#form-perfil-password")?.addEventListener("submit", async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  try {
    await api("/api/perfil/me/password", {
      method: "PUT",
      body: JSON.stringify({
        currentPassword: fd.get("currentPassword"),
        newPassword: fd.get("newPassword"),
      }),
    });
    toast("Contraseña cambiada", "ok");
    e.target.reset();
  } catch (ex) {
    toast(ex.message, "err");
  }
});

$$(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    if (!puedeVerTab(tab.dataset.tab)) {
      toast("No tienes acceso a esta pantalla con tu perfil", "err");
      return;
    }
    irATab(tab.dataset.tab);
  });
});

function rolPrincipalDeUsuario(roles) {
  const r = roles || [];
  if (r.includes("ADMIN")) return "ADMIN";
  if (r.includes("OPERADOR")) return "OPERADOR";
  if (r.includes("CLIENTE")) return "CLIENTE";
  return r[0] || "—";
}

function escHtml(s) {
  return String(s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

async function loadUsuarios() {
  const tbody = $("#usuarios-tbody");
  if (!tbody) return;
  tbody.innerHTML = `<tr><td colspan="6" class="empty-row">Cargando…</td></tr>`;
  const list = await api("/api/admin/users");
  state.usuariosCache = list || [];
  if (!state.usuariosCache.length) {
    tbody.innerHTML = `<tr><td colspan="6" class="empty-row">Sin usuarios</td></tr>`;
    return;
  }
  tbody.innerHTML = state.usuariosCache
    .map((u) => {
      const roles = (u.roles || []).join(", ") || "—";
      const nombre = [u.firstName, u.lastName].filter(Boolean).join(" ") || "—";
      const estado = u.enabled ? "Activo" : "Inactivo";
      return `<tr data-id="${escHtml(u.id)}">
        <td><strong>${escHtml(u.username)}</strong></td>
        <td>${escHtml(nombre)}</td>
        <td>${escHtml(u.email || "—")}</td>
        <td>${escHtml(roles)}</td>
        <td><span class="session-pill">${estado}</span></td>
        <td class="user-actions">
          <button type="button" class="btn secondary btn-sm" data-edit="${escHtml(u.id)}">Editar</button>
          <button type="button" class="btn ghost dark btn-sm" data-pwd="${escHtml(u.id)}">Password</button>
          <button type="button" class="btn ghost dark btn-sm" data-toggle="${escHtml(u.id)}">
            ${u.enabled ? "Desactivar" : "Activar"}
          </button>
        </td>
      </tr>`;
    })
    .join("");

  tbody.querySelectorAll("[data-edit]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const u = state.usuariosCache.find((x) => x.id === btn.dataset.edit);
      if (u) abrirModalUser(u);
    });
  });
  tbody.querySelectorAll("[data-pwd]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const u = state.usuariosCache.find((x) => x.id === btn.dataset.pwd);
      if (u) abrirModalPassword(u.id, u.username);
    });
  });
  tbody.querySelectorAll("[data-toggle]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const u = state.usuariosCache.find((x) => x.id === btn.dataset.toggle);
      if (!u) return;
      const enabled = !u.enabled;
      const ok = await confirmar(
        enabled ? "Activar usuario" : "Desactivar usuario",
        enabled ? "¿Activar este usuario?" : "¿Desactivar este usuario?"
      );
      if (!ok) return;
      try {
        await api("/api/admin/users/" + u.id, {
          method: "PUT",
          body: JSON.stringify({
            email: u.email,
            firstName: u.firstName,
            lastName: u.lastName,
            enabled,
          }),
        });
        toast(enabled ? "Usuario activado" : "Usuario desactivado", "ok");
        await loadUsuarios();
      } catch (e) {
        toast(e.message, "err");
      }
    });
  });
}

function abrirModalUser(u) {
  $("#edit-user-id").value = u.id;
  $("#edit-firstName").value = u.firstName || "";
  $("#edit-lastName").value = u.lastName || "";
  $("#edit-email").value = u.email || "";
  $("#edit-role").value = rolPrincipalDeUsuario(u.roles);
  $("#edit-enabled").checked = !!u.enabled;
  $("#modal-user-title").textContent = "Editar · " + (u.username || "");
  $("#modal-user").hidden = false;
}

function cerrarModalUser() {
  $("#modal-user").hidden = true;
}

function abrirModalPassword(id, username) {
  $("#pwd-user-id").value = id;
  $("#pwd-user-label").textContent = "Usuario: " + (username || id);
  $("#pwd-new").value = "";
  $("#modal-password").hidden = false;
}

function cerrarModalPassword() {
  $("#modal-password").hidden = true;
}

async function loadPerfil() {
  const me = await api("/api/perfil/me");
  $("#perfil-username").textContent =
    "Usuario: " + (me.username || "") + " · Roles: " + ((me.roles || []).join(", ") || "—");
  $("#perfil-firstName").value = me.firstName || "";
  $("#perfil-lastName").value = me.lastName || "";
  $("#perfil-email").value = me.email || "";
}

$("#btn-refresh-plazas").addEventListener("click", () =>
  loadPlazas().catch((e) => toast(e.message, "err"))
);

$("#modal-ok").addEventListener("click", () => cerrarModal(true));
$("#modal-cancel").addEventListener("click", () => cerrarModal(false));
$("#modal-confirm").addEventListener("click", (e) => {
  if (e.target.id === "modal-confirm") cerrarModal(false);
});

$$("#filtros-tickets .chip").forEach((chip) => {
  chip.addEventListener("click", () => {
    $$("#filtros-tickets .chip").forEach((c) => c.classList.remove("active"));
    chip.classList.add("active");
    state.filtroTickets = chip.dataset.filtro || "TODOS";
    renderTickets();
  });
});

function validarPlacaFront(placa) {
  const p = String(placa || "").trim().toUpperCase().replace(/\s+/g, "");
  if (!p) return { ok: false, msg: "La placa es obligatoria" };
  if (p.length < 4 || p.length > 12 || !/^[A-Z0-9]{2,8}(-?[A-Z0-9]{1,4})?$/.test(p)) {
    return { ok: false, msg: "Placa inválida. Ej: ABC-123" };
  }
  return { ok: true, placa: p };
}

$("#form-vehiculo").addEventListener("submit", async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  const vp = validarPlacaFront(fd.get("placa"));
  if (!vp.ok) {
    toast(vp.msg, "err");
    return;
  }
  if (!String(fd.get("propietario") || "").trim()) {
    toast("El propietario es obligatorio", "err");
    return;
  }
  try {
    await api("/api/vehiculos", {
      method: "POST",
      body: JSON.stringify({
        placa: vp.placa,
        tipo: fd.get("tipo"),
        marca: fd.get("marca"),
        color: fd.get("color"),
        propietario: fd.get("propietario"),
      }),
    });
    e.target.reset();
    toast("Vehículo registrado", "ok");
    await loadVehiculos();
  } catch (ex) {
    toast(ex.message, "err");
  }
});

$("#form-ticket").addEventListener("submit", async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  const vp = validarPlacaFront(fd.get("placa"));
  if (!vp.ok) {
    toast(vp.msg, "err");
    return;
  }
  if (!String(fd.get("plaza") || "").trim()) {
    toast("Elige una plaza libre", "err");
    return;
  }
  // Evitar abrir en plaza que ya figura ocupada en caché
  const plazaSel = String(fd.get("plaza")).trim().toUpperCase();
  const plazaCache = (state.plazasCache || []).find(
    (p) => String(p.codigo).toUpperCase() === plazaSel || String(p.id) === plazaSel
  );
  if (plazaCache && plazaCache.ocupado) {
    toast("La plaza " + (plazaCache.codigo || plazaSel) + " está ocupada. Elige otra libre.", "err");
    return;
  }
  // Evitar segunda apertura si ya hay ticket abierto de esa placa en caché
  const abiertoPlaca = (state.ticketsCache || []).find(
    (t) => t.estado === "ABIERTO" && String(t.placa).toUpperCase() === vp.placa
  );
  if (abiertoPlaca) {
    toast("Ya tienes un ticket abierto para " + vp.placa + " (" + abiertoPlaca.codigo + ")", "err");
    return;
  }
  const abiertoPlaza = (state.ticketsCache || []).find(
    (t) =>
      t.estado === "ABIERTO" &&
      (String(t.espacioCodigo || "").toUpperCase() === plazaSel ||
        String(t.espacioId) === plazaSel)
  );
  if (abiertoPlaza) {
    toast(
      "Esa plaza ya tiene ticket abierto (" + abiertoPlaza.codigo + " · " + abiertoPlaza.placa + ")",
      "err"
    );
    return;
  }
  try {
    const espacioId = await resolverEspacioId(fd.get("plaza"));
    const ticket = await api("/api/tickets/abrir", {
      method: "POST",
      body: JSON.stringify({
        placa: vp.placa,
        espacioId,
      }),
    });
    e.target.reset();
    if (rolPrincipal() === "CLIENTE") {
      llenarPlacas(state.vehiculosCache || []);
    }
    toast(
      `Ticket abierto: ${ticket.codigo || ""} · plaza ${ticket.espacioCodigo || fd.get("plaza")}`,
      "ok"
    );
    await loadTickets();
    await loadPlazas();
  } catch (ex) {
    toast(ex.message, "err");
  }
});

$("#btn-resilience-once").addEventListener("click", () => {
  probarResilience(1).catch((e) => toast(e.message, "err"));
});

$("#btn-resilience-stress").addEventListener("click", () => {
  probarResilience(5).catch((e) => toast(e.message, "err"));
});

$("#btn-resilience-clear").addEventListener("click", () => {
  state.resilienceLog = [];
  state.resilienceOk = 0;
  state.resilienceFail = 0;
  renderResiliencePanel(null);
  toast("Historial Resilience limpiado", "info");
});

async function refreshEspaciosDemoStatus() {
  const pill = $("#espacios-demo-status");
  if (!pill || !puedeVerTab("resilience")) return;
  try {
    const s = await api("/api/tickets/demo/espacios/status");
    pill.textContent = s.running ? "ms-espacios: UP" : "ms-espacios: DOWN";
  } catch (e) {
    pill.textContent = "estado: ?";
  }
}

$("#btn-espacios-status")?.addEventListener("click", () => {
  refreshEspaciosDemoStatus()
    .then(() => toast("Estado actualizado", "info"))
    .catch((e) => toast(e.message, "err"));
});

$("#btn-espacios-stop")?.addEventListener("click", async () => {
  const ok = await confirmar(
    "Detener ms-espacios",
    "¿Detener el contenedor ms-espacios? Luego prueba Resilience para ver el fallback."
  );
  if (!ok) return;
  try {
    const r = await api("/api/tickets/demo/espacios/stop", { method: "POST" });
    toast(r.mensaje || "ms-espacios detenido", "ok");
    await refreshEspaciosDemoStatus();
  } catch (e) {
    toast(e.message, "err");
  }
});

$("#btn-espacios-start")?.addEventListener("click", async () => {
  try {
    const r = await api("/api/tickets/demo/espacios/start", { method: "POST" });
    toast(r.mensaje || "ms-espacios iniciado", "ok");
    await refreshEspaciosDemoStatus();
    // Esperar a que el servicio levante y liberar plazas de pagos hechos offline
    toast("Sincronizando plazas pendientes…", "info");
    await new Promise((resolve) => setTimeout(resolve, 8000));
    try {
      if (rolPrincipal() === "ADMIN") {
        await api("/api/tickets/reconciliar-plazas", { method: "POST" });
      }
      const sync = await sincronizarLiberacionesPendientes(true);
      if (sync && sync.liberadas > 0) {
        toast("Pagos verificados: plazas liberadas y disponibles", "ok");
      }
    } catch (syncErr) {
      toast(
        "ms-espacios inició, pero aún no sincroniza. Usa «Sincronizar plazas» en Tickets.",
        "info"
      );
    }
    await refreshEspaciosDemoStatus();
  } catch (e) {
    toast(e.message, "err");
  }
});

$("#btn-sync-liberaciones")?.addEventListener("click", () => {
  sincronizarLiberacionesPendientes(true).catch(() => {});
});

function renderResiliencePanel(last) {
  const stats = $("#resilience-stats");
  const status = $("#resilience-status");
  const out = $("#resilience-out");
  const log = $("#resilience-log");
  if (!stats || !status || !out || !log) return;

  stats.innerHTML = `
    <div class="stat"><span>OK</span><strong>${state.resilienceOk}</strong></div>
    <div class="stat"><span>Fallback / error</span><strong>${state.resilienceFail}</strong></div>
    <div class="stat"><span>Total pruebas</span><strong>${state.resilienceOk + state.resilienceFail}</strong></div>
  `;

  if (!last) {
    status.classList.remove("ia-error", "resilience-ok");
    status.innerHTML =
      "<p>Pulsa <strong>Probar 1 llamada</strong> para ver Circuit Breaker + Retry en acción.</p>";
    out.textContent = "—";
  } else if (last.ok) {
    status.classList.remove("ia-error");
    status.classList.add("resilience-ok");
    const fallback = !!last.data?.fallback;
    status.innerHTML = fallback
      ? `<h3>Fallback activo</h3><p>${last.data.mensaje || "ms-espacios no respondió; Resilience4j devolvió respuesta segura."}</p>
         <p><small>Origen: ${last.data.origen || "—"} · ${last.ms} ms</small></p>`
      : `<h3>Circuit Breaker + Retry OK</h3>
         <p>Ocupación live: total ${last.data.total ?? "—"} · ocupados ${last.data.ocupados ?? "—"} · libres ${last.data.libres ?? "—"}</p>
         <p><small>${last.data.resilience || ""} · origen: ${last.data.origen || "—"} · ${last.ms} ms</small></p>`;
    out.textContent = JSON.stringify(last.data, null, 2);
  } else {
    status.classList.add("ia-error");
    status.classList.remove("resilience-ok");
    status.innerHTML = `<h3>Error en la prueba</h3><p>${last.error}</p>`;
    out.textContent = last.error;
  }

  log.innerHTML = state.resilienceLog.length
    ? state.resilienceLog
        .map(
          (e) => `
      <div class="row">
        <div>
          <strong>#${e.n}</strong> · ${e.label}
          <span class="badge-estado">${e.ok ? (e.fallback ? "FALLBACK" : "OK") : "ERROR"}</span>
          <br/><small>${e.hora} · ${e.ms} ms</small>
        </div>
      </div>`
        )
        .join("")
    : `<div class="empty-state">Sin pruebas aún.</div>`;
}

async function probarResilience(veces) {
  if (rolPrincipal() !== "ADMIN") {
    toast("Solo ADMIN puede usar Resilience", "err");
    return;
  }
  const status = $("#resilience-status");
  if (status) {
    status.classList.remove("ia-error", "resilience-ok");
    status.innerHTML = `<p>Ejecutando ${veces} llamada(s) a <code>/api/tickets/ocupacion</code>…</p>`;
  }
  for (let i = 0; i < veces; i++) {
    const t0 = performance.now();
    try {
      const data = await api("/api/tickets/ocupacion");
      const ms = Math.round(performance.now() - t0);
      const fallback = !!data.fallback;
      if (fallback) state.resilienceFail += 1;
      else state.resilienceOk += 1;
      const entry = {
        n: state.resilienceLog.length + 1,
        ok: true,
        fallback,
        label: fallback ? "Fallback Resilience4j" : "Live ms-espacios",
        hora: new Date().toLocaleTimeString("es-EC"),
        ms,
        data,
      };
      state.resilienceLog.unshift(entry);
      renderResiliencePanel({ ok: true, data, ms, fallback });
    } catch (e) {
      const ms = Math.round(performance.now() - t0);
      state.resilienceFail += 1;
      const entry = {
        n: state.resilienceLog.length + 1,
        ok: false,
        fallback: false,
        label: e.message,
        hora: new Date().toLocaleTimeString("es-EC"),
        ms,
        error: e.message,
      };
      state.resilienceLog.unshift(entry);
      renderResiliencePanel({ ok: false, error: e.message, ms });
    }
  }
  toast(
    veces === 1 ? "Prueba Resilience completada" : `Stress ×${veces} completado`,
    "ok"
  );
}

$("#btn-reporte-ia").addEventListener("click", async () => {
  const box = $("#reporte-ia-box");
  const desde = $("#reporte-desde")?.value;
  const hasta = $("#reporte-hasta")?.value;
  if (!desde || !hasta) {
    toast("Indica fecha desde y hasta", "err");
    return;
  }
  if (hasta < desde) {
    toast("La fecha hasta no puede ser anterior a desde", "err");
    return;
  }
  box.hidden = false;
  box.classList.remove("ia-error");
  box.innerHTML = `<p>Generando reporte del ${desde} al ${hasta} con Ollama…</p>`;
  try {
    const r = await api("/api/ia/reporte-tickets", {
      method: "POST",
      body: JSON.stringify({ fechaDesde: desde, fechaHasta: hasta }),
    });
    box.innerHTML = renderReporteClaro(r);
  } catch (e) {
    box.classList.add("ia-error");
    box.innerHTML = `<h3>No se pudo generar el reporte</h3><p>${e.message}</p>`;
  }
});

function renderReporteClaro(r) {
  const periodo =
    r.fechaDesde === r.fechaHasta
      ? `Hoy / día ${r.fechaDesde}`
      : `${r.fechaDesde} → ${r.fechaHasta}`;
  const ok = !!r.cuadranConPlazas;
  const detalle = r.metricas?.plazasOcupadasDetalle || [];
  const zonas = r.metricas?.porZona || {};
  const zonasHtml = Object.keys(zonas).length
    ? Object.entries(zonas)
        .map(([z, n]) => `<li><strong>${z}</strong>: ${n} ticket(s)</li>`)
        .join("")
    : "<li>Sin actividad en el periodo</li>";

  const ocupadasHtml = detalle.length
    ? detalle
        .map(
          (d) => `
      <tr>
        <td><strong>${d.plaza}</strong></td>
        <td>${d.placa || "-"}</td>
        <td>${d.cliente && d.cliente !== "-" ? d.cliente : "—"}</td>
        <td>${d.zona || "—"}</td>
        <td>$${Number(d.porCobrar || 0).toFixed(2)}</td>
      </tr>`
        )
        .join("")
    : `<tr><td colspan="5" class="empty-row">Ninguna plaza ocupada ahora</td></tr>`;

  return `
    <div class="reporte-claro">
      <header class="reporte-claro-head">
        <div>
          <h3>Reporte del parqueadero</h3>
          <p class="reporte-periodo">Periodo de tickets: <strong>${periodo}</strong></p>
        </div>
        <div class="reporte-estado ${ok ? "ok" : "warn"}">
          ${ok ? "Estado: en orden" : "Estado: revisar"}
        </div>
      </header>

      <section class="reporte-sec">
        <h4>1. Plazas actuales</h4>
        <p class="reporte-help">Lo que hay ahora mismo en el estacionamiento.</p>
        <div class="metricas metricas-4">
          <div><span>Total</span><strong>${r.plazasTotal ?? "—"}</strong></div>
          <div class="m-ocup"><span>Ocupadas</span><strong>${r.plazasOcupadas ?? "—"}</strong></div>
          <div class="m-libre"><span>Libres</span><strong>${r.plazasLibres ?? "—"}</strong></div>
          <div><span>Ocupación</span><strong>${r.plazasPorcentaje ?? "—"}%</strong></div>
        </div>
      </section>

      <section class="reporte-sec">
        <h4>2. Quién está estacionado ahora</h4>
        <p class="reporte-help">Número de plaza ocupada por cada cliente.</p>
        <div class="table-wrap">
          <table class="reporte-table">
            <thead>
              <tr>
                <th>Plaza</th>
                <th>Placa</th>
                <th>Cliente</th>
                <th>Zona</th>
                <th>Por cobrar</th>
              </tr>
            </thead>
            <tbody>${ocupadasHtml}</tbody>
          </table>
        </div>
      </section>

      <section class="reporte-sec">
        <h4>3. Dinero del periodo</h4>
        <p class="reporte-help">Tickets entre las fechas seleccionadas.</p>
        <div class="metricas metricas-money">
          <div><span>Tickets del periodo</span><strong>${r.totalTickets}</strong></div>
          <div><span>Cerrados (cobrados)</span><strong>${r.cerrados}</strong></div>
          <div class="m-money"><span>Recaudado</span><strong>$${Number(r.recaudado).toFixed(2)}</strong></div>
          <div class="m-pending"><span>Por cobrar ahora</span><strong>$${Number(r.porCobrarEstimado).toFixed(2)}</strong></div>
        </div>
      </section>

      <section class="reporte-sec">
        <h4>4. Resumen IA</h4>
        <blockquote class="reporte-ia-quote">${r.resumenIa || "Sin resumen."}</blockquote>
      </section>

      <section class="reporte-sec">
        <h4>5. Puntos clave</h4>
        <ul class="reporte-puntos">
          ${(r.hallazgos || []).map((h) => `<li>${h}</li>`).join("")}
        </ul>
      </section>

      <section class="reporte-sec">
        <h4>6. Actividad por zona</h4>
        <ul class="reporte-zonas">${zonasHtml}</ul>
      </section>

      <p class="reporte-foot">Generado: ${r.metricas?.generadoEn || "—"} · ${r.proveedorIa || ""}</p>
    </div>`;
}

function isoHoy() {
  return new Date().toISOString().slice(0, 10);
}

function initFechasReporte() {
  const desde = $("#reporte-desde");
  const hasta = $("#reporte-hasta");
  if (!desde || !hasta) return;
  const hoy = isoHoy();
  const d7 = new Date();
  d7.setDate(d7.getDate() - 6);
  desde.value = d7.toISOString().slice(0, 10);
  hasta.value = hoy;
}

$("#btn-reporte-hoy")?.addEventListener("click", () => {
  const hoy = isoHoy();
  $("#reporte-desde").value = hoy;
  $("#reporte-hasta").value = hoy;
});

$("#btn-reporte-7d")?.addEventListener("click", () => {
  initFechasReporte();
});

initFechasReporte();

$("#form-ia").addEventListener("submit", async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  const vp = validarPlacaFront(fd.get("placa"));
  if (!vp.ok) {
    toast(vp.msg, "err");
    return;
  }
  const box = $("#ia-result");
  box.hidden = false;
  box.classList.remove("ia-error");
  box.innerHTML = "<p>Ollama está eligiendo y asignando plaza…</p>";
  try {
    const r = await api("/api/ia/asignar", {
      method: "POST",
      body: JSON.stringify({
        placa: vp.placa,
        tipoVehiculo: fd.get("tipoVehiculo"),
        preferencia: fd.get("preferencia"),
      }),
    });
    mostrarOkIa(`
      <h3>Asignado: ${r.espacioCodigo} · ${r.zona}</h3>
      <p><strong>Placa:</strong> ${r.placa} · <strong>Ticket:</strong> ${r.ticketCodigo} (ID ${r.ticketId})</p>
      <p><strong>Plaza ID:</strong> ${r.espacioId} · $${r.tarifaHora}/h · Nivel ${r.nivel}</p>
      <p>${r.motivo}</p>
      <p><em>${r.explicacionIa}</em></p>
      <p><small>Proveedor: ${r.proveedorIa}</small></p>
    `);
    await loadTickets();
    await loadPlazas();
  } catch (ex) {
    mostrarErrorIa(ex.message);
  }
});

$("#btn-recomendar").addEventListener("click", async () => {
  const form = $("#form-ia");
  const fd = new FormData(form);
  const box = $("#ia-result");
  box.hidden = false;
  box.classList.remove("ia-error");
  box.innerHTML = "<p>Consultando Ollama…</p>";
  try {
    const r = await api("/api/ia/recomendar", {
      method: "POST",
      body: JSON.stringify({
        tipoVehiculo: fd.get("tipoVehiculo"),
        preferencia: fd.get("preferencia"),
      }),
    });
    mostrarOkIa(`
      <h3>Sugerencia: ${r.espacioCodigo} · ${r.zonaRecomendada}</h3>
      <p><strong>ID plaza:</strong> ${r.espacioId} · $${r.tarifaHora}/h · Nivel ${r.nivel}</p>
      <p>${r.motivo}</p>
      <p><em>${r.explicacionIa}</em></p>
      <p><small>Proveedor: ${r.proveedorIa}</small></p>
      <p><small>Alternativas: ${(r.alternativas || []).join(", ") || "ninguna"}</small></p>
    `);
  } catch (ex) {
    mostrarErrorIa(ex.message);
  }
});

$("#btn-ollama-status").addEventListener("click", async () => {
  try {
    const s = await api("/api/ia/ollama");
    $("#ollama-status").textContent = s.mensaje + " · modelo " + s.modelo + " · " + s.baseUrl;
  } catch (e) {
    $("#ollama-status").textContent = e.message;
  }
});

// Boot
if (loadSession() && Date.now() < state.expiresAt) {
  showApp();
} else {
  clearSession();
  showLogin();
}
