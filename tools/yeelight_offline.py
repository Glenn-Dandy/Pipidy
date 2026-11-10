#!/usr/bin/env python3
"""Set up a factory-reset Yeelight without the cloud and without internet.

Reset the lamp (switch it off and on five times), connect this computer to the
lamp's own Wi-Fi (yeelink-light-...) and run this script. It reads the token
from the lamp, switches on LAN control and sends the lamp the credentials of
the Wi-Fi it should join. Needs the Python package "cryptography".

Usage: python3 yeelight_offline.py [lamp ip]
"""
import getpass, hashlib, json, os, socket, struct, subprocess, sys, time
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

HELLO = bytes.fromhex('21310020' + 'ff' * 28)


def md5(data):
    return hashlib.md5(data).digest()


def gateway():
    out = subprocess.run(['ip', 'route', 'show', 'default'], capture_output=True, text=True).stdout.split()
    return out[out.index('via') + 1] if 'via' in out else None


def exchange(sock, host, packet, tries=5):
    for _ in range(tries):
        sock.sendto(packet, (host, 54321))
        try:
            return sock.recvfrom(4096)[0]
        except socket.timeout:
            pass
    return None


class Lamp:
    def __init__(self, host, token):
        self.host, self.token = host, token
        self.key = md5(token)
        self.iv = md5(self.key + token)
        self.n = int(time.time()) % 100000

    def call(self, method, params):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(2)
        hello = exchange(sock, self.host, HELLO)
        if hello is None:
            raise TimeoutError('Lampe antwortet nicht')
        did, stamp = struct.unpack('>II', hello[8:16])
        self.n += 1
        plain = json.dumps({'id': self.n, 'method': method, 'params': params}, separators=(',', ':')).encode()
        padder = padding.PKCS7(128).padder()
        data = padder.update(plain) + padder.finalize()
        enc = Cipher(algorithms.AES(self.key), modes.CBC(self.iv)).encryptor()
        body = enc.update(data) + enc.finalize()
        head = struct.pack('>HHIII', 0x2131, 32 + len(body), 0, did, stamp + 1)
        reply = exchange(sock, self.host, head + md5(head + self.token + body) + body)
        if reply is None:
            raise TimeoutError('Lampe antwortet nicht auf den Befehl')
        dec = Cipher(algorithms.AES(self.key), modes.CBC(self.iv)).decryptor()
        raw = dec.update(reply[32:]) + dec.finalize()
        unpadder = padding.PKCS7(128).unpadder()
        raw = unpadder.update(raw) + unpadder.finalize()
        return json.loads(raw.rstrip(b'\0'))


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else gateway()
    if not host:
        sys.exit('Keine Verbindung gefunden. Ist der Laptop mit dem WLAN der Lampe verbunden?')
    print(f'Lampe wird unter {host} gesucht ...')
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(2)
    hello = exchange(sock, host, HELLO)
    if hello is None or len(hello) < 32 or hello[:2] != b'\x21\x31':
        sys.exit('Unter dieser Adresse antwortet keine Lampe. Nichts geändert.')
    did = struct.unpack('>I', hello[8:12])[0]
    token = hello[16:32]
    print(f'Lampe gefunden, Geräte-ID {did}.')
    if token in (b'\xff' * 16, b'\x00' * 16):
        sys.exit('Die Lampe verrät ihren Token nicht (verdeckt). Nichts geändert.\n'
                 'Entweder ist sie nicht zurückgesetzt, oder ihre Firmware gibt ihn nie heraus.')

    path = os.path.expanduser(f'~/yeelight-{did}.token')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.write(fd, token.hex().encode())
    os.close(fd)
    print(f'Token gelesen und gespeichert in {path}')

    lamp = Lamp(host, token)
    try:
        info = lamp.call('miIO.info', [])
    except (ValueError, TimeoutError) as error:
        sys.exit(f'Der gelesene Token funktioniert nicht ({error}). Nichts geändert.')
    result = info.get('result', {})
    print(f"Modell {result.get('model')}, Firmware {result.get('fw_ver')}")

    try:
        print('LAN-Steuerung einschalten:', lamp.call('set_ps', ['cfg_lan_ctrl', '1']).get('result'))
    except (ValueError, TimeoutError) as error:
        print(f'LAN-Steuerung ließ sich jetzt nicht setzen ({error}). Das holen wir nach, wenn die Lampe im WLAN ist.')

    print('\nJetzt die Zugangsdaten des IoT-WLANs (2,4 GHz), in das die Lampe soll.')
    ssid = input('Name des WLANs: ').strip()
    password = getpass.getpass('Passwort (wird nicht angezeigt): ')
    if not ssid:
        sys.exit('Kein WLAN-Name eingegeben. Die Lampe bleibt in ihrem Einrichtungsmodus.')
    try:
        answer = lamp.call('miIO.config_router', {'ssid': ssid, 'passwd': password, 'uid': 0})
        print('Zugangsdaten gesendet:', answer.get('result', answer))
    except TimeoutError:
        print('Zugangsdaten gesendet, keine Bestätigung mehr erhalten (die Lampe wechselt oft sofort das WLAN).')
    print('\nFertig. Verbinde den Laptop wieder mit deinem normalen WLAN und sag Claude Bescheid.')


if __name__ == '__main__':
    main()
