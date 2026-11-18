#!/bin/bash
# uso: qemu_shot.sh <imagem> <bios|uefi> <saida.ppm> [segundos]
IMG=$1; MODE=$2; OUT=$3; WAIT=${4:-25}
SOCK=$(mktemp -u /tmp/qmon.XXXX)
FW=()
if [ "$MODE" = uefi ]; then
  cp /usr/share/OVMF/OVMF_VARS_4M.fd /tmp/vars.$$.fd
  FW=(-drive if=pflash,format=raw,readonly=on,file=/usr/share/OVMF/OVMF_CODE_4M.fd -drive if=pflash,format=raw,file=/tmp/vars.$$.fd)
fi
qemu-system-x86_64 -m 1024 -machine q35 -accel tcg "${FW[@]}" \
  -drive file="$IMG",format=raw,if=none,id=usb -device qemu-xhci -device usb-storage,drive=usb,bootindex=1 \
  -display none -vga std -monitor unix:$SOCK,server,nowait -no-reboot &
PID=$!
sleep $WAIT
echo "screendump $OUT" | python3 -c "
import socket,sys,time
s=socket.socket(socket.AF_UNIX); s.connect('$SOCK'); time.sleep(0.5); s.recv(4096)
s.send(sys.stdin.read().encode()+b'\n'); time.sleep(1.5); s.send(b'quit\n'); time.sleep(0.5)"
wait $PID 2>/dev/null
rm -f /tmp/vars.$$.fd
