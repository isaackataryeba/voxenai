"""Entrypoint for Hugging Face Spaces / FastAPI deployment.

Spaces looks for a top-level `app` object (FastAPI/Starlette) in the repository.
This file re-exports the FastAPI app defined in main.py.

It also provides a fallback `__main__` runner for local testing.
"""

from main import app

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=7860, log_level="info")
