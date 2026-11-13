"""Regenerates ios/KeyCatch/Sources/Strings.swift from the Android app's strings.xml."""
import re, html, os
root = os.path.join(os.path.dirname(__file__), '..', '..')
src = open(os.path.join(root, 'app/src/main/res/values/strings.xml')).read()
items = re.findall(r'<string name="([a-z0-9_]+)">(.*?)</string>', src, re.S)
def camel(n):
    p = n.split('_'); return p[0] + ''.join(x[:1].upper() + x[1:] for x in p[1:])
lines = ['// Generated from app/src/main/res/values/strings.xml by tools/ios/strings.py, so the',
         '// iPhone and Android apps say the same things. Edit the Android strings, then regenerate.',
         '// Placeholders use String(format:) positional syntax: %1$@ for text, %1$d for numbers.',
         '', 'enum S {']
# Where the iPhone app must say something different from Android.
OVERRIDES = {
    'limits_thanks': "KeyCatch listens with three open research models: CREPE, by Jong Wook Kim, Justin Salamon, Peter Li and Juan Pablo Bello (MIT License), Basic Pitch, by Spotify\\'s Audio Intelligence Lab (Apache 2.0), and Beat This!, by Francesco Foscarin, Jan Schlüter and Gerhard Widmer (MIT License). All three run on your iPhone through Core ML. Text is set in Manrope (SIL Open Font License). Their licenses ship with the app.",
    'permission_denied': "KeyCatch can\\'t hear without the microphone. Turn it on for KeyCatch in Settings, under Privacy & Security, Microphone.",
    'share_clips': "Share clips",
}
for name, value in items:
    if name in {'permission_needed', 'share_clips_title'}: continue
    value = OVERRIDES.get(name, value)
    v = html.unescape(value.replace("\\'", "'").replace('\\"', '"'))
    v = re.sub(r'%(\d+)\$s', r'%\1$@', v)
    v = v.replace('\\', '\\\\').replace('"', '\\"')
    lines.append(f'    static let {camel(name)} = "{v}"')
lines.append('}')
open(os.path.join(root, 'ios/KeyCatch/Sources/Strings.swift'), 'w').write('\n'.join(lines) + '\n')
