#!/bin/zsh
# Double-click launcher for macOS. Edit config/uploader.json once before use.
cd "$(dirname "$0")/.." || exit 1
python3 tools/upload_logs.py "$@"
status=$?
if [ $status -ne 0 ]; then
  echo ""
  echo "Upload failed with status $status. Logs remain queued; run again later."
fi
echo ""
read -k 1 "?Press any key to close..."
exit $status
