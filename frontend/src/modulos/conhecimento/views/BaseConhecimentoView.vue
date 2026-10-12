<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { useAuthStore } from '@/stores/auth'
import { conhecimentoApi } from '@/modulos/conhecimento/api'
import { data, porcentagem, SITUACOES_DO_REGISTRO, situacaoDoRegistro, TIPOS_DO_REGISTRO } from '@/modulos/conhecimento/formato'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import TextoDestacado from '@/modulos/conhecimento/components/TextoDestacado.vue'

/**
 * A base de conhecimento: a pesquisa exata (palavras sem diferença de acento, código de tela ou um pedaço dele,
 * mensagem de erro mesmo com uma palavra diferente, cliente) com os filtros, e os resultados com o trecho que bateu
 * destacado. A pesquisa fica no endereço da página: voltar de um registro mostra a mesma lista.
 */
const auth = useAuthStore()
const modulos = useAcessoModulosStore()
const route = useRoute()
const router = useRouter()

const FILTROS = ['q', 'tipo', 'situacao', 'cliente', 'modulo', 'autor', 'de', 'ate', 'validadas', 'pagina']

function vazios() {
  return { q: '', tipo: '', situacao: '', cliente: '', modulo: '', autor: '', de: '', ate: '', validadas: false }
}

const filtros = ref(vazios())
const resultado = ref(null)
const autores = ref([])
const carregando = ref(false)
const erro = ref(null)
const verFiltros = ref(false)

const comFiltro = computed(() => Object.entries(filtros.value).some(([c, v]) => c !== 'q' && v))
const pesquisado = computed(() => FILTROS.some((c) => route.query[c]))

function daRota(query) {
  const f = vazios()
  for (const c of Object.keys(f)) {
    if (c === 'validadas') f[c] = query[c] === 'true'
    else f[c] = typeof query[c] === 'string' ? query[c] : ''
  }
  return f
}

/** A pesquisa vai para o endereço; quem carrega é o watch da rota. */
function pesquisar(pagina = 1) {
  const query = {}
  for (const [c, v] of Object.entries(filtros.value)) {
    if (c === 'validadas') {
      if (v) query[c] = 'true'
    } else if (String(v).trim()) {
      query[c] = String(v).trim()
    }
  }
  if (pagina > 1) query.pagina = String(pagina)
  if (mesmaPesquisa(query, route.query)) carregar() // o endereço não muda: pesquisa de novo
  else router.replace({ name: 'base-conhecimento', query })
}

function mesmaPesquisa(a, b) {
  const chaves = new Set([...Object.keys(a), ...Object.keys(b)])
  return [...chaves].every((c) => String(a[c] ?? '') === String(b[c] ?? ''))
}

function limpar() {
  const q = filtros.value.q
  filtros.value = { ...vazios(), q }
  pesquisar()
}

async function carregar() {
  if (!modulos.pesquisar) return
  carregando.value = true
  erro.value = null
  try {
    resultado.value = await conhecimentoApi.pesquisar({ ...daRota(route.query), pagina: route.query.pagina })
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}

async function carregarAutores() {
  try {
    autores.value = await conhecimentoApi.autores()
  } catch {
    autores.value = [] // sem a lista, o filtro por autor some; o resto funciona
  }
}

watch(() => route.query, (query) => {
  if (route.name !== 'base-conhecimento') return
  filtros.value = daRota(query)
  if (comFiltro.value) verFiltros.value = true
  carregar()
}, { immediate: false })

watch(() => modulos.pesquisar, (pode) => {
  if (pode) {
    carregar()
    carregarAutores()
  }
})

onMounted(() => {
  filtros.value = daRota(route.query)
  verFiltros.value = comFiltro.value
  if (modulos.pesquisar) {
    carregar()
    carregarAutores()
  }
})

const situacao = (codigo) => situacaoDoRegistro(codigo) ?? { rotulo: codigo, classe: 'border-linha' }
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <p class="rotulo">Erros e solicitações já atendidos</p>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Base de conhecimento</h1>
    </header>

    <div class="mt-6">
      <EstadoDaTela :carregando="!modulos.carregado" :erro="modulos.erro" carregando-texto="Conferindo o seu acesso…" @tentar="modulos.carregar()">
        <section v-if="!modulos.pesquisar" class="cartao px-5 py-4">
          <p class="font-semibold">A base de conhecimento não está liberada para você.</p>
          <p class="mt-1 text-sm text-tinta-suave">
            {{ auth.ehAdmin ? 'Libere em Acessos (inclusive para você mesmo).' : 'Peça ao administrador para liberar.' }}
          </p>
          <RouterLink v-if="auth.ehAdmin" :to="{ name: 'acessos-modulos' }" class="botao-secundario mt-3 inline-block">Abrir Acessos</RouterLink>
        </section>

        <template v-else>
          <!-- pesquisa -->
          <form class="cartao px-4 py-4 sm:px-5" role="search" aria-label="Pesquisar na base" @submit.prevent="pesquisar()">
            <label for="base-q" class="rotulo">Pesquisar</label>
            <div class="mt-1 flex flex-wrap gap-2">
              <input
                id="base-q"
                v-model="filtros.q"
                type="search"
                class="campo min-w-0 flex-1 font-sans! text-sm!"
                placeholder="Código da tela, mensagem de erro, cliente ou palavras"
                autocomplete="off"
              />
              <button type="submit" class="botao-primario" :disabled="carregando">{{ carregando ? 'Pesquisando…' : 'Pesquisar' }}</button>
            </div>
            <p class="mt-1 text-xs text-tinta-suave">
              Sem diferença de acento e de maiúsculas. Um pedaço do código acha a tela (<span class="carimbo">eqr7</span> acha
              <span class="carimbo">EQR76</span>), e a mensagem de erro é encontrada mesmo com uma palavra diferente.
            </p>

            <div class="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
              <button type="button" class="font-semibold underline underline-offset-4" :aria-expanded="verFiltros" aria-controls="base-filtros" @click="verFiltros = !verFiltros">
                {{ verFiltros ? 'Esconder os filtros' : 'Filtros' }}
              </button>
              <button v-if="comFiltro" type="button" class="text-xs underline underline-offset-4" @click="limpar">Limpar os filtros</button>
            </div>

            <div v-show="verFiltros" id="base-filtros" class="mt-3 grid gap-3 border-t border-linha pt-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
              <div>
                <label for="base-tipo" class="rotulo">Tipo</label>
                <select id="base-tipo" v-model="filtros.tipo" class="campo mt-1 font-sans! text-sm!">
                  <option value="">Todos</option>
                  <option v-for="t in TIPOS_DO_REGISTRO" :key="t.codigo" :value="t.codigo">{{ t.rotulo }}</option>
                </select>
              </div>
              <div>
                <label for="base-situacao" class="rotulo">Situação</label>
                <select id="base-situacao" v-model="filtros.situacao" class="campo mt-1 font-sans! text-sm!">
                  <option value="">Todas</option>
                  <option v-for="s in SITUACOES_DO_REGISTRO" :key="s.codigo" :value="s.codigo">{{ s.rotulo }}</option>
                </select>
              </div>
              <div>
                <label for="base-cliente" class="rotulo">Cliente</label>
                <input id="base-cliente" v-model="filtros.cliente" type="text" class="campo mt-1 font-sans! text-sm!" />
              </div>
              <div>
                <label for="base-modulo" class="rotulo">Módulo</label>
                <input id="base-modulo" v-model="filtros.modulo" type="text" class="campo mt-1 font-sans! text-sm!" />
              </div>
              <div v-if="autores.length">
                <label for="base-autor" class="rotulo">Autor</label>
                <select id="base-autor" v-model="filtros.autor" class="campo mt-1 font-sans! text-sm!">
                  <option value="">Todos</option>
                  <option v-for="a in autores" :key="a.id" :value="a.id">{{ a.nome }}</option>
                </select>
              </div>
              <div>
                <label for="base-de" class="rotulo">De</label>
                <input id="base-de" v-model="filtros.de" type="date" class="campo mt-1 font-sans! text-sm!" />
              </div>
              <div>
                <label for="base-ate" class="rotulo">Até</label>
                <input id="base-ate" v-model="filtros.ate" type="date" class="campo mt-1 font-sans! text-sm!" />
              </div>
              <label class="flex items-center gap-2 self-end pb-2">
                <input v-model="filtros.validadas" type="checkbox" class="size-4 accent-tinta" />
                Só soluções validadas
              </label>
              <div class="sm:col-span-2 lg:col-span-4">
                <button type="submit" class="botao-secundario" :disabled="carregando">Aplicar os filtros</button>
              </div>
            </div>
          </form>

          <!-- resultados -->
          <div class="mt-4">
            <EstadoDaTela
              :carregando="carregando && !resultado"
              :erro="erro"
              :vazio="!!resultado && !resultado.registros.length"
              :manter="!!resultado?.registros.length"
              carregando-texto="Pesquisando…"
              @tentar="carregar"
            >
              <template #vazio>
                <template v-if="pesquisado">Nada encontrado. Tente outras palavras, um pedaço do código ou tire filtros.<template v-if="resultado?.significado && !resultado.significado.ativa"> {{ resultado.significado.aviso }}</template></template>
                <template v-else>A base ainda não tem registros. Eles nascem quando alguém confirma um texto no gerador de atendimentos.</template>
              </template>

              <template v-if="resultado">
                <p class="text-sm text-tinta-suave" role="status">
                  <b class="text-tinta">{{ resultado.total }}</b> {{ resultado.total === 1 ? 'registro' : 'registros' }}<template v-if="resultado.comTexto">, do que mais bate para o que menos bate</template><template v-else>, do mais novo para o mais antigo</template>.
                </p>
                <p v-if="resultado.significado" class="mt-1 text-xs" :class="resultado.significado.ativa ? 'text-credito' : 'text-tinta-suave'" aria-label="Pesquisa por significado">
                  <template v-if="resultado.significado.ativa">Pesquisa por significado ligada: entram também os casos com outras palavras e o mesmo assunto.</template>
                  <template v-else>{{ resultado.significado.aviso }}</template>
                  <span v-if="resultado.significado.pendentes" class="ml-1">{{ resultado.significado.pendentes === 1 ? '1 registro ainda está' : `${resultado.significado.pendentes} registros ainda estão` }} sem o cálculo do significado (entram só pela pesquisa exata até o vetor ser gerado, com a chave do autor).</span>
                </p>

                <ul class="mt-3 grid gap-3" aria-label="Registros encontrados">
                  <li v-for="r in resultado.registros" :key="r.id">
                    <RouterLink :to="{ name: 'registro-da-base', params: { id: r.id } }" class="cartao block px-4 py-3 hover:bg-papel-escuro/30 sm:px-5">
                      <div class="flex flex-wrap items-start justify-between gap-2">
                        <h2 class="min-w-0 flex-1 font-sans text-base leading-snug font-bold break-words">
                          <TextoDestacado :partes="r.titulo" />
                        </h2>
                        <div class="flex min-w-0 flex-wrap gap-1.5">
                          <span class="rounded-[2px] border border-linha px-1.5 py-0.5 text-xs font-semibold">{{ r.tipoRotulo }}</span>
                          <span class="rounded-[2px] border px-1.5 py-0.5 text-xs font-bold tracking-wide uppercase" :class="situacao(r.situacao).classe">{{ r.situacaoRotulo }}</span>
                          <span v-if="r.solucaoValidada" class="rounded-[2px] border border-credito/50 px-1.5 py-0.5 text-xs font-semibold text-credito">✓ solução validada</span>
                          <span v-if="r.similaridade != null" class="rounded-[2px] border border-tinta/30 px-1.5 py-0.5 text-xs" :title="r.porSignificado ? 'Nenhuma palavra bateu: entrou pelo assunto' : 'Bateu no texto e também pelo assunto'">
                            {{ r.porSignificado ? 'pelo significado' : 'parecido' }} {{ porcentagem(r.similaridade) }}
                          </span>
                        </div>
                      </div>
                      <p class="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-tinta-suave">
                        <span v-if="r.telaCodigo.length || r.telaNome">
                          <span v-if="r.telaCodigo.length" class="carimbo font-semibold text-tinta"><TextoDestacado :partes="r.telaCodigo" /></span>
                          <span v-if="r.telaNome" :class="r.telaCodigo.length ? 'ml-1.5' : ''">{{ r.telaNome }}</span>
                        </span>
                        <span v-if="r.cliente">Cliente: {{ r.cliente }}</span>
                        <span v-if="r.modulo">{{ r.modulo }}</span>
                        <span v-if="r.chamadoDev">Chamado: {{ r.chamadoDev }}<template v-if="r.versaoCorrecao"> · corrigido na {{ r.versaoCorrecao }}</template></span>
                      </p>
                      <p v-if="r.trecho.length" class="mt-2 text-sm break-words"><TextoDestacado :partes="r.trecho" /></p>
                      <p class="mt-2 text-[0.7rem] text-tinta-apagada">{{ r.autor }} · {{ data(r.criadoEm) }}</p>
                    </RouterLink>
                  </li>
                </ul>

                <nav v-if="resultado.paginas > 1" class="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm" aria-label="Páginas">
                  <button type="button" class="botao-secundario" :disabled="carregando || resultado.pagina <= 1" @click="pesquisar(resultado.pagina - 1)">Anterior</button>
                  <span class="text-tinta-suave">Página {{ resultado.pagina }} de {{ resultado.paginas }}</span>
                  <button type="button" class="botao-secundario" :disabled="carregando || resultado.pagina >= resultado.paginas" @click="pesquisar(resultado.pagina + 1)">Próxima</button>
                </nav>
              </template>
            </EstadoDaTela>
          </div>
        </template>
      </EstadoDaTela>
    </div>
  </div>
</template>
