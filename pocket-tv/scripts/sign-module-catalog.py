#!/usr/bin/env python3
"""Sign an explicitly reviewed module release; the private keystore stays outside git."""
import argparse
import base64
import json
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import pkcs12

p = argparse.ArgumentParser()
p.add_argument('--keystore', type=Path, required=True)
p.add_argument('--password-file', type=Path, required=True)
p.add_argument('--release', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--public-key', type=Path)
a = p.parse_args()
payload = json.dumps(json.loads(a.release.read_text()), ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
key, cert, _ = pkcs12.load_key_and_certificates(a.keystore.read_bytes(), a.password_file.read_bytes().strip())
signature = key.sign(payload, padding.PKCS1v15(), hashes.SHA256())
envelope = {'payload': base64.b64encode(payload).decode(), 'signature': base64.b64encode(signature).decode()}
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(envelope, indent=2) + '\n')
if a.public_key:
    a.public_key.write_text(base64.b64encode(cert.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode() + '\n')
print('Signed release catalog:', a.output)
