<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { conhecimentoApi } from '@/modulos/conhecimento/api'
import {
  dataHora,
  formularioDoRegistro,
  LIMITES_DO_REGISTRO,
  mesmoFormulario,
  problemasDoRegistro,
  SITUACOES_DO_REGISTRO,
  situacaoDoRegistro,
} from '@/modulos/conhecimento/formato'
import { mensagemDe } from '@/utils/erros'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import FormularioDoRegistro from '@/modulos/conhecimento/components/FormularioDoRegistro.vue'

/**
 * Um registro da base: a leitura (identificação, problema, resolução, o texto confirmado e as imagens), a situação no
 * desenvolvimento com o histórico, a validação da solução, a edição e apagar. Quem pesquisa lê; o autor e quem cura a
 * base mudam. Se outra pessoa mudou antes, o servidor recusa e a tela oferece recarregar.
 */
const props = defineProps({
  id: { type: String, required: true },
})

const router = useRouter()
const modulos = useAcessoModulosStore()

const registro = ref(null)
const carregando = ref(true)
const erro = ref(null)
const acao = ref('')
const mensagem = ref(null)
const conflito = ref(false)

const editando = ref(false)
const form = ref(formularioDoRegistro(null))
const mudandoSituacao = ref(false)
const situacao = ref({ situacao: '', chamadoDev: '', versaoCorrecao: '' })
const confirmandoApagar = ref(false)
const miniaturas = ref({})

const problemas = computed(() => problemasDoRegistro(form.value))
const situacaoAtual = computed(() => situacaoDoRegistro(registro.value?.situacao) ?? { rotulo: registro.value?.situacao, classe: '' })
const chamadoLongo = computed(() => situacao.value.chamadoDev.trim().length > LIMITES_DO_REGISTRO.chamadoDev)
const versaoLonga = computed(() => situacao.value.versaoCorrecao.trim().length > 40)

const CAMPOS = [
  { campo: 'cliente', rotulo: 'Cliente' },
  { campo: 'contato', rotulo: 'Contato' },
  { campo: 'modulo', rotulo: 'Módulo' },
  { campo: 'telaNome', rotulo: 'Tela' },
  { campo: 'telaCodigo', rotulo: 'Código da tela', carimbo: true },
]
const TEXTOS = [
  { campo: 'sintoma', rotulo: 'Sintoma' },
  { campo: 'mensagemErro', rotulo: 'Mensagem de erro', literal: true },
  { campo: 'causa', rotulo: 'Causa' },
  { campo: 'resolucao', rotulo: 'Resolução' },
]

async function carregar() {
  carregando.value = !registro.value
  erro.value = null
  try {
    aplicar(await conhecimentoApi.registro(props.id))
    conflito.value = false
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}

/** Depois de um conflito: a versão atual, sem o formulário aberto (a pessoa refaz a mudança sobre ela). */
function recarregar() {
  mensagem.value = null
  conflito.value = false
  editando.value = false
  mudandoSituacao.value = false
  carregar()
}

function aplicar(view) {
  registro.value = view
  carregarMiniaturas()
}

async function carregarMiniaturas() {
  for (const imagem of registro.value?.imagens ?? []) {
    if (!imagem.disponivel || miniaturas.value[imagem.numero]) continue
    try {
      const resposta = await conhecimentoApi.imagem(props.id, imagem.numero)
      miniaturas.value = { ...miniaturas.value, [imagem.numero]: URL.createObjectURL(resposta.data) }
    } catch {
      // sem miniatura: a imagem continua listada com o número e a legenda
    }
  }
}

function limparMiniaturas() {
  Object.values(miniaturas.value).forEach((url) => URL.revokeObjectURL(url))
  miniaturas.value = {}
}

onMounted(() => {
  if (modulos.pesquisar) carregar()
})
watch(() => modulos.pesquisar, (pode) => pode && carregar())
watch(() => props.id, () => {
  limparMiniaturas()
  registro.value = null
  editando.value = false
  carregar()
})
onUnmounted(limparMiniaturas)

async function executar(nome, chamada, sucesso) {
  acao.value = nome
  mensagem.value = null
  try {
    const resultado = await chamada()
    if (sucesso) mensagem.value = { tipo: 'ok', texto: sucesso }
    return resultado
  } catch (e) {
    conflito.value = e?.status === 409 || e?.codigo === 'REGISTRO_JA_ALTERADO'
    mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
    return null
  } finally {
    acao.value = ''
  }
}

// ------------------------------------------------------------------------------------------- edição

function editar() {
  form.value = formularioDoRegistro(registro.value)
  editando.value = true
  mudandoSituacao.value = false
}

async function salvar() {
  if (mesmoFormulario(form.value, formularioDoRegistro(registro.value))) {
    editando.value = false
    return
  }
  const view = await executar('salvar', () => conhecimentoApi.editar(props.id, registro.value.versao, form.value), 'Registro salvo.')
  if (view) {
    aplicar(view)
    editando.value = false
  }
}

// ------------------------------------------------------------------------------------------- situação e validação

function abrirSituacao() {
  situacao.value = {
    situacao: registro.value.situacao,
    chamadoDev: registro.value.chamadoDev ?? '',
    versaoCorrecao: registro.value.versaoCorrecao ?? '',
  }
  mudandoSituacao.value = true
  editando.value = false
}

async function salvarSituacao() {
  const s = situacao.value
  const view = await executar('situacao', () => conhecimentoApi.mudarSituacao(props.id, registro.value.versao, s.situacao,
    s.chamadoDev, s.situacao === 'corrigido' ? s.versaoCorrecao : null), 'Situação salva.')
  if (view) {
    aplicar(view)
    mudandoSituacao.value = false
  }
}

async function validar(validada) {
  const view = await executar('validar', () => conhecimentoApi.validar(props.id, registro.value.versao, validada),
    validada ? 'Solução marcada como validada.' : 'A marca de solução validada saiu.')
  if (view) aplicar(view)
}

async function apagar() {
  const ok = await executar('apagar', async () => {
    await conhecimentoApi.apagar(props.id)
    return true
  })
  if (ok) router.replace({ name: 'base-conhecimento' })
}

function voltar() {
  if (window.history.state?.back) router.back()
  else router.push({ name: 'base-conhecimento' })
}

function descricaoDaMudanca(m) {
  const partes = [m.de ? `de ${m.deRotulo} para ${m.paraRotulo}` : `criado como ${m.paraRotulo}`]
  if (m.chamadoDev) partes.push(`chamado ${m.chamadoDev}`)
  if (m.versaoCorrecao) partes.push(`versão ${m.versaoCorrecao}`)
  return partes.join(' · ')
}
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <button type="button" class="text-xs font-semibold underline underline-offset-4" @click="voltar">← Voltar à pesquisa</button>
      <p class="rotulo mt-3">Base de conhecimento<template v-if="registro"> · {{ registro.tipoRotulo }}</template></p>
      <h1 class="mt-1 font-sans text-2xl leading-tight font-extrabold tracking-tight break-words [font-stretch:85%] sm:text-3xl">
        {{ registro?.titulo ?? 'Registro' }}
      </h1>
      <div v-if="registro" class="mt-2 flex flex-wrap items-center gap-1.5">
        <span class="rounded-[2px] border px-1.5 py-0.5 text-xs font-bold tracking-wide uppercase" :class="situacaoAtual.classe">{{ registro.situacaoRotulo }}</span>
        <span v-if="registro.solucaoValidada" class="rounded-[2px] border border-credito/50 px-1.5 py-0.5 text-xs font-semibold text-credito">✓ solução validada</span>
        <span class="text-xs text-tinta-suave">por {{ registro.autor.nome }} em {{ dataHora(registro.criadoEm) }}<template v-if="registro.atualizadoEm && registro.atualizadoEm !== registro.criadoEm"> · alterado em {{ dataHora(registro.atualizadoEm) }}</template></span>
      </div>
    </header>

    <div class="mt-6">
      <EstadoDaTela :carregando="!modulos.carregado" :erro="modulos.erro" carregando-texto="Conferindo o seu acesso…" @tentar="modulos.carregar()">
        <section v-if="!modulos.pesquisar" class="cartao px-5 py-4">
          <p class="font-semibold">A base de conhecimento não está liberada para você.</p>
          <p class="mt-1 text-sm text-tinta-suave">Peça ao administrador para liberar.</p>
        </section>

        <EstadoDaTela v-else :carregando="carregando" :erro="erro" :manter="!!registro" carregando-texto="Abrindo o registro…" @tentar="carregar">
          <div v-if="registro" class="grid gap-4">
            <!-- ações -->
            <div v-if="registro.podeEditar && !editando" class="flex flex-wrap gap-2">
              <button type="button" class="botao-secundario" :disabled="!!acao" @click="editar">Editar</button>
              <button v-if="!mudandoSituacao" type="button" class="botao-secundario" :disabled="!!acao" @click="abrirSituacao">Mudar a situação</button>
              <button v-if="!registro.solucaoValidada" type="button" class="botao-secundario" :disabled="!!acao" @click="validar(true)">
                {{ acao === 'validar' ? 'Marcando…' : 'Marcar a solução como validada' }}
              </button>
              <button v-else type="button" class="botao-secundario" :disabled="!!acao" @click="validar(false)">
                {{ acao === 'validar' ? 'Tirando…' : 'Tirar a validação' }}
              </button>
              <button v-if="!confirmandoApagar" type="button" class="botao-secundario text-carimbo!" :disabled="!!acao" @click="confirmandoApagar = true">Apagar</button>
            </div>
            <p v-else-if="!registro.podeEditar" class="text-xs text-tinta-suave">
              Só o autor ({{ registro.autor.nome }}) ou quem cura a base muda este registro.
            </p>

            <div v-if="confirmandoApagar" role="alertdialog" aria-label="Confirmar apagar" class="flex flex-wrap items-center gap-2 rounded-[3px] border border-carimbo/40 bg-carimbo/5 px-3 py-2 text-sm">
              <span>Apagar este registro de vez? Ele e as imagens saem da base (o atendimento de origem não muda).</span>
              <button type="button" class="botao-primario" :disabled="!!acao" @click="apagar">{{ acao === 'apagar' ? 'Apagando…' : 'Apagar' }}</button>
              <button type="button" class="botao-secundario" :disabled="!!acao" @click="confirmandoApagar = false">Cancelar</button>
            </div>

            <p
              v-if="mensagem"
              :role="mensagem.tipo === 'erro' ? 'alert' : 'status'"
              class="flex flex-wrap items-center gap-2 rounded-[3px] border px-3 py-2 text-sm"
              :class="mensagem.tipo === 'ok' ? 'border-credito/40 bg-credito/5 text-credito' : 'border-carimbo/40 bg-carimbo/5 text-carimbo'"
            >
              <span>{{ mensagem.texto }}</span>
              <button v-if="conflito" type="button" class="font-semibold underline underline-offset-4" @click="recarregar">Recarregar</button>
            </p>

            <!-- edição -->
            <section v-if="editando" class="cartao px-4 py-4 sm:px-5" aria-label="Editar o registro">
              <h2 class="rotulo mb-3">Editando o registro</h2>
              <FormularioDoRegistro v-model="form" prefixo="editar" :desligado="!!acao" />
              <div class="mt-3 flex flex-wrap gap-2">
                <button type="button" class="botao-primario" :disabled="!!acao || problemas.length > 0" @click="salvar">{{ acao === 'salvar' ? 'Salvando…' : 'Salvar' }}</button>
                <button type="button" class="botao-secundario" :disabled="!!acao" @click="editando = false">Cancelar</button>
              </div>
            </section>

            <template v-else>
              <!-- identificação -->
              <section class="cartao px-4 py-4 sm:px-5" aria-label="Identificação">
                <dl class="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
                  <div v-for="c in CAMPOS" :key="c.campo">
                    <dt class="rotulo">{{ c.rotulo }}</dt>
                    <dd class="break-words" :class="[c.carimbo ? 'carimbo font-semibold' : '', registro[c.campo] ? '' : 'text-tinta-apagada']">{{ registro[c.campo] ?? 'não informado' }}</dd>
                  </div>
                </dl>
              </section>

              <!-- problema e resolução -->
              <section class="cartao grid gap-3 px-4 py-4 sm:px-5" aria-label="Problema e resolução">
                <div v-for="t in TEXTOS" :key="t.campo">
                  <h2 class="rotulo">{{ t.rotulo }}</h2>
                  <p v-if="registro[t.campo]" class="mt-0.5 text-sm break-words whitespace-pre-wrap" :class="t.literal ? 'carimbo' : ''">{{ registro[t.campo] }}</p>
                  <p v-else class="mt-0.5 text-sm text-tinta-apagada">não informado</p>
                </div>
              </section>
            </template>

            <!-- situação -->
            <section class="cartao px-4 py-4 sm:px-5" aria-label="Situação no desenvolvimento">
              <h2 class="rotulo">Situação no desenvolvimento</h2>
              <p class="mt-1 text-sm">
                <b>{{ registro.situacaoRotulo }}</b>
                <template v-if="registro.chamadoDev"> · chamado {{ registro.chamadoDev }}</template>
                <template v-if="registro.versaoCorrecao"> · corrigido na versão {{ registro.versaoCorrecao }}</template>
              </p>
              <p v-if="registro.solucaoValidada" class="mt-1 text-xs text-credito">Solução validada por {{ registro.validadaPor }} em {{ dataHora(registro.validadaEm) }}.</p>

              <div v-if="mudandoSituacao" class="mt-3 grid gap-3 rounded-[3px] border border-tinta/30 bg-papel-escuro/30 px-3 py-3 text-sm sm:grid-cols-3">
                <div>
                  <label for="situacao-nova" class="rotulo">Situação</label>
                  <select id="situacao-nova" v-model="situacao.situacao" class="campo mt-1 font-sans! text-sm!" :disabled="!!acao">
                    <option v-for="s in SITUACOES_DO_REGISTRO" :key="s.codigo" :value="s.codigo">{{ s.rotulo }}</option>
                  </select>
                </div>
                <div>
                  <label for="situacao-chamado" class="rotulo">Chamado no desenvolvimento</label>
                  <input id="situacao-chamado" v-model="situacao.chamadoDev" type="text" class="campo mt-1 font-sans! text-sm!" :class="chamadoLongo ? 'border-carimbo!' : ''" :disabled="!!acao" placeholder="ex.: número do OTRS" />
                </div>
                <div v-if="situacao.situacao === 'corrigido'">
                  <label for="situacao-versao" class="rotulo">Corrigido na versão</label>
                  <input id="situacao-versao" v-model="situacao.versaoCorrecao" type="text" class="campo mt-1 font-sans! text-sm!" :class="versaoLonga ? 'border-carimbo!' : ''" :disabled="!!acao" />
                </div>
                <div class="flex flex-wrap gap-2 sm:col-span-3">
                  <button type="button" class="botao-primario" :disabled="!!acao || chamadoLongo || versaoLonga" @click="salvarSituacao">{{ acao === 'situacao' ? 'Salvando…' : 'Salvar a situação' }}</button>
                  <button type="button" class="botao-secundario" :disabled="!!acao" @click="mudandoSituacao = false">Cancelar</button>
                </div>
              </div>

              <h3 class="rotulo mt-4">Histórico</h3>
              <ol class="mt-1 grid gap-1 text-sm" aria-label="Histórico da situação">
                <li v-for="(m, i) in [...registro.historico].reverse()" :key="i" class="grid grid-cols-[minmax(0,auto)_minmax(0,1fr)] gap-x-3">
                  <span class="text-xs whitespace-nowrap text-tinta-suave">{{ dataHora(m.em) }}</span>
                  <span class="break-words">{{ descricaoDaMudanca(m) }} <span class="text-xs text-tinta-suave">({{ m.por }})</span></span>
                </li>
              </ol>
            </section>

            <!-- texto confirmado -->
            <section v-if="registro.texto" class="cartao px-4 py-4 sm:px-5" aria-label="Texto confirmado">
              <h2 class="rotulo">Texto confirmado</h2>
              <p class="mt-0.5 text-xs text-tinta-suave">Como foi para o sistema de chamados quando o texto foi confirmado.</p>
              <div class="previa mt-2 rounded-[3px] border border-linha bg-white/80 px-4 py-3 text-sm" v-html="registro.texto" />
            </section>

            <!-- imagens -->
            <section v-if="registro.imagens.length" class="cartao px-4 py-4 sm:px-5" aria-label="Imagens do registro">
              <h2 class="rotulo">Imagens</h2>
              <ul class="mt-2 grid gap-2 sm:grid-cols-2">
                <li v-for="imagem in registro.imagens" :key="imagem.numero" class="flex gap-3 rounded-[3px] border border-linha bg-cartao p-2">
                  <a v-if="miniaturas[imagem.numero]" :href="miniaturas[imagem.numero]" target="_blank" rel="noopener" :title="`Abrir a imagem ${imagem.numero}`">
                    <img :src="miniaturas[imagem.numero]" :alt="`Imagem ${imagem.numero}`" class="h-20 w-28 shrink-0 rounded-[2px] border border-linha bg-white object-contain" />
                  </a>
                  <div v-else class="grid h-20 w-28 shrink-0 place-items-center rounded-[2px] border border-dashed border-linha text-center text-xs text-tinta-apagada">
                    {{ imagem.disponivel ? 'carregando' : 'arquivo não encontrado' }}
                  </div>
                  <div class="min-w-0 flex-1 text-sm">
                    <p class="font-semibold">Imagem {{ imagem.numero }} <span class="carimbo text-xs font-normal text-tinta-suave">{{ imagem.arquivo }}</span></p>
                    <p class="break-words text-tinta-suave">{{ imagem.legenda ?? 'sem legenda' }}</p>
                  </div>
                </li>
              </ul>
            </section>

            <p v-if="registro.souAutor && registro.atendimentoId && modulos.gerador" class="text-xs text-tinta-suave">
              Veio do
              <RouterLink :to="{ name: 'atendimento', params: { id: registro.atendimentoId } }" class="underline underline-offset-4">seu atendimento</RouterLink>
              (os atendimentos são apagados pela retenção; o registro fica).
            </p>
          </div>
        </EstadoDaTela>
      </EstadoDaTela>
    </div>
  </div>
</template>

<style scoped>
.previa :deep(p) {
  margin: 0.5rem 0;
}
.previa :deep(ol) {
  list-style: decimal;
  padding-left: 1.5rem;
  margin: 0.5rem 0;
}
.previa :deep(ul) {
  list-style: disc;
  padding-left: 1.5rem;
}
.previa :deep(li) {
  margin: 0.25rem 0;
}
.previa :deep(h1),
.previa :deep(h2),
.previa :deep(h3),
.previa :deep(h4) {
  font-weight: 700;
  margin: 0.75rem 0 0.25rem;
}
</style>
