# Pipidy

Android-App für die Mijia-Pipi-Lampe (`dreame.light.r2229`, MJPPD01ZM). Sie spricht per miIO (UDP 54321) direkt mit der Lampe, ohne Xiaomi-Cloud. Die Lampe kann dabei komplett vom Internet getrennt sein.

## Funktionen

An/Aus, Helligkeit, Farbtemperatur, Lichtmodi, 14 Bewegungen, Gesten-Erkennung, Persönlichkeit, Ausschalt-Timer.

## Voraussetzungen

- Token der Lampe (32 Hex-Zeichen), wird beim ersten Start eingetragen und bleibt auf dem Gerät.
- Die Lampe antwortet nur Absendern aus ihrem eigenen Subnetz. Liegt sie in einem anderen Netz als das Handy, muss der Router UDP 54321 zu ihr maskieren.

## Bauen

```
JAVA_HOME=/pfad/zu/jdk17 ./gradlew assembleDebug
```
