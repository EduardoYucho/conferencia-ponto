# Receptor de backup

Traz para a máquina do trabalho o backup **inteiro** das pastas do sistema de um cliente (AppSrc, Data com os
`.DAT`/`.K*`/`.VLD`/`.TAG`, DDSrc, logs…), sem instalar nada no cliente: lá, só o Chrome ou o Edge.

```
Navegador do cliente (pelo WTS ou pelo UltraViewer)          Máquina do trabalho
  escolhe C:\sislojas, marca as pastas                         Receptor (este projeto)
  lê, monta o .tar, SHA-256 de cada arquivo                     recebe os pedaços em ordem
  compacta com zstd (em paralelo)          ── túnel HTTPS ──►   grava <Cliente>_<data>.tar.zst
  envia em pedaços, continua se cair         (cloudflared)      confere o SHA-256 de cada arquivo
```

O resultado é um único `<Cliente>_<aaaa-mm-dd_hhmmss>.tar.zst` por coleta, em
`<pasta dos backups>\<Cliente>\`. Abre no `tar` do próprio Windows 11 (`tar -xf arquivo.tar.zst`), no 7-Zip ZS, no
PeaZip ou pelo botão **Extrair** da tela.

## Instalação (na máquina do trabalho)

Precisa do Java 17 ou mais novo (o mesmo do ponto).

```bat
instalar-receptor.cmd -PastaBackups D:\Backups-Clientes
receptor tunel
```

O primeiro compila e instala (tarefa agendada `ReceptorBackup`, que sobe no logon e volta se cair, e o comando
`receptor`). O segundo instala o `cloudflared` pelo winget: é ele que dá o endereço público
(`https://….trycloudflare.com`) por onde o navegador do cliente chega aqui, **sem abrir porta no firewall**.

Depois, em qualquer terminal novo: `receptor` (situação), `receptor abrir` (tela), `receptor logs`, `receptor config`,
`receptor reiniciar`, `receptor atualizar` (depois de um `git pull`), `receptor desinstalar`.

## Uso

1. `receptor abrir` → cadastre o cliente (nome e, como lembrete, a pasta do sistema, ex.: `G:\sislojas3`).
2. **Gerar link**: o link já vai para a área de transferência.
3. No cliente (WTS no servidor, de preferência; ou UltraViewer), abra o link no Chrome/Edge → **Escolher pasta** →
   marque as subpastas → **Iniciar coleta**.
4. Acompanhe pela página ou pela tela do Receptor. Ao terminar, o Receptor confere cada arquivo e mostra
   **íntegro**. **Extrair** descompacta em `<Cliente>\extraido\<nome>` (ou em outra pasta, por **Extrair em…**).

### O que acontece por baixo

- **Leitura rápida, envio no ritmo da internet.** A página lê e compacta tudo em minutos e guarda os pedaços prontos
  numa área temporária do navegador (OPFS; até metade do espaço que o navegador oferece, no máximo 8 GB). O envio
  segue no ritmo da conexão. Assim a "foto" do sistema fica consistente mesmo com upload de 0,4 Mbps.
- **Continua de onde parou.** Pedaço que falha é reenviado sozinho (a conexão pode cair quantas vezes for). Se a aba
  fechar, abra o link de novo: com os pedaços guardados, continua o envio sem ler de novo; sem eles, continua a
  leitura do ponto que o Receptor confirmou (até do meio de um `.DAT` grande, com o SHA-256 parcial salvo). Se aquele
  arquivo mudou nesse meio-tempo, o Receptor volta para antes dele e ele é lido de novo inteiro.
- **Sistema em uso.** Arquivo pequeno que muda durante a leitura é lido de novo (fica consistente). Arquivo grande que
  muda no meio é relido a partir de uma nova "foto" e sai **marcado** no relatório ("mudaram durante a leitura"), para
  você saber que um `.K1` diferente pode ser da cópia, não do cliente. Arquivo travado: novas tentativas; se não der,
  vai para "não lidos" com o motivo.
- **Integridade.** Cada pedaço leva o SHA-256 do próprio conteúdo (pedaço corrompido no caminho é recusado e
  reenviado). No fim, o `.tar.zst` traz `_coleta/manifesto.json` com o SHA-256 de cada arquivo calculado no cliente, e o
  Receptor relê o backup inteiro e confere um por um. **Conferir de novo** repete isso quando quiser.
- **Compactação.** zstd nível 17 (configurável). Medido em registros parecidos com `.DAT` do DataFlex, por núcleo:
  nível 15 = 40 MB/s e 20×; **17 = 17 MB/s e 35×**; 19 = 1,4 MB/s e 37×. A página usa metade dos núcleos (até 4),
  para não pesar no servidor do cliente.

### Internet muito lenta

Com 0,4 Mbps de upload passam uns 3 MB por minuto; nenhum programa muda isso. A página mostra a velocidade real e a
previsão logo no começo e, se passar de 1 hora, sugere conectar um celular por cabo USB (ancoragem USB) ou o roteador
do celular: o envio continua sozinho pela nova conexão.

## Configuração (`%USERPROFILE%\.receptor-backup\config\application.yml`)

| Chave | Padrão | Para quê |
|---|---|---|
| `receptor.pasta-dados` | `%USERPROFILE%\Backups-Clientes` | onde ficam os backups |
| `receptor.porta-admin` / `porta-publica` | 8095 / 8096 | tela (só localhost) / porta que o túnel publica (só `/c/...`) |
| `receptor.endereco-publico` | vazio | túnel nomeado com domínio próprio (fixo); vazio = túnel rápido do cloudflared |
| `receptor.cloudflared` | vazio | caminho do `cloudflared.exe` (vazio = procura no PATH e nas pastas do winget) |
| `receptor.nivel-zstd` | 17 | nível do zstd no navegador do cliente |
| `receptor.tamanho-bloco-mib` | 8 | tamanho de cada bloco lido (cada um vira um pedaço do envio) |
| `receptor.validade-link-horas` | 48 | o link vence depois disso sem contato (cada contato renova) |
| `receptor.espaco-minimo-livre-mib` | 2048 | não aceita pedaço se o disco ficar com menos que isso |
| `receptor.limpeza-dias` | 0 | apaga coletas com mais de N dias (0 = desligada), às 3h30 e ao subir |
| `receptor.manter-ultima-por-cliente` | true | na limpeza, mantém sempre a mais recente de cada cliente |

## Segurança

- A tela e a API dela respondem só em `127.0.0.1` e recusam o que vem pelo túnel (cabeçalhos do Cloudflare), Host que
  não seja localhost (DNS rebinding) e alteração sem o cabeçalho `X-Receptor` (um site de fora não consegue mandá-lo).
- Pela porta pública só passa `/c/...`: a página de coleta e a API de uma coleta, identificada por um token de 32 bytes
  aleatórios no link (um por coleta, vence sem uso, deixa de aceitar pedaços depois de concluída ou cancelada).
- Nada do que vem do cliente vira caminho de arquivo no Receptor: os pedaços são gravados por número. Na extração,
  caminho com `..`, absoluto, com letra de unidade ou nome reservado do Windows é ignorado (e listado).
- No cliente, a página só **lê** a pasta escolhida.

## Limites conhecidos

- Precisa do **Chrome ou Edge** (a leitura de pasta inteira não existe no Firefox nem no Internet Explorer). Em Windows
  Server 2012 R2, o último Chrome que roda lá (109) tem o necessário, mas ainda não foi testado num servidor real.
- O Chrome não deixa escolher a **raiz de um disco** (`G:\`) nem pastas do sistema; com o sistema numa pasta própria
  (`G:\sislojas3`) funciona.
- O navegador não informa o caminho completo nem o nome do computador: o caminho fica no cadastro do cliente e o
  computador é um campo opcional na página.
- O **túnel rápido** muda de endereço quando o Receptor (ou o cloudflared) reinicia: copie o link de novo na tela; a
  coleta continua de onde parou. Para um endereço fixo, use um túnel nomeado com domínio próprio
  (`receptor.endereco-publico`).
- A primeira leitura de cada coleta lê tudo (não há cópia anterior para comparar): o ganho vem da compactação e de não
  recomeçar do zero quando cai.

## Testes

```bat
cd receptor-backup
mvn test
```

Ponta a ponta com os mesmos módulos da página (leitura, tar, SHA-256, zstd em wasm), contra um Receptor rodando:

```bat
java -jar target\receptor-backup.jar --receptor.pasta-dados=%TEMP%\rb --receptor.porta-admin=18095 --receptor.porta-publica=18096 --receptor.tamanho-bloco-mib=1
node src\test\js\ponta-a-ponta.mjs 18095 18096 %TEMP%\rb
```

Cobre: coleta inteira e extração idêntica byte a byte; queda no meio de um arquivo grande que muda antes de retomar;
retomada no meio de um arquivo que não mudou; pedaço corrompido e de geração antiga; regras das duas portas; e se o
`tar.exe` do Windows extrai o resultado.

## Arquivos

| Onde | O quê |
|---|---|
| `src/main/java/.../coleta/ServicoDeColetas.java` | recebimento em ordem, retomada, fechamento com o manifesto, conferência, extração |
| `src/main/java/.../coleta/LeitorDoBackup.java` | lê o `.tar.zst` de passagem (conferir/extrair) |
| `src/main/java/.../config/FiltroDePortas.java` | o que é público e o que é só desta máquina |
| `src/main/java/.../tunel/ServicoDeTunel.java` | sobe o `cloudflared` e lê o endereço público |
| `src/main/resources/static/c/_/coleta.js` | a página de coleta (tela, envio, retomada) |
| `src/main/resources/static/c/_/nucleo/` | empacotador (blocos do `.tar`), `.tar`, SHA-256 retomável, envelope, zstd |
| `src/main/resources/static/c/_/guarda.js` | pedaços prontos no OPFS do navegador e a pasta lembrada (IndexedDB) |
| `src/main/resources/static/c/_/vendor/` | zstd compilado para wasm (`@bokuweb/zstd-wasm` 0.0.27, MIT) |
| `src/main/resources/static/index.html`, `admin.js` | a tela do Receptor |
| `../deploy/windows/receptor.ps1` | o comando `receptor` (instalar, iniciar, túnel, logs…) |
