from dataclasses import dataclass
from pathlib import Path
import os


@dataclass(frozen=True)
class Settings:
    data_dir: Path

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(data_dir=Path(os.environ.get("TUBEGO_DATA_DIR", "./data/mobile")).resolve())

    @property
    def database_path(self) -> Path:
        return self.data_dir / "tubego.sqlite3"
