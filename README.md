# Pipidy

Android-App für die Mijia-Pipi-Lampe und Yeelight-Lampen. Lokal per miIO und Yeelight-LAN-Steuerung, ohne Cloud. Die Pipi-Lampe braucht ihren Token.

`tools/lampe_offline.py` richtet eine zurückgesetzte Yeelight ohne Cloud ein: Es liest den Token, schaltet die LAN-Steuerung ein und bringt die Lampe ins WLAN. Bei der Pipi-Lampe liest es den Token aus; nach einem Reset bleibt sie aber nur im WLAN, wenn sie danach die Xiaomi-Cloud erreicht. Braucht Python mit `cryptography`.
