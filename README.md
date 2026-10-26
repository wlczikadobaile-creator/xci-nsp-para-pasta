# XCI/NSP para Pasta (Android)

App Android que converte jogos de Switch em **XCI** ou **NSP** para **pasta**, direto no celular, sem PC.
Toda a lógica é Kotlin puro, baseada nos formatos documentados pelas ferramentas de PC de código aberto
[hactool](https://github.com/SciresM/hactool), [LibHac/hactoolnet](https://github.com/Thealexbarney/LibHac) e no [Switchbrew](https://switchbrew.org).

## Modos

| Modo | Precisa de chaves? | Resultado |
|---|---|---|
| **Extrair jogo** | Sim (`prod.keys`) | `NomeDoJogo/exefs/` (main, main.npdm, subsdk...), `NomeDoJogo/romfs/`, `NomeDoJogo/control/` (ícone e nacp). Formato de jogo extraído usado por emuladores e mods LayeredFS. |
| **Só desempacotar** | Não | `NomeDoJogo/` com as `.nca`, `.tik` e `.cert` que estão dentro do NSP, ou da partição *secure* do XCI. |

## Como usar

1. Instale o APK da página de [Releases](../../releases) (permita "instalar apps desconhecidos").
2. Toque em **Escolher arquivo XCI / NSP**.
3. Toque em **Escolher pasta de saída** (a pasta fica salva para as próximas vezes).
4. Para *Extrair jogo*, escolha o seu `prod.keys`. Ele **não vem com o app**: tire do **seu próprio console** com o [Lockpick_RCM](https://github.com/shchmue/Lockpick_RCM).
5. Toque em **Converter**. A tela fica ligada durante a conversão; deixe o app aberto.

## O que funciona

- NSP (PFS0) e XCI (HFS0, incluindo dumps com key area de 0x1000 bytes e XCIs "trimmed")
- NCA3 e NCA2, criptografia padrão (key area) e por title key (ticket `.tik` comum)
- ExeFS (PFS0) e RomFS (IVFC) com AES-CTR
- Jogos base, DLC (`data_<titleid>/romfs`) e várias Program NCAs no mesmo arquivo

## Limitações

- **NSZ/XCZ** (comprimidos) não são suportados. Descomprima antes no PC.
- **Atualizações**: o RomFS de update é um patch (BKTR) e precisa do jogo base. Só o ExeFS é extraído.
- Tickets **personalizados** não são suportados (os de NSP normais são "common").
- Seções comprimidas/esparsas das NCAs mais recentes não são suportadas.

## Compilar

```bash
./gradlew testDebugUnitTest assembleRelease
```

Os testes (`app/src/test`) conferem o AES-XTS contra os vetores IEEE 1619 e montam NSP/XCI cifrados sintéticos
para conferir a extração completa.

Código em `app/src/main/java/com/nsxpasta/core` (sem dependência de Android): `Storage`, `Crypto` (XTS no estilo
Nintendo e CTR), `Containers` (PFS0/HFS0/XCI/ticket), `Nca`, `RomFs`, `Keys` e `Converter`.

Use somente com jogos que você possui.
