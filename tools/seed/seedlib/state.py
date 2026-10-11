"""What earlier runs created (media assets by image hash). Services stay the source of truth for everything else:
the seeder looks records up through their APIs, so deleting this file only costs re-uploads."""
from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any


class State:
    def __init__(self, path: str | os.PathLike[str]):
        self.path = Path(path)
        data: dict[str, Any] = {}
        try:
            data = json.loads(self.path.read_text())
        except (OSError, ValueError):
            pass
        self.assets: dict[str, dict[str, Any]] = data.get("assets", {})
        # Every image a run used (sha256 -> ImageFile fields + usedBy), so ATTRIBUTION.md covers all runs.
        self.images: dict[str, dict[str, Any]] = data.get("images", {})

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=self.path.parent, suffix=".json")
        with os.fdopen(fd, "w") as f:
            json.dump({"assets": self.assets, "images": self.images}, f, indent=1, sort_keys=True)
        os.replace(tmp, self.path)
