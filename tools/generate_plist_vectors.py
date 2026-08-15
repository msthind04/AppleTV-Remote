"""Generate binary-plist / RTI conformance vectors from pyatv.

The Kotlin implementation is checked byte-for-byte against these, because a
malformed NSKeyedArchiver payload is rejected silently by tvOS -- equality with
a known-good encoder is the only cheap way to be confident.

Usage:
    pip install pyatv
    python tools/generate_plist_vectors.py > \
        protocol/src/test/resources/plistvectors.json
"""

import binascii
import json
import plistlib

from pyatv.protocols.companion.plist_payloads import (
    get_rti_clear_text_payload,
    get_rti_input_text_payload,
)


def hexs(data: bytes) -> str:
    return binascii.hexlify(data).decode()


def main() -> None:
    uuid = bytes(range(16))
    vectors = {
        "clear": hexs(get_rti_clear_text_payload(uuid)),
        "input_hello": hexs(get_rti_input_text_payload(uuid, "hello")),
        # Non-ASCII forces UTF-16BE string encoding rather than the ASCII path.
        "input_unicode": hexs(get_rti_input_text_payload(uuid, "café – naïve")),
        # >= 15 characters pushes the length out into a follow-on integer.
        "input_long": hexs(get_rti_input_text_payload(uuid, "x" * 300)),
        "input_empty": hexs(get_rti_input_text_payload(uuid, "")),
        "uuid": hexs(uuid),
        # A generic case exercising the codec itself rather than RTI.
        "generic": hexs(
            plistlib.dumps(
                {
                    "a": 1,
                    "b": [True, False, None],
                    "c": b"\x01\x02",
                    "d": "x" * 20,
                    "e": 3.5,
                    "f": {"g": 65536},
                },
                fmt=plistlib.PlistFormat.FMT_BINARY,
                sort_keys=False,
            )
        ),
    }
    print(json.dumps(vectors, indent=1))


if __name__ == "__main__":
    main()
