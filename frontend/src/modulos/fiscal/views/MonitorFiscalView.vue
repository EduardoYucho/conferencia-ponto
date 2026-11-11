<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { useAuthStore } from '@/stores/auth'
import { fiscalApi } from '@/modulos/fiscal/api'
import { hora, haQuanto, PERIODOS, SITUACOES } from '@/modulos/fiscal/formato'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import QuadroDosEstados from '@/modulos/fiscal/components/QuadroDosEstados.vue'
import DetalheDoEstado from '@/modulos/fiscal/components/DetalheDoEstado.vue'
import ListaDeIncidentes from '@/modulos/fiscal/components/ListaDeIncidentes.vue'
import TabelaDeDisponibilidade from '@/modulos/fiscal/components/TabelaDeDisponibilidade.vue'
import { mensagemDe } from '@/utils/erros'
import { ACESSOS } from '@/utils/permissoes'

/**
 * Monitor fiscal: a situação da SEFAZ por estado e documento (NF-e, NFC-e, CT-e, MDF-e), lida a cada 5 minutos
 * dos painéis públicos da SVRS e por teste de conexão nos outros autorizadores. A tela se atualiza sozinha a cada
 * minuto (enquanto está à vista). A aba, o período e o estado aberto ficam no endereço da página.
 */
const auth = useAuthStore()
const modulos = useAcessoModulosStore()
const route = useRoute()
const router = useRouter()

const ABAS = [
  { id: 'quadro', rotulo: 'Quadro' },
  { id: 'incidentes', rotulo: 'Incidentes' },
  { id: 'disponibilidade', rotulo: 'Disponibilidade' },
]
const ATUALIZAR_A_CADA_MS = 60_000

const aba = computed(() => (ABAS.some((a) => a.id === route.query.aba) ? route.query.aba : 'quadro'))
const dias = computed(() => (PERIODOS.some((p) => String(p.dias) === route.query.dias) ? Number(route.query.dias) : 1))
const ufAberta = computed(() => (typeof route.query.uf === 'string' ? route.query.uf : null))

const painel = ref(null)
const carregando = ref(false)
const erro = ref(null)
const lista = ref(null)
const carregandoLista = ref(false)
const erroLista = ref(null)
const soProblemas = ref(false)
const lendo = ref(false)
const mensagemLeitura = ref('')
const verFontes = ref(false)
/** Incidentes de uma leitura ruim só, já fechados (um teste lento, uma recusa que passou): escondidos de início. */
const mostrarOscilacoes = ref(false)
const agora = ref(new Date())

function mudarRota(mudancas) {
  const query = { ...route.query, ...mudancas }
  for (const [c, v] of Object.entries(query)) if (v === null || v === undefined || v === '') delete query[c]
  router.replace({ name: 'monitor-fiscal', query })
}

async function carregarPainel() {
  carregando.value = true
  try {
    painel.value = await fiscalApi.painel()
    erro.value = null
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
    agora.value = new Date()
  }
}

async function carregarLista() {
  if (aba.value === 'quadro') return
  const pedida = `${aba.value}:${dias.value}`
  carregandoLista.value = true
  try {
    const dados = aba.value === 'incidentes' ? await fiscalApi.incidentes(dias.value) : await fiscalApi.disponibilidade(dias.value)
    if (pedida === `${aba.value}:${dias.value}`) {
      lista.value = { chave: pedida, linhas: dados ?? [] }
      erroLista.value = null
    }
  } catch (e) {
    erroLista.value = e
  } finally {
    carregandoLista.value = false
  }
}

function atualizar() {
  carregarPainel()
  carregarLista()
}

async function lerAgora() {
  lendo.value = true
  mensagemLeitura.value = ''
  try {
    const rodada = await fiscalApi.coletar()
    mensagemLeitura.value = rodada?.semInternet
      ? 'Leitura feita, mas sem internet neste computador: nada pôde ser conferido.'
      : rodada?.fontesComProblema
        ? `Leitura feita. ${rodada.fontesComProblema} ${rodada.fontesComProblema === 1 ? 'fonte teve problema' : 'fontes tiveram problema'}.`
        : 'Leitura feita.'
    atualizar()
  } catch (e) {
    mensagemLeitura.value = mensagemDe(e)
  } finally {
    lendo.value = false
  }
}

// atualiza sozinha a cada minuto, só com a aba do navegador à vista
let relogio = null
function aoMudarVisibilidade() {
  if (document.visibilityState === 'visible' && modulos.fiscal) atualizar()
}
onMounted(() => {
  if (modulos.fiscal) atualizar()
  relogio = setInterval(() => {
    agora.value = new Date()
    if (document.visibilityState === 'visible' && modulos.fiscal && !carregando.value) atualizar()
  }, ATUALIZAR_A_CADA_MS)
  document.addEventListener('visibilitychange', aoMudarVisibilidade)
})
onBeforeUnmount(() => {
  clearInterval(relogio)
  document.removeEventListener('visibilitychange', aoMudarVisibilidade)
})
watch([aba, dias], () => {
  lista.value = null
  carregarLista()
})

const ultima = computed(() => painel.value?.ultimaRodada ?? null)
/** A última leitura ficou velha: o sistema (ou este computador) pode ter ficado parado. */
const atrasada = computed(() => {
  if (!ultima.value || !painel.value?.coletaAtiva) return false
  const minutos = (agora.value.getTime() - new Date(ultima.value.fim).getTime()) / 60000
  return minutos > 3 * (painel.value.intervaloMinutos || 5)
})
const fontesComProblema = computed(() => (painel.value?.fontes ?? []).filter((f) => f.erro))
const contagem = computed(() => painel.value?.contagem ?? null)
const linhas = computed(() => (lista.value?.chave === `${aba.value}:${dias.value}` ? lista.value.linhas : []))
const oscilacao = (i) => !i.aberto && i.leituras < 2
const oscilacoes = computed(() => (aba.value === 'incidentes' ? linhas.value.filter(oscilacao).length : 0))
const linhasVisiveis = computed(() =>
  aba.value === 'incidentes' && !mostrarOscilacoes.value ? linhas.value.filter((i) => !oscilacao(i)) : linhas.value,
)
/** Os comunicados iguais em vários portais (o mesmo "não há manutenção prevista") viram uma linha só. */
const avisos = computed(() => {
  const porTexto = new Map()
  for (const a of painel.value?.avisos ?? []) {
    const g = porTexto.get(a.texto) ?? { texto: a.texto, documentos: [], desde: a.desde }
    g.documentos.push(a.rotuloDocumento)
    if (a.desde < g.desde) g.desde = a.desde
    porTexto.set(a.texto, g)
  }
  return [...porTexto.values()].map((g) => ({ ...g, semManutencao: /^n[ãa]o h[áa] manuten/i.test(g.texto) }))
})
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="flex flex-wrap items-end gap-x-4 gap-y-3 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div class="min-w-0 flex-1">
        <p class="rotulo">Situação da SEFAZ por estado e documento</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Monitor fiscal</h1>
      </div>
      <button v-if="modulos.configurarFiscal" type="button" class="botao-secundario" :disabled="lendo" @click="lerAgora">
        {{ lendo ? 'Lendo…' : 'Ler agora' }}
      </button>
    </header>

    <div class="mt-6">
      <section v-if="!modulos.fiscal" class="cartao px-5 py-4">
        <p class="font-semibold">O monitor fiscal não está liberado para você.</p>
        <p class="mt-1 text-sm text-tinta-suave">
          {{ auth.pode(ACESSOS) ? 'Libere em Usuários, no acesso da pessoa ou num perfil (inclusive para você mesmo).' : 'Peça a quem cuida dos acessos para liberar.' }}
        </p>
        <RouterLink v-if="auth.pode(ACESSOS)" :to="{ name: 'usuarios' }" class="botao-secundario mt-3 inline-block">Abrir Usuários</RouterLink>
      </section>

      <EstadoDaTela v-else :carregando="carregando && !painel" :erro="erro" :manter="!!painel" carregando-texto="Lendo a situação…" @tentar="atualizar">
        <template v-if="painel">
          <!-- a última leitura -->
          <section class="cartao px-4 py-3 sm:px-5" aria-live="polite">
            <p v-if="!ultima" class="text-sm">
              <strong>Ainda sem leitura.</strong>
              <span class="text-tinta-suave"> A primeira sai uns 30 segundos depois que o sistema sobe, e depois a cada {{ painel.intervaloMinutos }} min.</span>
            </p>
            <p v-else-if="ultima.semInternet" role="alert" class="text-sm text-carimbo">
              <strong>Sem internet neste computador</strong> na última leitura ({{ hora(ultima.fim, agora) }}): nada pôde ser
              conferido, e o quadro mostra “sem informação” até a próxima.
            </p>
            <div v-else class="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-sm">
              <span>Última leitura às <strong class="carimbo">{{ hora(ultima.fim, agora) }}</strong> <span class="text-tinta-suave">({{ haQuanto(ultima.fim, agora) }})</span></span>
              <span class="text-tinta-suave">· a cada {{ painel.intervaloMinutos }} min</span>
              <button
                v-if="fontesComProblema.length"
                type="button"
                class="font-semibold text-carimbo underline underline-offset-4"
                :aria-expanded="verFontes"
                aria-controls="fiscal-fontes"
                @click="verFontes = !verFontes"
              >{{ fontesComProblema.length }} {{ fontesComProblema.length === 1 ? 'fonte com problema' : 'fontes com problema' }}</button>
              <button v-else type="button" class="text-tinta-suave underline underline-offset-4" :aria-expanded="verFontes" aria-controls="fiscal-fontes" @click="verFontes = !verFontes">fontes</button>
            </div>
            <p v-if="painel.redeOscilando && !ultima?.semInternet" role="alert" class="mt-1 text-sm text-carimbo">
              Na última leitura, muitos servidores ficaram sem resposta ao mesmo tempo: foi a internet deste computador
              que oscilou, não a SEFAZ. Eles aparecem como “sem informação” até a próxima leitura.
            </p>
            <p v-if="!painel.coletaAtiva" class="mt-1 text-sm text-tinta-suave">A coleta automática está desligada nesta máquina; a tela mostra o que já foi lido.</p>
            <p v-else-if="atrasada" class="mt-1 text-sm text-carimbo">
              A última leitura foi {{ haQuanto(ultima.fim, agora) }}: o sistema pode ter ficado parado. As leituras só
              acontecem com este computador ligado.
            </p>
            <ul v-if="verFontes" id="fiscal-fontes" class="mt-2 divide-y divide-linha/70 border-t border-linha/70 text-sm">
              <li v-for="f in painel.fontes" :key="f.nome" class="flex flex-wrap gap-x-2 py-1.5">
                <span class="font-semibold">{{ f.rotulo }}</span>
                <span v-if="f.erro" class="text-carimbo">{{ f.erro }}</span>
                <span v-else class="text-credito">ok</span>
                <span class="basis-full text-xs text-tinta-suave">
                  Última tentativa {{ hora(f.ultimaTentativa, agora) }}<template v-if="f.erro && f.ultimoSucesso"> · último sucesso {{ hora(f.ultimoSucesso, agora) }}</template>
                </span>
              </li>
            </ul>
            <p v-if="mensagemLeitura" role="status" class="mt-2 text-sm font-semibold">{{ mensagemLeitura }}</p>
          </section>

          <!-- comunicados dos portais -->
          <section v-if="avisos.length" class="mt-3 cartao px-4 py-3 sm:px-5" aria-label="Comunicados dos portais">
            <p class="rotulo">Comunicados dos portais</p>
            <ul class="mt-1 grid gap-1 text-sm">
              <li v-for="a in avisos" :key="a.texto" :class="a.semManutencao ? 'text-tinta-suave' : ''">
                <strong :class="a.semManutencao ? 'font-semibold' : ''">{{ a.documentos.join(', ') }}:</strong> {{ a.texto }}
                <span class="text-xs text-tinta-suave">(desde {{ hora(a.desde, agora) }})</span>
              </li>
            </ul>
          </section>

          <!-- abas -->
          <nav class="mt-5 flex flex-wrap items-center gap-x-1 gap-y-2 border-b border-linha" aria-label="Partes do monitor">
            <button
              v-for="a in ABAS"
              :key="a.id"
              type="button"
              class="-mb-px border-b-2 px-3 py-2 text-sm font-semibold transition"
              :class="aba === a.id ? 'border-tinta text-tinta' : 'border-transparent text-tinta-suave hover:text-tinta'"
              :aria-current="aba === a.id ? 'page' : undefined"
              @click="mudarRota({ aba: a.id === 'quadro' ? null : a.id })"
            >{{ a.rotulo }}</button>
          </nav>

          <!-- quadro -->
          <section v-if="aba === 'quadro'" class="mt-4">
            <div class="mb-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
              <p v-if="contagem" class="flex flex-wrap gap-x-3 gap-y-1">
                <span v-for="s in SITUACOES" :key="s.codigo" class="inline-flex items-center gap-1.5">
                  <span class="inline-block size-3 rounded-[2px]" :class="s.classe" aria-hidden="true" />
                  <span>{{ s.rotulo }}</span>
                  <span class="carimbo font-semibold">{{ { FORA: contagem.fora, INSTAVEL: contagem.instavel, NORMAL: contagem.normal, SEM_INFORMACAO: contagem.semInformacao }[s.codigo] }}</span>
                </span>
              </p>
              <label class="ml-auto inline-flex items-center gap-2">
                <input v-model="soProblemas" type="checkbox" class="size-4 accent-tinta" />
                Só estados com problema
              </label>
            </div>
            <QuadroDosEstados
              :estados="painel.estados"
              :documentos="painel.documentos"
              :so-problemas="soProblemas"
              :selecionado="ufAberta"
              @abrir="(uf) => mudarRota({ uf })"
            />
            <p class="mt-2 text-xs text-tinta-suave">
              Cada célula mostra quem autoriza o documento naquele estado (a SEFAZ do estado, a SVRS, a SVAN ou a SVSP),
              na cor da situação. Os contadores são por autorizador: uma queda na SVRS aparece em vários estados ao mesmo tempo.
              Verde não garante que a nota passa (cadastro e regras fiscais continuam valendo).
            </p>
          </section>

          <!-- incidentes e disponibilidade -->
          <section v-else class="mt-4">
            <div class="mb-3 flex flex-wrap items-center gap-2" role="group" aria-label="Período">
              <span class="rotulo mr-1">Período</span>
              <button
                v-for="p in PERIODOS"
                :key="p.dias"
                type="button"
                class="rounded-[3px] border px-2.5 py-1 text-sm"
                :class="dias === p.dias ? 'border-tinta bg-tinta text-cartao' : 'border-linha bg-cartao hover:border-tinta'"
                :aria-pressed="dias === p.dias"
                @click="mudarRota({ dias: p.dias === 1 ? null : String(p.dias) })"
              >{{ p.rotulo }}</button>
            </div>
            <label v-if="oscilacoes" class="mb-3 flex items-center gap-2 text-sm">
              <input v-model="mostrarOscilacoes" type="checkbox" class="size-4 accent-tinta" />
              Mostrar também as oscilações (uma leitura ruim só, já resolvida): {{ oscilacoes }}
            </label>
            <EstadoDaTela
              :carregando="carregandoLista && !linhas.length"
              :erro="erroLista"
              :manter="linhasVisiveis.length > 0"
              :vazio="!linhasVisiveis.length"
              :vazio-texto="aba === 'incidentes' ? 'Nenhum incidente no período.' : 'Ainda sem leituras no período.'"
              @tentar="carregarLista"
            >
              <ListaDeIncidentes v-if="aba === 'incidentes'" :incidentes="linhasVisiveis" :agora="agora" />
              <TabelaDeDisponibilidade v-else :linhas="linhasVisiveis" />
            </EstadoDaTela>
          </section>
        </template>
      </EstadoDaTela>
    </div>

    <DetalheDoEstado v-if="painel && ufAberta && aba === 'quadro'" :uf="ufAberta" :painel="painel" :agora="agora" @fechar="mudarRota({ uf: null })" />
  </div>
</template>
