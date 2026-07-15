"""
Java↔Python bridge client (ROADMAP §II.4 / ADR-0003).

Connects to the Java {@code BridgeServer} sidecar over TCP using the same
length-prefixed float32 wire format as ObservationCodec.java.

Usage::

    bridge = BridgeClient(host="localhost", port=50052)
    bridge.connect()
    obs = bridge.reset(seed=42)
    obs, reward, terminated, truncated, info = bridge.step(16)
    bridge.close()

Start the Java sidecar with::

    ./gradlew :suika-app:run --args="--bridge-port 50052"
"""

from __future__ import annotations

import socket
import struct
from typing import Any


# Must match BridgeServer.CMD_* on the JVM side.
CMD_RESET = 0.0
CMD_STEP = 1.0
CMD_CLOSE = 2.0


class BridgeClient:
    """
    TCP client for the Java sidecar that wraps GymBridge.java.

    Wire protocol (both directions):
        [int32 length][float32 × length]  little-endian

    Commands:
        reset → [0, seed]
        step  → [1, action]
        close → [2]
    """

    def __init__(self, host: str = "localhost", port: int = 50052, timeout: float = 30.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self._sock: socket.socket | None = None

    def connect(self) -> None:
        self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self._sock.settimeout(self.timeout)

    def close(self) -> None:
        if self._sock is not None:
            try:
                try:
                    self._send_floats([CMD_CLOSE])
                except Exception:
                    pass
                self._sock.close()
            finally:
                self._sock = None

    def is_connected(self) -> bool:
        return self._sock is not None

    # ------------------------------------------------------------------
    # Gymnasium-style API mirroring GymBridge.java
    # ------------------------------------------------------------------

    def reset(self, seed: int = 0) -> list[float]:
        """Send reset command; return initial observation as float list."""
        self._send_floats([CMD_RESET, float(seed)])
        return self._recv_floats()

    def step(self, action: float) -> tuple[list[float], float, bool, bool, dict]:
        """Send action; return (obs, reward, terminated, truncated, info)."""
        self._send_floats([CMD_STEP, float(action)])
        payload = self._recv_floats()
        # Protocol: [obs... | reward | terminated | truncated | merges_this_step]
        n_obs = len(payload) - 4
        if n_obs < 1:
            raise ValueError(f"Step payload too short: {len(payload)}")
        obs = payload[:n_obs]
        reward = payload[n_obs]
        terminated = bool(payload[n_obs + 1] > 0.5)
        truncated = bool(payload[n_obs + 2] > 0.5)
        merges = int(payload[n_obs + 3])
        return obs, reward, terminated, truncated, {"merges": merges}

    # ------------------------------------------------------------------

    def _send_floats(self, values: list[float]) -> None:
        if self._sock is None:
            raise RuntimeError("Not connected; call connect() first")
        buf = struct.pack("<i", len(values))
        buf += struct.pack(f"<{len(values)}f", *values)
        self._sock.sendall(buf)

    def _recv_floats(self) -> list[float]:
        if self._sock is None:
            raise RuntimeError("Not connected; call connect() first")
        raw_len = self._recv_exact(4)
        n = struct.unpack("<i", raw_len)[0]
        if n < 0 or n > 100_000:
            raise ValueError(f"Suspicious payload length: {n}")
        raw = self._recv_exact(n * 4)
        return list(struct.unpack(f"<{n}f", raw))

    def _recv_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise EOFError("Connection closed by peer")
            buf.extend(chunk)
        return bytes(buf)


class JavaBackedSuikaEnv:
    """
    Thin Gymnasium-style wrapper around :class:`BridgeClient`.

    Delegates obs/step to the Java sidecar; all game logic runs there.
    Use this when you want gradient-based deep-RL training to match
    the exact physics of the shipped game.
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 50052,
        action_bins: int = 32,
        action_space_type: str = "discrete",
        timeout: float = 30.0,
    ):
        self._client = BridgeClient(host, port, timeout=timeout)
        self._connected = False
        self.action_bins = action_bins
        self.action_space_type = action_space_type
        self._obs_dim = 584

    def connect(self) -> None:
        self._client.connect()
        self._connected = True

    def reset(self, seed: int = 0) -> tuple[list[float], dict]:
        if not self._connected:
            self.connect()
        obs = self._client.reset(seed)
        self._obs_dim = len(obs)
        return obs, {}

    def step(self, action: Any) -> tuple[list[float], float, bool, bool, dict]:
        if not self._connected:
            raise RuntimeError("Not connected; call reset() or connect() first")
        a = float(action) if not hasattr(action, "__float__") else float(action)
        return self._client.step(a)

    def close(self) -> None:
        self._client.close()
        self._connected = False

    @property
    def observation_dim(self) -> int:
        return self._obs_dim
