import json
import uuid
import logging
import logging.handlers
import sqlite3
import pathlib
import contextlib
import multiprocessing.pool

import requests

logger = logging.getLogger(__name__)

ZOOM_ROOM = 'Jarl Haggerty\'s Zoom Meeting'
DOCUMENTS_ZOOM = pathlib.Path.home() / 'Documents' / 'Zoom'
ONEDRIVE_ZOOM = pathlib.Path.home() / 'OneDrive' / 'Documents' / 'Zoom'
SLEEVE_DIR = pathlib.Path.home() / '.sleeve'
UUID_FILE = SLEEVE_DIR / 'uuid.txt'
SLEEVE_DIR.mkdir(exist_ok=True)

#HOST = 'http://128.91.19.194:8080'
HOST = 'http://127.0.0.1:5000'

def get_uuid() -> uuid.UUID:
  '''
  Make sure UUID_FILE contains a valid UUID and return it.
  '''
  parsed = None
  if UUID_FILE.exists():
    with open(UUID_FILE, 'r') as fid:
      text = fid.read()
      try:
        parsed = uuid.UUID(text)
      except ValueError:
        parsed = None

  if parsed is None:
    uuid_text = str(uuid.uuid4())
    with open(UUID_FILE, 'w') as fid:
      fid.write(uuid_text)

  with open(UUID_FILE, 'r') as fid:
    text = fid.read()
    return uuid.UUID(text)

def main():
  logging.basicConfig(
    format='%(asctime)s %(levelname)-8s %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S',
    handlers=[
      logging.StreamHandler(),
      logging.handlers.RotatingFileHandler(str(SLEEVE_DIR / 'scrape.log'), maxBytes=1000000, backupCount=10)
    ],
    level=logging.DEBUG)

  sql_conn = sqlite3.connect(str(SLEEVE_DIR / 'database.db'))
  cursor = sql_conn.cursor()
  exists = cursor.execute('SELECT * FROM sqlite_master WHERE name =\'seen_files\'').fetchone()
  if not exists:
    cursor.execute('CREATE TABLE seen_files (filename)')
    cursor.execute('CREATE UNIQUE INDEX seen_files_index ON seen_files (filename)')
    sql_conn.commit()

  our_uuid = get_uuid()
  if DOCUMENTS_ZOOM.exists():
    ZOOM = DOCUMENTS_ZOOM
  else:
    ZOOM = ONEDRIVE_ZOOM

  thread_pool = multiprocessing.pool.ThreadPool()
  def exists(path: pathlib.Path) -> bool:
    return requests.get(HOST + '/exists', params={'uuid': str(our_uuid), 'path':str(path.as_posix())}).json()

  discovered = [f.relative_to(ZOOM) for f in ZOOM.rglob(f'*{ZOOM_ROOM}/*')]
  discovered_and_seen = []
  for d in discovered:
    seen = cursor.execute('SELECT * FROM seen_files WHERE filename=?', (str(d),)).fetchone()
    logger.info(f'%s %s', d, seen)
    if seen:
      discovered_and_seen.append(d)
    else:
      cursor.execute('INSERT INTO seen_files VALUES (?)', (str(d),))
  sql_conn.commit()
  logger.info('discovered_and_seen %s', discovered_and_seen)
  if not discovered_and_seen:
    logger.info('All discovered files are new, will upload next cycle')
    return

  does_exist = thread_pool.map(exists, discovered_and_seen)
  if all(does_exist):
    logger.info('All files synced')
    return

  logger.info('%s', does_exist)
  with contextlib.ExitStack() as exit_stack:
    files = {}
    for e, file in zip(does_exist, discovered_and_seen):
      if e:
        continue
      logger.info('upload %s', file)
      fid = open(ZOOM / file, 'rb')
      files[f'{file.as_posix()}'] = fid
      exit_stack.enter_context(fid)
    requests.post(HOST + '/upload', params={'uuid': str(our_uuid)}, files=files)

if __name__ == '__main__':
  main()

