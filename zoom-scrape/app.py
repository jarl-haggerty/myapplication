# save this as app.py
import json
import pathlib

from flask import Flask, request, jsonify

app = Flask(__name__)

@app.route("/exists")
def exists():
  path_raw = request.args.get('path')
  print('exists', path_raw)
  if path_raw is None:
    return jsonify(False)
  path = pathlib.Path()
  exists = path.exists()
  return jsonify(exists)

@app.route("/upload", methods=['POST'])
def upload():
  print('upload')
  for f in request.files:
    print('  ', f.filename)
  path_raw = request.args.get('path')
  if path_raw is None:
    return jsonify(False)
  path = pathlib.Path()
  exists = path.exists()
  return jsonify(exists)
