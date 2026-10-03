<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { dataBR } from '@/utils/horas'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'

/**
 * Logs do sistema (administrador).
 *
 * "Ao vivo": o que o servidor está registrando agora, com filtro por pessoa, por gravidade e por texto — é o
 * lugar para colar o protocolo que apareceu na tela de alguém. "Arquivos guardados": o histórico, uma pasta por
 * pessoa, um arquivo por hora.
 */
const ABAS = [
  { valor: 'ao-vivo', rotulo: 'Ao vivo' },
  { valor: 'arquivos', rotulo: 'Arquivos guardados' },
]
const aba = ref('ao-vivo') // 'ao-vivo' | 'arquivos'

// ------------------------------------------------------------------ ao vivo
const MAXIMO_NA_TELA = 1500
const linhas = ref([])
const ultima = ref(0)
const pausado = ref(false)
const acompanhar = ref(true) // rola para o fim a cada linha nova
const erroAoVivo = ref(null)
const carregouUmaVez = ref(false)
const filtroLogin = ref('')
const filtroNivel = ref('INFO')
const filtroTexto = ref('')
const abertas = ref(new Set()) // linhas com o detalhe técnico aberto
const quadro = ref(null)
const pessoas = ref([])
let timer = null
let pedido = 0
let buscando = false

const niveis = [
  { valor: 'INFO', rotulo: 'Tudo' },
  { valor: 'ACOES', rotulo: 'Sem as consultas (só o que muda algo)' },
  { valor: 'WARN', rotulo: 'Avisos e erros' },
  { valor: 'ERROR', rotulo: 'Só erros' },
]

async function buscar() {
  if (buscando || pausado.value || aba.value !== 'ao-vivo') return
  buscando = true
  const meu = pedido
  try {
    const dados = await pontoApi.logsAoVivo({
      depois: ultima.value,
      nivel: filtroNivel.value === 'ACOES' ? 'INFO' : filtroNivel.value,
      semConsultas: filtroNivel.value === 'ACOES' || undefined,
      login: filtroLogin.value || undefined,
      texto: filtroTexto.value.trim() || undefined,
    })
    if (meu !== pedido) return // o filtro mudou enquanto a resposta vinha
    ultima.value = dados.ultima
    if (dados.linhas.length) {
      linhas.value = [...linhas.value, ...dados.linhas].slice(-MAXIMO_NA_TELA)
      if (acompanhar.value) {
        await nextTick()
        if (quadro.value) quadro.value.scrollTop = quadro.value.scrollHeight
      }
    }
    erroAoVivo.value = null
    carregouUmaVez.value = true
  } catch (e) {
    if (meu === pedido) erroAoVivo.value = e
  } finally {
    buscando = false
  }
}

/** Filtro novo: a lista recomeça com o que o servidor ainda tem na memória. */
function recomecar() {
  pedido++
  buscando = false
  linhas.value = []
  ultima.value = 0
  carregouUmaVez.value = false
  abertas.value = new Set()
  buscar()
}

let timerTexto = null
watch([filtroLogin, filtroNivel], recomecar)
watch(filtroTexto, () => {
  clearTimeout(timerTexto)
  timerTexto = setTimeout(recomecar, 400)
})
watch(pausado, (parado) => !parado && buscar())

function limparTela() {
  linhas.value = []
  abertas.value = new Set()
}

function alternar(seq) {
  const novo = new Set(abertas.value)
  if (novo.has(seq)) novo.delete(seq)
  else novo.add(seq)
  abertas.value = novo
}

const hora = (iso) => new Date(iso).toLocaleTimeString('pt-BR', { hour12: false })
/** Etiqueta do nível e cor do texto da linha (as mesmas nos dois temas: vêm dos tokens). */
const classeNivel = (nivel) => ({
  ERROR: 'bg-negativo-solido text-white',
  WARN: 'bg-atencao-suave text-atencao ring-1 ring-atencao-borda',
  INFO: 'bg-neutro text-texto-2',
}[nivel] ?? 'bg-neutro text-texto-3')
const corDaLinha = (nivel) => ({
  ERROR: 'text-negativo',
  WARN: 'text-atencao',
  INFO: 'text-texto',
}[nivel] ?? 'text-texto-3')
const rotuloNivel = (nivel) => ({ ERROR: 'ERRO', WARN: 'AVISO', INFO: 'INFO' }[nivel] ?? nivel)
const situacaoAoVivo = computed(() => {
  if (pausado.value) return { selo: 'selo-neutro', texto: 'Pausado' }
  if (erroAoVivo.value) return { selo: 'selo-negativo', texto: 'Sem resposta do servidor' }
  return { selo: 'selo-positivo', texto: 'Ao vivo', pisca: true }
})

// ------------------------------------------------------------------ arquivos
const arquivos = ref(null)
const erroArquivos = ref(null)
const carregandoArquivos = ref(false)
const pasta = ref('') // login escolhido
const dia = ref('')
const conteudo = ref(null)
const erroConteudo = ref(null)
const carregandoConteudo = ref(false)
/** A última hora pedida (para o "Tentar de novo" quando o arquivo não abre). */
const horaPedida = ref(null)

async function carregarArquivos() {
  carregandoArquivos.value = true
  try {
    arquivos.value = await pontoApi.arquivosDeLog()
    erroArquivos.value = null
    if (!arquivos.value.usuarios.some((u) => u.login === pasta.value)) {
      pasta.value = arquivos.value.usuarios[0]?.login ?? ''
    }
  } catch (e) {
    erroArquivos.value = e
  } finally {
    carregandoArquivos.value = false
  }
}

const pastaEscolhida = computed(() => arquivos.value?.usuarios.find((u) => u.login === pasta.value) ?? null)
const diaEscolhido = computed(() => pastaEscolhida.value?.dias.find((d) => d.data === dia.value) ?? null)
watch(pastaEscolhida, (p) => {
  if (!p?.dias.some((d) => d.data === dia.value)) dia.value = p?.dias[0]?.data ?? ''
  conteudo.value = null
  erroConteudo.value = null
})
watch(dia, () => {
  conteudo.value = null
  erroConteudo.value = null
})

async function abrirHora(h) {
  horaPedida.value = h
  carregandoConteudo.value = true
  erroConteudo.value = null
  try {
    conteudo.value = await pontoApi.arquivoDeLog(pasta.value, dia.value, h.hora)
  } catch (e) {
    conteudo.value = null
    erroConteudo.value = e
  } finally {
    carregandoConteudo.value = false
  }
}

const duasCasas = (n) => String(n).padStart(2, '0')
const tamanho = (bytes) => (bytes < 1024 ? `${bytes} B` : bytes < 1_048_576 ? `${Math.round(bytes / 1024)} KB` : `${(bytes / 1_048_576).toFixed(1)} MB`)
const nomeDaPasta = (login) => (login === 'sistema' ? 'sistema (não é de uma pessoa)' : login)

watch(aba, (qual) => {
  if (qual === 'arquivos') carregarArquivos()
  else buscar()
})

// ------------------------------------------------------------------ ciclo de vida
onMounted(async () => {
  buscar()
  timer = setInterval(buscar, 2000)
  try {
    pessoas.value = (await pontoApi.usuarios()).map((u) => u.login).sort()
  } catch {
    // sem a lista, o filtro por pessoa fica só com "todos" e "sistema"
  }
})
onBeforeUnmount(() => {
  clearInterval(timer)
  clearTimeout(timerTexto)
})
</script>

<template>
  <main class="pagina">
    <header>
      <h1 class="titulo-pagina">Logs do sistema</h1>
      <p class="subtitulo-pagina">O registro de tudo o que o sistema fez: quem fez o quê, o que foi recusado e o que deu errado.</p>
    </header>

    <div class="flex flex-wrap gap-2" role="tablist" aria-label="O que ver">
      <button
        v-for="t in ABAS"
        :key="t.valor"
        type="button"
        role="tab"
        :aria-selected="aba === t.valor"
        class="pilula"
        :class="{ 'pilula-ativa': aba === t.valor }"
        @click="aba = t.valor"
      >{{ t.rotulo }}</button>
    </div>

    <!-- ============================================================ ao vivo -->
    <section v-if="aba === 'ao-vivo'" class="cartao p-5 sm:p-6" aria-labelledby="titulo-ao-vivo">
      <h2 id="titulo-ao-vivo" class="titulo-secao">O que está acontecendo agora</h2>
      <p class="mt-1 text-[0.95rem] text-texto-3">
        As linhas novas aparecem sozinhas. Alguém viu uma mensagem com <b class="text-texto-2">protocolo</b>? Cole o código na busca para achar a linha exata.
      </p>

      <div class="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-[12rem_19rem_minmax(0,1fr)]">
        <div>
          <label class="rotulo" for="log-pessoa">De quem</label>
          <select id="log-pessoa" v-model="filtroLogin" class="campo mt-1.5">
            <option value="">Todas as pessoas</option>
            <option value="sistema">sistema</option>
            <option v-for="p in pessoas" :key="p" :value="p">{{ p }}</option>
          </select>
        </div>
        <div>
          <label class="rotulo" for="log-nivel">O que mostrar</label>
          <select id="log-nivel" v-model="filtroNivel" class="campo mt-1.5">
            <option v-for="n in niveis" :key="n.valor" :value="n.valor">{{ n.rotulo }}</option>
          </select>
        </div>
        <div class="sm:col-span-2 xl:col-span-1">
          <label class="rotulo" for="log-busca">Buscar um texto ou um protocolo</label>
          <input id="log-busca" v-model="filtroTexto" type="search" class="campo mt-1.5" placeholder="Por exemplo: K7M2QX" spellcheck="false" autocomplete="off" />
        </div>
      </div>

      <div class="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2">
        <button type="button" class="min-h-11" :class="pausado ? 'botao-primario' : 'botao-secundario'" @click="pausado = !pausado">
          {{ pausado ? 'Continuar' : 'Pausar' }}
        </button>
        <button type="button" class="botao-secundario min-h-11" @click="limparTela">Limpar a tela</button>
        <label class="flex min-h-11 cursor-pointer items-center gap-2 text-[0.95rem]">
          <input v-model="acompanhar" type="checkbox" class="size-4 accent-botao" /> Acompanhar as linhas novas
        </label>
        <p class="ml-auto flex flex-wrap items-center gap-x-3 gap-y-1 text-[0.95rem] text-texto-3" role="status">
          <span class="selo" :class="situacaoAoVivo.selo">
            <span class="size-2 rounded-full bg-current" :class="{ 'animate-pulse': situacaoAoVivo.pisca }" aria-hidden="true" />
            {{ situacaoAoVivo.texto }}
          </span>
          <span>{{ linhas.length === 1 ? '1 linha' : `${linhas.length} linhas` }} na tela</span>
        </p>
      </div>

      <div v-if="erroAoVivo" class="mt-3">
        <EstadoDaTela :erro="erroAoVivo" manter @tentar="buscar" />
      </div>

      <div
        ref="quadro"
        class="mt-3 h-[60vh] min-h-72 overflow-auto rounded-xl border border-borda bg-superficie-2 p-2 font-mono text-sm leading-relaxed text-texto"
        tabindex="0"
        role="region"
        aria-label="Linhas de log"
      >
        <p v-if="!linhas.length" class="px-2 py-6 text-center font-sans text-[0.95rem] text-texto-3">
          {{ carregouUmaVez ? 'Nenhuma linha com este filtro por enquanto. As novas aparecem aqui sozinhas.' : 'Carregando…' }}
        </p>
        <div v-for="l in linhas" :key="l.seq" class="rounded-lg px-1.5 py-0.5 hover:bg-neutro">
          <div class="flex flex-wrap items-baseline gap-x-2">
            <span class="text-texto-3">{{ hora(l.quando) }}</span>
            <span class="rounded-md px-1.5 text-[0.8rem] font-bold" :class="classeNivel(l.nivel)">{{ rotuloNivel(l.nivel) }}</span>
            <button
              type="button"
              class="font-semibold text-primaria hover:underline"
              :aria-label="`Ver só as linhas de ${l.usuario}`"
              title="Ver só as linhas desta pessoa"
              @click="filtroLogin = l.usuario"
            >{{ l.usuario }}</button>
            <button
              v-if="l.protocolo"
              type="button"
              class="text-texto-2 underline decoration-borda-forte underline-offset-2 hover:decoration-texto-2"
              :aria-label="`Ver só as linhas do protocolo ${l.protocolo}`"
              title="Ver só as linhas deste protocolo"
              @click="filtroTexto = l.protocolo"
            >{{ l.protocolo }}</button>
            <span class="text-texto-3">{{ l.origem }}</span>
            <span class="min-w-0 flex-1 basis-64 break-words whitespace-pre-wrap" :class="corDaLinha(l.nivel)">{{ l.mensagem }}</span>
            <button v-if="l.erro" type="button" class="font-sans font-semibold text-primaria underline underline-offset-2" :aria-expanded="abertas.has(l.seq)" @click="alternar(l.seq)">
              {{ abertas.has(l.seq) ? 'Esconder o detalhe' : 'Ver o detalhe técnico' }}
            </button>
          </div>
          <pre v-if="l.erro && abertas.has(l.seq)" class="mt-1 overflow-x-auto rounded-lg border border-borda bg-neutro p-2 text-[0.8rem] text-texto-2">{{ l.erro }}</pre>
        </div>
      </div>
      <p class="mt-2 text-sm text-texto-3">
        Clique no nome de uma pessoa ou num protocolo para ver só as linhas dele. Aqui ficam as últimas 3.000 linhas
        desde que o sistema foi ligado; o histórico completo está em “Arquivos guardados”.
      </p>
    </section>

    <!-- ============================================================ arquivos -->
    <section v-else class="cartao p-5 sm:p-6" aria-labelledby="titulo-arquivos">
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="titulo-arquivos" class="titulo-secao">Arquivos guardados</h2>
          <p class="mt-1 text-[0.95rem] text-texto-3">O histórico completo: uma pasta por pessoa, um arquivo para cada hora do dia.</p>
        </div>
        <button v-if="arquivos" type="button" class="botao-secundario min-h-11" :disabled="carregandoArquivos" @click="carregarArquivos">
          <Icone nome="atualizar" tamanho="18" /> {{ carregandoArquivos ? 'Atualizando…' : 'Atualizar a lista' }}
        </button>
      </div>

      <div class="mt-4">
        <EstadoDaTela
          :carregando="carregandoArquivos"
          :erro="erroArquivos"
          :manter="!!arquivos"
          carregando-texto="Lendo a pasta de logs…"
          @tentar="carregarArquivos"
        >
          <template v-if="arquivos">
            <p class="rounded-xl bg-superficie-2 px-4 py-3 text-[0.95rem] text-texto-2">
              No computador do servidor, os arquivos ficam em
              <span class="font-mono text-sm break-all text-texto">{{ arquivos.pasta }}</span>
              e são apagados depois de {{ arquivos.diasGuardados }} dias.
            </p>

            <p v-if="!arquivos.usuarios.length" class="py-8 text-center text-[0.95rem] text-texto-3">
              Ainda não há arquivos de log guardados.
            </p>

            <div v-else class="mt-4 grid gap-5 lg:grid-cols-[18rem_minmax(0,1fr)]">
              <div class="flex flex-col gap-3">
                <div>
                  <label class="rotulo" for="arquivo-pessoa">De quem</label>
                  <select id="arquivo-pessoa" v-model="pasta" class="campo mt-1.5">
                    <option v-for="u in arquivos.usuarios" :key="u.login" :value="u.login">{{ nomeDaPasta(u.login) }}</option>
                  </select>
                </div>
                <div v-if="pastaEscolhida">
                  <label class="rotulo" for="arquivo-dia">Dia</label>
                  <select id="arquivo-dia" v-model="dia" class="campo mt-1.5">
                    <option v-for="d in pastaEscolhida.dias" :key="d.data" :value="d.data">{{ dataBR(d.data) }}</option>
                  </select>
                </div>
                <div v-if="diaEscolhido" role="group" aria-labelledby="rotulo-hora">
                  <p id="rotulo-hora" class="rotulo">Hora</p>
                  <ul class="mt-1.5 grid grid-cols-3 gap-2 lg:grid-cols-2">
                    <li v-for="h in diaEscolhido.horas" :key="h.hora">
                      <button
                        type="button"
                        class="flex min-h-11 w-full flex-col rounded-xl border-2 px-3 py-1.5 text-left transition-colors"
                        :class="conteudo?.hora === h.hora && conteudo?.data === dia && conteudo?.login === pasta
                          ? 'border-primaria bg-primaria-suave'
                          : 'border-borda bg-superficie hover:bg-neutro'"
                        :aria-pressed="conteudo?.hora === h.hora && conteudo?.data === dia && conteudo?.login === pasta"
                        :aria-label="`Abrir o arquivo das ${duasCasas(h.hora)} horas (${tamanho(h.bytes)})`"
                        :disabled="carregandoConteudo"
                        @click="abrirHora(h)"
                      >
                        <span class="font-bold">{{ duasCasas(h.hora) }}h</span>
                        <span class="text-sm text-texto-3">{{ tamanho(h.bytes) }}</span>
                      </button>
                    </li>
                  </ul>
                </div>
              </div>

              <div class="min-w-0">
                <EstadoDaTela
                  v-if="carregandoConteudo || erroConteudo"
                  :carregando="carregandoConteudo"
                  :erro="erroConteudo"
                  carregando-texto="Abrindo o arquivo…"
                  @tentar="abrirHora(horaPedida)"
                />
                <p v-else-if="!conteudo" class="rounded-xl border border-dashed border-borda-forte px-4 py-8 text-center text-[0.95rem] text-texto-3">
                  Escolha uma hora para ver o que aconteceu nela.
                </p>
                <template v-else>
                  <p class="text-[0.95rem]">
                    <b>{{ nomeDaPasta(conteudo.login) }}</b> · {{ dataBR(conteudo.data) }} · das {{ duasCasas(conteudo.hora) }}:00 às {{ duasCasas(conteudo.hora) }}:59
                    <span class="text-texto-3">({{ tamanho(conteudo.bytes) }})</span>
                  </p>
                  <p v-if="conteudo.cortado" class="aviso-atencao mt-2">
                    O arquivo é grande: aqui aparece só o fim dele. O arquivo inteiro está na pasta do servidor.
                  </p>
                  <pre class="mt-2 max-h-[62vh] overflow-auto rounded-xl border border-borda bg-superficie-2 p-3 font-mono text-sm leading-relaxed whitespace-pre-wrap text-texto" tabindex="0" aria-label="Conteúdo do arquivo de log">{{ conteudo.texto || '(arquivo vazio)' }}</pre>
                </template>
              </div>
            </div>
          </template>
        </EstadoDaTela>
      </div>
    </section>
  </main>
</template>
