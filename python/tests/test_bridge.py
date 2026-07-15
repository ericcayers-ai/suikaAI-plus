"""Python-side bridge client protocol unit tests (no JVM required)."""

from __future__ import annotations

import struct
from unittest.mock import MagicMock

import pytest

from suika.bridge import CMD_CLOSE, CMD_RESET, CMD_STEP, BridgeClient


def test_command_opcodes_match_java_contract() -> None:
    assert CMD_RESET == 0.0
    assert CMD_STEP == 1.0
    assert CMD_CLOSE == 2.0


def test_bridge_client_pack_reset_frame() -> None:
    client = BridgeClient(host="127.0.0.1", port=9)
    sent: list[bytes] = []

    sock = MagicMock()
    sock.sendall = lambda b: sent.append(b)
    # reset response: length-prefixed 2 floats
    resp = struct.pack("<i", 2) + struct.pack("<2f", 0.1, 0.2)
    sock.recv = MagicMock(side_effect=[resp[:4], resp[4:]])
    client._sock = sock

    obs = client.reset(seed=42)
    assert len(sent) == 1
    frame = sent[0]
    n = struct.unpack_from("<i", frame, 0)[0]
    vals = struct.unpack_from(f"<{n}f", frame, 4)
    assert vals == (CMD_RESET, 42.0)
    assert obs == pytest.approx([0.1, 0.2])


def test_make_rejects_unknown_backend() -> None:
    import suika

    with pytest.raises(ValueError, match="Unknown backend"):
        suika.make(backend="rust")
