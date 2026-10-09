from dataclasses import dataclass
from pathlib import Path
import os

@dataclass(frozen=True)
class Settings:
    data_dir: Path
    public_url: str = "https://localhost"
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_username: str = ""
    smtp_password: str = ""
    smtp_sender: str = ""
    smtp_tls: bool = True

    @classmethod
    def from_env(cls):
        return cls(Path(os.environ.get("TUBEGO_DATA_DIR", "./data/mobile")).resolve(),
            os.environ.get("TUBEGO_PUBLIC_URL", "https://localhost").rstrip("/"),
            os.environ.get("TUBEGO_SMTP_HOST", ""), int(os.environ.get("TUBEGO_SMTP_PORT", "587")),
            os.environ.get("TUBEGO_SMTP_USERNAME", ""), os.environ.get("TUBEGO_SMTP_PASSWORD", ""),
            os.environ.get("TUBEGO_SMTP_SENDER", ""), os.environ.get("TUBEGO_SMTP_TLS", "true").lower() == "true")

    @property
    def database_path(self):
        return self.data_dir / "tubego.sqlite3"
