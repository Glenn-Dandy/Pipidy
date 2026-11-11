# Pipidy

Android-App für die Mijia-Pipi-Lampe und Yeelight-Lampen. Lokal per miIO und Yeelight-LAN-Steuerung, ohne Cloud. Die Pipi-Lampe braucht ihren Token.

`tools/lampe_offline.py` richtet eine zurückgesetzte Yeelight ohne Cloud ein: Es liest den Token, schaltet die LAN-Steuerung ein und bringt die Lampe ins WLAN. Eine Pipi-Lampe lässt sich nur steuern, wenn sie einmal in Mi Home an ein Konto gebunden wurde; dafür liest das Skript nur den Token einer bereits gebundenen Lampe aus dem WLAN aus. Braucht Python mit `cryptography`.
