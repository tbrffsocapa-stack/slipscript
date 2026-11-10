#!/usr/bin/env bash
# slip2apk: transforma um app Slip (script + módulos + bibliotecas nativas + ícone) num APK instalável.
#
#   tools/slip2apk.sh MEU_APP [opções]
#
# MEU_APP é uma pasta com main.slip (ou main.sp) e, se quiser: modules/, libs/ (arquivos .so), icon.png e app.properties
# (linhas nome=valor com: name, id, version, code). Também aceita um único arquivo .slip/.sp.
#
# Opções (valem mais que o app.properties):
#   --name "Meu App"     nome embaixo do ícone              (padrão: nome da pasta/arquivo)
#   --id com.eu.meuapp   identificador único do app          (padrão: com.slipapp.<nome>)
#   --version 1.0        versionName                         --code 1   versionCode (inteiro; suba a cada atualização)
#   --icon icon.png      ícone PNG (192x192 ou 512x512)      --modules DIR   --libs DIR   (quando a entrada é um arquivo)
#   --out arquivo.apk    onde gravar o APK                   (padrão: ./<nome>.apk)
#   --debug              gera o APK de debug em vez do release
#   --no-native          não compila a ponte FFI (dispensa NDK/CMake; `native_*` deixa de funcionar). Automático se não há libs/
#   --native             força compilar a ponte FFI
#   --stage-only         só verifica e prepara os arquivos; não chama o Gradle
#
# Assinatura: exporte SLIP_KEYSTORE, SLIP_KEYSTORE_PASSWORD, SLIP_KEY_ALIAS e SLIP_KEY_PASSWORD (veja tools/make-keystore.sh).
# Sem keystore o APK sai assinado com a chave de debug: instala e roda, mas não serve para publicar nem para atualizar com outra chave.
# Requisitos: JDK 17+, Android SDK (ANDROID_HOME) e, com FFI, o NDK + CMake. Veja BUILD.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
die() { echo "slip2apk: $*" >&2; exit 1; }
warn() { echo "slip2apk: aviso: $*" >&2; }

INPUT="" NAME="" ID="" VERSION="" CODE="" ICON="" MODULES="" LIBS="" OUT="" VARIANT=release NATIVE=auto STAGE_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="${2:?falta o valor de --name}"; shift 2 ;;
    --id) ID="${2:?falta o valor de --id}"; shift 2 ;;
    --version) VERSION="${2:?falta o valor de --version}"; shift 2 ;;
    --code) CODE="${2:?falta o valor de --code}"; shift 2 ;;
    --icon) ICON="${2:?falta o valor de --icon}"; shift 2 ;;
    --modules) MODULES="${2:?falta o valor de --modules}"; shift 2 ;;
    --libs) LIBS="${2:?falta o valor de --libs}"; shift 2 ;;
    --out) OUT="${2:?falta o valor de --out}"; shift 2 ;;
    --debug) VARIANT=debug; shift ;;
    --no-native) NATIVE=no; shift ;;
    --native) NATIVE=yes; shift ;;
    --stage-only) STAGE_ONLY=1; shift ;;
    -h|--help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) die "opção desconhecida: $1 (use --help)" ;;
    *) [ -z "$INPUT" ] || die "mais de uma entrada: $INPUT e $1"; INPUT="$1"; shift ;;
  esac
done
[ -n "$INPUT" ] || die "informe a pasta do app ou o arquivo .slip (use --help)"
[ -e "$INPUT" ] || die "não existe: $INPUT"

# ---- localizar as partes do app
if [ -d "$INPUT" ]; then
  APPDIR="$(cd "$INPUT" && pwd)"
  MAIN=""
  for f in main.slip main.sp; do [ -f "$APPDIR/$f" ] && { MAIN="$APPDIR/$f"; break; }; done
  [ -n "$MAIN" ] || die "a pasta $INPUT precisa ter main.slip (ou main.sp)"
  [ -z "$MODULES" ] && [ -d "$APPDIR/modules" ] && MODULES="$APPDIR/modules"
  [ -z "$LIBS" ] && [ -d "$APPDIR/libs" ] && LIBS="$APPDIR/libs"
  [ -z "$ICON" ] && [ -f "$APPDIR/icon.png" ] && ICON="$APPDIR/icon.png"
  BASENAME="$(basename "$APPDIR")"
  if [ -f "$APPDIR/app.properties" ]; then
    getp() { grep -E "^[[:space:]]*$1[[:space:]]*=" "$APPDIR/app.properties" | tail -1 | sed -E 's/^[^=]*=[[:space:]]*//; s/[[:space:]]+$//' || true; }
    [ -z "$NAME" ] && NAME="$(getp name)"
    [ -z "$ID" ] && ID="$(getp id)"
    [ -z "$VERSION" ] && VERSION="$(getp version)"
    [ -z "$CODE" ] && CODE="$(getp code)"
  fi
else
  MAIN="$(cd "$(dirname "$INPUT")" && pwd)/$(basename "$INPUT")"
  case "$MAIN" in *.slip|*.sp) ;; *) die "a entrada deve ser uma pasta ou um arquivo .slip/.sp" ;; esac
  BASENAME="$(basename "$INPUT")"; BASENAME="${BASENAME%.*}"
fi
[ -z "$MODULES" ] || [ -d "$MODULES" ] || die "pasta de módulos não existe: $MODULES"
[ -z "$LIBS" ] || [ -d "$LIBS" ] || die "pasta de libs não existe: $LIBS"
[ -z "$ICON" ] || [ -f "$ICON" ] || die "ícone não existe: $ICON"

# ---- identidade (com validação)
[ -n "$NAME" ] || NAME="$BASENAME"
SLUG="$(printf '%s' "$BASENAME" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/_/g; s/^_+//; s/_+$//')"
case "$SLUG" in [a-z]*) ;; *) SLUG="app_$SLUG" ;; esac
[ -n "$ID" ] || ID="com.slipapp.$SLUG"
[ -n "$VERSION" ] || VERSION="1.0"
[ -n "$CODE" ] || CODE="1"
printf '%s' "$ID" | grep -Eq '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$' || die "id inválido: '$ID' (use letras minúsculas, números e _, com pelo menos um ponto: com.eu.app)"
printf '%s' "$CODE" | grep -Eq '^[0-9]+$' || die "--code deve ser um inteiro: '$CODE'"
printf '%s' "$VERSION" | grep -Eq '^[A-Za-z0-9._+-]+$' || die "--version aceita só letras, números e . _ + -: '$VERSION'"
case "$NAME" in *'"'*|*'<'*|*'>'*|*'&'*|*"'"*|*'\'*|*'$'*) die "o nome não pode ter aspas, < > & \\ ou \$: '$NAME'" ;; esac
[ -n "$OUT" ] || OUT="./$SLUG.apk"
if [ -n "$ICON" ]; then
  [ "$(head -c 8 "$ICON" | od -An -tx1 | tr -d ' \n')" = "89504e470d0a1a0a" ] || die "o ícone precisa ser um arquivo PNG: $ICON"
fi

# ---- 'compilar': verificar a sintaxe do script e dos módulos antes de empacotar
check_syntax() {
  if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
    warn "javac/java não encontrados: pulei a verificação de sintaxe (instale um JDK para ela rodar)"; return 0
  fi
  local src="$ROOT/app/src/main/java/com/slipscript/ide" cls="$ROOT/build-apk/.check"
  mkdir -p "$cls"
  javac -encoding UTF-8 -d "$cls" "$src/SlipInterpreter.java" "$src/SlipUi.java" "$src/SlipJson.java" "$src/SlipSys.java" \
        "$src/NativeBridge.java" "$src/NativeLibraryManager.java" "$src/SlipCli.java" 2>/dev/null \
    || { warn "não consegui compilar o verificador: pulei a verificação de sintaxe"; return 0; }
  local f bad=0
  for f in "$MAIN" ${MODULES:+"$MODULES"/*.slip "$MODULES"/*.sp}; do
    [ -f "$f" ] || continue
    java -Dfile.encoding=UTF-8 -cp "$cls" com.slipscript.ide.SlipCli --check "$f" >/dev/null || { echo "  ^ em $f" >&2; bad=1; }
  done
  [ "$bad" = 0 ] || die "erro de sintaxe: corrija e rode de novo"
}
check_syntax

# ---- montar os arquivos do app (assets/slip-app/...) e recursos (ícone)
STAGE="$ROOT/build-apk/$ID"
rm -rf "$STAGE"
mkdir -p "$STAGE/assets/slip-app" "$STAGE/res"
cp "$MAIN" "$STAGE/assets/slip-app/main.slip"
if [ -n "$MODULES" ]; then
  mkdir -p "$STAGE/assets/slip-app/modules"
  for f in "$MODULES"/*.slip "$MODULES"/*.sp; do [ -f "$f" ] && cp "$f" "$STAGE/assets/slip-app/modules/"; done
fi
if [ -n "$LIBS" ]; then
  mkdir -p "$STAGE/assets/slip-app/libs"
  for f in "$LIBS"/*.so; do [ -f "$f" ] && cp "$f" "$STAGE/assets/slip-app/libs/"; done
  [ -n "$(ls -A "$STAGE/assets/slip-app/libs")" ] || { rmdir "$STAGE/assets/slip-app/libs"; warn "$LIBS não tem arquivos .so"; }
fi
if [ -n "$ICON" ]; then
  mkdir -p "$STAGE/res/drawable"
  cp "$ICON" "$STAGE/res/drawable/ic_launcher.png"
fi
[ "$NATIVE" = auto ] && { [ -d "$STAGE/assets/slip-app/libs" ] && NATIVE=yes || NATIVE=no; }

ARGS=(-PslipAssets="$STAGE/assets" -PslipRes="$STAGE/res" -PslipAppId="$ID" -PslipAppName="$NAME" -PslipVersionName="$VERSION" -PslipVersionCode="$CODE")
[ "$NATIVE" = yes ] || ARGS+=(-PslipNoNative=true)
TASK=":app:assemble$([ "$VARIANT" = release ] && echo Release || echo Debug)"

echo "slip2apk: app '$NAME' ($ID) v$VERSION [$CODE] -> $OUT"
echo "slip2apk: arquivos preparados em $STAGE (ponte FFI: $NATIVE, variante: $VARIANT)"
if [ "$STAGE_ONLY" = 1 ]; then
  echo "slip2apk: (--stage-only) comando que seria executado:"
  printf '  %q' sh "$ROOT/gradlew" "${ARGS[@]}" "$TASK"; echo
  exit 0
fi

# ---- chamar o Gradle
[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] || [ -f "$ROOT/local.properties" ] \
  || die "Android SDK não encontrado. Defina ANDROID_HOME (ou crie local.properties com sdk.dir=...). Veja BUILD.md."
if [ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]; then GRADLE=(sh "$ROOT/gradlew")   # "sh": funciona mesmo sem o bit de executável (upload pelo site do GitHub)
elif command -v gradle >/dev/null 2>&1; then GRADLE=(gradle)
else die "nem o gradle wrapper nem o comando 'gradle' foram encontrados"; fi
[ -z "${SLIP_KEYSTORE:-}" ] || [ -f "$SLIP_KEYSTORE" ] || die "SLIP_KEYSTORE aponta para um arquivo que não existe: $SLIP_KEYSTORE"
if [ "$VARIANT" = release ] && [ -z "${SLIP_KEYSTORE:-}" ]; then
  warn "sem SLIP_KEYSTORE: o APK vai assinado com a chave de debug (instala, mas não serve para publicar)"
fi

APK_DIR="$ROOT/app/build/outputs/apk/$VARIANT"
rm -rf "$APK_DIR"
( cd "$ROOT" && "${GRADLE[@]}" --no-daemon "${ARGS[@]}" "$TASK" )
APK="$(ls "$APK_DIR"/*.apk 2>/dev/null | head -1 || true)"
[ -n "$APK" ] || die "o Gradle terminou, mas não achei o APK em $APK_DIR"
case "$APK" in *unsigned*) warn "o APK saiu sem assinatura (unsigned): configure o keystore ou use --debug" ;; esac
mkdir -p "$(dirname "$OUT")"
cp "$APK" "$OUT"
echo "slip2apk: pronto: $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
