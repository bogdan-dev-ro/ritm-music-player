// Copies Ritm's native Android code into the generated android/ project
// and patches the manifest + theme. Safe to run more than once.
const fs = require('fs');
const path = require('path');

const root = process.cwd();
const cfg = JSON.parse(fs.readFileSync(path.join(root, 'capacitor.config.json'), 'utf8'));
if (cfg.appId !== 'com.ritm.app') {
  console.error('appId must stay "com.ritm.app" (the native files use that package name).');
  process.exit(1);
}

const mainDir = path.join(root, 'android', 'app', 'src', 'main');
if (!fs.existsSync(mainDir)) {
  console.error('android/ folder not found. Run "npx cap add android" first.');
  process.exit(1);
}

// 1) Java sources (also replaces the template MainActivity)
const javaDir = path.join(mainDir, 'java', 'com', 'ritm', 'app');
fs.mkdirSync(javaDir, { recursive: true });
for (const f of fs.readdirSync(path.join(root, 'native', 'java'))) {
  fs.copyFileSync(path.join(root, 'native', 'java', f), path.join(javaDir, f));
  console.log('java      ->', f);
}

// 2) Drawable used by the notification and the bubble
const drawDir = path.join(mainDir, 'res', 'drawable');
fs.mkdirSync(drawDir, { recursive: true });
fs.copyFileSync(path.join(root, 'native', 'res', 'drawable', 'ic_note.xml'), path.join(drawDir, 'ic_note.xml'));
console.log('drawable  -> ic_note.xml');

// 3) AndroidManifest: permissions + the foreground service
const mfPath = path.join(mainDir, 'AndroidManifest.xml');
let mf = fs.readFileSync(mfPath, 'utf8');
const perms = [
  'android.permission.INTERNET',
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
  'android.permission.POST_NOTIFICATIONS',
  'android.permission.SYSTEM_ALERT_WINDOW',
  'android.permission.WAKE_LOCK',
  'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'
];
const missing = perms.filter((p) => !mf.includes(p));
if (missing.length) {
  mf = mf.replace('</manifest>', missing.map((p) => '    <uses-permission android:name="' + p + '" />').join('\n') + '\n</manifest>');
}
if (!mf.includes('PlayerService')) {
  mf = mf.replace(
    '</application>',
    '        <service android:name=".PlayerService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n    </application>'
  );
}
fs.writeFileSync(mfPath, mf);
console.log('manifest  -> patched');

// 4) Dark status bar / navigation bar
const stPath = path.join(mainDir, 'res', 'values', 'styles.xml');
if (fs.existsSync(stPath)) {
  let st = fs.readFileSync(stPath, 'utf8');
  if (!st.includes('statusBarColor')) {
    st = st.replace(
      '<item name="android:background">@null</item>',
      '<item name="android:background">@null</item>\n' +
        '        <item name="android:statusBarColor">#0e0b15</item>\n' +
        '        <item name="android:navigationBarColor">#0e0b15</item>\n' +
        '        <item name="android:windowLightStatusBar">false</item>'
    );
    fs.writeFileSync(stPath, st);
    console.log('styles    -> dark bars');
  }
}

// 4b) App icon (launcher): mipmap-* folders + adaptive background colour
const resSrc = path.join(root, 'native', 'res');
const resDst = path.join(mainDir, 'res');
for (const d of fs.readdirSync(resSrc)) {
  if (!d.startsWith('mipmap-') && d !== 'values') continue;
  const from = path.join(resSrc, d);
  const to = path.join(resDst, d);
  fs.mkdirSync(to, { recursive: true });
  for (const f of fs.readdirSync(from)) {
    fs.copyFileSync(path.join(from, f), path.join(to, f));
    console.log('icon      ->', d + '/' + f);
  }
}
console.log('Done.');
