#include <Wire.h>
#include <VoltifyUart.h>
#include <Adafruit_INA219.h>
#include <LiquidCrystal_I2C.h>
#include <SoftwareSerial.h>

// ======================================================
// VOLTIFY - ARDUINO UNO
// INA219 + LCD I2C + LEDs + buzzer + UART hacia ESP8266
// ======================================================

// Sensor INA219
Adafruit_INA219 ina219;

// Pantalla LCD 16x2
LiquidCrystal_I2C lcd(0x27, 16, 2);

// Comunicación con NodeMCU
// Arduino D2 = RX
// Arduino D3 = TX
SoftwareSerial comunicacion(2, 3);

// Pines
const int LED_VERDE = 4;
const int LED_AMARILLO = 5;
const int LED_ROJO = 6;
const int BUZZER = 7;

// Configuración Voltify
const float POTENCIA_REFERENCIA_W = 1.00;
const float TARIFA_CLP_KWH = 185.0;

// Variables
float voltajeBus = 0.0;
float corriente_mA = 0.0;
float potencia_W = 0.0;

float porcentaje = 0.0;
float energia_Wh = 0.0;
float energia_kWh = 0.0;
float costo_CLP = 0.0;

String estado = "NORMAL";

unsigned long ultimoCalculo = 0;
unsigned long ultimaPantalla = 0;
unsigned long ultimoEnvio = 0;

class CheckedTelemetry : public Print {
 public:
  uint16_t crc = 0xffff;
  size_t write(uint8_t byte) override {
    crc = VoltifyUart::update(crc, byte);
    return comunicacion.write(byte);
  }
};

char commandLine[VoltifyUart::FrameSize];
char lastCommand[VoltifyUart::IdSize + 1] = {};
uint8_t commandLength = 0;
bool commandOverflow = false;
char operation = 0;
unsigned long pulseStarted = 0;

bool receiveCommand(char (&id)[VoltifyUart::IdSize + 1]) {
  while (comunicacion.available()) {
    const char byte = comunicacion.read();
    if (byte == '\r') continue;
    if (byte == '\n') {
      commandLine[commandLength] = 0;
      const char op = commandOverflow ? 0 : VoltifyUart::decode(commandLine, id);
      commandLength = 0;
      commandOverflow = false;
      if (!op || op == 'A' || !strcmp(id, lastCommand)) continue;
      strcpy(lastCommand, id);
      operation = op;
      pulseStarted = millis();
      return true;
    }
    if (!commandOverflow && commandLength < sizeof(commandLine) - 1) {
      commandLine[commandLength++] = byte;
    } else {
      commandOverflow = true;
    }
  }
  return false;
}

void setup() {

  Serial.begin(9600);
  comunicacion.begin(9600);

  pinMode(LED_VERDE, OUTPUT);
  pinMode(LED_AMARILLO, OUTPUT);
  pinMode(LED_ROJO, OUTPUT);
  pinMode(BUZZER, OUTPUT);

  digitalWrite(LED_VERDE, LOW);
  digitalWrite(LED_AMARILLO, LOW);
  digitalWrite(LED_ROJO, LOW);
  digitalWrite(BUZZER, LOW);

  Wire.begin();

  lcd.init();
  lcd.backlight();

  lcd.setCursor(0, 0);
  lcd.print("VOLTIFY");
  lcd.setCursor(0, 1);
  lcd.print("Iniciando...");

  if (!ina219.begin()) {

    Serial.println("ERROR: INA219 no detectado");

    lcd.clear();
    lcd.setCursor(0, 0);
    lcd.print("ERROR INA219");

    while (1) {
      delay(100);
    }
  }

  Serial.println("================================");
  Serial.println("VOLTIFY - Arduino UNO");
  Serial.println("INA219 OK");
  Serial.println("LCD OK");
  Serial.println("UART NodeMCU OK");
  Serial.println("================================");

  delay(1500);
  lcd.clear();

  ultimoCalculo = millis();
}

void loop() {

  // ======================================================
  // LECTURA INA219
  // ======================================================

  voltajeBus = ina219.getBusVoltage_V();
  corriente_mA = ina219.getCurrent_mA();
  potencia_W = ina219.getPower_mW() / 1000.0;

  // Evitar pequeños valores negativos por ruido
  if (corriente_mA < 0) {
    corriente_mA = 0;
  }

  if (potencia_W < 0) {
    potencia_W = 0;
  }

  // ======================================================
  // CALCULAR ENERGÍA
  // ======================================================

  unsigned long ahora = millis();

  float tiempoHoras =
      (ahora - ultimoCalculo) / 3600000.0;

  energia_Wh += potencia_W * tiempoHoras;

  energia_kWh = energia_Wh / 1000.0;

  costo_CLP =
      energia_kWh * TARIFA_CLP_KWH;

  ultimoCalculo = ahora;

  // ======================================================
  // PORCENTAJE DE REFERENCIA
  // ======================================================

  if (POTENCIA_REFERENCIA_W > 0) {

    porcentaje =
        (potencia_W / POTENCIA_REFERENCIA_W) * 100.0;

  } else {

    porcentaje = 0;

  }

  // ======================================================
  // ESTADOS / LEDs / BUZZER
  // ======================================================

  digitalWrite(LED_VERDE, LOW);
  digitalWrite(LED_AMARILLO, LOW);
  digitalWrite(LED_ROJO, LOW);
  digitalWrite(BUZZER, LOW);

  if (porcentaje < 70.0) {

    estado = "NORMAL";

    digitalWrite(LED_VERDE, HIGH);

  }
  else if (porcentaje < 100.0) {

    estado = "ADVERTENCIA";

    digitalWrite(LED_AMARILLO, HIGH);

  }
  else {

    estado = "ELEVADO";

    digitalWrite(LED_ROJO, HIGH);
    digitalWrite(BUZZER, HIGH);

  }

  // ======================================================
  // MONITOR SERIAL
  // ======================================================

  Serial.print("Voltaje: ");
  Serial.print(voltajeBus, 2);
  Serial.print(" V | Corriente: ");
  Serial.print(corriente_mA, 2);
  Serial.print(" mA | Potencia: ");
  Serial.print(potencia_W, 3);
  Serial.print(" W | Consumo: ");
  Serial.print(porcentaje, 1);
  Serial.print("% | Energia: ");
  Serial.print(energia_kWh, 6);
  Serial.print(" kWh | Costo: $");
  Serial.print(costo_CLP, 2);
  Serial.print(" | Estado: ");
  Serial.println(estado);

  // ======================================================
  // LCD
  // ======================================================

  if (millis() - ultimaPantalla >= 1000) {

    ultimaPantalla = millis();

    lcd.clear();

    lcd.setCursor(0, 0);

    lcd.print("P:");
    lcd.print(potencia_W, 2);
    lcd.print("W ");

    lcd.print(porcentaje, 0);
    lcd.print("%");

    lcd.setCursor(0, 1);

    lcd.print("E:");
    lcd.print(energia_kWh, 4);

    lcd.print(" $");
    lcd.print(costo_CLP, 1);
  }

  // ======================================================
  // ENVÍO UART AL NODEMCU
  // Formato que habíamos definido para Voltify
  // ======================================================

  char commandId[VoltifyUart::IdSize + 1];
  const bool newCommand = receiveCommand(commandId);
  // Las pruebas solo agregan HIGH y nunca sustituyen las alertas automáticas.
  if (operation && millis() - pulseStarted >= 500) operation = 0;
  if (operation) {
    switch (operation) {
      case 'V': digitalWrite(LED_VERDE, HIGH); break;
      case 'Y': digitalWrite(LED_AMARILLO, HIGH); break;
      case 'B': digitalWrite(LED_ROJO, HIGH); break;
      case 'Z': digitalWrite(BUZZER, HIGH); break;
    }
  }
  if (millis() - ultimoEnvio >= 1000 || (newCommand && operation == 'R')) {
    CheckedTelemetry telemetry;

    ultimoEnvio = millis();

    telemetry.print("V=");
    telemetry.print(voltajeBus, 2);

    telemetry.print(";I=");
    telemetry.print(corriente_mA, 2);

    telemetry.print(";P=");
    telemetry.print(potencia_W, 3);

    telemetry.print(";POR=");
    telemetry.print(porcentaje, 1);

    telemetry.print(";EST=");
    telemetry.print(estado);

    telemetry.print(";E=");
    telemetry.print(energia_kWh, 6);

    telemetry.print(";C=");
    telemetry.print(costo_CLP, 2);

    char crc[5];
    snprintf(crc, sizeof(crc), "%04x", static_cast<unsigned>(telemetry.crc));
    comunicacion.print(";CRC=");
    comunicacion.println(crc);
  }

  if (newCommand) {
    char response[VoltifyUart::FrameSize];
    if (VoltifyUart::frame(response, commandId, 'A')) comunicacion.println(response);
  }
  delay(250);
}
