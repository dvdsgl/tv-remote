"""Generate golden vectors for the Kotlin Companion port, using pyatv 0.18.0 itself.

Run from android/:
  uv run --no-project --with pyatv==0.18.0 --with srptools==1.0.1 \
      python tools/golden.py > app/src/test/resources/companion_golden.json

Every value is fixed (keys, salt, nonces), so the output only changes if pyatv,
srptools, cryptography or plistlib change how they encode things.
"""

import binascii
import json
import sys
from uuid import UUID

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
import srptools.context

import pyatv.auth.hap_srp as hap_srp
from pyatv.auth.hap_srp import SRPAuthHandler, hkdf_expand
from pyatv.auth.hap_tlv8 import TlvValue, read_tlv, write_tlv
from pyatv.auth.server_auth import PIN_CODE
from pyatv.protocols.companion import keyed_archiver
from pyatv.protocols.companion.connection import CompanionConnection, FrameType
from pyatv.protocols.companion.plist_payloads import (
    get_rti_clear_text_payload,
    get_rti_input_text_payload,
)
from pyatv.protocols.companion.server_auth import CompanionServerAuth
from pyatv.support import chacha20, opack

RAW = serialization.Encoding.Raw


def h(b: bytes) -> str:
    return binascii.hexlify(b).decode()


def typed(v):
    """Encode an OPACK value as typed JSON so the Kotlin test can rebuild it exactly."""
    if v is None:
        return {"t": "null"}
    if isinstance(v, bool):
        return {"t": "bool", "v": v}
    if isinstance(v, UUID):
        return {"t": "uuid", "v": str(v)}
    if isinstance(v, int):
        return {"t": "int", "v": str(int(v))}
    if isinstance(v, float):
        return {"t": "float", "v": v}
    if isinstance(v, str):
        return {"t": "str", "v": v}
    if isinstance(v, bytes):
        return {"t": "bytes", "v": h(v)}
    if isinstance(v, list):
        return {"t": "list", "v": [typed(x) for x in v]}
    if isinstance(v, dict):
        return {"t": "dict", "v": [[typed(k), typed(x)] for k, x in v.items()]}
    raise TypeError(type(v))


def seq(n: int, start: int) -> bytes:
    return bytes((start + i) & 0xFF for i in range(n))


# ── OPACK ──

OPACK_MESSAGES = [
    {"_i": "_hidC", "_t": 2, "_c": {"_hBtS": 1, "_hidC": 6}, "_x": 41235},
    {"_i": "_hidT", "_t": 1, "_c": {"_ns": 1234567890123, "_tFg": 1, "_cx": 500, "_tPh": 3, "_cy": 1000}, "_x": 7},
    {"_i": "_mcc", "_t": 2, "_c": {"_mcc": 7, "_skpS": -10.0}, "_x": 65536},
    {"_i": "_interest", "_t": 1, "_c": {"_regEvents": ["_iMC", "SystemStatus", "TVSystemStatus"]}, "_x": 300},
    {"_i": "_touchStart", "_t": 2, "_c": {"_height": 1000.0, "_tFl": 0, "_width": 1000.0}, "_x": 39},
    {"_i": "_sessionStop", "_t": 2, "_c": {"_srvT": "com.apple.tvremoteservices", "_sid": (5555 << 32) | 4000000000}, "_x": 40},
    {
        "_i": "_systemInfo", "_t": 2, "_x": 0xFFFFFFFF,
        "_c": {
            "_bf": 0, "_cf": 512, "_clFl": 128, "_i": "cafecafecafe",
            "_idsID": b"4D797FD3-3538-427E-A47B-A32FC6CF3A6A",
            "_pubID": "FF:70:79:61:74:76", "_sf": 256, "_sv": "170.18",
            "model": "iPhone10,6", "name": "TV Remote",
        },
    },
    {"_pd": seq(300, 1), "_pwTy": 1, "_x": 12, "_auTy": 4},
    {"_pd": seq(70000, 3), "long": "x" * 300, "short": "y" * 32, "mid": "z" * 33},
    {"list": list(range(20)), "dict": {f"k{i}": i for i in range(16)}, "empty": {}, "nolist": []},
    {"flags": [True, False, None], "uuid": UUID("12345678-1234-5678-1234-567812345678"), "neg": 0, "big": 2**64 - 1},
    {"repeat": ["same string", "same string", b"bytesbytes", b"bytesbytes", 1000, 1000], "nested": {"a": {"b": "same string"}}},
    {"unicode": "héllo wörld →", "i8": 0x27, "i9": 0x28, "u8": 0xFF, "u16": 0x100, "u32": 0x10000, "u64": 0x100000000},
]


def opack_vectors():
    out = []
    for msg in OPACK_MESSAGES:
        packed = opack.pack(msg)
        unpacked, rest = opack.unpack(packed)
        assert rest == b""
        out.append({"value": typed(msg), "packed": h(packed), "unpacked": typed(unpacked)})
    # Decoding-only cases pyatv never produces itself: float32, explicit reference forms.
    extra = [
        bytes([0xE2, 0x41, 0x66, 0x35]) + bytes.fromhex("0000c03f") + bytes([0x41, 0x67, 0xA1]),
        bytes([0xE1, 0x43]) + b"abc" + bytes([0xD2, 0x43]) + b"xyz" + bytes([0xC1, 0x01]),
    ]
    for packed in extra:
        unpacked, _ = opack.unpack(packed)
        out.append({"packed": h(packed), "unpacked": typed(unpacked)})
    return out


# ── TLV8 ──

def tlv_vectors():
    items = {
        TlvValue.SeqNo: b"\x03",
        TlvValue.PublicKey: seq(384, 7),
        TlvValue.Proof: seq(64, 9),
        TlvValue.Name: b"",
        0x1B: b"\x01",
    }
    written = write_tlv(items)
    read = read_tlv(written)
    return {
        "items": [[int(k), h(v)] for k, v in items.items()],
        "written": h(written),
        "read": [[int(k), h(v)] for k, v in read.items()],
    }


# ── Primitives ──

def primitive_vectors():
    out = {"hkdf": [], "ed25519": [], "x25519": [], "chacha": []}
    for salt, info, ikm in [
        ("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", seq(32, 1)),
        ("", "ClientEncrypt-main", seq(32, 2)),
        ("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", seq(64, 3)),
    ]:
        out["hkdf"].append({"salt": salt, "info": info, "ikm": h(ikm), "okm": h(hkdf_expand(salt, info, ikm))})

    for i, msg in enumerate([b"", b"abc", seq(1000, 5)]):
        seed = seq(32, 0x40 + i * 3)
        key = Ed25519PrivateKey.from_private_bytes(seed)
        pub = key.public_key().public_bytes(RAW, serialization.PublicFormat.Raw)
        out["ed25519"].append({"seed": h(seed), "public": h(pub), "message": h(msg), "signature": h(key.sign(msg))})

    for i in range(3):
        a = X25519PrivateKey.from_private_bytes(seq(32, 0x10 + i))
        b = X25519PrivateKey.from_private_bytes(seq(32, 0x90 + i))
        out["x25519"].append({
            "private": h(seq(32, 0x10 + i)),
            "public": h(a.public_key().public_bytes(RAW, serialization.PublicFormat.Raw)),
            "peerPublic": h(b.public_key().public_bytes(RAW, serialization.PublicFormat.Raw)),
            "shared": h(a.exchange(b.public_key())),
        })

    # Frame cipher: 12-byte little-endian counter nonce, header as AAD.
    cipher = chacha20.Chacha20Cipher(seq(32, 0x21), seq(32, 0x61), nonce_length=12)
    for i, data in enumerate([b"first", seq(200, 1), b""]):
        aad = bytes([8]) + (len(data) + 16).to_bytes(3, "big")
        out["chacha"].append({"key": h(seq(32, 0x21)), "nonce": h(i.to_bytes(12, "little")), "aad": h(aad),
                              "plain": h(data), "sealed": h(cipher.encrypt(data, aad=aad))})
    # Pairing cipher: fixed 8-byte label nonce, left-padded with four zero bytes.
    cipher = chacha20.Chacha20Cipher8byteNonce(seq(32, 0x33), seq(32, 0x33))
    out["chacha"].append({"key": h(seq(32, 0x33)), "nonce": h(b"\0\0\0\0PS-Msg05"), "aad": "",
                          "plain": h(b"tlv data"), "sealed": h(cipher.encrypt(b"tlv data", nonce=b"PS-Msg05"))})
    return out


# ── SRP / pair-setup / pair-verify against pyatv's own server side ──

class CapturingServer(CompanionServerAuth):
    """pyatv's Companion server auth (used by its fake Apple TV), with sends captured."""

    def __init__(self):
        self.sent = []
        self.keys_out = None
        super().__init__("Fake")

    def send_to_client(self, frame_type, data):
        self.sent.append((frame_type, data))

    def enable_encryption(self, output_key, input_key):
        self.keys_out = (output_key, input_key)


def initialized(ed25519_seed: bytes, x25519_private: bytes) -> SRPAuthHandler:
    """An SRPAuthHandler whose initialize() drew the given keys from os.urandom."""
    handler = SRPAuthHandler()
    queue = [ed25519_seed, x25519_private]
    real = hap_srp.os.urandom
    hap_srp.os.urandom = lambda n: queue.pop(0)
    try:
        handler.initialize()
    finally:
        hap_srp.os.urandom = real
    return handler


def pairing_vectors():
    salt = int.from_bytes(seq(16, 0xA0), "big")
    srptools.context.SRPContext.generate_salt = lambda self: salt

    setup_seed, setup_x = seq(32, 0x51), seq(32, 0x71)
    verify_seed, verify_x = seq(32, 0x81), seq(32, 0xC1)
    pairing_id = b"0F4C8C0E-6F44-4A1B-9F1D-6C3E2B9A7D10"
    name = "TV Remote"
    pin = str(PIN_CODE).zfill(4)

    server = CapturingServer()
    client = initialized(setup_seed, setup_x)
    client.pairing_id = pairing_id

    m1 = write_tlv({TlvValue.Method: b"\x00", TlvValue.SeqNo: b"\x01"})
    server.handle_auth_frame(FrameType.PS_Start, {"_pd": m1})
    m2 = server.sent[-1][1]["_pd"]
    m2t = read_tlv(m2)
    client.step1(pin)
    pub, proof = client.step2(m2t[TlvValue.PublicKey], m2t[TlvValue.Salt])
    session = client._session  # pylint: disable=protected-access
    m3 = write_tlv({TlvValue.SeqNo: b"\x03", TlvValue.PublicKey: pub, TlvValue.Proof: proof})
    server.handle_auth_frame(FrameType.PS_Next, {"_pd": m3})
    m4 = server.sent[-1][1]["_pd"]
    m5 = write_tlv({TlvValue.SeqNo: b"\x05", TlvValue.EncryptedData: client.step3(name=name)})
    server.handle_auth_frame(FrameType.PS_Next, {"_pd": m5})
    m6 = server.sent[-1][1]["_pd"]
    creds = client.step4(read_tlv(m6)[TlvValue.EncryptedData])

    # Wrong PIN: the server answers M3 with an Authentication error.
    bad = CapturingServer()
    wrong = initialized(setup_seed, setup_x)
    bad.handle_auth_frame(FrameType.PS_Start, {"_pd": m1})
    wrong.step1("9999")
    wpub, wproof = wrong.step2(m2t[TlvValue.PublicKey], m2t[TlvValue.Salt])
    bad.handle_auth_frame(FrameType.PS_Next, {"_pd": write_tlv(
        {TlvValue.SeqNo: b"\x03", TlvValue.PublicKey: wpub, TlvValue.Proof: wproof})})
    wrong_m4 = bad.sent[-1][1]["_pd"]

    verifier = initialized(verify_seed, verify_x)
    vpub = verifier._public_bytes  # pylint: disable=protected-access
    v1 = write_tlv({TlvValue.SeqNo: b"\x01", TlvValue.PublicKey: vpub})
    server.handle_auth_frame(FrameType.PV_Start, {"_pd": v1})
    v2 = server.sent[-1][1]["_pd"]
    v2t = read_tlv(v2)
    v3 = write_tlv({TlvValue.SeqNo: b"\x03", TlvValue.EncryptedData: verifier.verify1(
        creds, v2t[TlvValue.PublicKey], v2t[TlvValue.EncryptedData])})
    server.handle_auth_frame(FrameType.PV_Next, {"_pd": v3})
    v4 = server.sent[-1][1]["_pd"]
    out_key, in_key = verifier.verify2("", "ClientEncrypt-main", "ServerEncrypt-main")
    assert server.keys_out == (in_key, out_key)

    return {
        "pin": pin,
        "name": name,
        "pairingId": h(pairing_id),
        "setupSeed": h(setup_seed),
        "srp": {
            "a": h(setup_seed),
            "salt": h(m2t[TlvValue.Salt]),
            "B": h(m2t[TlvValue.PublicKey]),
            "A": h(pub),
            "K": session.key.decode() if isinstance(session.key, bytes) else session.key,
            "M": h(proof),
            "serverProof": h(binascii.unhexlify(session.key_proof_hash)),
        },
        "setup": {"m1": h(m1), "m2": h(m2), "m3": h(m3), "m4": h(m4), "m5": h(m5), "m6": h(m6)},
        "wrongPinM4": h(wrong_m4),
        "credentials": str(creds),
        "verifyPrivate": h(verify_x),
        "verify": {"m1": h(v1), "m2": h(v2), "m3": h(v3), "m4": h(v4)},
        "outputKey": h(out_key),
        "inputKey": h(in_key),
    }


# ── Encrypted frames on the wire ──

class CapturingTransport:
    def __init__(self):
        self.data = []

    def write(self, data):
        self.data.append(data)


def frame_vectors(out_key: bytes, in_key: bytes):
    conn = CompanionConnection(None, "127.0.0.1", 0)
    transport = CapturingTransport()
    conn.connection_made(transport)
    conn.enable_encryption(out_key, in_key)
    sent = []
    for msg in [
        {"_i": "_hidC", "_t": 2, "_c": {"_hBtS": 1, "_hidC": 1}, "_x": 100},
        {"_i": "_hidC", "_t": 2, "_c": {"_hBtS": 2, "_hidC": 1}, "_x": 101},
        {"_i": "_hidT", "_t": 1, "_c": {"_ns": 16000000, "_tFg": 1, "_cx": 10, "_tPh": 1, "_cy": 990}, "_x": 102},
    ]:
        conn.send(FrameType.E_OPACK, opack.pack(msg))
        sent.append({"message": typed(msg), "wire": h(transport.data[-1])})

    # What the Apple TV sends back, encrypted with the opposite keys.
    server = chacha20.Chacha20Cipher(in_key, out_key, nonce_length=12)
    received = []
    for msg in [
        {"_i": "_hidC", "_x": 100, "_t": 3, "_c": {}},
        {"_i": "SystemStatus", "_x": 1234, "_t": 1, "_c": {"state": 1}},
        {"_i": "_tiStart", "_x": 103, "_t": 3, "_ec": 58822, "_ed": "RPErrorDomain", "_em": "No request handler"},
    ]:
        data = opack.pack(msg)
        header = bytes([FrameType.E_OPACK.value]) + (len(data) + 16).to_bytes(3, "big")
        received.append({"message": typed(msg), "wire": h(header + server.encrypt(data, aad=header))})
    return {"sent": sent, "received": received}


# ── Binary plists (RTI keyboard payloads) ──

def plist_vectors():
    import plistlib

    session = b"0123456789abcdef"
    archive = plistlib.dumps(
        {
            "$top": {"sessionUUID": plistlib.UID(1), "documentState": plistlib.UID(2)},
            "$objects": ["$null", session, {"docSt": plistlib.UID(3)}, {"contextBeforeInput": plistlib.UID(4)}, "Typed so far"],
        },
        fmt=plistlib.PlistFormat.FMT_BINARY,
        sort_keys=False,
    )
    uuid_, text = keyed_archiver.read_archive_properties(
        archive, ["sessionUUID"], ["documentState", "docSt", "contextBeforeInput"])
    empty = plistlib.dumps({"$top": {"sessionUUID": plistlib.UID(1)}, "$objects": ["$null", session]},
                           fmt=plistlib.PlistFormat.FMT_BINARY, sort_keys=False)
    return {
        "session": h(session),
        "input": [{"text": t, "payload": h(get_rti_input_text_payload(session, t))}
                  for t in ["a", "hello world", "héllo wörld →", "x" * 40]],
        "clear": h(get_rti_clear_text_payload(session)),
        "archive": h(archive),
        "archiveSession": h(uuid_),
        "archiveText": text,
        "archiveNoText": h(empty),
    }


def main():
    pairing = pairing_vectors()
    out = {
        "opack": opack_vectors(),
        "tlv": tlv_vectors(),
        "primitives": primitive_vectors(),
        "pairing": pairing,
        "frames": frame_vectors(bytes.fromhex(pairing["outputKey"]), bytes.fromhex(pairing["inputKey"])),
        "plist": plist_vectors(),
    }
    json.dump(out, sys.stdout, indent=1, ensure_ascii=False)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
