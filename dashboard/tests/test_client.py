import pytest
import requests

from peerlens_dashboard.client import PeerLensClient, PeerLensError


class FakeResponse:
    def __init__(self, status_code, body=None, reason="OK"):
        self.status_code = status_code
        self._body = body
        self.reason = reason

    def json(self):
        if self._body is None:
            raise ValueError("no json")
        return self._body


class FakeSession:
    def __init__(self, response=None, error=None):
        self.response = response
        self.error = error
        self.urls = []

    def get(self, url, timeout):
        self.urls.append(url)
        if self.error:
            raise self.error
        return self.response


def test_builds_urls_from_base():
    session = FakeSession(FakeResponse(200, []))
    client = PeerLensClient("http://api:8080/", session=session)
    client.sectors()
    client.comparison("banks")
    client.history("banks")
    assert session.urls == [
        "http://api:8080/api/sectors",
        "http://api:8080/api/sectors/banks/comparison",
        "http://api:8080/api/sectors/banks/history",
    ]


def test_base_url_from_environment(monkeypatch):
    monkeypatch.setenv("PEERLENS_API_URL", "http://example:9000")
    assert PeerLensClient(session=FakeSession()).base_url == "http://example:9000"


def test_connection_error_becomes_friendly_message():
    client = PeerLensClient("http://api", session=FakeSession(error=requests.ConnectionError()))
    with pytest.raises(PeerLensError, match="Is the service running"):
        client.sectors()


def test_problem_detail_is_surfaced():
    response = FakeResponse(404, {"title": "Not Found", "detail": "Unknown sector: energy"})
    client = PeerLensClient("http://api", session=FakeSession(response))
    with pytest.raises(PeerLensError, match="Unknown sector: energy"):
        client.comparison("energy")


def test_non_json_error_falls_back_to_reason():
    client = PeerLensClient("http://api", session=FakeSession(FakeResponse(502, reason="Bad Gateway")))
    with pytest.raises(PeerLensError, match="502.*Bad Gateway"):
        client.sectors()
