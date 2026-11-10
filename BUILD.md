# Gerar APK

Há dois caminhos: **o IDE Slip Script** (editor + console, o app deste projeto) e **o APK de um script seu** (um app que abre direto no seu programa Slip, sem editor).

## Requisitos

- JDK 17 ou mais novo (`java -version`).
- Android SDK com a plataforma 35 e as build-tools (`ANDROID_HOME` apontando para ele, ou `local.properties` com `sdk.dir=/caminho/do/sdk`).
- Só se o app usar FFI (`native_*`): NDK e CMake instalados pelo SDK Manager.
- O Gradle vem junto (`./gradlew` baixa o 8.9 na primeira vez; precisa de internet nessa primeira vez).
- Linux, macOS ou, no Windows, WSL/Git Bash (os scripts de `tools/` são bash).

> **No celular (Termux):** o Gradle/AGP costuma falhar lá (o `aapt2` oficial não roda em ARM/bionic). O caminho que funciona é a nuvem:
> suba o projeto para um repositório no GitHub e rode **Actions → APK → Run workflow** (`.github/workflows/apk.yml`); o APK sai como artefato para baixar.
> Validar o código no celular continua possível: `tools/slip2apk.sh MEU_APP --stage-only` só confere a sintaxe e prepara os arquivos, sem Gradle.

## Passo a passo no GitHub Actions

1. Crie um repositório no GitHub e envie **todo** o conteúdo desta pasta, inclusive a pasta oculta `.github/` (o upload arrastando pelo site às vezes ignora pastas com ponto; com `git push` não há esse problema. No Termux: `pkg install git`, depois `git init`, `git add -A`, `git commit -m slip`, `git remote add origin URL`, `git push -u origin main`, usando um *token* do GitHub como senha).
2. No repositório: **Actions → APK → Run workflow**. No campo *app* deixe `examples/apps/contador` (ou a pasta do seu app dentro do repositório); vazio gera o IDE.
3. Quando terminar (uns 5–15 min), abra a execução e baixe **apk** em *Artifacts* (vem num .zip com o .apk dentro).
4. Sem *secrets* de assinatura o APK vai com a chave de debug: instala normalmente (permita "fontes desconhecidas"). Para uma chave sua, gere com `tools/make-keystore.sh` e cadastre em *Settings → Secrets and variables → Actions*: `SLIP_KEYSTORE_BASE64` (o keystore em base64: `base64 -w0 meu.keystore`), `SLIP_KEYSTORE_PASSWORD`, `SLIP_KEY_ALIAS`, `SLIP_KEY_PASSWORD`.
5. Se falhar, abra o passo vermelho do log e copie o final dele (as últimas ~40 linhas).

## 1. APK do IDE

```sh
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

## 2. APK de um script Slip

Organize o app numa pasta:

```
meu_app/
  main.slip          # obrigatório (ou main.sp): o programa
  modules/           # opcional: módulos .slip/.sp usados com use("nome")
  libs/              # opcional: bibliotecas nativas .so (arm64-v8a / x86_64) para native_load
  icon.png           # opcional: ícone PNG (192x192 ou 512x512)
  app.properties     # opcional: name=Meu App / id=com.eu.meuapp / version=1.0 / code=1
```

```sh
tools/slip2apk.sh meu_app                      # gera ./meu_app.apk
tools/slip2apk.sh examples/apps/contador       # exemplo pronto
tools/slip2apk.sh programa.slip --name "Oi" --id com.eu.oi --out oi.apk   # a partir de um arquivo só
tools/slip2apk.sh --help
```

O que acontece:

1. **Verificação de sintaxe** do `main` e de todos os módulos (usa o próprio interpretador, `SlipCli --check`). Erro de sintaxe para tudo, com arquivo e linha. Precisa de JDK; sem ele, o passo é pulado com aviso.
2. Os arquivos vão para `build-apk/<id>/` e o Gradle empacota tudo como assets, trocando nome, id, versão e ícone do app.
3. No APK gerado, o ícone abre o `RunnerActivity` (o IDE fica desligado): ele copia `modules/` e `libs/` para a pasta privada do app, roda `main.slip` e mostra a interface criada com `vt<>`. O console só aparece se o script imprimir algo ou falhar.
4. Se o script não tem `libs/`, a ponte FFI nem é compilada (`-PslipNoNative`): não precisa de NDK, e `native_*` devolve erro no app. Use `--native` para forçar.

### Assinatura

Sem chave, o APK sai assinado com a **chave de debug**: instala e roda, mas não serve para publicar e não atualiza um app assinado com outra chave.
Para uma chave sua:

```sh
tools/make-keystore.sh meu.keystore minhachave        # guarde o arquivo e a senha!
export SLIP_KEYSTORE=$PWD/meu.keystore SLIP_KEY_ALIAS=minhachave
export SLIP_KEYSTORE_PASSWORD=... SLIP_KEY_PASSWORD=...
tools/slip2apk.sh meu_app
```

Para atualizar um app já instalado, mantenha o mesmo `id`, a mesma chave e suba o `code` (versionCode) a cada versão.

### Parâmetros do Gradle (se preferir chamar direto)

`-PslipAssets=DIR -PslipRes=DIR -PslipAppId=ID -PslipAppName=NOME -PslipVersionName=V -PslipVersionCode=N -PslipNoNative=true`
e, para assinar, `-PslipKeystore -PslipKeystorePassword -PslipKeyAlias -PslipKeyPassword`. Veja o topo de `app/build.gradle`.

## O que isto é (e o que não é)

É **empacotamento**, não compilação para código de máquina: o APK leva o interpretador Slip e o seu script como arquivo de texto dentro de `assets/`
— quem abrir o APK consegue ler o código-fonte. Não coloque segredos (chaves de API, senhas) no script.

Limites atuais: `targetSdk 28` (necessário para carregar `.so` da pasta privada; o Android recente mostra um aviso de "app feito para versão antiga" e a Play Store
não aceita esse nível para novos apps, então o APK serve para instalar direto/distribuição própria); a FFI só tem as ABIs `arm64-v8a` e `x86_64`; `http` exige `https://`.

## Verificado e não verificado

Verificado: staging, validações e mensagens do `slip2apk.sh`, a checagem de sintaxe, a execução do app de exemplo no interpretador e a compilação das classes Android
contra o `android.jar` da API 28. **Não verificado:** o build Gradle completo e a instalação/execução do APK num aparelho (o ambiente em que isto foi escrito não tem o Android SDK).
Se o primeiro build falhar, o erro vem do Gradle/AGP: mande a saída completa.
