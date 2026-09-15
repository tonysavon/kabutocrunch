"""Generate a redistributable 8 KiB fixture loaded at $4000."""
from pathlib import Path
root = Path(__file__).resolve().parent
data = bytes(range(256)) * 16 + b"Kabutocrunch" * 256 + b"Z" * 1024
(root / "data.bin").write_bytes(data)
(root / "data.prg").write_bytes(bytes([0x00, 0x40]) + data)
