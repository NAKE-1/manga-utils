#!/bin/sh
# SeleniumBase manages its own Xvfb (Driver(..., xvfb=True)) so the display is sized right for the
# captcha-clicking (PyAutoGUI). We just launch the Flask app.
set -e
exec python app.py
