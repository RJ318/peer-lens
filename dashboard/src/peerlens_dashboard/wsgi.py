"""Entry point for a production WSGI server: gunicorn peerlens_dashboard.wsgi:server"""

from .app import create_app

server = create_app().server
