#!/bin/bash

# MediaMTX (formerly rtsp-simple-server) setup script for Debian/Ubuntu
# This server acts as a bridge for the ProDashcam project.
# It receives a single PIP-mixed RTMP stream from the dashcam and serves it to viewers.

set -e

# Environment checks
command -v curl >/dev/null 2>&1 || { echo "Error: curl is required but not installed. Aborting." >&2; exit 1; }
command -v tar >/dev/null 2>&1 || { echo "Error: tar is required but not installed. Aborting." >&2; exit 1; }

echo "Updating system..."
sudo apt update && sudo apt upgrade -y

echo "Installing additional dependencies..."
sudo apt install -y wget

# Get the latest MediaMTX release
VERSION=$(curl -s https://api.github.com/repos/bluenviron/mediamtx/releases/latest | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/')
FILENAME="mediamtx_${VERSION}_linux_amd64.tar.gz"

echo "Downloading MediaMTX ${VERSION}..."
wget "https://github.com/bluenviron/mediamtx/releases/download/${VERSION}/${FILENAME}"

echo "Extracting..."
mkdir -p ~/mediamtx_server
tar -xf "${FILENAME}" -C ~/mediamtx_server
rm "${FILENAME}"

cd ~/mediamtx_server

echo "Configuring MediaMTX..."
# Note: Default ports are 1935 (RTMP), 8554 (RTSP), 8888 (HLS), 8889 (WebRTC).
# If these ports are already in use, you must edit mediamtx.yml manually.

echo "Creating systemd service..."
sudo tee /etc/systemd/system/mediamtx.service <<EOF
[Unit]
Description=MediaMTX real-time media server
After=network.target

[Service]
ExecStart=$(pwd)/mediamtx $(pwd)/mediamtx.yml
WorkingDirectory=$(pwd)
Restart=always
User=$(whoami)

[Install]
WantedBy=multi-user.target
EOF

echo "Starting MediaMTX service..."
sudo systemctl daemon-reload
sudo systemctl enable mediamtx
sudo systemctl start mediamtx

IP_ADDR=$(hostname -I | awk '{print $1}')

echo "-------------------------------------------------------"
echo "MediaMTX is now running!"
echo "DashCam configuration:"
echo "  RTMP Publish URL: rtmp://$IP_ADDR/live/dashcam"
echo ""
echo "Remote Viewer (on another Android):"
echo "  RTMP URL: rtmp://$IP_ADDR/live/dashcam"
echo ""
echo "You can check status at: http://$IP_ADDR:9997 (if enabled in mediamtx.yml)"
echo "-------------------------------------------------------"
