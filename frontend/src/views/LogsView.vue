<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { mensagemDe } from '@/utils/erros'
import EstadoDaTela from '@/components/EstadoDaTela.vue'

/**
 * Logs do sistema (administrador).
 *
 * "Ao vivo": o que o servidor está registrando agora, com filtro por pessoa, por gravidade e por texto — é o
 * lugar para colar o protocolo que apareceu na tela de alguém. "Arquivos": o que ficou guardado, uma pasta por
 * pessoa, um arquivo por hora.
 */
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
const classeNivel = (nivel) => ({
  ERROR: 'bg-carimbo text-white',
  WARN: 'bg-amber-500 text-black',
  INFO: 'bg-papel-escuro text-tinta-suave',
}[nivel] ?? 'bg-papel-escuro text-tinta-apagada')
const rotuloNivel = (nivel) => ({ ERROR: 'ERRO', WARN: 'AVISO', INFO: 'INFO' }[nivel] ?? nivel)

// ------------------------------------------------------------------ arquivos
const arquivos = ref(null)
const erroArquivos = ref(null)
const carregandoArquivos = ref(false)
const pasta = ref('') // login escolhido
const dia = ref('')
const conteudo = ref(null)
const erroConteudo = ref('')
const carregandoConteudo = ref(false)

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
  erroConteudo.value = ''
})
watch(dia, () => {
  conteudo.value = null
  erroConteudo.value = ''
})

async function abrirHora(h) {
  carregandoConteudo.value = true
  erroConteudo.value = ''
  try {
    conteudo.value = await pontoApi.arquivoDeLog(pasta.value, dia.value, h.hora)
  } catch (e) {
    conteudo.value = null
    erroConteudo.value = mensagemDe(e)
  } finally {
    carregandoConteudo.value = false
  }
}

const dataBR = (iso) => iso?.split('-').reverse().join('/')
const duasCasas = (n) => String(n).padStart(2, '0')
const tamanho = (bytes) => (bytes < 1024 ? `${bytes} B` : bytes < 1_048_576 ? `${Math.round(bytes / 1024)} KB` : `${(bytes / 1_048_576).toFixed(1)} MB`)
const nomeDaPasta = (login) => (login === 'sistema' ? 'sistema (o que não é de uma pessoa)' : login)

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
  <main class="mx-auto max-w-6xl px-4 py-6 sm:px-6">
    <header>
      <p class="rotulo">Administração</p>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%]">Logs do sistema</h1>
      <p class="mt-2 max-w-3xl text-sm text-tinta-suave">
        Tudo o que o sistema registra: quem fez o quê, o que foi recusado e o que deu errado. Quando alguém vir uma
        mensagem com <b>protocolo</b>, cole o código na busca para achar a linha exata.
      </p>
    </header>

    <div class="mt-5 flex gap-1 border-b border-linha" role="tablist">
      <button
        v-for="t in [{ valor: 'ao-vivo', rotulo: 'Ao vivo' }, { valor: 'arquivos', rotulo: 'Arquivos por pessoa e hora' }]"
        :key="t.valor"
        type="button"
        role="tab"
        :aria-selected="aba === t.valor"
        class="-mb-px border-b-2 px-3 py-2 text-sm font-semibold transition"
        :class="aba === t.valor ? 'border-tinta text-tinta' : 'border-transparent text-tinta-suave hover:text-tinta'"
        @click="aba = t.valor"
      >{{ t.rotulo }}</button>
    </div>

    <!-- ============================================================ ao vivo -->
    <section v-if="aba === 'ao-vivo'" class="mt-4" aria-label="Log ao vivo">
      <div class="flex flex-wrap items-end gap-3">
        <label class="block">
          <span class="rotulo block">Pessoa</span>
          <select v-model="filtroLogin" class="campo mt-1 w-44 py-1.5! font-sans text-sm">
            <option value="">Todas</option>
            <option value="sistema">sistema</option>
            <option v-for="p in pessoas" :key="p" :value="p">{{ p }}</option>
          </select>
        </label>
        <label class="block">
          <span class="rotulo block">Mostrar</span>
          <select v-model="filtroNivel" class="campo mt-1 w-64 py-1.5! font-sans text-sm">
            <option v-for="n in niveis" :key="n.valor" :value="n.valor">{{ n.rotulo }}</option>
          </select>
        </label>
        <label class="block min-w-48 flex-1">
          <span class="rotulo block">Buscar (texto ou protocolo)</span>
          <input v-model="filtroTexto" type="search" class="campo mt-1 py-1.5! font-sans text-sm" placeholder="ex.: K7M2QX" spellcheck="false" />
        </label>
        <div class="flex items-center gap-2 pb-0.5">
          <button type="button" class="py-1.5! text-xs" :class="pausado ? 'botao-primario' : 'botao-secundario'" @click="pausado = !pausado">
            {{ pausado ? 'Continuar' : 'Pausar' }}
          </button>
          <button type="button" class="botao-secundario py-1.5! text-xs" @click="limparTela">Limpar a tela</button>
        </div>
      </div>

      <p class="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-tinta-suave" role="status">
        <span class="flex items-center gap-1.5">
          <span class="size-2 rounded-full" :class="pausado ? 'bg-tinta-apagada' : erroAoVivo ? 'bg-carimbo' : 'animate-pulse bg-credito'" aria-hidden="true" />
          {{ pausado ? 'Pausado' : erroAoVivo ? 'Sem resposta do servidor' : 'Ao vivo: atualiza a cada 2 segundos' }}
        </span>
        <span>{{ linhas.length }} linha(s) na tela</span>
        <label class="flex items-center gap-1.5">
          <input v-model="acompanhar" type="checkbox" class="accent-tinta" /> acompanhar as linhas novas
        </label>
      </p>

      <div v-if="erroAoVivo" class="mt-3">
        <EstadoDaTela :erro="erroAoVivo" manter @tentar="buscar" />
      </div>

      <div
        ref="quadro"
        class="mt-3 h-[60vh] min-h-72 overflow-auto rounded-[3px] border border-linha bg-[#1d1c1a] p-2 font-mono text-[0.78rem] leading-relaxed text-[#e9e4d8]"
        tabindex="0"
        aria-label="Linhas de log"
      >
        <p v-if="!linhas.length" class="px-2 py-6 text-center text-[#9a9282]">
          {{ carregouUmaVez ? 'Nenhuma linha com este filtro por enquanto. As novas aparecem aqui sozinhas.' : 'Carregando…' }}
        </p>
        <div v-for="l in linhas" :key="l.seq" class="rounded-[2px] px-1.5 py-0.5 hover:bg-white/5">
          <div class="flex flex-wrap items-baseline gap-x-2">
            <span class="text-[#9a9282]">{{ hora(l.quando) }}</span>
            <span class="rounded-[2px] px-1 text-[0.65rem] font-bold" :class="classeNivel(l.nivel)">{{ rotuloNivel(l.nivel) }}</span>
            <button
              type="button"
              class="font-semibold text-[#8fc7a8] hover:underline"
              title="Filtrar por esta pessoa"
              @click="filtroLogin = l.usuario"
            >{{ l.usuario }}</button>
            <button
              v-if="l.protocolo"
              type="button"
              class="text-[#d9b36a] hover:underline"
              title="Ver só as linhas deste protocolo"
              @click="filtroTexto = l.protocolo"
            >{{ l.protocolo }}</button>
            <span class="text-[#9a9282]">{{ l.origem }}</span>
            <span class="min-w-0 flex-1 basis-64 break-words whitespace-pre-wrap" :class="{ 'text-[#ff9d95]': l.nivel === 'ERROR', 'text-[#f0cf85]': l.nivel === 'WARN' }">{{ l.mensagem }}</span>
            <button v-if="l.erro" type="button" class="text-[#9a9282] underline underline-offset-2" @click="alternar(l.seq)">
              {{ abertas.has(l.seq) ? 'ocultar detalhe' : 'detalhe técnico' }}
            </button>
          </div>
          <pre v-if="l.erro && abertas.has(l.seq)" class="mt-1 overflow-x-auto rounded-[2px] bg-black/40 p-2 text-[0.72rem] text-[#c9c2b3]">{{ l.erro }}</pre>
        </div>
      </div>
      <p class="mt-2 text-xs text-tinta-suave">
        Aqui ficam as últimas 3.000 linhas desde que o sistema subiu. O histórico completo está na aba
        "Arquivos por pessoa e hora".
      </p>
    </section>

    <!-- ============================================================ arquivos -->
    <section v-else class="mt-4" aria-label="Arquivos de log">
      <EstadoDaTela
        :carregando="carregandoArquivos"
        :erro="erroArquivos"
        :manter="!!arquivos"
        carregando-texto="Lendo a pasta de logs…"
        @tentar="carregarArquivos"
      >
        <template v-if="arquivos">
          <p class="text-sm text-tinta-suave">
            Uma pasta por pessoa, um arquivo por hora. No computador do servidor ficam em
            <code class="carimbo rounded-[2px] bg-papel px-1.5 py-0.5 text-xs break-all text-tinta">{{ arquivos.pasta }}</code>
            e são apagados depois de {{ arquivos.diasGuardados }} dias.
            <button type="button" class="ml-1 font-semibold text-tinta underline underline-offset-4" :disabled="carregandoArquivos" @click="carregarArquivos">
              {{ carregandoArquivos ? 'atualizando…' : 'atualizar a lista' }}
            </button>
          </p>

          <p v-if="!arquivos.usuarios.length" class="py-6 text-center text-sm text-tinta-suave">
            Ainda não há arquivos de log guardados.
          </p>

          <div v-else class="mt-4 grid gap-4 lg:grid-cols-[16rem_1fr]">
            <div class="space-y-3">
              <label class="block">
                <span class="rotulo">Pessoa</span>
                <select v-model="pasta" class="campo mt-1 py-1.5! font-sans text-sm">
                  <option v-for="u in arquivos.usuarios" :key="u.login" :value="u.login">{{ nomeDaPasta(u.login) }}</option>
                </select>
              </label>
              <label v-if="pastaEscolhida" class="block">
                <span class="rotulo">Dia</span>
                <select v-model="dia" class="campo mt-1 py-1.5! font-sans text-sm">
                  <option v-for="d in pastaEscolhida.dias" :key="d.data" :value="d.data">{{ dataBR(d.data) }}</option>
                </select>
              </label>
              <div v-if="diaEscolhido">
                <p class="rotulo">Hora</p>
                <ul class="mt-1 grid grid-cols-3 gap-1.5 lg:grid-cols-2">
                  <li v-for="h in diaEscolhido.horas" :key="h.hora">
                    <button
                      type="button"
                      class="w-full rounded-[3px] border px-2 py-1.5 text-left text-sm transition"
                      :class="conteudo?.hora === h.hora && conteudo?.data === dia && conteudo?.login === pasta
                        ? 'border-tinta bg-tinta text-cartao'
                        : 'border-linha bg-cartao hover:border-tinta'"
                      :disabled="carregandoConteudo"
                      @click="abrirHora(h)"
                    >
                      <span class="carimbo font-semibold">{{ duasCasas(h.hora) }}h</span>
                      <span class="block text-xs opacity-70">{{ tamanho(h.bytes) }}</span>
                    </button>
                  </li>
                </ul>
              </div>
            </div>

            <div class="min-w-0">
              <p v-if="erroConteudo" role="alert" class="rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erroConteudo }}</p>
              <p v-else-if="carregandoConteudo" class="py-6 text-center text-sm text-tinta-suave">Abrindo o arquivo…</p>
              <p v-else-if="!conteudo" class="py-6 text-center text-sm text-tinta-suave">Escolha uma hora para ver o que aconteceu nela.</p>
              <template v-else>
                <p class="text-sm">
                  <b>{{ nomeDaPasta(conteudo.login) }}</b> · {{ dataBR(conteudo.data) }} · das {{ duasCasas(conteudo.hora) }}:00 às {{ duasCasas(conteudo.hora) }}:59
                  <span class="text-tinta-suave">({{ tamanho(conteudo.bytes) }})</span>
                </p>
                <p v-if="conteudo.cortado" class="mt-1 text-xs text-amber-800">
                  O arquivo é grande: está sendo mostrado só o fim dele. O arquivo inteiro está na pasta do servidor.
                </p>
                <pre class="mt-2 max-h-[62vh] overflow-auto rounded-[3px] border border-linha bg-[#1d1c1a] p-3 font-mono text-[0.76rem] leading-relaxed whitespace-pre-wrap text-[#e9e4d8]">{{ conteudo.texto || '(arquivo vazio)' }}</pre>
              </template>
            </div>
          </div>
        </template>
      </EstadoDaTela>
    </section>
  </main>
</template>
