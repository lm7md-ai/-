#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
RIHLA_TOOLS=/workspace/android-tools
mkdir -p "$RIHLA_TOOLS" "$RIHLA_TOOLS/user" "$RIHLA_TOOLS/gradle-home"
export ANDROID_USER_HOME="$RIHLA_TOOLS/user"
export GRADLE_USER_HOME="$RIHLA_TOOLS/gradle-home"
if ! command -v javac >/dev/null 2>&1; then
  if [ ! -x "$RIHLA_TOOLS/jdk-21.0.8+9/bin/javac" ]; then
    RIHLA_JDK_URL='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz'
    curl -fL "$RIHLA_JDK_URL" -o "$RIHLA_TOOLS/jdk.tar.gz"
    curl -fL "$RIHLA_JDK_URL.sha256.txt" -o "$RIHLA_TOOLS/jdk.sha256"
    python3 - "$RIHLA_TOOLS" <<'PY'
import hashlib,pathlib,sys
p=pathlib.Path(sys.argv[1]);assert hashlib.sha256((p/'jdk.tar.gz').read_bytes()).hexdigest()==(p/'jdk.sha256').read_text().split()[0], 'JDK checksum mismatch'
PY
    tar -xzf "$RIHLA_TOOLS/jdk.tar.gz" -C "$RIHLA_TOOLS"
  fi
  export JAVA_HOME="$RIHLA_TOOLS/jdk-21.0.8+9"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
export ANDROID_HOME="${ANDROID_HOME:-$RIHLA_TOOLS/sdk}"
python3 - "$GRADLE_USER_HOME" "$RIHLA_TOOLS" <<'PY'
import os,pathlib,sys,urllib.parse
props=pathlib.Path(sys.argv[1])/'gradle.properties';s=props.read_text() if props.exists() else ''
proxy=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY',''));java=[]
if proxy.hostname:
 for protocol in ('https','http'):
  for key,value in [('proxyHost',proxy.hostname),('proxyPort',str(proxy.port or 80))]:
   line=f'systemProp.{protocol}.{key}={value}'
   if line not in s:s+='\n'+line
 java=[f'-Dhttps.proxyHost={proxy.hostname}',f'-Dhttps.proxyPort={proxy.port or 80}']
trust='/etc/ssl/certs/java/cacerts'
if pathlib.Path(trust).exists():
 line=f'systemProp.javax.net.ssl.trustStore={trust}'
 if line not in s:s+='\n'+line
 java.append(f'-Djavax.net.ssl.trustStore={trust}')
props.write_text(s+'\n');(pathlib.Path(sys.argv[2])/'sdk-java-options').write_text(' '.join(java))
PY
export JAVA_OPTS="${JAVA_OPTS:-} $(cat "$RIHLA_TOOLS/sdk-java-options") -Duser.home=$RIHLA_TOOLS/user"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o "$RIHLA_TOOLS/cli.zip"
  curl -fL https://dl.google.com/android/repository/repository2-3.xml -o "$RIHLA_TOOLS/repository.xml"
  python3 - "$RIHLA_TOOLS" <<'PY'
import pathlib,hashlib,sys,xml.etree.ElementTree as ET
p=pathlib.Path(sys.argv[1]);tree=ET.parse(p/'repository.xml');expected=None
for a in tree.iter('complete'):
 if a.findtext('url')=='commandlinetools-linux-13114758_latest.zip':expected=a.findtext('checksum');break
assert expected and hashlib.sha1((p/'cli.zip').read_bytes()).hexdigest()==expected, 'Android CLI checksum mismatch'
PY
  mkdir -p "$ANDROID_HOME/cmdline-tools" "$RIHLA_TOOLS/cli-unpack"
  unzip -q "$RIHLA_TOOLS/cli.zip" -d "$RIHLA_TOOLS/cli-unpack"
  mv "$RIHLA_TOOLS/cli-unpack/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-37.2' 'build-tools;37.0.0' 'platform-tools' < <(for RIHLA_INDEX in {1..100}; do printf 'y\n'; done)
if [ ! -f local.properties ]; then printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties; fi
./gradlew --no-daemon --max-workers=2 assembleClassicDebug assembleNextDebug testNextDebugUnitTest lintClassicDebug lintNextDebug

python3 tools/verify_schema.py
python3 tools/verify_offline_map.py
