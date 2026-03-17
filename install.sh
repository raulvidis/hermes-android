#!/usr/bin/env bash
# One-liner install for hermes-android plugin into hermes-agent v0.3.0+
#
# Usage:
#   curl -sSL https://raw.githubusercontent.com/raulvidis/hermes-android/main/install.sh | bash
#
set -euo pipefail

PLUGIN_DIR="$HOME/.hermes/plugins/hermes-android"
REPO="https://github.com/raulvidis/hermes-android.git"
TMP_DIR="$(mktemp -d)"

echo "Installing hermes-android plugin..."

# Clone just the plugin directory (shallow, single-branch)
git clone --depth 1 --single-branch "$REPO" "$TMP_DIR" 2>/dev/null

# Copy plugin into place
mkdir -p "$HOME/.hermes/plugins"
rm -rf "$PLUGIN_DIR"
cp -r "$TMP_DIR/hermes-android-plugin" "$PLUGIN_DIR"

# Install Python dependency (aiohttp) if missing
if ! python3 -c "import aiohttp" 2>/dev/null; then
    echo "Installing aiohttp..."
    pip install aiohttp 2>/dev/null || pip3 install aiohttp 2>/dev/null || echo "Warning: could not install aiohttp — install it manually"
fi

# Cleanup
rm -rf "$TMP_DIR"

echo "✓ hermes-android plugin installed to $PLUGIN_DIR"
echo "  Restart hermes-gateway, then run /plugins to verify."
