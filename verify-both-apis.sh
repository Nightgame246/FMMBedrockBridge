#!/usr/bin/env bash
#
# verify-both-apis.sh — beweist, dass EIN JAR auf beiden Minecraft-Generationen läuft.
#
# HINTERGRUND: Mojang hat 2026 von "1.21.x" auf jahresbasierte Versionen umgestellt
# (26.1 "Tiny Takeover" im März, 26.2 "Chaos Cubed" im Juni — ein 1.22 gibt es nicht).
# Die Server laufen noch auf 1.21.x, das Ziel ist 26.2. Bis der Umstieg durch ist, muss
# das Plugin BEIDES bedienen.
#
# Der Trick: dieselben Quellen zweimal kompilieren.
#   Durchlauf 1 (default)      → paper-api 26.2  → findet ALLES, was 26.2 entfernt hat
#   Durchlauf 2 (legacy-1.21)  → paper-api 1.21.10 → findet ALLES, was es in 1.21.x noch nicht gibt
# Nur wenn beide grün sind, ist das JAR nachweislich auf beiden lauffähig. Ein einzelner
# Durchlauf kann das grundsätzlich nicht zeigen.
#
# JAVA: Paper 26.2 liefert Class-File-Version 69 (Java 25) aus — javac 21 kann die Dateien
# nicht einmal lesen. Gebaut wird deshalb mit JDK 25, das Bytecode-Target bleibt aber 21,
# damit das JAR auf den Java-21-Servern weiterläuft (siehe maven.compiler.release im pom).
#
# Verwendung:  bash verify-both-apis.sh

set -euo pipefail
cd "$(dirname "$0")"

# --- JDK 25 finden (nur zum Kompilieren nötig) -------------------------------
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ] \
   || ! "${JAVA_HOME}/bin/javac" -version 2>&1 | grep -qE 'javac 2[5-9]|javac [3-9][0-9]'; then
  for c in /usr/lib/jvm/java-25-openjdk /usr/lib/jvm/java-26-openjdk /usr/lib/jvm/default; do
    if [ -x "$c/bin/javac" ] && "$c/bin/javac" -version 2>&1 | grep -qE 'javac 2[5-9]|javac [3-9][0-9]'; then
      export JAVA_HOME="$c"; break
    fi
  done
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
  echo "FEHLER: Kein JDK >= 25 gefunden (javac fehlt)." >&2
  echo "  Paper 26.2 hat Class-File-Version 69 — javac 21 kann sie nicht lesen." >&2
  echo "  Arch/CachyOS:  sudo pacman -S jdk25-openjdk" >&2
  echo "  Achtung: jre25-openjdk reicht NICHT, das hat kein javac." >&2
  exit 1
fi
echo "JDK: $("$JAVA_HOME/bin/javac" -version 2>&1)  ($JAVA_HOME)"

# --- Maven finden (IntelliJ bündelt eins, falls keins im PATH) ---------------
if command -v mvn >/dev/null 2>&1; then
  MVN=mvn
elif [ -x /usr/share/idea/plugins/maven/lib/maven3/bin/mvn ]; then
  MVN=/usr/share/idea/plugins/maven/lib/maven3/bin/mvn
else
  echo "FEHLER: kein mvn gefunden." >&2; exit 1
fi

echo
echo "════ Durchlauf 1/2: Minecraft 26.2 (primäres Ziel) ════"
$MVN clean package

echo
echo "════ Durchlauf 2/2: Minecraft 1.21.10 (Rückwärtskompatibilität) ════"
$MVN -Plegacy-1.21 clean test

echo
echo "════ Bytecode-Ziel prüfen ════"
# Der erste Durchlauf hat das JAR gebaut, der zweite es weggeräumt — neu bauen fürs Artefakt.
$MVN -q clean package
JAR=$(ls -t target/FMMBedrockBridge-*.jar | head -1)
# od padded auf 3 Stellen ("065") -> 10#, sonst wird das als Oktal gelesen bzw.
# schlaegt der Stringvergleich fehl.
CLS=$((10#$(unzip -p "$JAR" de/crazypandas/fmmbedrockbridge/bridge/McVersions.class \
            | od -An -tu1 -j6 -N2 | tr -d ' ')))
if [ "$CLS" -ne 65 ]; then
  echo "FEHLER: Bytecode-Version $CLS, erwartet 65 (Java 21)." >&2
  echo "  Ein höherer Wert lädt auf den Java-21-Servern nicht." >&2
  exit 1
fi
echo "OK — Class-File-Version 65 (Java 21), läuft auf Java 21 und 25."
echo
echo "Artefakt: $JAR"
echo "BEIDE API-Generationen grün."
