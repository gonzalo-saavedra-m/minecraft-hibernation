#!/usr/bin/env python3
"""Sends one command over RCON (127.0.0.1:25575 by default) and prints the answer.
Usage: tools/rcon.py <server dir> <command...>   (the password comes from <server dir>/server.properties)
Env: RCON_HOST, RCON_PORT."""
import os, re, socket, struct, sys

props = open(os.path.join(sys.argv[1], "server.properties")).read()
password = re.search(r"^rcon\.password=(.*)$", props, re.M).group(1)


def send(s, req_id, kind, body):
    payload = struct.pack("<ii", req_id, kind) + body.encode() + b"\0\0"
    s.sendall(struct.pack("<i", len(payload)) + payload)


def recv(s):
    def read(n):
        data = b""
        while len(data) < n:
            chunk = s.recv(n - len(data))
            if not chunk:
                sys.exit("rcon: connection closed")
            data += chunk
        return data
    (n,) = struct.unpack("<i", read(4))
    req_id, _ = struct.unpack("<ii", read(8))
    return req_id, read(n - 8)[:-2].decode()


with socket.create_connection((os.environ.get("RCON_HOST", "127.0.0.1"), int(os.environ.get("RCON_PORT", "25575"))), timeout=10) as s:
    send(s, 1, 3, password)
    if recv(s)[0] == -1:
        sys.exit("rcon: wrong password")
    send(s, 2, 2, " ".join(sys.argv[2:]))
    print(recv(s)[1])
