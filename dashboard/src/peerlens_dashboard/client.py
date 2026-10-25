"""Thin HTTP client for the Peer Lens API. All business logic stays in the Java service."""

from __future__ import annotations

import os
from typing import Any

import requests

DEFAULT_BASE_URL = "http://localhost:8080"


class PeerLensError(RuntimeError):
    """Raised when the API is unreachable or answers with an error."""


class PeerLensClient:
    def __init__(
        self,
        base_url: str | None = None,
        session: requests.Session | None = None,
        timeout: float = 10.0,
    ) -> None:
        self.base_url = (base_url or os.environ.get("PEERLENS_API_URL") or DEFAULT_BASE_URL).rstrip("/")
        self.session = session or requests.Session()
        self.timeout = timeout

    def sectors(self) -> list[dict[str, Any]]:
        return self._get("/api/sectors")

    def comparison(self, sector: str) -> dict[str, Any]:
        return self._get(f"/api/sectors/{sector}/comparison")

    def history(self, sector: str) -> dict[str, Any]:
        return self._get(f"/api/sectors/{sector}/history")

    def _get(self, path: str) -> Any:
        url = f"{self.base_url}{path}"
        try:
            response = self.session.get(url, timeout=self.timeout)
        except requests.RequestException as exc:
            raise PeerLensError(
                f"Could not reach the Peer Lens API at {self.base_url}. Is the service running?"
            ) from exc
        if response.status_code != 200:
            detail = _problem_detail(response)
            raise PeerLensError(f"The API returned {response.status_code} for {path}: {detail}")
        return response.json()


def _problem_detail(response: requests.Response) -> str:
    """Pull the human-readable message out of an RFC 9457 problem response, if there is one."""
    try:
        body = response.json()
    except ValueError:
        return response.reason or "no detail"
    if isinstance(body, dict):
        return str(body.get("detail") or body.get("title") or "no detail")
    return "no detail"
