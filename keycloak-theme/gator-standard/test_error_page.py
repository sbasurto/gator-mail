"""Read-only HTTP regression for standard browser errors and token API responses."""
import json
import sys
import urllib.error
import urllib.request

base = sys.argv[1].rstrip('/')
assert base.startswith(('https://', 'http://127.0.0.1:', 'http://localhost:'))


def request(path, accept, data=None):
    req = urllib.request.Request(base + path, data=data, headers={'Accept': accept})
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers.get('Content-Type', ''), response.read().decode()


status, content_type, body = request('/protocol/openid-connect/token', 'text/html')
assert status == 405 and content_type.startswith('text/html'), (status, content_type)
assert '/login/gator-standard/' in body, 'Browser errors must use the standard theme'
assert 'gator-login-brands' in body and '/img/soft-gator.png' in body
assert 'Abre tu aplicación' in body, 'A browser GET must explain how to start login'
assert '<title>Abre tu aplicación | Soft Gator</title>' in body
assert 'Gator Mail' not in body and 'error interno del servidor' not in body

status, content_type, body = request('/protocol/openid-connect/token', 'application/json')
assert status == 405 and content_type.startswith('application/json')
assert json.loads(body)['error'] == 'HTTP 405 Method Not Allowed'

status, content_type, body = request('/protocol/openid-connect/token', 'application/json', b'')
assert status == 400 and content_type.startswith('application/json')
assert json.loads(body)['error'] == 'invalid_request'

status, content_type, body = request('/protocol/openid-connect/nonexistent-standard-error-check', 'text/html')
assert status == 404 and content_type.startswith('text/html')
assert '/login/gator-standard/' in body and 'gator-login-brands' in body
assert 'Abre tu aplicación' not in body, 'Other errors must retain their own message'
assert 'kc-error-message' in body
print('PASS: standard browser 405/404 pages; JSON GET and POST token errors preserved.')
