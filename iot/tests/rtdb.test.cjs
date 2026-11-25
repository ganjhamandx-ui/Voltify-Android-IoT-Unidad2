const assert = require("node:assert/strict");
const fs = require("node:fs");
const { createRequire } = require("node:module");
const path = require("node:path");

const load = createRequire(path.resolve(__dirname, "../build/emulator/package.json"));
const { assertSucceeds, initializeTestEnvironment } = load("@firebase/rules-unit-testing");
const projectId = "demo-voltify-iot";
const epoch = 1767225600000;
const rules = fs.readFileSync(path.resolve(__dirname, "../../firebase_database_rules.json"), "utf8");

async function main() {
  const env = await initializeTestEnvironment({ projectId, database: { rules } });
  try {
    const auth = await fetch("http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: "test-device@example.test",
        password: "Emulator-only!27",
        returnSecureToken: true
      })
    });
    assert.equal(auth.status, 200);
    const { localId } = await auth.json();
    const signIn = await fetch("http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=fake", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: "test-device@example.test",
        password: "Emulator-only!27",
        returnSecureToken: true
      })
    });
    assert.equal(signIn.status, 200);
    const { idToken, refreshToken } = await signIn.json();
    const refreshed = await fetch("http://127.0.0.1:9099/securetoken.googleapis.com/v1/token?key=fake", {
      method: "POST",
      body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: refreshToken })
    });
    assert.equal(refreshed.status, 200);
    const token = (await refreshed.json()).id_token;

    const artifact = {
      id: 42, nombre: "Prueba INA219", categoria: "Otro", marca: "", modelo: "",
      potenciaW: 1, activo: true, monitoreando: true, fechaRegistro: 1, tipoFuente: "IOT_REAL"
    };
    const binding = { enabled: true, ownerUid: "owner", artifactKey: "42", slot: 0 };
    const seed = (ref, value) => env.withSecurityRulesDisabled(context => context.database().ref(ref).set(value));
    await seed("/", {
      iotDeviceBindings: { [localId]: binding },
      users: { owner: { artifacts: { 42: artifact, 43: { ...artifact, id: 43 } } } }
    });

    const now = Date.now();
    const fecha = Math.floor(now / 30000) * 30000;
    const id = 1900000000 + (fecha - epoch) / 30000;
    const key = "users/owner/consumptions/" + id;
    const base = "http://127.0.0.1:9000/";
    const url = (ref, authToken = token) =>
      base + ref + ".json?ns=" + projectId + (authToken ? "&auth=" + authToken : "");
    const row = {
      id, artefactoId: 42, artefactoNombre: artifact.nombre, artefactoCategoria: "Otro",
      artefactoMarca: "", artefactoModelo: "", consumoKwh: 0.00001, fecha, tipoFuente: "IOT_REAL",
      telemetria: {
        muestraMs: now, voltajeV: 9, corrienteMa: 90, potenciaW: 0.81, porcentaje: 50,
        estado: "NORMAL", energiaTotalKwh: 0.001, costoTotalClp: 1
      }
    };
    const put = (ref, value, authToken) => fetch(url(ref, authToken), {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(value)
    });
    async function denied(response) {
      assert.ok([401, 403].includes(response.status), "Expected denial: HTTP " + response.status);
    }
    await denied(await put(key, row, ""));
    await denied(await put(key.replace("owner", "other"), row));
    await denied(await put(key, { ...row, artefactoId: 43 }));
    await denied(await put(key + "0", row));
    await denied(await put(key, { ...row, id: id + 10000000 }));
    await denied(await put(key, { ...row, consumoKwh: 0.5 }));
    await denied(await put(key, { ...row, telemetria: { ...row.telemetria, extra: 1 } }));
    await denied(await put(key, { ...row, telemetria: { ...row.telemetria, muestraMs: now - 100000 } }));
    await denied(await put("iotDeviceBindings/" + localId + "/extra", true));
    await denied(await put("users/" + localId + "/profile", {
      nombre: "Placa", correo: "test-device@example.test", telefono: "", comuna: "",
      empresa: "", usarTarifaManual: false
    }));
    await denied(await fetch(url("users/owner/artifacts/42")));
    await seed("users/owner/artifacts/42/monitoreando", false);
    await denied(await put(key, row));
    await seed("users/owner/artifacts/42/monitoreando", true);
    await seed("iotDeviceBindings/" + localId + "/enabled", false);
    await denied(await put(key, row));
    await seed("iotDeviceBindings/" + localId + "/enabled", true);

    const accepted = await fetch(url(key) + "&print=silent", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(row)
    });
    assert.equal(accepted.status, 204, await accepted.text());
    await denied(await put(key, row, idToken));
    await denied(await put(key, { ...row, consumoKwh: 0.0009 }));

    const owner = env.authenticatedContext("owner", { email_verified: true }).database();
    const snapshot = await assertSucceeds(owner.ref(key).once("value"));
    assert.deepEqual(snapshot.val(), row);
    await assert.rejects(owner.ref(key).remove());
    await assert.rejects(owner.ref(key + "/consumoKwh").set(5));
    const { telemetria, ...legacy } = row;
    await assertSucceeds(owner.ref("users/owner/consumptions/123").set({
      ...legacy, id: 123, tipoFuente: "SIMULADA"
    }));
    console.log("RTDB/Auth: escritura, renovación, permisos, repetición e historial verificados");
  } finally {
    await env.cleanup();
  }
}
main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
