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
    const owner = env.authenticatedContext("owner", { email_verified: true }).database();
    const peer = env.authenticatedContext("owner", { email_verified: true }).database();
    const outsider = env.authenticatedContext("outsider", { email_verified: true }).database();
    const liveKey = "users/owner/iotLive/42";
    const historyRef = peer.ref("users/owner/consumptions");
    let historyEvents = 0;
    const countHistory = () => { historyEvents += 1; };
    historyRef.on("value", countHistory);
    await historyRef.once("value");

    const firstLive = { ...row.telemetria, muestraMs: now - 2000 };
    const nextLive = { ...row.telemetria, potenciaW: 0.9 };
    await denied(await put(liveKey, firstLive, ""));
    await denied(await put(liveKey.replace("owner", "other"), firstLive));
    await denied(await put("users/owner/iotLive/43", firstLive));
    await denied(await put(liveKey, { ...firstLive, muestraMs: now - 11000 }));
    await denied(await put(liveKey, { ...firstLive, muestraMs: now + 60000 }));
    await denied(await put(liveKey, { ...firstLive, consumoKwh: 0.5 }));
    await denied(await put(liveKey, { ...firstLive, corrienteMa: 5100 }));
    await denied(await put(liveKey, { ...firstLive, estado: null }));
    await seed("users/owner/artifacts/42/monitoreando", false);
    await denied(await put(liveKey, firstLive));
    await seed("users/owner/artifacts/42/monitoreando", true);
    await seed("iotDeviceBindings/" + localId + "/enabled", false);
    await denied(await put(liveKey, firstLive));
    await seed("iotDeviceBindings/" + localId + "/enabled", true);

    const liveRef = peer.ref("users/owner/iotLive");
    const seenLive = [];
    const recordLive = snapshot => { if (snapshot.hasChild("42")) seenLive.push(snapshot.child("42").val()); };
    const waitForLive = (sample) => {
      let listener;
      return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
          liveRef.off("value", listener);
          reject(new Error("No llegó la muestra " + sample.muestraMs));
        }, 5000);
        listener = snapshot => {
          const reading = snapshot.child("42").val();
          if (reading?.muestraMs === sample.muestraMs) {
            clearTimeout(timeout);
            liveRef.off("value", listener);
            resolve(reading);
          }
        };
        liveRef.on("value", listener);
      });
    };
    liveRef.on("value", recordLive);
    try {
      assert.equal((await put(liveKey, firstLive)).status, 200);
      assert.deepEqual(await waitForLive(firstLive), firstLive);
      assert.equal((await put(liveKey, nextLive)).status, 200);
      assert.deepEqual(await waitForLive(nextLive), nextLive);
      assert.deepEqual(seenLive, [firstLive, nextLive]);
      assert.equal((await liveRef.once("value")).numChildren(), 1);
      assert.equal(historyEvents, 1, "Las escrituras en vivo no notifican el historial");
    } finally {
      liveRef.off("value", recordLive);
      historyRef.off("value", countHistory);
    }
    await denied(await put(liveKey, firstLive));
    await denied(await put(liveKey, nextLive));
    await denied(await put(liveKey, null));
    await denied(await fetch(url(liveKey)));
    await assert.rejects(outsider.ref(liveKey).once("value"));
    await assert.rejects(owner.ref(liveKey).set(nextLive));
    await assert.rejects(owner.ref(liveKey).remove());
    console.log("RTDB en vivo: dos muestras por el listener dedicado, un nodo y cero eventos de historial adicionales");

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

    const snapshot = await assertSucceeds(owner.ref(key).once("value"));
    assert.deepEqual(snapshot.val(), row);
    await assert.rejects(owner.ref(key).remove());
    await assert.rejects(owner.ref(key + "/consumoKwh").set(5));
    // Compatibilidad de las reglas para el cliente Android en dos sesiones del mismo UID.
    const profile = {
      nombre: "Usuario de prueba", correo: "owner@example.test", telefono: "",
      comuna: "Santiago", empresa: "", usarTarifaManual: false
    };
    const pendingOwner = env.authenticatedContext("owner", { email: profile.correo, email_verified: false }).database();
    const verifiedOwner = env.authenticatedContext("owner", { email: profile.correo, email_verified: true }).database();
    await assertSucceeds(pendingOwner.ref("users/owner/profile").set(profile));
    await assert.rejects(pendingOwner.ref("users/owner/profile/telefono").set("123"));
    await assertSucceeds(verifiedOwner.ref("users/owner/profile/telefono").set("123"));
    assert.equal((await peer.ref("users/owner/profile/telefono").once("value")).val(), "123");

    const simArtifact = owner.ref("users/owner/artifacts/51");
    await assertSucceeds(simArtifact.set({ ...artifact, id: 51, tipoFuente: "SIMULADA" }));
    await assertSucceeds(simArtifact.child("nombre").set("Actualizado"));
    assert.equal((await peer.ref("users/owner/artifacts/51/nombre").once("value")).val(), "Actualizado");
    await assert.rejects(outsider.ref("users/owner/artifacts/51").once("value"));
    await assertSucceeds(simArtifact.remove());
    assert.equal((await peer.ref("users/owner/artifacts/51").once("value")).exists(), false);

    const meta = owner.ref("users/owner/meta");
    await assertSucceeds(meta.set({ consumoKwh: 20, costoClp: 2000 }));
    assert.equal((await peer.ref("users/owner/meta/consumoKwh").once("value")).val(), 20);
    await assertSucceeds(meta.remove());

    const { telemetria, ...legacy } = row;
    const legacyRef = owner.ref("users/owner/consumptions/123");
    await assertSucceeds(legacyRef.set({ ...legacy, id: 123 }));
    const simKey = "users/owner/consumptions/1000000001";
    const simulated = { ...legacy, id: 1000000001, tipoFuente: "SIMULADA" };
    const saveMax = (client, kwh) => client.ref(simKey).transaction(current =>
      current && current.consumoKwh >= kwh ? current : { ...simulated, consumoKwh: kwh });
    await Promise.all([saveMax(owner, 0.02), saveMax(peer, 0.03)]);
    const finalHistory = (await historyRef.once("value")).val();
    assert.equal(finalHistory["1000000001"].consumoKwh, 0.03);
    assert.deepEqual(finalHistory[id], row);
    assert.deepEqual(finalHistory["123"], { ...legacy, id: 123 });
    await assertSucceeds(legacyRef.remove());
    const manifest = JSON.parse(fs.readFileSync(path.resolve(__dirname, "../iotComponents.example.json"), "utf8"));
    const registry = "users/owner/iotComponents";
    const unoId = "voltify-01-arduino-uno";
    const inaId = "voltify-01-ina219";
    const uno = manifest[unoId];
    assert.equal(Object.keys(manifest).length, 8);
    for (const [componentId, entry] of Object.entries(manifest)) {
      assert.equal(entry.componentId, componentId);
      assert.equal(entry.prototypeId, "voltify-01");
      assert.ok(manifest[entry.connection.targetComponentId]);
      await assertSucceeds(owner.ref(registry + "/" + componentId).set(entry));
    }
    assert.deepEqual((await assertSucceeds(peer.ref(registry).once("value"))).val(), manifest);
    await assertSucceeds(owner.ref(registry + "/" + unoId + "/model").set("Arduino Uno R3 / revisión"));
    assert.equal((await peer.ref(registry + "/" + unoId + "/model").once("value")).val(),
                 "Arduino Uno R3 / revisión");

    await assert.rejects(outsider.ref(registry).once("value"));
    await assert.rejects(outsider.ref(registry + "/" + unoId).set(uno));
    await assert.rejects(pendingOwner.ref(registry + "/" + unoId).set(uno));
    await denied(await put(registry + "/" + unoId, uno));
    await denied(await put("users/" + localId + "/iotComponents/" + unoId, uno));
    await denied(await fetch(url(registry)));
    await denied(await fetch(url(registry, "")));
    const verifiedGateway = env.authenticatedContext(localId, { email_verified: true }).database();
    await assert.rejects(verifiedGateway.ref("users/" + localId + "/iotComponents/" + unoId).set(uno));

    await assert.rejects(owner.ref(registry + "/otro-id").set(uno));
    await assert.rejects(owner.ref(registry + "/" + unoId + "/prototypeId").set("voltify-02"));
    await assert.rejects(owner.ref(registry + "/" + unoId + "/connection/targetComponentId").set(unoId));
    await assert.rejects(owner.ref(registry + "/" + unoId + "/connection/address").set(64));
    await assert.rejects(owner.ref(registry + "/" + inaId + "/connection/address").set(128));
    await assert.rejects(owner.ref(registry + "/" + inaId + "/connection/address").remove());
    await assert.rejects(owner.ref(registry + "/" + unoId + "/action").set("ON"));
    await assert.rejects(owner.ref("users/owner/iotCommands/example").set({ targetComponentId: "voltify-01-led-verde" }));
    await denied(await fetch(url("users/owner/iotCommands/example")));

    await assertSucceeds(owner.ref(registry + "/" + unoId).remove());
    assert.equal((await peer.ref(registry).once("value")).numChildren(), 7);
    console.log("Inventario: 8 IDs, CRUD del propietario, aislamiento y validación; canal de comandos cerrado");
    console.log("RTDB/Auth: autenticación, permisos e historial físico sin duplicados; perfil, CRUD, metas y simulación compatibles entre dos sesiones");
  } finally {
    await env.cleanup();
  }
}
main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
