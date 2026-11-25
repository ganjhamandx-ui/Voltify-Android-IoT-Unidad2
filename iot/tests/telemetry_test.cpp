#include <cassert>
#include <string>
#include "../firmware/esp8266/Voltify_NodeMCU_Firebase/TelemetryJson.h"

using namespace Voltify;

int main() {
  Reading input{};
  assert(parse("V=0.00;I=0.00;P=0.000;POR=0.0;EST=NORMAL;E=0.000000;C=0.000", input));
  assert(input.volts == 0 && std::string(input.state) == "NORMAL");
  assert(parse("V=9.00;I=90.00;P=0.810;POR=12.1;EST=ADVERTENCIA;E=0.000012;C=1.200", input));
  assert(input.milliamps == 90 && input.watts == 0.810 && input.costClp == 1.200);
  for (const char* bad : {
      "V=9",
      "V=9;I=90;P=0.810;POR=12.1;EST=ADVERTENCIA;E=0.000012",
      "V=9;I=NaN;P=1;POR=1;EST=NORMAL;E=0;C=0",
      "V=9;I=-1;P=1;POR=1;EST=NORMAL;E=0;C=0",
      "V=33;I=1;P=1;POR=1;EST=NORMAL;E=0;C=0",
      "V=9;I=90;P=1;POR=1;EST=DESCONOCIDO;E=0;C=0",
      "V=9;I=90;P=1;POR=1;EST=NORMAL;E=0;C=0;P=1"}) {
    assert(!parse(bad, input));
  }

  Aggregator readings;
  readings.accept(input, Epoch - 1, 0);
  readings.accept(input, Epoch + 1, 1000);
  readings.accept(input, Epoch + 2, 2000);  // Repetición UART.
  input.totalKwh += 0.000001;
  readings.accept(input, Epoch + 3, 3000);
  input.totalKwh = 0;
  readings.accept(input, Epoch + 4, 4000);  // Reinicio del Arduino.
  input.totalKwh = 0.000001;
  readings.accept(input, Epoch + 5, 5000);
  input.totalKwh = 0.001;
  readings.accept(input, Epoch + 25, 25000);  // Hueco de recepción.
  readings.closeBucket(Epoch + 30);
  assert(readings.pending.bucket == 0);
  assert(readings.pending.time == Epoch + 25);
  assert(fabs(readings.pending.increment - 0.000002) < 1e-12);
  assert(recordId(0, 0) == 1900000000);
  assert(recordId(23, BucketCount - 1) == 2139999999);

  ArduinoJson::JsonDocument document;
  makeRecord(document, readings.pending, recordId(0, 0), 42,
             "Medidor \"A\"", "Otro", "", "");
  std::string json;
  serializeJson(document, json);
  ArduinoJson::JsonDocument decoded;
  assert(!deserializeJson(decoded, json));
  assert(decoded["id"].as<int>() == 1900000000);
  assert(decoded["fecha"].as<int64_t>() == Epoch * 1000);
  assert(decoded["artefactoNombre"].as<std::string>() == "Medidor \"A\"");
  assert(decoded["tipoFuente"].as<std::string>() == "IOT_REAL");
  assert(decoded["telemetria"]["muestraMs"].as<int64_t>() == (Epoch + 25) * 1000);
  assert(decoded["telemetria"]["corrienteMa"].as<double>() == 90);
  assert(decoded["telemetria"]["energiaTotalKwh"].as<double>() == 0.001);

  readings.accept(input, Epoch + 29, 29000);  // Un ajuste de reloj no reabre el bloque.
  readings.closeBucket(Epoch + 116);
  assert(readings.pending.bucket == -1);

  assert(classifyWrite(204) == WriteResult::Accepted);
  assert(classifyWrite(412) == WriteResult::Rejected);
  assert(classifyWrite(401) == WriteResult::RenewToken);
  assert(classifyWrite(403) == WriteResult::Rejected);
  for (int code : {-11, 408, 429, 500, 503}) assert(classifyWrite(code) == WriteResult::Retry);
}
