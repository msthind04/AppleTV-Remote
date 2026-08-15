"""Generate crypto/serialization conformance vectors from pyatv.

Usage:
    pip install pyatv
    python tools/generate_vectors.py > protocol/src/test/resources/vectors.json
"""

import binascii
import hashlib
import json
from pyatv.support import opack
from pyatv.auth.hap_srp import hkdf_expand
from pyatv.auth.hap_tlv8 import write_tlv, read_tlv
from pyatv.support import chacha20
from srptools import SRPClientSession, SRPContext, constants

h = lambda b: binascii.hexlify(b).decode()
V = {}

# ---- OPACK vectors ----
opack_cases = [
    None, True, False, 0, 1, 39, 40, 255, 256, 65535, 65536, 4294967296,
    "", "hello", "a"*32, "b"*33, "c"*300,
    b"", b"\x01\x02\x03", b"\xff"*33,
    [1,2,3], [],
    {"_i": "_hidC", "_t": 2, "_c": {"_hBtS": 1, "_hidC": 6}},
    {"_pd": b"\x06\x01\x01\x00\x01\x00", "_pwTy": 1},
    ["dup","dup","dup"],
    {"a": [1, {"b": "c"}], "d": None, "e": True},
    list(range(20)),
    {str(i): i for i in range(18)},
]
V["opack"] = []
for c in opack_cases:
    try:
        packed = opack.pack(c)
        V["opack"].append({"desc": repr(c)[:60], "hex": h(packed)})
    except Exception as e:
        V["opack"].append({"desc": repr(c)[:60], "error": str(e)})

# ---- TLV8 vectors ----
V["tlv8"] = [
    {"desc": "simple", "hex": h(write_tlv({0x06: b"\x01", 0x00: b"\x00"}))},
    {"desc": "long260", "hex": h(write_tlv({0x03: b"\xAB"*260}))},
    {"desc": "empty_val", "hex": h(write_tlv({0x06: b""}))},
]

# ---- HKDF vectors ----
V["hkdf"] = []
for salt, info, secret in [
    ("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", b"\x01"*64),
    ("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", b"\x02"*32),
    ("", "ClientEncrypt-main", b"\x03"*32),
    ("", "ServerEncrypt-main", b"\x03"*32),
]:
    V["hkdf"].append({"salt": salt, "info": info, "secret": h(secret),
                      "out": h(hkdf_expand(salt, info, secret))})

# ---- ChaCha20-Poly1305 vectors ----
key = bytes(range(32))
c = chacha20.Chacha20Cipher8byteNonce(key, key)
V["chacha_named"] = {"key": h(key), "nonce": "PS-Msg05", "pt": h(b"hello world"),
                     "ct": h(c.encrypt(b"hello world", nonce=b"PS-Msg05"))}
c2 = chacha20.Chacha20Cipher(key, key, nonce_length=12)
aad = bytes([8,0,0,11])
V["chacha_counter"] = {"key": h(key), "aad": h(aad), "pt": h(b"counter-msg"),
                       "ct0": h(c2.encrypt(b"counter-msg", aad=aad)),
                       "ct1": h(c2.encrypt(b"counter-msg", aad=aad))}

# ---- SRP vectors: fixed a, salt, B, pin ----
auth_private = bytes(range(32))          # acts as SRP 'a'
salt = bytes([0x00] + list(range(1,16))) # deliberately leading-zero salt
B = bytes([0x07] * 384)
pin = "1234"
ctx = SRPContext("Pair-Setup", pin, prime=constants.PRIME_3072,
                 generator=constants.PRIME_3072_GEN, hash_func=hashlib.sha512)
sess = SRPClientSession(ctx, binascii.hexlify(auth_private).decode())
sess.process(h(B), h(salt))
V["srp"] = {
    "a": h(auth_private), "salt": h(salt), "B": h(B), "pin": pin,
    "A": h(binascii.unhexlify(sess.public)),
    "K": h(binascii.unhexlify(sess.key)),
    "M1": h(binascii.unhexlify(sess.key_proof)),
    "M2": h(binascii.unhexlify(sess.key_proof_hash)),
}

print(json.dumps(V, indent=1))
