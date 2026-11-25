package com.example.voltify.model

enum class IotTarget(val componentId: String, val label: String) {
    INA219("voltify-01-ina219", "Solicitar lectura INA219"),
    GREEN("voltify-01-led-verde", "Probar LED verde"),
    YELLOW("voltify-01-led-amarillo", "Probar LED amarillo"),
    RED("voltify-01-led-rojo", "Probar LED rojo"),
    BUZZER("voltify-01-buzzer", "Probar buzzer")
}
