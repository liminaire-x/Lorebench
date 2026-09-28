# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""A stand-in for the game's editor API, to click through the editor without a game.

Serves editor/dist/index.html (build it first: `npm run build` in editor/) with fixed
answers for /api/*. POST bodies (publish, quest reset) are saved under build/mock/ so
you can check what the editor sent. Needs only the standard library. Run from the
repository root:

    python tools/mock_server.py      then open http://127.0.0.1:5174
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / 'editor' / 'dist' / 'index.html'
SAVED = ROOT / 'build' / 'mock'
PORT = 5174

NPC = 'npc_farmer01'
API = {
    '/api/health': {'status': 'ok'},
    '/api/schema': {'nodes': []},
    '/api/graphs': {'format': 1, 'graphs': []},
    '/api/npcs': {'format': 1, 'npcs': [{'id': NPC, 'name': '농부', 'model': 'chief'}]},
    '/api/npc-placements': {},
    '/api/quests': {'format': 1, 'quests': [
        {'id': 'quest_farm0001', 'title': '밭일 배우기', 'giver': NPC,
         'supplies': [{'item': 'minecraft:wheat_seeds', 'count': 5}],
         'stages': [{'id': 'stage_farm0001', 'text': 'wheat',
                     'goals': [{'harvest': 'minecraft:wheat', 'count': 10}]},
                    {'id': 'stage_farm0002', 'text': 'tell', 'to': NPC}],
         'rewards': [{'item': 'minecraft:iron_hoe', 'count': 1}]},
        {'id': 'quest_cows0001', 'title': 'cows', 'giver': NPC,
         'stages': [{'id': 'stage_cows0001', 'text': 'breed', 'goals': [{'breed': 'minecraft:cow', 'count': 2}]}],
         'rewards': []}]},
    '/api/players': {'players': ['Dev1']},
    '/api/held-item': {'item': 'minecraft:iron_sword[custom_name=\'"Blade"\']'},
    '/api/crops': {'crops': [{'id': 'minecraft:wheat', 'name': 'Wheat Crops'},
                             {'id': 'minecraft:carrots', 'name': 'Carrots'},
                             {'id': 'minecraft:potatoes', 'name': 'Potatoes'}]},
    '/api/animals': {'animals': [{'id': 'minecraft:cow', 'name': 'Cow'}, {'id': 'minecraft:sheep', 'name': 'Sheep'}]},
    '/api/quest-players': {'players': [
        {'uuid': '00000000-0000-0000-0000-000000000001', 'name': 'Dev1', 'online': True, 'state': 'done', 'progress': {}},
        {'uuid': '00000000-0000-0000-0000-000000000002', 'name': 'Dev2', 'online': False, 'state': 'active',
         'progress': {'harvest:minecraft:wheat': 3}, 'stage': 'stage_farm0001'}]},
}
POSTS = {'/api/publish': ('publish.json', {'accepted': True}), '/api/quest-reset': ('quest-reset.json', {'ok': True})}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, content_type='application/json; charset=utf-8'):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split('?')[0]
        if path in API:
            return self._send(200, API[path])
        return self._send(200, PAGE.read_bytes(), 'text/html; charset=utf-8')

    def do_POST(self):
        path = self.path.split('?')[0]
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if path not in POSTS:
            return self._send(404, {'error': 'unknown ' + path})
        name, answer = POSTS[path]
        SAVED.mkdir(parents=True, exist_ok=True)
        (SAVED / name).write_bytes(body)
        return self._send(200, answer)

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    print(f'Mock editor API at http://127.0.0.1:{PORT} (POST bodies go to {SAVED})')
    HTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
