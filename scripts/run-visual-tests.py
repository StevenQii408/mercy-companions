#!/usr/bin/env python3
"""Run the current bag and vanilla UI fixture in its isolated client directory."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).with_name('run-ui-tests.py')), run_name='__main__')
