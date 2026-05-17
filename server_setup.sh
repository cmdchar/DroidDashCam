#!/bin/bash

# MediaMTX (formerly rtsp-simple-server) setup script for Debian/Ubuntu
# This server acts as a bridge for the DroidDashCam project.

set -e

echo "Updating system..."
sudo apt update && sudo apt upgrade -y

echo "Installing dependencies..."
sudo apt install -y wget tar

# Get the latest MediaMTX release
VERSION=$(curl -s https://api.github.com/repos/bluenviron/mediamtx/releases/latest | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/')
FILENAME="mediamtx_${VERSION}_linux_amd64.tar.gz"

echo "Downloading MediaMTX ${VERSION}..."
wget "https://github.com/bluenviron/mediamtx/releases/download/${VERSION}/${FILENAME}"

echo "Extracting..."
mkdir -p mediamtx
tar -xf "${FILENAME}" -C mediamtx
rm "${FILENAME}"

cd mediamtx

echo "Configuring MediaMTX..."
# Default configuration allows RTMP publishing on port 1935 and viewing via RTSP/RTMP/HLS
# You can edit mediamtx.yml to customize ports or security.

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

echo "-------------------------------------------------------"
echo "MediaMTX is now running!"
echo "RTMP Publish URL: rtmp://$(hostname -I | awk '{print $1}'):1935/live"
echo "RTSP View URL:    rtsp://$(hostname -I | awk '{print $1}'):8554/live"
echo "RTMP View URL:    rtmp://$(hostname -I | awk '{print $1}'):1935/live"
echo "-------------------------------------------------------"
