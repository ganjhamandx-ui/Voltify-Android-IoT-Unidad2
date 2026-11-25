#include <ESP8266WiFi.h>
#include <SoftwareSerial.h>

// ======================================================
// VOLTIFY - ESP8266 NODEMCU
// WiFi + recepción UART desde Arduino UNO
// ======================================================

// NodeMCU:
// D2 = RX desde Arduino
// D1 = TX hacia Arduino
SoftwareSerial comunicacion(D2, D1);

// ===========================================
// COLOCA TU WIFI
// ESP8266 trabaja con redes 2.4 GHz
// ===========================================

const char* ssid = "NOMBRE_DE_TU_WIFI";
const char* password = "CLAVE_DE_TU_WIFI";

String datosArduino = "";

void conectarWiFi() {

  Serial.println();
  Serial.println("==============================");
  Serial.println("VOLTIFY - ESP8266");
  Serial.println("==============================");

  Serial.print("Conectando a WiFi: ");
  Serial.println(ssid);

  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);

  int intentos = 0;

  while (WiFi.status() != WL_CONNECTED) {

    delay(500);
    Serial.print(".");

    intentos++;

    if (intentos >= 40) {

      Serial.println();
      Serial.println("No fue posible conectar al WiFi.");
      Serial.println("Reintentando...");

      WiFi.disconnect();
      delay(1000);
      WiFi.begin(ssid, password);

      intentos = 0;
    }
  }

  Serial.println();
  Serial.println("WiFi conectado");

  Serial.print("IP: ");
  Serial.println(WiFi.localIP());

  Serial.print("Intensidad WiFi: ");
  Serial.print(WiFi.RSSI());
  Serial.println(" dBm");

  Serial.println("==============================");
}

void setup() {

  Serial.begin(9600);

  comunicacion.begin(9600);

  delay(1000);

  conectarWiFi();

  Serial.println();
  Serial.println("VOLTIFY - NodeMCU");
  Serial.println("Esperando datos del Arduino...");
}

void loop() {

  // ======================================================
  // CONTROLAR CONEXIÓN WIFI
  // ======================================================

  if (WiFi.status() != WL_CONNECTED) {

    Serial.println("WiFi desconectado");

    conectarWiFi();
  }

  // ======================================================
  // RECIBIR UART DESDE ARDUINO
  // ======================================================

  while (comunicacion.available()) {

    char caracter = comunicacion.read();

    if (caracter == '\n') {

      datosArduino.trim();

      if (datosArduino.length() > 0) {

        Serial.println();
        Serial.println("Dato recibido desde Arduino:");

        Serial.println(datosArduino);

        Serial.print("WiFi: conectado | IP: ");
        Serial.println(WiFi.localIP());

        // ==================================================
        // AQUÍ DESPUÉS ENVIAREMOS EL DATO A FIREBASE
        // ==================================================

      }

      datosArduino = "";

    } else {

      datosArduino += caracter;

    }
  }

  delay(10);
}
