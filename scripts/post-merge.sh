#!/bin/bash
set -e

# Post-merge setup script for Agent Ultra
# Runs after task merges to ensure environment consistency

echo "=== Post-Merge Setup Starting ==="

# Install/update npm dependencies
if [ -f "package.json" ]; then
  echo "Installing npm dependencies..."
  npm install --no-optional
fi

# Run TypeScript type check on key files to catch compilation errors early
if command -v tsc &> /dev/null; then
  echo "Running TypeScript type check..."
  tsc --noEmit 2>&1 | head -50 || true
fi

echo "=== Post-Merge Setup Complete ==="
