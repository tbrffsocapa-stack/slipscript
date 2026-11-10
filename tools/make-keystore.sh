#!/usr/bin/env bash
# Cria uma chave de assinatura (keystore) para publicar/atualizar APKs. GUARDE O ARQUIVO E A SENHA: sem eles não dá para
# atualizar o app depois (o Android recusa uma atualização assinada com outra chave).
#   tools/make-keystore.sh [arquivo.keystore] [alias]
set -euo pipefail
command -v keytool >/dev/null 2>&1 || { echo "keytool não encontrado: instale um JDK" >&2; exit 1; }
KS="${1:-slip.keystore}"; ALIAS="${2:-slip}"
[ ! -e "$KS" ] || { echo "$KS já existe; não vou sobrescrever" >&2; exit 1; }
keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000
cat <<MSG

Pronto: $KS (alias: $ALIAS). Para assinar os APKs, exporte:
  export SLIP_KEYSTORE="$(cd "$(dirname "$KS")" && pwd)/$(basename "$KS")"
  export SLIP_KEY_ALIAS="$ALIAS"
  export SLIP_KEYSTORE_PASSWORD='a senha que você acabou de definir'
  export SLIP_KEY_PASSWORD='a mesma senha (ou a da chave, se for diferente)'
MSG
