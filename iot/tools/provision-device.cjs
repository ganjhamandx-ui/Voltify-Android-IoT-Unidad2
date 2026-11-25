const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");

const deviceUid = "voltify-01-nodemcu";
const projectId = "voltify-e51f9";
const provisioningDir = path.resolve(__dirname, "../build/provisioning");
const loadAdmin = createRequire(path.join(provisioningDir, "package.json"));

async function exchangeCustomToken(endpoint, customToken, expectedUid, expectedProject) {
  const response = await fetch(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: customToken, returnSecureToken: true }),
    redirect: "error",
    signal: AbortSignal.timeout(15000)
  });
  if (!response.ok) throw new Error("Custom token exchange failed");
  const result = await response.json();
  const payload = JSON.parse(Buffer.from(result.idToken.split(".")[1], "base64url"));
  if (payload.sub !== expectedUid || payload.aud !== expectedProject || payload.firebase?.sign_in_provider !== "custom" ||
      !Number.isSafeInteger(payload.auth_time) || payload.auth_time <= 0 ||
      typeof result.refreshToken !== "string" || !result.refreshToken || result.refreshToken.length > 2048) {
    throw new Error("Unexpected device identity or credential");
  }
  return { deviceUid: expectedUid, refreshToken: result.refreshToken, minAuthTime: payload.auth_time };
}

async function main() {
  const action = process.argv[2];
  const signer = process.env.VOLTIFY_SIGNER_EMAIL;
  const apiKey = process.env.VOLTIFY_CLIENT_API_KEY;
  if (process.argv.length !== 3 || !["--provision", "--revoke"].includes(action) ||
      process.env.FIREBASE_AUTH_EMULATOR_HOST || process.env.GOOGLE_APPLICATION_CREDENTIALS ||
      process.env.NODE_DEBUG || process.env.DEBUG) {
    process.stderr.write("Acción requerida: --provision o --revoke. Usar ADC local, sin emulador ni depuración.\n");
    process.exitCode = 2;
    return;
  }
  if (action === "--provision" && (!/^[a-z][a-z0-9-]+@voltify-e51f9\.iam\.gserviceaccount\.com$/.test(signer || "") ||
      !/^[A-Za-z0-9_-]+$/.test(apiKey || ""))) {
    process.stderr.write("Configura VOLTIFY_SIGNER_EMAIL y VOLTIFY_CLIENT_API_KEY para voltify-e51f9.\n");
    process.exitCode = 2;
    return;
  }

  const { initializeApp, applicationDefault, deleteApp } = loadAdmin("firebase-admin/app");
  const { getAuth } = loadAdmin("firebase-admin/auth");
  const app = initializeApp({ projectId, credential: applicationDefault(), serviceAccountId: signer });
  try {
    const auth = getAuth(app);
    if (action === "--revoke") {
      await auth.revokeRefreshTokens(deviceUid);
      process.stdout.write("Revocación solicitada para voltify-01-nodemcu. Mantén deshabilitada la vinculación.\n");
      return;
    }
    const output = path.join(provisioningDir, "device-session.local.json");
    const fd = fs.openSync(output, "wx", 0o600);
    try {
      const token = await auth.createCustomToken(deviceUid);
      const endpoint = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=" + apiKey;
      const session = await exchangeCustomToken(endpoint, token, deviceUid, projectId);
      fs.writeFileSync(fd, JSON.stringify(session, null, 2) + "\n");
    } finally {
      fs.closeSync(fd);
    }
    process.stdout.write("Sesión guardada en iot/build/provisioning/device-session.local.json.\n");
  } finally {
    await deleteApp(app);
  }
}

module.exports = { deviceUid, exchangeCustomToken, loadAdmin };
if (require.main === module) {
  main().catch(error => {
    const status = error?.code === "EEXIST" ? "El archivo privado ya existe." : "La operación no se completó.";
    process.stderr.write(status + " Revisa ADC, permisos y configuración del proyecto sin imprimir credenciales.\n");
    process.exitCode = 1;
  });
}
