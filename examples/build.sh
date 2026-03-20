#!/bin/bash

# Set script to exit immediately on error
set -e

# Define directories
BAL_EXAMPLES_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BAL_CENTRAL_DIR="$HOME/.ballerina/repositories/central.ballerina.io"
BAL_HOME_DIR="$BAL_EXAMPLES_DIR/../ballerina"

# Validate input command
if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <build|run>"
  exit 1
fi

case "$1" in
  build)
    BAL_CMD="build"
    ;;
  run)
    BAL_CMD="run"
    ;;
  *)
    echo "Invalid command provided: '$1'. Please provide 'build' or 'run' as the command."
    exit 1
    ;;
esac

# Read Ballerina package name from Ballerina.toml
if [[ ! -f "$BAL_HOME_DIR/Ballerina.toml" ]]; then
  echo "Error: Ballerina.toml not found in $BAL_HOME_DIR"
  exit 1
fi
BAL_PACKAGE_NAME=$(awk -F'"' '/^name/ {print $2}' "$BAL_HOME_DIR/Ballerina.toml")

# Validate the package name before using it in any destructive operations
if [[ -z "$BAL_PACKAGE_NAME" ]]; then
  echo "Error: Could not read package name from Ballerina.toml"
  exit 1
fi
if ! [[ "$BAL_PACKAGE_NAME" =~ ^[a-zA-Z0-9_.:-]+$ ]]; then
  echo "Error: Invalid package name '$BAL_PACKAGE_NAME' — expected alphanumeric, underscore, dot, colon, or hyphen characters only"
  exit 1
fi

# Push the package to the local repository
echo "Packing and pushing the Ballerina package..."
cd "$BAL_HOME_DIR"
bal pack
bal push --repository=local

# Remove cache directories in the central repository
echo "Cleaning cache directories in the central repository..."
cacheDirs=$(find "$BAL_CENTRAL_DIR" -type d -name "cache-*" 2>/dev/null) || true
for dir in $cacheDirs; do
  if [[ -d "$dir" ]]; then
    rm -r "$dir"
    echo "Removed cache directory: $dir"
  fi
done
echo "Successfully cleaned the cache directories."

# Create the package directory in the central repository
echo "Updating the central repository..."
BAL_DESTINATION_DIR="$BAL_CENTRAL_DIR/bala/ballerina/$BAL_PACKAGE_NAME"
BAL_SOURCE_DIR="$HOME/.ballerina/repositories/local/bala/ballerina/$BAL_PACKAGE_NAME"
if [[ -d "$BAL_DESTINATION_DIR" ]]; then
  rm -r "$BAL_DESTINATION_DIR"
fi
if [[ -d "$BAL_SOURCE_DIR" ]]; then
  cp -r "$BAL_SOURCE_DIR" "$BAL_DESTINATION_DIR"
  echo "Successfully updated the local central repository."
else
  echo "Warning: Source directory $BAL_SOURCE_DIR does not exist."
fi

echo "Source Directory: $BAL_SOURCE_DIR"
echo "Destination Directory: $BAL_DESTINATION_DIR"

# Loop through examples in the examples directory and execute the command
echo "Processing examples in the examples directory..."
cd "$BAL_EXAMPLES_DIR"
find "$BAL_EXAMPLES_DIR" -maxdepth 1 -mindepth 1 -type d -not -name 'build' -print0 | while IFS= read -r -d '' dir; do
  echo "Processing example: $dir"
  (cd "$dir" && bal "$BAL_CMD")
done

# Remove generated JAR files in the Ballerina home directory
echo "Cleaning up generated JAR files..."
find "$BAL_HOME_DIR" -maxdepth 1 -type f -name "*.jar" -exec rm {} \;
echo "Successfully removed generated JAR files."

echo "Script execution completed successfully."
