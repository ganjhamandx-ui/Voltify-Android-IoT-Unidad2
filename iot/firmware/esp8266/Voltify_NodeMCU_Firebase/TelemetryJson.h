#pragma once
#include <ArduinoJson.h>
#include "Telemetry.h"

namespace Voltify {
inline void makeTelemetry(ArduinoJson::JsonObject telemetry, const Sample& sample) {
  const Reading& reading = sample.reading;
  telemetry["muestraMs"] = sample.time * 1000;
  telemetry["voltajeV"] = reading.volts;
  telemetry["corrienteMa"] = reading.milliamps;
  telemetry["potenciaW"] = reading.watts;
  telemetry["porcentaje"] = reading.percent;
  telemetry["estado"] = reading.state;
  telemetry["energiaTotalKwh"] = reading.totalKwh;
  telemetry["costoTotalClp"] = reading.costClp;
}

inline void makeRecord(ArduinoJson::JsonDocument& row, const Sample& sample, int id,
                       int artifactId, const char* name, const char* category,
                       const char* brand, const char* model) {
  row["id"] = id;
  row["artefactoId"] = artifactId;
  row["artefactoNombre"] = name;
  row["artefactoCategoria"] = category;
  row["artefactoMarca"] = brand;
  row["artefactoModelo"] = model;
  row["consumoKwh"] = sample.increment;
  row["fecha"] = (Epoch + sample.bucket * BucketSeconds) * 1000;
  row["tipoFuente"] = "IOT_REAL";
  makeTelemetry(row["telemetria"].to<ArduinoJson::JsonObject>(), sample);
}
}
