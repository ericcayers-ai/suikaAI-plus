"""Contract gates for Python surfaces (fruit table + version + obs dim)."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_version_matches_gradle() -> None:
    build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'version\s*=\s*"([^"]+)"', build)
    assert m, "Gradle version not found"
    gradle_ver = m.group(1)

    init = (ROOT / "python" / "suika" / "__init__.py").read_text(encoding="utf-8")
    pm = re.search(r'__version__\s*=\s*"([^"]+)"', init)
    assert pm, "Python __version__ not found"
    assert pm.group(1) == gradle_ver

    setup = (ROOT / "python" / "setup.py").read_text(encoding="utf-8")
    sm = re.search(r'version\s*=\s*"([^"]+)"', setup)
    assert sm, "setup.py version not found"
    assert sm.group(1) == gradle_ver

    fallback_src = (
        ROOT / "suika-game" / "src" / "main" / "java" / "dev" / "suika" / "game" / "SuikaVersion.java"
    ).read_text(encoding="utf-8")
    fm = re.search(r'FALLBACK\s*=\s*"([^"]+)"', fallback_src)
    assert fm, "SuikaVersion.FALLBACK not found"
    assert fm.group(1) == gradle_ver


def test_fruit_tiers_match_java_and_json() -> None:
    from suika.env import FRUIT_TIERS, DROPPABLE_TIERS, DOUBLE_WATERMELON_BONUS

    json_text = (ROOT / "suika-assets" / "src" / "main" / "resources" / "fruits.json").read_text(
        encoding="utf-8"
    )
    java_text = (ROOT / "suika-core" / "src" / "main" / "java" / "dev" / "suika" / "core" / "FruitTier.java").read_text(
        encoding="utf-8"
    )

    assert len(FRUIT_TIERS) == 11
    assert DROPPABLE_TIERS == [1, 2, 3, 4, 5]
    assert DOUBLE_WATERMELON_BONUS == 100

    for tier, radius, score in FRUIT_TIERS:
        # fruits.json
        jm = re.search(
            rf'\{{\s*"tier"\s*:\s*{tier}\s*,[^}}]*"radius"\s*:\s*([0-9.]+)[^}}]*"mergeScore"\s*:\s*(-?\d+)',
            json_text,
            re.DOTALL,
        )
        assert jm, f"tier {tier} missing from fruits.json"
        assert abs(float(jm.group(1)) - radius) < 1e-5
        assert int(jm.group(2)) == score

        # FruitTier.java — match the float literal next to the tier ordinal comment-free line
        # e.g. CHERRY (1, 0.59f, 0),
        jvm = re.search(rf'\(\s*{tier}\s*,\s*([0-9.]+)f\s*,\s*(-?\d+)\s*\)', java_text)
        assert jvm, f"tier {tier} missing from FruitTier.java"
        assert abs(float(jvm.group(1)) - radius) < 1e-5
        assert int(jvm.group(2)) == score


def test_state_observation_dim_is_584() -> None:
    from suika.env import StandaloneSimulator, MAX_FRUITS

    sim = StandaloneSimulator(seed=0)
    obs = sim.encode_observation(num_bins=32)
    assert len(obs) == 8 + MAX_FRUITS * 9
    assert len(obs) == 584


def test_make_env_step_finite() -> None:
    import suika

    env = suika.make(action_bins=32, seed=42)
    obs, info = env.reset()
    assert len(obs) == 584
    obs, reward, terminated, truncated, info = env.step(16)
    assert reward == reward  # not NaN
    assert isinstance(terminated, bool)
