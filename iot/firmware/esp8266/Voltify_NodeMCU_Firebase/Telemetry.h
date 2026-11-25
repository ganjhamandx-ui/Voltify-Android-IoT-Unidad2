#pragma once
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

namespace Voltify {
constexpr int64_t Epoch = 1767225600; // 2026-01-01 UTC
constexpr int BucketSeconds = 30;
constexpr int Slots = 24;
constexpr int BucketCount = 10000000;
constexpr int IdBase = 1900000000;
constexpr int MaxAgeSeconds = 90;
constexpr int LiveMaxAgeSeconds = 5;
constexpr uint32_t LiveIntervalMs = 2000;

struct Reading {
  double volts, milliamps, watts, percent, totalKwh, costClp;
  const char* state;
};

inline bool number(const char*& cursor, const char* prefix, char delimiter,
                   double maximum, double& value) {
  const size_t length = strlen(prefix);
  if (strncmp(cursor, prefix, length) != 0) return false;
  cursor += length;
  const char* start = cursor;
  while (*cursor >= '0' && *cursor <= '9') ++cursor;
  if (cursor == start) return false;
  if (*cursor == '.') {
    const char* fractional = ++cursor;
    while (*cursor >= '0' && *cursor <= '9') ++cursor;
    if (cursor == fractional) return false;
  }
  if (*cursor != delimiter) return false;
  value = strtod(start, nullptr);
  if (!isfinite(value) || value > maximum) return false;
  if (delimiter) ++cursor;
  return true;
}

inline bool parse(const char* line, Reading& output) {
  Reading result{};
  if (!number(line, "V=", ';', 32, result.volts) ||
      !number(line, "I=", ';', 5000, result.milliamps) ||
      !number(line, "P=", ';', 160, result.watts) ||
      !number(line, "POR=", ';', 1000000, result.percent) ||
      strncmp(line, "EST=", 4) != 0) return false;
  line += 4;
  for (const char* state : {"NORMAL", "ADVERTENCIA", "ELEVADO"}) {
    const size_t length = strlen(state);
    if (strncmp(line, state, length) == 0 && line[length] == ';') {
      result.state = state;
      line += length + 1;
      break;
    }
  }
  if (!result.state || !number(line, "E=", ';', 100000, result.totalKwh) ||
      !number(line, "C=", '\0', 1000000000, result.costClp)) return false;
  output = result;
  return true;
}

struct Sample {
  int bucket = -1;
  int64_t time = 0;
  Reading reading{};
  double increment = 0;
};

inline int recordId(int slot, int bucket) {
  return IdBase + slot * BucketCount + bucket;
}

class Aggregator {
  Sample current;
  int lastClosed = -1;
  uint32_t previousMillis = 0;
  double previousTotal = 0;
  bool hasPrevious = false;

 public:
  Sample pending;

  void accept(const Reading& reading, int64_t time, uint32_t ticks) {
    const int64_t index = (time - Epoch) / BucketSeconds;
    if (time < Epoch || index < 0 || index >= BucketCount || index <= lastClosed ||
        (current.bucket >= 0 && time < current.time)) return;
    closeBucket(time);
    const uint32_t gap = ticks - previousMillis;
    const double delta = reading.totalKwh - previousTotal;
    // Tolerancia de dos unidades de la resolución UART (0,000001 kWh).
    if (hasPrevious && gap <= 15000 && delta >= 0 &&
        delta <= 160.0 * gap / 3600000000.0 + 0.000002) {
      current.increment += delta;
    }
    hasPrevious = true;
    previousTotal = reading.totalKwh;
    previousMillis = ticks;
    current.bucket = static_cast<int>(index);
    current.time = time;
    current.reading = reading;
  }

  const Sample* liveSample(int64_t now, int64_t after) const {
    if (current.bucket < 0 || current.time <= after || now < current.time ||
        now - current.time > LiveMaxAgeSeconds) return nullptr;
    return &current;
  }

  void closeBucket(int64_t time) {
    if (current.bucket >= 0 && time >= Epoch + (current.bucket + 1LL) * BucketSeconds) {
      pending = current;
      lastClosed = current.bucket;
      current = Sample{};
    }
    if (pending.bucket >= 0 && (time < pending.time || time - pending.time > MaxAgeSeconds)) {
      pending = Sample{};
    }
  }
};

enum class WriteResult { Accepted, RenewToken, Retry, Rejected };

inline WriteResult classifyWrite(int status) {
  if (status == 200 || status == 204) return WriteResult::Accepted;
  if (status == 401) return WriteResult::RenewToken;
  if (status <= 0 || status == 408 || status == 429 || status >= 500) return WriteResult::Retry;
  return WriteResult::Rejected;
}
}
