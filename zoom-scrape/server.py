# save this as app.py
import os
import json
import uuid
import pathlib
from azure.identity import DefaultAzureCredential
from azure.storage.blob import BlobServiceClient, ContainerClient, BlobBlock, BlobClient, StandardBlobTier

from flask import Flask, request, jsonify
from werkzeug.utils import secure_filename

app = Flask(__name__)

account_url = "https://sleeve.blob.core.windows.net"
credential = DefaultAzureCredential()

def create_app(test_config=None):
  # create and configure the app
  app = Flask(__name__, instance_relative_config=True)
  app.config.from_mapping(
    SECRET_KEY=str(uuid.uuid4()),
    DATABASE=os.path.join(app.instance_path, 'sleeve.sqlite'),
  )

  if test_config is None:
    # load the instance config, if it exists, when not testing
    app.config.from_pyfile('config.py', silent=True)
  else:
    # load the test config if passed in
    app.config.from_mapping(test_config)

  # ensure the instance folder exists
  try:
    os.makedirs(app.instance_path)
  except OSError:
    pass

  blob_service_client = BlobServiceClient(account_url, credential=credential)
  container_client = blob_service_client.get_container_client(container='sleeve')

  @app.route("/exists")
  def exists():
    uuid = request.args.get('uuid')
    if uuid is None:
      return jsonify(False)
  
    path_raw = request.args.get('path')
    print('exists', path_raw)
    if path_raw is None:
      return jsonify(False)
  
    path = pathlib.Path(uuid) / secure_filename(path_raw)
    exists = container_client.get_blob_client(str(path)).exists()
    print('result', path, exists)
    return jsonify(exists)
  
  @app.route("/upload", methods=['POST'])
  def upload():
    uuid = request.args.get('uuid')
    if uuid is None:
      return 'OK'
  
    pathlib.Path(uuid).mkdir(exist_ok=True)
  
    print('upload', request.files)
    for name, file in request.files.items():
      path = pathlib.Path(uuid) / secure_filename(name)
      exists = container_client.get_blob_client(str(path)).exists()
      if exists:
        continue 

      blob_client = container_client.upload_blob(name=str(path), data=file.stream)
    return 'OK'

  return app
