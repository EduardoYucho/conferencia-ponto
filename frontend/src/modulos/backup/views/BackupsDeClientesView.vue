<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { useAuthStore } from '@/stores/auth'
import { backupApi } from '@/modulos/backup/api'
import { useRecepcaoStore } from '@/modulos/backup/recepcao'
import { escolherPasta, gravarPastaDaColeta, gravarPastaPadrao, pastaPadrao, permissao, seletorDisponivel } from '@/modulos/backup/destino'
import { novaChave } from '@/modulos/backup/cifra'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { mensagemDe } from '@/utils/erros'
import { ACESSOS } from '@/utils/permissoes'

/**
 * Backups de clientes: escolha uma pasta DESTE computador e gere o link que o cliente abre no Chrome/Edge (pelo WTS ou
 * pelo UltraViewer). A página lê as pastas do sistema dele, compacta e envia; cada pedaço passa pelo sistema só de
 * passagem e é gravado por este navegador na pasta escolhida. No fim, este navegador junta o .tar.zst e confere cada
 * arquivo. O sistema precisa ficar aberto nesta aba (em qualquer tela) enquanto chega.
 */
const auth = useAuthStore()
const modulos = useAcessoModulosStore()
const recepcao = useRecepcaoStore()

const SITUACOES = {
  AGUARDANDO: { rotulo: 'aguardando o cliente', classe: 'text-tinta-suave' },
  RECEBENDO: { rotulo: 'recebendo', classe: 'text-alerta' },
  FECHANDO: { rotulo: 'montando e conferindo', classe: 'text-alerta' },
  INTEGRA: { rotulo: 'íntegro', classe: 'text-credito' },
  DIVERGENTE: { rotulo: 'com diferenças', classe: 'text-carimbo' },
  CANCELADA: { rotulo: 'cancelada', classe: 'text-tinta-apagada' },
}
const ATUALIZAR_A_CADA_MS = 3000
const SOLTOS = '__arquivos-da-raiz__'

const estado = ref(null)
const coletas = ref([])
const clientes = ref([])
const carregando = ref(false)
const erro = ref(null)
const aviso = ref('')
const ocupado = ref(false)

const destino = ref(null)
const destinoSemPermissao = ref(false)
const podeEscolher = seletorDisponivel()
const origem = window.location.origin

const clienteEscolhido = ref('')
const copiado = ref('')

const formulario = reactive({ nome: '', pastaSistema: '' })
const editando = ref(null)
const erroCliente = ref('')
const abertos = reactive(new Set())

const tunel = computed(() => estado.value?.tunel ?? null)
const tunelOk = computed(() => tunel.value && (tunel.value.modo === 'ATIVO' || tunel.value.modo === 'FIXO'))

let versaoAoAbrir = null
const desatualizada = ref(false)
const recarregar = () => window.location.reload()

async function carregar() {
  if (!modulos.backup) return
  carregando.value = true
  try {
    const [e, lista, cl] = await Promise.all([backupApi.estado(), backupApi.coletas(), backupApi.clientes()])
    // o sistema foi atualizado com esta aba aberta: a tela velha não pode continuar gerando links
    if (versaoAoAbrir === null) versaoAoAbrir = e?.versao ?? ''
    else if (e?.versao && e.versao !== versaoAoAbrir) desatualizada.value = true
    estado.value = e
    coletas.value = lista ?? []
    clientes.value = cl ?? []
    if (!clienteEscolhido.value && clientes.value.length) clienteEscolhido.value = clientes.value[0].nome
    erro.value = null
    if (podeEscolher) recepcao.acompanhar(coletas.value)
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}

async function acao(fn) {
  aviso.value = ''
  ocupado.value = true
  try {
    await fn()
    await carregar()
  } catch (e) {
    aviso.value = mensagemDe(e)
  } finally {
    ocupado.value = false
  }
}

// ------------------------------------------------------------------ pasta de destino (deste computador)

async function lerDestino() {
  if (!podeEscolher) return
  destino.value = await pastaPadrao()
  destinoSemPermissao.value = !!destino.value && (await permissao(destino.value)) !== 'granted'
}

async function trocarDestino() {
  aviso.value = ''
  try {
    const pasta = await escolherPasta()
    if (!pasta) return
    await gravarPastaPadrao(pasta)
    destino.value = pasta
    destinoSemPermissao.value = false
  } catch (e) {
    aviso.value = e?.name === 'SecurityError' || e?.name === 'NotAllowedError'
      ? 'O navegador não deixou usar essa pasta (ele bloqueia a raiz do disco e pastas do sistema). Escolha outra, por exemplo D:\\Backups.'
      : `Não consegui abrir a pasta: ${e.message}`
  }
}

async function confirmarDestino() {
  if (destino.value && (await destino.value.requestPermission({ mode: 'readwrite' })) === 'granted') destinoSemPermissao.value = false
}

// ------------------------------------------------------------------ coletas

/** Túnel desligado ou cloudflared não instalado: o link só serve nesta rede. */
const soNestaRede = computed(() => !!tunel.value && ['DESLIGADO', 'SEM_CLOUDFLARED'].includes(tunel.value.modo))

/**
 * O link que o cliente abre: sempre o do túnel. Enquanto o túnel abre, nenhum (um link "local" não abre no cliente);
 * só com o túnel desligado é que vale o desta rede (para testar).
 */
function linkDe(c) {
  // a chave da criptografia vai depois do "#": o navegador nunca a manda para servidor nenhum (nem o túnel a vê)
  const chave = recepcao.estados[c.id]?.chave
  if (!chave) return null
  if (c.link) return `${c.link}#k=${chave}`
  return soNestaRede.value && c.token ? `${origem}/c/${c.token}#k=${chave}` : null
}

/** A chave (e o link completo) só existe no navegador que gerou o link. */
const semChaveAqui = (c) => !recepcao.estados[c.id]?.chave

/** A coleta do último link gerado (o link acompanha o endereço atual do túnel). */
const linkGeradoId = ref(null)
const coletaGerada = computed(() => coletas.value.find((c) => c.id === linkGeradoId.value) ?? null)
const linkAtual = computed(() => (coletaGerada.value ? linkDe(coletaGerada.value) : null))

function reiniciarTunel() {
  if (window.confirm('Abrir outro túnel muda o endereço público: os links já enviados deixam de abrir e precisam ser copiados de novo. Continuar?')) {
    acao(backupApi.reiniciarTunel)
  }
}

async function copiar(texto, chave) {
  try {
    await navigator.clipboard.writeText(texto)
  } catch {
    const campo = document.createElement('textarea')
    campo.value = texto
    document.body.appendChild(campo)
    campo.select()
    document.execCommand('copy')
    campo.remove()
  }
  copiado.value = chave
  setTimeout(() => {
    if (copiado.value === chave) copiado.value = ''
  }, 2500)
}

function gerarLink() {
  acao(async () => {
    if (destinoSemPermissao.value) await confirmarDestino()
    if (!destino.value || destinoSemPermissao.value) throw new Error('Escolha a pasta deste computador onde o backup vai ficar.')
    const c = await backupApi.criarColeta(clienteEscolhido.value)
    const chave = novaChave()
    await gravarPastaDaColeta(c.id, destino.value, c.nome, chave)
    recepcao.registrar(c.id, destino.value, chave)
    linkGeradoId.value = c.id
    coletas.value = [c, ...coletas.value.filter((x) => x.id !== c.id)]
    const link = linkDe(c)
    if (link) await copiar(link, 'novo')
  })
}

function apagar(c) {
  if (window.confirm(`Tirar ${c.nome} do histórico? O arquivo que já está no computador de quem recebeu continua lá.`)) {
    acao(() => backupApi.apagar(c.id))
  }
}

function liberar(c) {
  if (window.confirm('Liberar esta coleta para continuar em outro computador? O computador onde ela começou deixa de poder enviar; o próximo que abrir o link continua de onde parou.')) {
    acao(() => backupApi.liberar(c.id))
  }
}

function cancelar(c) {
  if (window.confirm('Cancelar esta coleta? Os pedaços já gravados na pasta ficam lá (pode apagá-los).')) acao(() => backupApi.cancelar(c.id))
}

function extrair(c) {
  aviso.value = ''
  recepcao.extrair(c.id).catch((e) => (aviso.value = e.message))
}

// ------------------------------------------------------------------ clientes

function editarCliente(c) {
  editando.value = c.nome
  formulario.nome = c.nome
  formulario.pastaSistema = c.pastaSistema || ''
  erroCliente.value = ''
}

function limparFormulario() {
  editando.value = null
  formulario.nome = ''
  formulario.pastaSistema = ''
  erroCliente.value = ''
}

async function salvarCliente() {
  erroCliente.value = ''
  try {
    const corpo = { nome: formulario.nome.trim(), pastaSistema: formulario.pastaSistema.trim() }
    if (editando.value) await backupApi.alterarCliente(editando.value, corpo)
    else await backupApi.criarCliente(corpo)
    if (!clienteEscolhido.value) clienteEscolhido.value = corpo.nome
    limparFormulario()
    await carregar()
  } catch (e) {
    erroCliente.value = mensagemDe(e)
  }
}

function removerCliente(c) {
  if (window.confirm(`Remover o cliente "${c.nome}"? O histórico e os backups dele ficam.`)) acao(() => backupApi.removerCliente(c.nome))
}

// ------------------------------------------------------------------ formato

function bytes(n) {
  if (n == null || isNaN(n)) return '—'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = n
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toLocaleString('pt-BR', { maximumFractionDigits: v < 10 && i > 0 ? 1 : 0 })} ${u[i]}`
}

function duracao(segundos) {
  if (segundos == null || !isFinite(segundos)) return '—'
  const s = Math.round(segundos)
  if (s < 60) return `${s} s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} min`
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')} min`
}

function quando(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
}

/** As pastas marcadas e, com "-", os tipos deixados de fora (ex.: "-.zip" → "sem .zip"). */
const pastas = (lista) => (lista || []).map((p) => (p === SOLTOS ? '(arquivos soltos)' : p.startsWith('-') ? `sem ${p.slice(1)}` : p)).join(', ')

function leitura(c) {
  const p = c.progresso
  if (!p) return { pct: 0, texto: 'Esperando a página do cliente informar o andamento' }
  if (p.fase === 'enviando') return { pct: 100, texto: `Leitura concluída (${bytes(c.totalBytes)})` }
  return { pct: c.totalBytes ? (p.lidoBytes / c.totalBytes) * 100 : 0, texto: `Lidos ${bytes(p.lidoBytes)} de ${bytes(c.totalBytes)}` }
}

function envio(c) {
  const p = c.progresso
  const estimativa = p && p.lidoBytes > 0 && c.totalBytes ? (p.compactadoBytes * c.totalBytes) / p.lidoBytes : null
  return {
    pct: estimativa ? Math.min(100, (c.bytesRecebidos / estimativa) * 100) : 0,
    texto: `Gravados aqui ${bytes(c.bytesRecebidos)}${estimativa ? ` de ~${bytes(estimativa)}` : ''}`,
  }
}

const semContato = (c) => c.situacao === 'RECEBENDO' && c.ultimoContato && Date.now() - new Date(c.ultimoContato).getTime() > 60000

function detalhes(c) {
  const lista = []
  if (c.alterados?.length) lista.push(['Mudaram durante a leitura (sistema em uso)', c.alterados])
  if (c.naoLidos?.length) lista.push(['Não lidos', c.naoLidos])
  const f = c.conferencia
  if (f?.divergentes?.length) lista.push(['Divergentes na conferência', f.divergentes])
  if (f?.faltando?.length) lista.push(['Faltando no arquivo', f.faltando])
  if (f?.sobrando?.length) lista.push(['A mais no arquivo', f.sobrando])
  return lista
}

function alternar(chave) {
  if (abertos.has(chave)) abertos.delete(chave)
  else abertos.add(chave)
}

let relogio = null
onMounted(() => {
  lerDestino()
  carregar()
  relogio = setInterval(() => {
    if (document.visibilityState === 'visible' && !carregando.value && !ocupado.value) carregar()
  }, ATUALIZAR_A_CADA_MS)
})
onBeforeUnmount(() => clearInterval(relogio))
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="flex flex-wrap items-end gap-x-4 gap-y-3 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div class="min-w-0 flex-1">
        <p class="rotulo">Suporte · o backup das pastas do sistema, pelo navegador do cliente</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Backups de clientes</h1>
      </div>
    </header>

    <div class="mt-6">
      <section v-if="!modulos.backup" class="cartao px-5 py-4">
        <p class="font-semibold">Os backups de clientes não estão liberados para você.</p>
        <p class="mt-1 text-sm text-tinta-suave">
          {{ auth.pode(ACESSOS) ? 'Libere em Usuários, no acesso da pessoa ou num perfil (permissão “Coletar backups de clientes”).' : 'Peça a quem cuida dos acessos para liberar.' }}
        </p>
        <RouterLink v-if="auth.pode(ACESSOS)" :to="{ name: 'usuarios' }" class="botao-secundario mt-3 inline-block">Abrir Usuários</RouterLink>
      </section>

      <EstadoDaTela v-else :carregando="carregando && !estado" :erro="erro" :manter="!!estado" carregando-texto="Carregando os backups…" @tentar="carregar">
        <template v-if="estado">
          <section v-if="desatualizada" role="alert" class="cartao mb-4 px-4 py-3 text-sm sm:px-5">
            <strong>O sistema foi atualizado.</strong> Recarregue a página antes de gerar ou copiar links.
            <button type="button" class="botao-primario ml-2" @click="recarregar">Recarregar</button>
          </section>
          <p v-if="aviso" role="alert" class="mb-4 text-sm text-carimbo">{{ aviso }}</p>

          <!-- túnel -->
          <section class="cartao px-4 py-3 sm:px-5" aria-live="polite">
            <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-sm">
              <span class="inline-block size-2.5 rounded-full" :class="tunelOk ? 'bg-credito' : ['INICIANDO', 'VERIFICANDO', 'CAIU'].includes(tunel?.modo) ? 'bg-alerta' : 'bg-carimbo'" aria-hidden="true" />
              <template v-if="tunelOk">
                <span>Endereço público: <strong class="font-mono text-[13px]">{{ tunel.endereco }}</strong></span>
                <button v-if="tunel.modo === 'ATIVO'" type="button" class="text-tinta-suave underline underline-offset-4" :disabled="ocupado" @click="reiniciarTunel">abrir outro túnel</button>
              </template>
              <span v-else>{{ tunel?.mensagem }}</span>
            </div>
            <p v-if="tunel?.modo === 'SEM_CLOUDFLARED'" class="mt-1 text-sm text-tinta-suave">
              Sem o túnel, o link só abre nesta rede. No computador onde o sistema roda, rode <code class="font-mono">expedio tunel</code> num terminal.
            </p>
          </section>

          <!-- pasta de destino -->
          <section class="cartao mt-4 px-4 py-4 sm:px-5">
            <h2 class="text-lg font-bold">Onde o backup fica</h2>
            <template v-if="podeEscolher">
              <div class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-2 text-sm">
                <span>Pasta neste computador:</span>
                <strong v-if="destino" class="font-mono">{{ destino.name }}</strong>
                <span v-else class="text-tinta-suave">nenhuma escolhida</span>
                <button type="button" class="botao-secundario" @click="trocarDestino">{{ destino ? 'Trocar pasta…' : 'Escolher pasta…' }}</button>
                <button v-if="destinoSemPermissao" type="button" class="botao-secundario" @click="confirmarDestino">Permitir de novo</button>
              </div>
              <p class="mt-2 text-xs text-tinta-suave">
                Abre as pastas do seu computador. O backup é gravado por este navegador nessa pasta: no servidor do sistema ele só passa,
                sem ser gravado. Por segurança, o navegador mostra só o nome da pasta, não o caminho inteiro. Deixe o sistema aberto
                enquanto o backup chega (nesta tela ou em outra); se fechar, o cliente espera e continua quando você abrir esta tela de novo.
              </p>
            </template>
            <div v-else class="mt-2 text-sm">
              <p class="text-carimbo">Este navegador não deixa escolher uma pasta do computador neste endereço ({{ origem }}).</p>
              <p class="mt-1 text-tinta-suave">
                O Chrome e o Edge só abrem o seletor de pastas em endereço seguro (https) ou em localhost. Para liberar este endereço,
                uma vez, neste computador: abra <code class="font-mono">chrome://flags/#unsafely-treat-insecure-origin-as-secure</code>
                (no Edge, <code class="font-mono">edge://flags/#unsafely-treat-insecure-origin-as-secure</code>), coloque
                <code class="font-mono">{{ origem }}</code>, mude para <em>Enabled</em> e reinicie o navegador.
              </p>
            </div>
          </section>

          <!-- nova coleta -->
          <section class="cartao mt-4 px-4 py-4 sm:px-5">
            <h2 class="text-lg font-bold">Nova coleta</h2>
            <p v-if="!clientes.length" class="mt-1 text-sm text-tinta-suave">Cadastre o cliente lá embaixo, em Clientes.</p>
            <div v-else class="mt-2 flex flex-wrap items-center gap-2">
              <select v-model="clienteEscolhido" class="campo min-w-56" aria-label="Cliente">
                <option v-for="c in clientes" :key="c.nome" :value="c.nome">{{ c.nome }}</option>
              </select>
              <button type="button" class="botao-primario" :disabled="ocupado || desatualizada || !clienteEscolhido || !podeEscolher || !destino" @click="gerarLink">Gerar link</button>
              <span v-if="podeEscolher && !destino" class="text-sm text-tinta-suave">Escolha antes a pasta deste computador (acima).</span>
            </div>
            <div v-if="coletaGerada && ['AGUARDANDO', 'RECEBENDO'].includes(coletaGerada.situacao)" class="mt-3 border-t border-linha/70 pt-3 text-sm">
              <p>Abra no <strong>Chrome ou Edge</strong> do cliente (pelo WTS no servidor ou pelo UltraViewer). O backup vai para a pasta <strong class="font-mono">{{ destino?.name }}</strong> deste computador.</p>
              <p v-if="!linkAtual" class="mt-2 text-alerta" aria-live="polite">Abrindo o túnel… o link aparece aqui em instantes (não mande nenhum outro antes).</p>
              <div v-else class="mt-2 flex flex-wrap items-center gap-2">
                <input :value="linkAtual" readonly class="campo min-w-0 flex-1 font-mono text-[13px]" aria-label="Link de coleta" @focus="$event.target.select()" />
                <button type="button" class="botao-secundario" @click="copiar(linkAtual, 'novo')">{{ copiado === 'novo' ? 'Copiado' : 'Copiar' }}</button>
              </div>
              <p class="mt-1 text-xs text-tinta-suave">
                <template v-if="coletaGerada.link">Vale por {{ estado.validadeLinkHoras }} h sem uso. Se o sistema reiniciar, o endereço do túnel muda: copie o link de novo aqui (a coleta continua de onde parou).</template>
                <template v-else-if="soNestaRede">Sem túnel: este link só abre nesta rede.</template>
              </p>
            </div>
          </section>

          <!-- coletas -->
          <section class="mt-6">
            <h2 class="text-lg font-bold">Coletas</h2>
            <p v-if="!coletas.length" class="mt-1 text-sm text-tinta-suave">Nenhuma coleta ainda.</p>
            <ul class="mt-2 space-y-3">
              <li v-for="c in coletas" :key="c.id" class="cartao px-4 py-3 sm:px-5">
                <div class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                  <p>
                    <strong>{{ c.cliente }}</strong>
                    <span class="ml-2 text-sm font-semibold" :class="SITUACOES[c.situacao]?.classe">{{ SITUACOES[c.situacao]?.rotulo ?? c.situacao }}</span>
                  </p>
                  <span class="text-xs text-tinta-suave">
                    gerado {{ quando(c.criadaEm) }}<template v-if="c.criadaPor"> por {{ c.criadaPor }}</template>
                    <template v-if="c.conferidaEm"> · chegou {{ quando(c.conferidaEm) }}</template>
                  </span>
                </div>
                <p class="mt-0.5 text-xs text-tinta-suave">
                  <span class="font-mono">{{ c.nome }}.tar.zst</span>
                  <template v-if="recepcao.estados[c.id]?.pasta"> · na pasta <span class="font-mono">{{ recepcao.estados[c.id].pasta }}</span> deste computador</template>
                  <template v-if="c.raiz"> · lido de {{ c.raiz }}</template>
                  <template v-if="c.computador"> · {{ c.computador }}</template>
                  <template v-if="c.pastas?.length"> · {{ pastas(c.pastas) }}</template>
                </p>

                <!-- quem recebe -->
                <template v-if="['AGUARDANDO', 'RECEBENDO', 'FECHANDO'].includes(c.situacao)">
                  <p v-if="!c.minha" class="mt-2 text-sm text-tinta-suave">Vai para o computador de {{ c.criadaPor }}<template v-if="!c.receptorOnline"> (com a tela fechada agora)</template>.</p>
                  <template v-else>
                    <p v-if="!podeEscolher" class="mt-2 text-sm text-carimbo">Este navegador não consegue gravar na pasta (veja “Onde o backup fica”).</p>
                    <p v-else-if="recepcao.estados[c.id]?.fase === 'outro'" class="mt-2 text-sm text-tinta-suave">Esta coleta grava no navegador onde o link foi gerado: abra o sistema nele para ela continuar.</p>
                    <p v-else-if="recepcao.estados[c.id]?.fase === 'permissao'" class="mt-2 text-sm">
                      O navegador pede para confirmar o acesso à pasta <strong class="font-mono">{{ recepcao.estados[c.id].pasta }}</strong>.
                      <button type="button" class="botao-primario ml-2" @click="recepcao.permitir(c.id)">Permitir e continuar</button>
                    </p>
                    <p v-else-if="recepcao.estados[c.id]?.fase === 'montando'" class="mt-2 text-sm text-alerta">
                      {{ recepcao.estados[c.id].mensagem }}
                      <template v-if="recepcao.estados[c.id].progresso"> {{ recepcao.estados[c.id].progresso.parte + 1 }} de {{ recepcao.estados[c.id].progresso.de }} pedaços.</template>
                    </p>
                    <p v-else-if="recepcao.estados[c.id]?.fase === 'erro'" class="mt-2 text-sm text-carimbo">{{ recepcao.estados[c.id].mensagem }}</p>
                    <p v-else-if="recepcao.estados[c.id]?.mensagem" class="mt-2 text-sm text-carimbo">{{ recepcao.estados[c.id].mensagem }}</p>
                  </template>
                </template>

                <div v-if="c.situacao === 'RECEBENDO'" class="mt-3 grid gap-3 sm:grid-cols-2">
                  <div>
                    <div class="h-2 overflow-hidden rounded bg-papel-escuro"><div class="h-full bg-tinta transition-all" :style="{ width: `${leitura(c).pct}%` }" /></div>
                    <p class="mt-1 text-xs text-tinta-suave">{{ leitura(c).texto }}</p>
                  </div>
                  <div>
                    <div class="h-2 overflow-hidden rounded bg-papel-escuro"><div class="h-full bg-credito transition-all" :style="{ width: `${envio(c).pct}%` }" /></div>
                    <p class="mt-1 text-xs text-tinta-suave">
                      {{ envio(c).texto }}<template v-if="c.velocidade > 0"> · {{ bytes(c.velocidade) }}/s</template><template v-if="c.previsaoSegundos != null"> · faltam ~{{ duracao(c.previsaoSegundos) }}</template>
                    </p>
                  </div>
                </div>
                <p v-if="semContato(c)" class="mt-2 text-sm text-carimbo">
                  Sem contato da página do cliente desde {{ quando(c.ultimoContato) }}. Se a aba foi fechada, abra o link de novo: continua de onde parou.
                </p>

                <p v-if="c.resumo && c.situacao !== 'RECEBENDO'" class="mt-2 text-sm">
                  {{ c.resumo.arquivos.toLocaleString('pt-BR') }} arquivos · {{ bytes(c.resumo.bytesOriginais) }} originais<template v-if="c.tamanhoArquivo"> → {{ bytes(c.tamanhoArquivo) }} compactado</template>
                  <span v-if="c.resumo.bytesOriginais && c.tamanhoArquivo" class="text-tinta-suave">({{ (c.resumo.bytesOriginais / c.tamanhoArquivo).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) }}×)</span>
                  <span v-if="c.inicioEnvio && c.conferidaEm" class="text-tinta-suave"> · levou {{ duracao((new Date(c.conferidaEm) - new Date(c.inicioEnvio)) / 1000) }}</span>
                </p>
                <p v-if="c.conferencia" class="mt-1 text-sm" :class="c.situacao === 'INTEGRA' ? 'text-credito' : 'text-carimbo'">{{ c.conferencia.mensagem }}</p>
                <p v-else-if="c.mensagem && c.situacao !== 'FECHANDO'" class="mt-1 text-sm text-tinta-suave">{{ c.mensagem }}</p>
                <p v-if="recepcao.estados[c.id]?.extracao" class="mt-1 text-sm" :class="recepcao.estados[c.id].extracao.situacao === 'FALHOU' ? 'text-carimbo' : ''">
                  <template v-if="recepcao.estados[c.id].extracao.situacao === 'EXTRAINDO'">Extraindo em “{{ recepcao.estados[c.id].extracao.destino }}”… {{ recepcao.estados[c.id].extracao.arquivos }} arquivos, {{ bytes(recepcao.estados[c.id].extracao.bytes) }}</template>
                  <template v-else>{{ recepcao.estados[c.id].extracao.mensagem }}</template>
                </p>

                <div v-for="[titulo, lista] in detalhes(c)" :key="titulo" class="mt-2 text-sm">
                  <button type="button" class="underline underline-offset-4" :aria-expanded="abertos.has(`${c.id}:${titulo}`)" @click="alternar(`${c.id}:${titulo}`)">{{ titulo }} ({{ lista.length }})</button>
                  <ul v-if="abertos.has(`${c.id}:${titulo}`)" class="mt-1 max-h-56 overflow-auto pl-4 font-mono text-xs">
                    <li v-for="item in lista" :key="item" class="list-disc">{{ item }}</li>
                  </ul>
                </div>

                <div class="mt-3 flex flex-wrap gap-2">
                  <template v-if="['AGUARDANDO', 'RECEBENDO'].includes(c.situacao) && c.token">
                    <button type="button" class="botao-primario" :disabled="!linkDe(c)" @click="copiar(linkDe(c), c.id)">
                      {{ copiado === c.id ? 'Copiado' : semChaveAqui(c) ? 'Link só no navegador que o gerou' : c.link ? 'Copiar link' : soNestaRede ? 'Copiar link (só nesta rede)' : 'Esperando o túnel…' }}
                    </button>
                    <button v-if="c.minha && c.vinculada" type="button" class="botao-secundario" :disabled="ocupado" title="A coleta fica presa ao computador do cliente onde começou" @click="liberar(c)">Liberar para outro computador</button>
                  </template>
                  <button v-if="['AGUARDANDO', 'RECEBENDO', 'FECHANDO'].includes(c.situacao)" type="button" class="botao-secundario text-carimbo" :disabled="ocupado" @click="cancelar(c)">Cancelar</button>
                  <button
                    v-if="['INTEGRA', 'DIVERGENTE'].includes(c.situacao) && c.minha && podeEscolher && recepcao.estados[c.id]?.pasta"
                    type="button"
                    class="botao-primario"
                    :disabled="recepcao.estados[c.id]?.extracao?.situacao === 'EXTRAINDO'"
                    @click="extrair(c)"
                  >Extrair em…</button>
                  <button
                    v-if="estado.podeApagar && ['INTEGRA', 'DIVERGENTE', 'CANCELADA'].includes(c.situacao)"
                    type="button"
                    class="botao-secundario"
                    :disabled="ocupado"
                    @click="apagar(c)"
                  >Tirar do histórico</button>
                </div>
              </li>
            </ul>
          </section>

          <!-- clientes -->
          <section class="cartao mt-6 px-4 py-4 sm:px-5">
            <h2 class="text-lg font-bold">Clientes</h2>
            <table v-if="clientes.length" class="mt-2 w-full text-sm">
              <thead>
                <tr class="text-left text-xs text-tinta-suave">
                  <th class="py-1.5 pr-3 font-semibold">Nome</th>
                  <th class="py-1.5 pr-3 font-semibold">Pasta do sistema no cliente</th>
                  <th class="hidden py-1.5 pr-3 font-semibold sm:table-cell">Última seleção</th>
                  <th />
                </tr>
              </thead>
              <tbody class="divide-y divide-linha/70 border-t border-linha/70">
                <tr v-for="c in clientes" :key="c.nome">
                  <td class="py-2 pr-3 font-semibold">{{ c.nome }}</td>
                  <td class="py-2 pr-3 font-mono text-xs">{{ c.pastaSistema || '—' }}</td>
                  <td class="hidden py-2 pr-3 text-xs text-tinta-suave sm:table-cell">{{ pastas(c.ultimaSelecao) || '—' }}</td>
                  <td class="py-2 text-right whitespace-nowrap">
                    <button type="button" class="text-sm underline underline-offset-4" @click="editarCliente(c)">editar</button>
                    <button type="button" class="ml-3 text-sm text-carimbo underline underline-offset-4" @click="removerCliente(c)">remover</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <form class="mt-3 flex flex-wrap items-end gap-2" @submit.prevent="salvarCliente">
              <label class="text-xs text-tinta-suave">Nome
                <input v-model="formulario.nome" maxlength="80" required class="campo mt-1 block w-56" placeholder="Nome do cliente" />
              </label>
              <label class="text-xs text-tinta-suave">Pasta do sistema no cliente (lembrete na página de coleta)
                <input v-model="formulario.pastaSistema" maxlength="200" class="campo mt-1 block w-72 font-mono" placeholder="G:\sislojas3" />
              </label>
              <button type="submit" class="botao-primario">{{ editando ? 'Salvar' : 'Cadastrar' }}</button>
              <button v-if="editando" type="button" class="botao-secundario" @click="limparFormulario">Cancelar</button>
            </form>
            <p v-if="erroCliente" role="alert" class="mt-2 text-sm text-carimbo">{{ erroCliente }}</p>
          </section>
        </template>
      </EstadoDaTela>
    </div>
  </div>
</template>
