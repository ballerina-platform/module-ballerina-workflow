#!/usr/bin/env bash
# Generates self-signed certificates for mTLS integration testing.
#
# Produces in CERTS_DIR:
#   ca.crt          — CA certificate (trusted by nginx and the Ballerina client)
#   server.crt/.key — nginx TLS server certificate (signed by the CA)
#   client.crt/.key — Ballerina client certificate for mTLS (signed by the CA)
#
# Usage:
#   ./generate-certs.sh [<output-dir>]
#   Default output dir: ./certs

set -euo pipefail

CERTS_DIR="${1:-./certs}"
mkdir -p "$CERTS_DIR"

echo "Generating test certificates in ${CERTS_DIR} ..."

# --- CA ---
openssl req -x509 -newkey rsa:2048 -days 3650 -nodes \
  -keyout "${CERTS_DIR}/ca.key" \
  -out    "${CERTS_DIR}/ca.crt" \
  -subj   "/C=US/O=TemporalTest/CN=Test CA" \
  2>/dev/null

# --- Server (nginx) ---
openssl req -newkey rsa:2048 -nodes \
  -keyout "${CERTS_DIR}/server.key" \
  -out    "${CERTS_DIR}/server.csr" \
  -subj   "/C=US/O=TemporalTest/CN=localhost" \
  2>/dev/null

openssl x509 -req -days 3650 \
  -in     "${CERTS_DIR}/server.csr" \
  -CA     "${CERTS_DIR}/ca.crt" \
  -CAkey  "${CERTS_DIR}/ca.key" \
  -CAcreateserial \
  -out    "${CERTS_DIR}/server.crt" \
  -extfile <(printf "subjectAltName=IP:127.0.0.1,DNS:localhost") \
  2>/dev/null

# --- Client ---
openssl req -newkey rsa:2048 -nodes \
  -keyout "${CERTS_DIR}/client.key" \
  -out    "${CERTS_DIR}/client.csr" \
  -subj   "/C=US/O=TemporalTest/CN=Ballerina Test Client" \
  2>/dev/null

openssl x509 -req -days 3650 \
  -in     "${CERTS_DIR}/client.csr" \
  -CA     "${CERTS_DIR}/ca.crt" \
  -CAkey  "${CERTS_DIR}/ca.key" \
  -CAcreateserial \
  -out    "${CERTS_DIR}/client.crt" \
  2>/dev/null

# Remove intermediary files
rm -f "${CERTS_DIR}"/*.csr "${CERTS_DIR}"/*.srl

echo "Done."
echo "  CA cert    : ${CERTS_DIR}/ca.crt"
echo "  Server cert: ${CERTS_DIR}/server.crt (+ server.key)"
echo "  Client cert: ${CERTS_DIR}/client.crt (+ client.key)"
