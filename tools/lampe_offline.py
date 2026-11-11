#!/usr/bin/env python3
"""Set up a factory-reset Yeelight or Mijia Pipi lamp without the cloud and without internet.

Reset the lamp, connect this computer to the lamp's own Wi-Fi and pick the lamp
type in the menu. The script reads the token from the lamp, switches on LAN
control (Yeelight) or shows the token (Pipi lamp), and sends the lamp the
credentials of the Wi-Fi it should join.

A Pipi lamp only accepts control once it is bound to a Mi account in the Mi Home
app (which needs internet once), so for it the script only reads the token of an
already bound lamp that is on the network.

Needs the Python package "cryptography".
Usage: python3 lampe_offline.py [lamp ip]
"""
import getpass, hashlib, json, os, socket, struct, subprocess, sys, time
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

HELLO = bytes.fromhex('21310020' + 'ff' * 28)
WIDTH = 62

# name, model prefix, name of the lamp's setup Wi-Fi, how to reset it
KINDS = {
    '1': ('Yeelight', 'yeelink.', 'yeelink-light-…',
          'fünfmal aus- und einschalten, jeweils etwa zwei Sekunden an und zwei Sekunden aus'),
    '2': ('Pipi-Lampe', 'dreame.light.', 'dreame-light-…',
          'wie in der Anleitung der Lampe beschrieben'),
}


# ---------------------------------------------------------------- screen

COLOUR = sys.stdout.isatty() and os.environ.get('TERM', 'dumb') != 'dumb'


def paint(text, code):
    return f'\033[{code}m{text}\033[0m' if COLOUR else text


def amber(text): return paint(text, '38;5;214')
def dim(text): return paint(text, '2')
def bold(text): return paint(text, '1')
def green(text): return paint(text, '32')
def red(text): return paint(text, '31')


def box(lines, title=None):
    top = f'─ {title} ' if title else ''
    print(amber('┌' + top + '─' * (WIDTH - len(top)) + '┐'))
    for line in lines:
        print(amber('│') + ' ' + line + ' ' * (WIDTH - 1 - len(line)) + amber('│'))
    print(amber('└' + '─' * WIDTH + '┘'))


def banner():
    if COLOUR:
        print('\033[2J\033[H', end='')
    print(amber('╔' + '═' * WIDTH + '╗'))
    for line in ('', '   ◉  LAMPE OFFLINE', '      Yeelight und Pipi-Lampe ohne Cloud einrichten', ''):
        print(amber('║') + bold(line) + ' ' * (WIDTH - len(line)) + amber('║'))
    print(amber('╚' + '═' * WIDTH + '╝'))


def heading(text):
    print('\n' + amber('▌ ') + bold(text))
    print(dim('─' * (WIDTH + 2)))


def step(text): print(amber('  ▸ ') + text)
def good(text): print(green('  ✔ ') + text)
def bad(text): print(red('  ✘ ') + text)
def note(text): print(dim('    ' + text))


def ask(question):
    return input(amber('  ? ') + question)


def yes(question, default):
    answer = ask(f"{question} [{'J/n' if default else 'j/N'}]: ").strip().lower()
    return default if not answer else answer in ('j', 'ja', 'y', 'yes')


# ---------------------------------------------------------------- miIO

class Abort(Exception):
    """Ends the current lamp and returns to the menu."""


def md5(data):
    return hashlib.md5(data).digest()


def encrypt(token, plain):
    key = md5(token)
    padder = padding.PKCS7(128).padder()
    enc = Cipher(algorithms.AES(key), modes.CBC(md5(key + token))).encryptor()
    return enc.update(padder.update(plain) + padder.finalize()) + enc.finalize()


def decrypt(token, body):
    key = md5(token)
    dec = Cipher(algorithms.AES(key), modes.CBC(md5(key + token))).decryptor()
    unpadder = padding.PKCS7(128).unpadder()
    return unpadder.update(dec.update(body) + dec.finalize()) + unpadder.finalize()


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
        self.n = int(time.time()) % 100000

    def call(self, method, params):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(2)
        hello = exchange(sock, self.host, HELLO)
        if hello is None:
            raise TimeoutError('Lampe antwortet nicht')
        did, stamp = struct.unpack('>II', hello[8:16])
        self.n += 1
        body = encrypt(self.token, json.dumps({'id': self.n, 'method': method, 'params': params},
                                              separators=(',', ':')).encode())
        head = struct.pack('>HHIII', 0x2131, 32 + len(body), 0, did, stamp + 1)
        reply = exchange(sock, self.host, head + md5(head + self.token + body) + body)
        if reply is None:
            raise TimeoutError('Lampe antwortet nicht auf den Befehl')
        return json.loads(decrypt(self.token, reply[32:]).rstrip(b'\0'))


def log(text):
    """Keeps the lamp's raw answers for troubleshooting. Never contains the Wi-Fi password."""
    path = os.path.expanduser('~/lampe_offline.log')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    os.write(fd, f"{time.strftime('%Y-%m-%d %H:%M:%S')} {text}\n".encode())
    os.close(fd)


def left_setup_wifi(host, seconds=45):
    """True once the lamp stops answering in its own Wi-Fi, which means it is joining the new one."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(2)
    silent = 0
    end = time.time() + seconds
    while time.time() < end:
        try:
            answered = exchange(sock, host, HELLO, tries=1) is not None
        except OSError:
            answered = False
        silent = 0 if answered else silent + 1
        if silent >= 3:
            return True
        time.sleep(2)
    return False


# ---------------------------------------------------------------- setup

def setup(choice, fixed_host, wifi):
    name, prefix, wifi_name, reset = KINDS[choice]
    if choice == '2':
        setup_pipi(name, fixed_host)
        return

    heading(f'{name} einrichten')
    step(f'Lampe zurücksetzen: {reset}.')
    step(f'Diesen Rechner mit dem WLAN der Lampe verbinden ({wifi_name}, ohne Passwort).')
    ask('Weiter mit Enter, wenn der Rechner im WLAN der Lampe ist ... ')

    host = fixed_host or gateway()
    if not host:
        raise Abort('Keine Verbindung gefunden. Ist der Rechner mit dem WLAN der Lampe verbunden?')
    step(f'Lampe wird unter {host} gesucht ...')
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(2)
    hello = exchange(sock, host, HELLO)
    if hello is None or len(hello) < 32 or hello[:2] != b'\x21\x31':
        raise Abort('Unter dieser Adresse antwortet keine Lampe. Nichts geändert.')
    did = struct.unpack('>I', hello[8:12])[0]
    token = hello[16:32]
    good(f'Lampe gefunden, Geräte-ID {did}.')
    if token in (b'\xff' * 16, b'\x00' * 16):
        raise Abort('Die Lampe verrät ihren Token nicht (verdeckt). Nichts geändert.\n'
                    'Entweder ist sie nicht zurückgesetzt, oder ihre Firmware gibt ihn nie heraus.')

    path = os.path.expanduser(f'~/miio-{did}.token')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.write(fd, token.hex().encode())
    os.close(fd)
    good(f'Token gelesen und gespeichert in {path}')

    lamp = Lamp(host, token)
    try:
        info = lamp.call('miIO.info', []).get('result') or {}
    except (ValueError, TimeoutError) as error:
        raise Abort(f'Der gelesene Token funktioniert nicht ({error}). Nichts geändert.')
    log(f"{name} {did} info {json.dumps({k: v for k, v in info.items() if k != 'token'})}")
    model = str(info.get('model', 'unbekannt'))
    good(f"Modell {model}, Firmware {info.get('fw_ver') or 'unbekannt'}")
    if not model.startswith(prefix) and not yes(f'Das sieht nicht nach einer {name} aus. Trotzdem weitermachen?', False):
        raise Abort('Abgebrochen. Nichts geändert.')

    try:
        lan_control = lamp.call('set_ps', ['cfg_lan_ctrl', '1']).get('result') == ['ok']
    except (ValueError, TimeoutError):
        lan_control = False
    if lan_control:
        good('LAN-Steuerung eingeschaltet.')
    else:
        bad('Die LAN-Steuerung ließ sich nicht einschalten. Das geht später noch mit dem gespeicherten Token.')

    if wifi and not yes(f"WLAN „{wifi['ssid']}“ wieder verwenden?", True):
        wifi.clear()
    if not wifi:
        step('Zugangsdaten des WLANs (2,4 GHz), in das die Lampe soll:')
        ssid = ask('Name des WLANs: ').strip()
        if not ssid:
            raise Abort('Kein WLAN-Name eingegeben. Die Lampe bleibt in ihrem Einrichtungsmodus.')
        wifi.update(ssid=ssid, passwd=getpass.getpass(amber('  ? ') + 'Passwort (wird nicht angezeigt): '))
    try:
        answer = lamp.call('miIO.config_router', {'ssid': wifi['ssid'], 'passwd': wifi['passwd'], 'uid': 0})
        log(f'{name} {did} config_router answer {json.dumps(answer)}')
        if answer.get('result') in (['ok'], 'ok'):
            good('Zugangsdaten gesendet, die Lampe hat sie angenommen.')
        else:
            bad(f'Antwort der Lampe: {answer}')
    except TimeoutError:
        log(f'{name} {did} config_router no answer')
        step('Zugangsdaten gesendet, aber keine Bestätigung erhalten (die Lampe wechselt oft sofort das WLAN).')
    except ValueError as error:
        log(f'{name} {did} config_router unreadable answer {error}')
        step(f'Zugangsdaten gesendet, die Antwort der Lampe war nicht lesbar ({error}).')

    step('Warte, ob die Lampe ihr Einrichtungs-WLAN verlässt ...')
    left = left_setup_wifi(host)
    log(f'{name} {did} left setup wifi: {left}')
    if not left:
        raise Abort('Die Lampe ist noch im Einrichtungsmodus, sie hat die WLAN-Daten nicht übernommen.\n'
                    f'Token: {token.hex()}\nEinzelheiten stehen in ~/lampe_offline.log')
    good(f"Die Lampe meldet sich im WLAN „{wifi['ssid']}“ an.")

    print()
    if lan_control:
        box([f'Alles okay: Yeelight {did}', 'LAN-Steuerung ist an.',
             'In Pipidy mit ihrer IP-Adresse eintragen.'], 'FERTIG')


def setup_pipi(name, fixed_host):
    """A Pipi lamp only accepts control once bound to a Mi account, so here we just
    read the token of an already bound lamp that is already on the network."""
    heading(f'{name}: Token auslesen')
    bad('Eine Pipi-Lampe nimmt Steuerbefehle nur an, wenn sie in der Mi-Home-App an')
    note('ein Konto gebunden ist. Das Binden braucht einmal Internet. Dieses Skript')
    note('richtet die Lampe nicht ein, es liest nur den Token einer bereits gebundenen')
    note('Lampe, die schon in deinem WLAN hängt.')
    print()
    host = fixed_host or ask('IP-Adresse der Lampe im WLAN: ').strip()
    if not host:
        raise Abort('Keine IP-Adresse eingegeben.')
    step(f'Lampe wird unter {host} gesucht ...')
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(2)
    hello = exchange(sock, host, HELLO)
    if hello is None or len(hello) < 32 or hello[:2] != b'\x21\x31':
        raise Abort('Unter dieser Adresse antwortet keine Lampe.')
    did = struct.unpack('>I', hello[8:12])[0]
    token = hello[16:32]
    good(f'Lampe gefunden, Geräte-ID {did}.')

    if token in (b'\xff' * 16, b'\x00' * 16):
        box(['Diese Lampe gibt ihren Token nicht preis (verdeckt).',
             'Bei gebundenen Lampen ist das normal. Hol den Token',
             'aus deinem Mi-Konto (Xiaomi Cloud Tokens Extractor)',
             f'und trag ihn mit der IP {host}',
             'von Hand in Pipidy ein.', '',
             f'Geräte-ID: {did}'], 'TOKEN VERDECKT')
        return

    # A bound lamp answers get_properties; an unbound one refuses with error -30015.
    controllable = None
    try:
        reply = Lamp(host, token).call('get_properties', [{'siid': 2, 'piid': 1}])
        controllable = 'error' not in reply
    except (ValueError, TimeoutError):
        controllable = None
    path = os.path.expanduser(f'~/miio-{did}.token')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.write(fd, token.hex().encode())
    os.close(fd)
    log(f'{name} {did} token read, controllable {controllable}')

    print()
    if controllable:
        box([f'Pipi-Lampe {did} ist gebunden und steuerbar.', '',
             'Token für Pipidy:', token.hex(), '',
             f'In Pipidy mit IP {host} und diesem Token eintragen.'], 'FERTIG')
    else:
        box([f'Pipi-Lampe {did} antwortet, lehnt Steuerung aber ab.',
             'Sie ist noch nicht in Mi Home an ein Konto gebunden.',
             'Erst in Mi Home hinzufügen, dann hier erneut auslesen.', '',
             'Token dieser Runde:', token.hex()], 'NOCH NICHT GEBUNDEN')


def main():
    fixed_host = sys.argv[1] if len(sys.argv) > 1 else None
    wifi = {}
    while True:
        banner()
        print()
        box(['', '  [1]  Yeelight', '  [2]  Pipi-Lampe', '', '  [0]  Beenden', ''], 'HAUPTMENÜ')
        choice = ask('Auswahl: ').strip()
        if choice == '0':
            return
        if choice not in KINDS:
            continue
        try:
            setup(choice, fixed_host, wifi)
        except Abort as stop:
            print()
            for line in str(stop).split('\n'):
                bad(line)
        print()
        ask('Zurück zum Hauptmenü mit Enter ... ')


if __name__ == '__main__':
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print('\nBeendet.')
