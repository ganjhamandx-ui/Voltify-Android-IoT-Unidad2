#include <ArduinoJson.h>
#include <VoltifyUart.h>
#include <ESP8266HTTPClient.h>
#include <ESP8266WiFi.h>
#include <SoftwareSerial.h>
#include <WiFiClientSecureBearSSL.h>
#include <time.h>

#include "GoogleRoots.h"
#include "TelemetryJson.h"
#if __has_include("VoltifyConfig.local.h")
#include "VoltifyConfig.local.h"
#else
#include "VoltifyConfig.example.h"
#endif

namespace {
constexpr char DeviceUid[] = "voltify-01-nodemcu";
constexpr char Database[] = "https://voltify-e51f9-default-rtdb.firebaseio.com";
constexpr uint32_t HttpTimeoutMs = 4500;
SoftwareSerial uart;
BearSSL::X509List roots(GoogleRoots);
Voltify::Aggregator aggregator;
BearSSL::WiFiClientSecure databaseTls;
HTTPClient databaseHttp;
char line[128];
size_t lineSize = 0;
bool discardLine = false;
bool provisioned = false;
bool wasConnected = false;
bool ntpStarted = false;
bool renewedAfterDenial = false;
uint32_t networkDue = 0;
uint32_t wifiDue = 0;
uint32_t backoff = 2000;
uint32_t tokenDue = 0;
uint32_t liveDue = 0;
int64_t lastLiveTime = 0;
String idToken;
String refreshToken;
uint32_t commandPollDue = 0;
int64_t commandExpiry = 0;
char commandId[VoltifyUart::IdSize + 1] = {};
char queuedFrame[VoltifyUart::FrameSize] = {};
bool commandConfirmed = false;


bool due(uint32_t deadline) {
  return static_cast<int32_t>(millis() - deadline) >= 0;
}

bool safeKey(const char* value) {
  const size_t length = strlen(value);
  return length > 0 && length <= 128 &&
         strspn(value, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-") == length;
}

void pollUart() {
  if (uart.overflow()) discardLine = true;
  while (uart.available()) {
    const char byte = uart.read();
    if (byte == '\n') {
      if (!discardLine) {
        if (lineSize && line[lineSize - 1] == '\r') --lineSize;
        line[lineSize] = '\0';
        Voltify::Reading reading;
        char confirmedId[VoltifyUart::IdSize + 1];
        if (line[0] == '@') {
          if (VoltifyUart::decode(line, confirmedId) == 'A' && !strcmp(confirmedId, commandId))
            commandConfirmed = true;
        } else {
          char* check = strstr(line, ";CRC=");
          const bool integrity = check
              ? VoltifyUart::crcMatches(check + 5, VoltifyUart::checksum(line, check - line))
              : ALLOW_LEGACY_UART;
          if (check) *check = '\0';
          if (integrity && Voltify::parse(line, reading)) {
            aggregator.accept(reading, time(nullptr), millis());
          } else {
            Serial.println(F("UART: trama invalida"));
          }
        }
      }
      discardLine = false;
      lineSize = 0;
    } else if (byte < 32 && byte != '\r') {
      discardLine = true;
    } else if (!discardLine && lineSize < sizeof(line) - 1) {
      line[lineSize++] = byte;
    } else {
      discardLine = true;
    }
  }
}

void configureHttp(HTTPClient& http, BearSSL::WiFiClientSecure& tls) {
  tls.setTrustAnchors(&roots);
  tls.setTimeout(HttpTimeoutMs);
  http.useHTTP10(true);
  http.setTimeout(HttpTimeoutMs);
}

String formEncode(const String& value) {
  static const char hex[] = "0123456789ABCDEF";
  String encoded;
  for (unsigned char byte : value) {
    if (isalnum(byte) || byte == '-' || byte == '_' || byte == '.' || byte == '~') {
      encoded += static_cast<char>(byte);
    } else {
      encoded += '%';
      encoded += hex[byte >> 4];
      encoded += hex[byte & 15];
    }
  }
  return encoded;
}

class AuthResponse {
  Stream& input;
  size_t remaining = 8192;
  const uint32_t deadline = millis() + HttpTimeoutMs;

 public:
  explicit AuthResponse(Stream& input) : input(input) {}

  int read() {
    char byte;
    return readBytes(&byte, 1) == 1 ? static_cast<unsigned char>(byte) : -1;
  }

  size_t readBytes(char* buffer, size_t length) {
    if (!remaining || due(deadline)) return 0;
    const size_t count = input.readBytes(buffer, length < remaining ? length : remaining);
    remaining -= count;
    return count;
  }
};

bool authenticate() {
  const String url = String("https://securetoken.googleapis.com/v1/token?key=") + FIREBASE_API_KEY;
  BearSSL::WiFiClientSecure tls;
  HTTPClient http;
  configureHttp(http, tls);
  if (!http.begin(tls, url)) return false;
  http.addHeader("Content-Type", "application/x-www-form-urlencoded");
  const int status = http.POST("grant_type=refresh_token&refresh_token=" + formEncode(refreshToken));
  Serial.printf("Renovacion Firebase HTTP %d\n", status);
  if (status != 200) {
    http.end();
    return false;
  }
  const char* tokenKey = "id_token";
  const char* refreshKey = "refresh_token";
  const char* expiryKey = "expires_in";
  const char* uidKey = "user_id";
  JsonDocument filter;
  filter[tokenKey] = true;
  filter[refreshKey] = true;
  filter[expiryKey] = true;
  filter[uidKey] = true;
  JsonDocument response;
  const int length = http.getSize();
  AuthResponse bodyStream(http.getStream());
  const bool validJson = length != 0 && length <= 8192 &&
      !deserializeJson(response, bodyStream, DeserializationOption::Filter(filter));
  http.end();
  if (!validJson) return false;

  const char* token = response[tokenKey] | "";
  const char* renewal = response[refreshKey] | "";
  const char* uid = response[uidKey] | "";
  const unsigned long seconds = strtoul(response[expiryKey] | "", nullptr, 10);
  if (strcmp(uid, DeviceUid) != 0 || !*token || strlen(token) > 4096 ||
      !*renewal || strlen(renewal) > 2048 || seconds < 120 || seconds > 86400) {
    Serial.println(F("Auth: identidad o respuesta invalida"));
    return false;
  }
  idToken = token;
  refreshToken = renewal;
  tokenDue = millis() + (seconds - 60) * 1000;
  return true;
}

int publish(const Voltify::Sample& sample, bool live) {
  JsonDocument row;
  String url = String(Database) + "/users/" + OWNER_UID;
  if (live) {
    Voltify::makeTelemetry(row.to<JsonObject>(), sample);
    url += String("/iotLive/") + ARTIFACT_KEY;
  } else {
    const int id = Voltify::recordId(DEVICE_SLOT, sample.bucket);
    Voltify::makeRecord(row, sample, id, atoi(ARTIFACT_KEY), ARTIFACT_NAME,
                        ARTIFACT_CATEGORY, ARTIFACT_BRAND, ARTIFACT_MODEL);
    url += String("/consumptions/") + id;
  }
  url += String(".json?print=silent&auth=") + idToken;
  String body;
  serializeJson(row, body);

  if (!databaseHttp.begin(databaseTls, url)) return -1;
  databaseHttp.addHeader("Content-Type", "application/json");
  const int status = databaseHttp.PUT(body);
  if (status != 204 && status != 200) databaseTls.stop();
  databaseHttp.end();
  if (!live || status != 204) {
    Serial.printf("RTDB %s HTTP %d | heap=%u bloque libre=%u\n",
                  live ? "en vivo" : "historial", status,
                  ESP.getFreeHeap(), ESP.getMaxFreeBlockSize());
  }
  return status;
}

// writeToStream descodifica respuestas chunked y este receptor limita su tamaño.
class CommandBody : public Stream {
 public:
  char data[512] = {};
  size_t count = 0;
  int available() override { return 0; }
  int read() override { return -1; }
  int peek() override { return -1; }
  void flush() override {}
  size_t write(uint8_t byte) override {
    if (count >= sizeof(data) - 1) return 0;
    data[count++] = byte;
    return 1;
  }
  size_t write(const uint8_t* bytes, size_t size) override {
    if (size > sizeof(data) - 1 - count) return 0;
    memcpy(data + count, bytes, size);
    count += size;
    return size;
  }
};

String commandUrl(bool ack) {
  return String(Database) + "/users/" + OWNER_UID + "/iotCommands/" + ARTIFACT_KEY +
      (ack ? "/ack.json?print=silent&auth=" : ".json?auth=") + idToken;
}

bool writeAck(const char* id, const char* state) {
  if (!databaseHttp.begin(databaseTls, commandUrl(true))) return false;
  databaseHttp.addHeader("Content-Type", "application/json");
  JsonDocument ack;
  ack["id"] = id;
  ack["state"] = state;
  ack["deviceUid"] = DeviceUid;
  String body;
  serializeJson(ack, body);
  const int status = databaseHttp.PUT(body);
  if (status != 204) databaseTls.stop();
  databaseHttp.end();
  return status == 204;
}

int64_t utcMs() { return static_cast<int64_t>(time(nullptr)) * 1000; }

void pollCommands() {
  if (commandId[0]) {
    if (utcMs() >= commandExpiry) {
      commandId[0] = 0;
      commandConfirmed = false;
    } else if (commandConfirmed && writeAck(commandId, "EXECUTED")) {
      commandId[0] = 0;
      commandConfirmed = false;
    }
    return;
  }

  if (!databaseHttp.begin(databaseTls, commandUrl(false))) return;
  const int status = databaseHttp.GET();
  CommandBody body;
  const bool received = status == 200 && databaseHttp.writeToStream(&body) >= 0;
  if (!received) databaseTls.stop();
  databaseHttp.end();
  JsonDocument command;
  if (!received || deserializeJson(command, body.data, body.count)) return;

  const char* id = command["id"] | "";
  const char op = VoltifyUart::operation(command["targetComponentId"] | "");
  const int64_t expiry = command["expiresAt"] | int64_t(0);
  const int64_t now = utcMs();
  if (!op || !VoltifyUart::validId(id) || !command["ack"].isNull() ||
      expiry <= now || expiry > now + 16000 || !writeAck(id, "CLAIMED") ||
      expiry <= utcMs()) return;
  strcpy(commandId, id);
  commandExpiry = expiry;
  VoltifyUart::frame(queuedFrame, id, op);
}

void postpone() {
  networkDue = millis() + backoff;
  backoff = backoff >= 30000 ? 60000 : backoff * 2;
}

void tickNetwork() {
  if (!provisioned) return;
  if (WiFi.status() != WL_CONNECTED) {
    if (wasConnected) {
      Serial.println(F("Wi-Fi desconectado"));
      databaseTls.stop();
      databaseHttp.end();
      wifiDue = millis();
      liveDue = wifiDue;
    }
    wasConnected = false;
    if (due(wifiDue)) {
      WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
      wifiDue = millis() + 20000;
    }
    return;
  }
  if (!wasConnected) Serial.println(F("Wi-Fi conectado"));
  wasConnected = true;
  if (!ntpStarted) {
    configTime(0, 0, "time.google.com", "pool.ntp.org");
    ntpStarted = true;
    Serial.println(F("Esperando hora SNTP para TLS"));
  }
  if (time(nullptr) < Voltify::Epoch || !due(networkDue)) return;

  const uint32_t started = millis();
  if (idToken.isEmpty() || due(tokenDue)) {
    // Liberar los búferes de RTDB antes de abrir TLS hacia Firebase Auth.
    databaseTls.stop();
    databaseHttp.end();
    if (authenticate()) {
      networkDue = millis();
    } else {
      postpone();
    }
  } else {
    const bool live = aggregator.pending.bucket < 0;
    const auto* sample = live ? aggregator.liveSample(time(nullptr), lastLiveTime)
                              : &aggregator.pending;
    if (sample == nullptr || (live && !due(liveDue))) {
      if (!due(commandPollDue)) return;
      commandPollDue = started + 2000;
      pollCommands();
    } else {
      if (live) liveDue = started + Voltify::LiveIntervalMs;

      const auto result = Voltify::classifyWrite(publish(*sample, live));
      if (result == Voltify::WriteResult::RenewToken && !renewedAfterDenial) {
        idToken = "";
        renewedAfterDenial = true;
        postpone();
      } else if (result == Voltify::WriteResult::Retry) {
        postpone();
      } else {
        if (live) {
          if (result == Voltify::WriteResult::Accepted) {
            lastLiveTime = sample->time;
          } else {
            liveDue = millis() + 10000;
          }
        } else {
          aggregator.pending = Voltify::Sample{};
        }
        if (result == Voltify::WriteResult::Accepted) renewedAfterDenial = false;
        networkDue = millis();
        backoff = 2000;
      }
    }
  }
  // La UART no trae marcas de tiempo; descartar el backlog de una operación larga.
  if (millis() - started >= 1000) {
    while (uart.available()) uart.read();
    uart.overflow();
    // CRC y formato distinguen una trama nueva de un fragmento del backlog.
    discardLine = false;
    lineSize = 0;
  }
}
}

void setup() {
  Serial.begin(9600);
  uart.begin(9600, SWSERIAL_8N1, D2, D1, false, 256, 1024);
  Serial.println(F("Voltify Firebase: UART 9600, RX D2"));
  char* end = nullptr;
  const long artifactId = strtol(ARTIFACT_KEY, &end, 10);
  provisioned = *WIFI_SSID && safeKey(FIREBASE_API_KEY) && *DEVICE_REFRESH_TOKEN &&
      strlen(DEVICE_REFRESH_TOKEN) <= 2048 && safeKey(OWNER_UID) &&
      strcmp(DeviceUid, OWNER_UID) != 0 && artifactId > 0 && !*end &&
      ARTIFACT_KEY[0] >= '1' && ARTIFACT_KEY[0] <= '9' &&
      DEVICE_SLOT >= 0 && DEVICE_SLOT < Voltify::Slots;
  if (!provisioned) {
    Serial.println(F("Completa VoltifyConfig.local.h para habilitar la red"));
    return;
  }
  refreshToken = DEVICE_REFRESH_TOKEN;
  databaseTls.setTrustAnchors(&roots);
  databaseTls.setTimeout(HttpTimeoutMs);
  databaseHttp.setTimeout(HttpTimeoutMs);
  databaseHttp.setReuse(true);
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
}

void loop() {
  pollUart();
  aggregator.closeBucket(time(nullptr));
  tickNetwork();
  if (queuedFrame[0]) {
    if (utcMs() < commandExpiry) uart.println(queuedFrame);
    queuedFrame[0] = 0;
  }
  delay(1);
}
