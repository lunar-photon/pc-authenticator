#!/usr/bin/env bash
# ==============================================================================
# PC Authenticator - Root Setup Wrapper
# Runs the master installer & setup tool from linux/setup.sh
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$SCRIPT_DIR/linux/setup.sh" "$@"
