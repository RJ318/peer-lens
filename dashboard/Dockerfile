FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY pyproject.toml ./
COPY src/ src/
RUN pip install --no-cache-dir .

RUN useradd --system --uid 10001 peerlens
USER peerlens

EXPOSE 8050
CMD ["gunicorn", "--bind", "0.0.0.0:8050", "--workers", "2", "--access-logfile", "-", "peerlens_dashboard.wsgi:server"]
