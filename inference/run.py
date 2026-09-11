"""Start the inference service using conf/config.yaml from any working directory."""
from __future__ import annotations

from pathlib import Path
import sys

from app.config import ConfigurationError, load_inference_settings


def main() -> int:
    try:
        settings = load_inference_settings()
    except ConfigurationError as error:
        print(f"Inference configuration error: {error}", file=sys.stderr)
        return 2

    import uvicorn

    inference_directory = Path(__file__).resolve().parent
    uvicorn.run(
        "app.main:app",
        app_dir=str(inference_directory),
        host=settings.host,
        port=settings.port,
        reload=settings.reload,
        reload_dirs=[str(inference_directory / "app")] if settings.reload else None,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
