#pragma once
#include <stdint.h>
#include <stdio.h>
#include <string.h>

namespace VoltifyUart {
constexpr size_t IdSize = 32;
constexpr size_t FrameSize = 42;

inline uint16_t update(uint16_t crc, uint8_t byte) {
  crc ^= uint16_t(byte) << 8;
  for (uint8_t i = 0; i < 8; ++i)
    crc = crc & 0x8000 ? (crc << 1) ^ 0x1021 : crc << 1;
  return crc;
}

inline uint16_t checksum(const char* bytes, size_t length) {
  uint16_t crc = 0xffff;
  for (size_t i = 0; i < length; ++i) crc = update(crc, bytes[i]);
  return crc;
}

inline bool validId(const char* id) {
  return strlen(id) == IdSize && strspn(id, "0123456789abcdef") == IdSize;
}

inline bool crcMatches(const char* hex, uint16_t crc) {
  char expected[5];
  snprintf(expected, sizeof(expected), "%04x", static_cast<unsigned>(crc));
  return strcmp(expected, hex) == 0;
}

inline bool frame(char (&out)[FrameSize], const char* id, char op) {
  if (!validId(id) || !op || !strchr("RVYBZA", op)) return false;
  snprintf(out, sizeof(out), "@%s|%c|", id, op);
  snprintf(out + 36, 6, "%04x", static_cast<unsigned>(checksum(out, 36)));
  return true;
}

inline char decode(const char* in, char (&id)[IdSize + 1]) {
  if (strlen(in) != 40 || in[0] != '@' || in[33] != '|' || in[35] != '|' ||
      !strchr("RVYBZA", in[34]) || !crcMatches(in + 36, checksum(in, 36))) return 0;
  memcpy(id, in + 1, IdSize);
  id[IdSize] = 0;
  return validId(id) ? in[34] : 0;
}

inline char operation(const char* target) {
  if (!strcmp(target, "voltify-01-ina219")) return 'R';
  if (!strcmp(target, "voltify-01-led-verde")) return 'V';
  if (!strcmp(target, "voltify-01-led-amarillo")) return 'Y';
  if (!strcmp(target, "voltify-01-led-rojo")) return 'B';
  if (!strcmp(target, "voltify-01-buzzer")) return 'Z';
  return 0;
}
}
