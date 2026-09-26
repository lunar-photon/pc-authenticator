#!/usr/bin/env bash
# ==============================================================================
# PC Authenticator - Root Setup Wrapper
# Runs the master installer & setup tool from linux/setup.sh
# ==============================================================================

SCRIPT_DIR="$(dirname "$(realpath "${BASH_SOURCE[0]}")")"
exec bash "$SCRIPT_DIR/linux/setup.sh" "$@"
