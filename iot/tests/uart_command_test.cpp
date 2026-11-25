#include <cassert>
#include <cstring>
#include <VoltifyUart.h>

int main() {
  using namespace VoltifyUart;
  constexpr char id[] = "abcdef0123456789abcdef0123456789";
  assert(checksum("123456789", 9) == 0x29b1);
  assert(operation("voltify-01-ina219") == 'R');
  assert(operation("voltify-01-led-verde") == 'V');
  assert(operation("voltify-01-led-amarillo") == 'Y');
  assert(operation("voltify-01-led-rojo") == 'B');
  assert(operation("voltify-01-buzzer") == 'Z');
  assert(operation("voltify-01-arduino-uno") == 0);
  assert(operation("voltify-02-led-verde") == 0);
  for (const char* p = "RVYBZA"; *p; ++p) {
    char data[FrameSize];
    char received[IdSize + 1];
    assert(frame(data, id, *p));
    assert(decode(data, received) == *p);
    assert(strcmp(id, received) == 0);
    data[10] = 'x';
    assert(decode(data, received) == 0);
    data[1] = 0;
    assert(decode(data, received) == 0);
  }
  char data[FrameSize];
  assert(!frame(data, id, 'O'));
  assert(!frame(data, "too-short", 'R'));
}
