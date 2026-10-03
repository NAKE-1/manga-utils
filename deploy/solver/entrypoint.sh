#!/bin/sh
# No browser/Xvfb — curl_cffi is a plain HTTP client. Just launch the Flask app.
set -e
exec python app.py
