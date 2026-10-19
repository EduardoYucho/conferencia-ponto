<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { conhecimentoApi } from '@/modulos/conhecimento/api'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { dataHora as dataHoraDaBase, formularioDoRegistro, problemasDoRegistro } from '@/modulos/conhecimento/formato'
import FormularioDoRegistro from '@/modulos/conhecimento/components/FormularioDoRegistro.vue'
import CasosParecidos from '@/modulos/conhecimento/components/CasosParecidos.vue'
import { mensagemDe } from '@/utils/erros'
import { salvarResposta } from '@/utils/download'
import {
  camposDaTela,
  dataHora,
  hora,
  LIMITE_DA_TELA,
  mesmosCampos,
  paraODesenvolvimento,
  problemasDosCampos,
  tokens,
  VARIANTES,
} from '@/modulos/atendimento/formato'
import {
  copiaDiretaDisponivel,
  copiarHtmlDireto,
  copiarPelaSelecao,
  copiarTextoDireto,
} from '@/modulos/atendimento/areaDeTransferencia'
import CamposDoChamado from './CamposDoChamado.vue'

/**
 * Um texto gerado (o resumo ou um chamado para o desenvolvimento): o assunto com o contador, a prévia com a fonte de
 * cada trecho, a edição do texto, as imagens numeradas (tirar, reordenar, baixar) e as três formas de copiar para o
 * sistema de chamados. Nos chamados, também a Tela/Funcionalidade, o que o Gemini identificou no erro e os dados que
 * a pessoa informa, que o servidor monta no cabeçalho e no rodapé (fora da edição; mudar não exige gerar de novo).
 * No fim, confirmar o texto e enviá-lo para a base de conhecimento, revisando antes o registro já preenchido; depois
 * de confirmado, o texto não muda mais aqui (continua dando para copiar). O HTML da prévia vem sempre gerado ou limpo
 * pelo servidor.
 */
const props = defineProps({
  atendimentoId: { type: String, required: true },
  saidaId: { type: String, required: true },
  /** Os dados dos chamados do atendimento (para o formulário). */
  campos: { type: Object, default: null },
})
const emit = defineEmits(['progresso', 'campos', 'confirmada'])
const router = useRouter()
const modulos = useAcessoModulosStore()

const saida = ref(null)
const carregando = ref(true)
const erro = ref('')
const acao = ref('')
const mensagem = ref(null)

const assunto = ref('')
const editando = ref(false)
const editor = ref(null)
const previa = ref(null)
const confirmandoGerar = ref(false)

const linha = ref(null)
const fontes = ref(null) // { ids: [], semFonte: bool }
const erroDaLinha = ref('')
const miniaturas = ref({})
/** Modo alternativo: o conteúdo já veio do servidor e espera o clique em "Copiar agora". */
const paraCopiar = ref(null) // { modo, rotulo, conteudo, como }

const tela = ref('')
const confirmando = ref(false)
const registro = ref(formularioDoRegistro(null, true))
const alterandoCampos = ref(false)
const camposEditados = ref(camposDaTela(null))

const MODOS = {
  com_imagens: 'com imagens',
  so_texto: 'só o texto',
  codigo_fonte: 'o código-fonte',
}

const limite = computed(() => saida.value?.limiteDoAssunto ?? 150)
const assuntoMudou = computed(() => saida.value && assunto.value.trim().replace(/\s+/g, ' ') !== (saida.value.assunto ?? ''))
const assuntoLongo = computed(() => assunto.value.trim().length > limite.value)
const incluidas = computed(() => (saida.value?.imagens ?? []).filter((i) => i.incluida))
const tiradas = computed(() => (saida.value?.imagens ?? []).filter((i) => !i.incluida))
const itensDaLinha = computed(() => Object.fromEntries((linha.value?.itens ?? []).map((i) => [i.id, i])))
const direta = copiaDiretaDisponivel()
const chamado = computed(() => paraODesenvolvimento(saida.value?.tipo))
/** Texto da habilidade do app do Gemini: vem sem as fontes de cada trecho (é texto para o chamado), então nada fica destacado. */
const doGemini = computed(() => ['habilidade do Gemini (colado)', 'Gem do Gemini (colado)'].includes(saida.value?.modelo))
/** Texto escrito pela IA local: também em texto corrido, sem as fontes de cada trecho. */
const daIaLocal = computed(() => /\(IA local\)$/.test(saida.value?.modelo ?? ''))
const semFontes = computed(() => doGemini.value || daIaLocal.value)
const telaMudou = computed(() => saida.value && tela.value.trim().replace(/\s+/g, ' ') !== (saida.value.telaFuncionalidade ?? ''))
const telaLonga = computed(() => tela.value.trim().length > LIMITE_DA_TELA)
const variantes = computed(() => (saida.value?.variantes ?? []).map((v) => VARIANTES[v] ?? v))
const camposComProblema = computed(() => problemasDosCampos(camposEditados.value).length > 0)
/** Confirmado e enviado para a base: o texto não muda mais aqui. */
const travado = computed(() => saida.value?.situacao === 'confirmada')
const registroComProblema = computed(() => problemasDoRegistro(registro.value).length > 0)
/** O caso para o aviso de caso parecido: o registro que o servidor preparou (só antes de confirmar). */
const casoParaComparar = computed(() => {
  const r = saida.value?.registro
  if (!r) return null
  return {
    titulo: r.titulo ?? '',
    texto: [r.sintoma, r.mensagemErro, r.causa].filter(Boolean).join('\n'),
    telaCodigo: r.telaCodigo ?? '',
  }
})

async function carregar() {
  carregando.value = true
  erro.value = ''
  try {
    aplicar(await atendimentoApi.saida(props.atendimentoId, props.saidaId))
  } catch (e) {
    erro.value = mensagemDe(e)
  } finally {
    carregando.value = false
  }
}

function aplicar(view) {
  saida.value = view
  assunto.value = view.assunto ?? ''
  tela.value = view.telaFuncionalidade ?? ''
  carregarMiniaturas()
}

/** A miniatura muda quando a marcação liga ou desliga (a imagem que vale passa a ser outra). */
const chaveDaMiniatura = (imagem) => `${imagem.id}:${imagem.comMarcacao ? 'marcada' : 'original'}`

async function carregarMiniaturas() {
  for (const imagem of saida.value?.imagens ?? []) {
    const chave = chaveDaMiniatura(imagem)
    if (!imagem.disponivel || miniaturas.value[chave]) continue
    try {
      const resposta = await atendimentoApi.imagem(props.atendimentoId, props.saidaId, imagem.id)
      miniaturas.value = { ...miniaturas.value, [chave]: URL.createObjectURL(resposta.data) }
    } catch {
      // sem miniatura: a imagem continua listada com o número e a legenda
    }
  }
}

onMounted(carregar)
onUnmounted(() => Object.values(miniaturas.value).forEach((url) => URL.revokeObjectURL(url)))

async function executar(nome, chamada, sucesso) {
  acao.value = nome
  mensagem.value = null
  try {
    const resultado = await chamada()
    if (sucesso) mensagem.value = { tipo: 'ok', texto: sucesso }
    return resultado
  } catch (e) {
    mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
    return null
  } finally {
    acao.value = ''
  }
}

// ------------------------------------------------------------------------------------------- assunto e texto

async function salvarAssunto() {
  const view = await executar('assunto', () => atendimentoApi.salvarAssunto(props.atendimentoId, props.saidaId, assunto.value),
    'Assunto salvo.')
  if (view) aplicar(view)
}

async function copiarAssunto() {
  await copiar('assunto')
}

// ------------------------------------------------------------------------------------------- dados do chamado

async function salvarTela() {
  const view = await executar('tela', () => atendimentoApi.salvarTela(props.atendimentoId, props.saidaId, tela.value),
    'Tela/Funcionalidade salva.')
  if (view) aplicar(view)
}

function alterarCampos() {
  camposEditados.value = camposDaTela(props.campos)
  alterandoCampos.value = true
}

/** Os dados valem para os dois chamados; o cabeçalho e o rodapé voltam montados de novo pelo servidor. */
async function salvarCampos() {
  if (mesmosCampos(camposEditados.value, props.campos)) {
    alterandoCampos.value = false
    return
  }
  const salvos = await executar('campos', async () => {
    const c = await atendimentoApi.salvarCampos(props.atendimentoId, camposEditados.value)
    aplicar(await atendimentoApi.saida(props.atendimentoId, props.saidaId))
    return c
  }, 'Dados do chamado salvos.')
  if (salvos) {
    alterandoCampos.value = false
    emit('campos', salvos)
  }
}

async function editar() {
  fontes.value = null
  editando.value = true
  await nextTick()
  editor.value.innerHTML = saida.value.html
  editor.value.focus()
}

async function salvarTexto() {
  const html = editor.value.innerHTML
  const view = await executar('texto', () => atendimentoApi.salvarTexto(props.atendimentoId, props.saidaId, html), 'Texto salvo.')
  if (view) {
    editando.value = false
    aplicar(view)
  }
}

async function desfazer() {
  const view = await executar('desfazer', () => atendimentoApi.desfazerEdicao(props.atendimentoId, props.saidaId),
    'Voltou ao texto gerado.')
  if (view) aplicar(view)
}

async function gerarDeNovo() {
  confirmandoGerar.value = false
  const progresso = await executar('gerar', () => atendimentoApi.gerarDeNovo(props.atendimentoId, saida.value.tipo))
  if (progresso) emit('progresso', progresso)
}

// ------------------------------------------------------------------------------------------- base de conhecimento

function abrirConfirmacao() {
  registro.value = formularioDoRegistro(saida.value.registro, true)
  confirmando.value = true
  editando.value = false
}

async function confirmar() {
  const resultado = await executar('confirmar', () => atendimentoApi.confirmar(props.atendimentoId, props.saidaId, registro.value))
  if (!resultado) return
  confirmando.value = false
  aplicar(resultado.saida)
  const avisos = resultado.avisos ?? []
  mensagem.value = { tipo: avisos.length ? 'aviso' : 'ok', texto: ['Texto confirmado e enviado para a base de conhecimento.', ...avisos].join(' ') }
  emit('confirmada', resultado.saida)
}

async function abrirRegistro() {
  const achado = await executar('registro', () => conhecimentoApi.daSaida(props.saidaId))
  if (achado?.id) router.push({ name: 'registro-da-base', params: { id: achado.id } })
}

// ------------------------------------------------------------------------------------------- fontes

async function aoClicarNaPrevia(evento) {
  const trecho = evento.target.closest?.('[data-fontes]')
  if (!trecho || !previa.value?.contains(trecho)) return
  const ids = (trecho.getAttribute('data-fontes') ?? '').split(/\s+/).filter(Boolean)
  fontes.value = { ids, semFonte: !ids.length }
  if (!linha.value && ids.length) {
    try {
      linha.value = await atendimentoApi.linhaDoTempo(props.atendimentoId)
    } catch (e) {
      erroDaLinha.value = mensagemDe(e)
    }
  }
}

function descricaoDaFonte(item) {
  if (!item) return ''
  if (item.tipo === 'mensagem') {
    return `${item.lado === 'cliente' ? 'Cliente' : 'Atendente'}${item.remetente ? ` (${item.remetente})` : ''}${item.momento ? ` às ${hora(item.momento)}` : ''}`
  }
  if (item.tipo === 'evento') return `Evento${item.momento ? ` às ${hora(item.momento)}` : ''}`
  const origem = { anexo_conversa: 'Anexo da conversa', ligacao: 'Ligação', video: 'Vídeo', print_extra: 'Print' }[item.origem] ?? 'Arquivo'
  return `${origem}${item.nome ? `: ${item.nome}` : ''}${item.semHorario ? ' (sem horário)' : ''}`
}

function trechoDaFonte(texto) {
  const t = (texto ?? '').trim()
  return t.length > 600 ? `${t.slice(0, 600)}…` : t
}

// ------------------------------------------------------------------------------------------- imagens

async function ordenar(ids) {
  const view = await executar('imagens', () => atendimentoApi.ordenarImagens(props.atendimentoId, props.saidaId, ids))
  if (view) aplicar(view)
}

function mover(imagem, passo) {
  const ids = incluidas.value.map((i) => i.id)
  const de = ids.indexOf(imagem.id)
  const para = de + passo
  if (de < 0 || para < 0 || para >= ids.length) return
  ;[ids[de], ids[para]] = [ids[para], ids[de]]
  ordenar(ids)
}

const tirar = (imagem) => ordenar(incluidas.value.map((i) => i.id).filter((id) => id !== imagem.id))
const devolver = (imagem) => ordenar([...incluidas.value.map((i) => i.id), imagem.id])

async function marcar(imagem, caixa) {
  const ligada = caixa.checked
  const view = await executar(`marcar-${imagem.id}`,
    () => atendimentoApi.marcarImagem(props.atendimentoId, props.saidaId, imagem.id, ligada),
    ligada ? `Imagem ${imagem.numero} com as marcações em vermelho.` : `Imagem ${imagem.numero} sem as marcações.`)
  if (view) aplicar(view)
  else caixa.checked = !!imagem.comMarcacao // não mudou: a caixa volta a mostrar o que vale
}

async function baixarZip() {
  const resposta = await executar('zip', () => atendimentoApi.imagensZip(props.atendimentoId, props.saidaId))
  if (resposta) salvarResposta(resposta, 'imagens.zip')
}

// ------------------------------------------------------------------------------------------- cópia

/** modo: com_imagens, so_texto, codigo_fonte ou assunto. */
async function copiar(modo) {
  mensagem.value = null
  paraCopiar.value = null
  const rotulo = modo === 'assunto' ? 'o assunto' : MODOS[modo]
  const como = modo === 'com_imagens' || modo === 'so_texto' ? 'html' : 'texto'
  const pedido = modo === 'assunto'
    ? Promise.resolve({ html: '', texto: saida.value.assunto ?? '', avisos: [] })
    : atendimentoApi.copia(props.atendimentoId, props.saidaId, modo)
  acao.value = `copiar-${modo}`
  try {
    if (direta) {
      if (como === 'html') {
        await copiarHtmlDireto(pedido)
      } else {
        await copiarTextoDireto(pedido.then((c) => (modo === 'assunto' ? c.texto : c.html)))
      }
      const avisos = (await pedido).avisos ?? []
      mensagem.value = { tipo: avisos.length ? 'aviso' : 'ok', texto: [`Copiado ${rotulo}.`, ...avisos].join(' ') }
    } else {
      const c = await pedido
      paraCopiar.value = { modo, rotulo, como, conteudo: modo === 'assunto' ? c.texto : c.html, avisos: c.avisos ?? [] }
    }
  } catch (e) {
    mensagem.value = { tipo: 'erro', texto: e?.name === 'NotAllowedError'
      ? 'O navegador não deixou copiar. Clique de novo no botão (com a janela em primeiro plano).'
      : mensagemDe(e) }
  } finally {
    acao.value = ''
  }
}

/** Modo alternativo: o segundo clique, direto na cópia do navegador. */
function copiarAgora() {
  const item = paraCopiar.value
  if (!item) return
  if (copiarPelaSelecao(item.conteudo, item.como)) {
    mensagem.value = { tipo: item.avisos.length ? 'aviso' : 'ok', texto: [`Copiado ${item.rotulo}.`, ...item.avisos].join(' ') }
    paraCopiar.value = null
  } else {
    mensagem.value = { tipo: 'erro', texto: 'O navegador não deixou copiar: selecione o conteúdo abaixo e use Ctrl+C.' }
  }
}
</script>

<template>
  <div class="grid grid-cols-[minmax(0,1fr)] gap-4">
    <p v-if="carregando" role="status" class="text-sm text-tinta-suave">Abrindo o texto…</p>
    <p v-else-if="erro" role="alert" class="text-sm text-carimbo">{{ erro }}</p>
    <template v-else-if="saida">
      <!-- assunto -->
      <div>
        <label :for="`assunto-${saida.id}`" class="rotulo">Assunto</label>
        <div class="mt-1 flex flex-wrap items-start gap-2">
          <input
            :id="`assunto-${saida.id}`"
            v-model="assunto"
            type="text"
            class="campo min-w-0 flex-1 font-sans! text-sm!"
            :class="assuntoLongo ? 'border-carimbo!' : ''"
            :readonly="travado"
            :aria-describedby="`contador-${saida.id}`"
            @keydown.enter.prevent="assuntoMudou && !assuntoLongo && !travado && salvarAssunto()"
          />
          <button v-if="assuntoMudou && !travado" type="button" class="botao-primario" :disabled="!!acao || assuntoLongo || !assunto.trim()" @click="salvarAssunto">
            {{ acao === 'assunto' ? 'Salvando…' : 'Salvar' }}
          </button>
          <button v-else type="button" class="botao-secundario" :disabled="!!acao" @click="copiarAssunto">Copiar o assunto</button>
        </div>
        <p :id="`contador-${saida.id}`" class="mt-1 text-xs" :class="assuntoLongo ? 'font-semibold text-carimbo' : 'text-tinta-suave'">
          {{ assunto.trim().length }}/{{ limite }} caracteres<span v-if="assuntoLongo">: encurte para salvar</span>
        </p>
      </div>

      <!-- dados do chamado -->
      <div v-if="chamado" class="grid gap-3 rounded-[3px] border border-linha px-3 py-3" aria-label="Dados do chamado" role="group">
        <div>
          <label :for="`tela-${saida.id}`" class="rotulo">Tela/Funcionalidade</label>
          <div class="mt-1 flex flex-wrap items-start gap-2">
            <input
              :id="`tela-${saida.id}`"
              v-model="tela"
              type="text"
              class="campo min-w-0 flex-1 font-sans! text-sm!"
              :class="telaLonga ? 'border-carimbo!' : ''"
              placeholder="não informado"
              :readonly="travado"
              @keydown.enter.prevent="telaMudou && !telaLonga && !travado && salvarTela()"
            />
            <button v-if="telaMudou && !travado" type="button" class="botao-primario" :disabled="!!acao || telaLonga" @click="salvarTela">
              {{ acao === 'tela' ? 'Salvando…' : 'Salvar a tela' }}
            </button>
          </div>
          <p class="mt-1 text-xs" :class="telaLonga ? 'font-semibold text-carimbo' : 'text-tinta-suave'">
            <template v-if="telaLonga">{{ tela.trim().length }}/{{ LIMITE_DA_TELA }} caracteres: encurte para salvar.</template>
            <template v-else>Escrita pela IA ou sugerida pelo código de tela lido nas imagens: confira. Vazia, sai "não informado".</template>
          </p>
        </div>
        <div v-if="variantes.length" class="flex flex-wrap items-center gap-2 text-xs">
          <span class="rotulo">A IA identificou</span>
          <span v-for="v in variantes" :key="v" class="rounded-[2px] border border-tinta/40 px-2 py-0.5 font-semibold">{{ v }}</span>
        </div>
        <div>
          <div class="flex flex-wrap items-center justify-between gap-2">
            <p class="rotulo">Versão, teste, backup e chamado relacionado</p>
            <button v-if="!alterandoCampos" type="button" class="botao-secundario" :disabled="!!acao || editando" @click="alterarCampos">
              Alterar os dados
            </button>
          </div>
          <p v-if="!alterandoCampos" class="mt-1 text-xs text-tinta-suave">
            Valem para os dois chamados e saem no cabeçalho e no rodapé da prévia abaixo.
          </p>
          <div v-else class="mt-2">
            <CamposDoChamado v-model="camposEditados" :prefixo="`campos-${saida.id}`" :desligado="!!acao" />
            <div class="mt-3 flex flex-wrap gap-2">
              <button type="button" class="botao-primario" :disabled="!!acao || camposComProblema" @click="salvarCampos">
                {{ acao === 'campos' ? 'Salvando…' : 'Salvar os dados' }}
              </button>
              <button type="button" class="botao-secundario" :disabled="!!acao" @click="alterandoCampos = false">Cancelar</button>
            </div>
          </div>
        </div>
      </div>

      <!-- casos parecidos na base (nos chamados, antes de copiar ou confirmar) -->
      <CasosParecidos
        v-if="chamado && modulos.pesquisar && casoParaComparar"
        :titulo="casoParaComparar.titulo"
        :texto="casoParaComparar.texto"
        :tela-codigo="casoParaComparar.telaCodigo"
        :atendimento-id="atendimentoId"
      />

      <!-- avisos da conferência -->
      <div v-if="saida.avisos.length" role="note" class="rounded-[3px] border border-carimbo/40 bg-carimbo/5 px-3 py-2 text-sm">
        <p class="font-semibold text-carimbo">Confira antes de usar</p>
        <ul class="mt-1 list-disc pl-5">
          <li v-for="(aviso, i) in saida.avisos" :key="i">{{ aviso }}</li>
        </ul>
      </div>

      <!-- copiar -->
      <div class="flex flex-wrap items-center gap-2" aria-label="Copiar para o sistema de chamados">
        <button type="button" class="botao-primario" :disabled="!!acao || editando" @click="copiar('com_imagens')">
          {{ acao === 'copiar-com_imagens' ? 'Copiando…' : 'Copiar com imagens' }}
        </button>
        <button type="button" class="botao-secundario" :disabled="!!acao || editando" @click="copiar('so_texto')">
          {{ acao === 'copiar-so_texto' ? 'Copiando…' : 'Copiar só o texto' }}
        </button>
        <button type="button" class="botao-secundario" :disabled="!!acao || editando" @click="copiar('codigo_fonte')">
          {{ acao === 'copiar-codigo_fonte' ? 'Copiando…' : 'Copiar o código-fonte' }}
        </button>
        <button v-if="incluidas.length" type="button" class="botao-secundario" :disabled="!!acao || saida.imagensApagadas" @click="baixarZip">
          {{ acao === 'zip' ? 'Preparando…' : 'Baixar as imagens (.zip)' }}
        </button>
      </div>
      <p v-if="!direta" class="-mt-2 text-xs text-tinta-suave">
        Este endereço não tem conexão segura: a cópia usa o modo alternativo (dois cliques).
      </p>

      <div v-if="paraCopiar" role="dialog" aria-label="Copiar agora" class="rounded-[3px] border border-tinta/40 bg-papel-escuro/40 px-3 py-3">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <p class="text-sm font-semibold">Pronto para copiar {{ paraCopiar.rotulo }}.</p>
          <div class="flex gap-2">
            <button type="button" class="botao-primario" @click="copiarAgora">Copiar agora</button>
            <button type="button" class="botao-secundario" @click="paraCopiar = null">Fechar</button>
          </div>
        </div>
        <p class="mt-1 text-xs text-tinta-suave">Se o navegador recusar, selecione o conteúdo abaixo e use Ctrl+C.</p>
        <div v-if="paraCopiar.como === 'html'" class="previa mt-2 max-h-64 overflow-auto rounded-[2px] bg-cartao px-3 py-2 text-sm" v-html="paraCopiar.conteudo" />
        <textarea v-else readonly class="campo mt-2 h-32 text-xs!" :value="paraCopiar.conteudo" />
      </div>

      <p
        v-if="mensagem"
        :role="mensagem.tipo === 'erro' ? 'alert' : 'status'"
        class="rounded-[3px] border px-3 py-2 text-sm"
        :class="mensagem.tipo === 'ok' ? 'border-credito/40 bg-credito/5 text-credito' : 'border-carimbo/40 bg-carimbo/5 text-carimbo'"
      >{{ mensagem.texto }}</p>

      <!-- prévia ou edição -->
      <div>
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h3 class="rotulo">{{ editando ? 'Editando o texto' : 'Prévia' }}</h3>
          <div class="flex flex-wrap gap-2">
            <template v-if="editando">
              <button type="button" class="botao-primario" :disabled="!!acao" @click="salvarTexto">{{ acao === 'texto' ? 'Salvando…' : 'Salvar o texto' }}</button>
              <button type="button" class="botao-secundario" :disabled="!!acao" @click="editando = false">Cancelar</button>
            </template>
            <template v-else-if="!travado">
              <button type="button" class="botao-secundario" :disabled="!!acao" @click="editar">Editar o texto</button>
              <button v-if="saida.editado" type="button" class="botao-secundario" :disabled="!!acao" @click="desfazer">
                {{ acao === 'desfazer' ? 'Desfazendo…' : 'Desfazer as mudanças' }}
              </button>
            </template>
          </div>
        </div>
        <p class="mt-1 text-xs text-tinta-suave">
          <template v-if="editando">Mexa só no texto: o parágrafo de cada imagem ("Imagem N: legenda") pode ser movido ou apagado, e o servidor renumera ao salvar.<template v-if="chamado"> O cabeçalho e o rodapé saem dos dados do chamado.</template></template>
          <template v-else-if="daIaLocal">Texto escrito pela IA local: confira com a conversa antes de usar.</template>
          <template v-else-if="doGemini">Texto escrito no app do Gemini: confira com a conversa antes de usar.</template>
          <template v-else>Clique num trecho para ver de onde ele veio. Trechos destacados vieram sem fonte: confira.</template>
        </p>
        <div
          v-if="chamado && saida.cabecalho"
          class="previa fixo mt-2 rounded-t-[3px] border border-b-0 border-dashed border-linha px-4 py-2 text-sm"
          aria-label="Cabeçalho do chamado"
          v-html="saida.cabecalho"
        />
        <div
          v-if="editando"
          ref="editor"
          contenteditable="true"
          role="textbox"
          aria-multiline="true"
          :aria-label="chamado ? 'Texto do chamado' : 'Texto do resumo'"
          class="previa editando rounded-[3px] border-2 border-tinta bg-white px-4 py-3 text-sm"
          :class="chamado && saida.cabecalho ? '' : 'mt-2'"
        />
        <div
          v-else
          ref="previa"
          class="previa rounded-[3px] border border-linha bg-white/80 px-4 py-3 text-sm"
          :class="[chamado && saida.cabecalho ? 'rounded-none' : 'mt-2', { 'sem-fontes': semFontes }]"
          aria-label="Prévia do texto"
          @click="aoClicarNaPrevia"
          v-html="saida.html"
        />
        <div
          v-if="chamado && saida.rodape"
          class="previa fixo rounded-b-[3px] border border-t-0 border-dashed border-linha px-4 py-2 text-sm"
          aria-label="Rodapé do chamado"
          v-html="saida.rodape"
        />
      </div>

      <!-- fontes do trecho clicado -->
      <div v-if="fontes && !editando" role="region" aria-label="Fontes do trecho" class="rounded-[3px] border border-tinta/30 bg-papel-escuro/30 px-3 py-2 text-sm">
        <div class="flex items-center justify-between gap-2">
          <p class="rotulo">De onde veio este trecho</p>
          <button type="button" class="text-xs underline underline-offset-4" @click="fontes = null">Fechar</button>
        </div>
        <p v-if="fontes.semFonte && semFontes" class="mt-1 text-tinta-suave">{{ daIaLocal ? 'A IA local' : 'O app do Gemini' }} não indica a fonte de cada trecho: confira com a conversa.</p>
        <p v-else-if="fontes.semFonte" class="mt-1 text-carimbo">A IA não indicou a fonte deste trecho: confira com a conversa antes de usar.</p>
        <p v-else-if="erroDaLinha" class="mt-1 text-carimbo">{{ erroDaLinha }}</p>
        <ul v-else class="mt-1 grid gap-2">
          <li v-for="id in fontes.ids" :key="id" class="grid grid-cols-[3rem_minmax(0,1fr)] gap-2">
            <span class="carimbo text-xs text-tinta-suave">{{ id }}</span>
            <div class="min-w-0">
              <p class="text-xs font-semibold">{{ descricaoDaFonte(itensDaLinha[id]) || 'Carregando…' }}</p>
              <p class="break-words whitespace-pre-wrap">{{ trechoDaFonte(itensDaLinha[id]?.texto) }}</p>
            </div>
          </li>
        </ul>
      </div>

      <!-- imagens -->
      <div v-if="saida.imagens.length">
        <h3 class="rotulo">Imagens</h3>
        <p v-if="saida.imagensApagadas" class="mt-1 text-sm text-carimbo">
          Os arquivos deste atendimento já foram apagados pela retenção: as imagens não estão mais no servidor.
        </p>
        <ul class="mt-2 grid gap-2 sm:grid-cols-2">
          <li v-for="(imagem, i) in incluidas" :key="imagem.id" class="flex gap-3 rounded-[3px] border border-linha bg-cartao p-2">
            <img v-if="miniaturas[chaveDaMiniatura(imagem)]" :src="miniaturas[chaveDaMiniatura(imagem)]" :alt="`Imagem ${imagem.numero}${imagem.comMarcacao ? ' com marcações' : ''}`" class="h-20 w-28 shrink-0 rounded-[2px] border border-linha object-contain bg-white" />
            <div v-else class="grid h-20 w-28 shrink-0 place-items-center rounded-[2px] border border-dashed border-linha text-xs text-tinta-apagada">sem prévia</div>
            <div class="min-w-0 flex-1 text-sm">
              <p class="font-semibold">Imagem {{ imagem.numero }} <span class="carimbo text-xs font-normal text-tinta-suave">{{ imagem.arquivo }}</span></p>
              <p class="break-words text-tinta-suave">{{ imagem.legenda ?? 'sem legenda' }}</p>
              <p v-if="imagem.comMarcacao && travado" class="mt-1 text-xs text-carimbo">Com as marcações em vermelho</p>
              <label v-if="imagem.podeMarcar && !travado" class="mt-1 flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs" :title="'As áreas que a análise apontou ganham uma borda vermelha numa cópia; o original não muda.'">
                <input
                  type="checkbox"
                  :checked="imagem.comMarcacao"
                  :disabled="!!acao || editando"
                  :aria-label="`Marcações em vermelho na imagem ${imagem.numero}`"
                  @change="marcar(imagem, $event.target)"
                />
                <span class="whitespace-nowrap">{{ acao === `marcar-${imagem.id}` ? 'Marcando…' : 'Marcações em vermelho' }}</span>
                <span class="rounded-[2px] border border-linha px-1 text-[10px] uppercase tracking-wide text-tinta-apagada">experimental</span>
              </label>
              <div v-if="!travado" class="mt-1 flex flex-wrap gap-x-3 text-xs">
                <button type="button" class="underline underline-offset-4 disabled:opacity-40" :disabled="!!acao || editando || i === 0" :aria-label="`Subir a imagem ${imagem.numero}`" @click="mover(imagem, -1)">Subir</button>
                <button type="button" class="underline underline-offset-4 disabled:opacity-40" :disabled="!!acao || editando || i === incluidas.length - 1" :aria-label="`Descer a imagem ${imagem.numero}`" @click="mover(imagem, 1)">Descer</button>
                <button type="button" class="text-carimbo underline underline-offset-4 disabled:opacity-40" :disabled="!!acao || editando" @click="tirar(imagem)">Tirar do texto</button>
              </div>
            </div>
          </li>
        </ul>
        <div v-if="tiradas.length" class="mt-2 text-sm">
          <p class="text-xs text-tinta-suave">Fora do texto:</p>
          <ul class="mt-1 flex flex-wrap gap-2">
            <li v-for="imagem in tiradas" :key="imagem.id" class="rounded-[2px] border border-linha px-2 py-1 text-xs">
              {{ imagem.legenda ?? imagem.arquivo }}
              <button v-if="!travado" type="button" class="ml-1 font-semibold underline underline-offset-4" :disabled="!!acao || editando" @click="devolver(imagem)">Voltar ao texto</button>
            </li>
          </ul>
        </div>
      </div>

      <!-- base de conhecimento -->
      <section class="rounded-[3px] border border-linha px-3 py-3 text-sm" aria-label="Base de conhecimento">
        <template v-if="travado">
          <p class="font-semibold text-credito">Enviado para a base de conhecimento em {{ dataHoraDaBase(saida.confirmadaEm) }}.</p>
          <p class="mt-0.5 text-xs text-tinta-suave">
            O texto não muda mais aqui (dá para copiar e baixar as imagens). Para corrigir o que está na base, edite o
            registro; para um texto novo, gere de novo.
          </p>
          <button v-if="modulos.pesquisar" type="button" class="botao-secundario mt-2" :disabled="!!acao" @click="abrirRegistro">
            {{ acao === 'registro' ? 'Abrindo…' : 'Abrir o registro na base' }}
          </button>
        </template>
        <template v-else-if="!confirmando">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="min-w-0">
              <p class="font-semibold">Base de conhecimento</p>
              <p class="text-xs text-tinta-suave">
                Confirme o texto quando ele estiver certo: o caso entra na base para a pesquisa, com uma cópia do texto e
                das imagens.
              </p>
            </div>
            <button type="button" class="botao-primario" :disabled="!!acao || editando || !modulos.pesquisar || !saida.registro" @click="abrirConfirmacao">
              Confirmar e enviar para a base
            </button>
          </div>
          <p v-if="!modulos.pesquisar" class="mt-1 text-xs font-semibold text-carimbo">
            A base de conhecimento não está liberada para você: peça a quem cuida dos acessos para poder enviar.
          </p>
          <p v-if="saida.naBase" class="mt-1 text-xs text-carimbo">
            A versão {{ saida.naBase.versao }} deste texto já foi para a base em {{ dataHoraDaBase(saida.naBase.confirmadaEm) }}:
            confirmar esta cria outro registro.
          </p>
        </template>
        <div v-else role="region" aria-label="Revisar o registro">
          <p class="font-semibold">Revise o registro antes de enviar</p>
          <p class="mt-0.5 mb-3 text-xs text-tinta-suave">
            Preenchido com o que a IA preparou para a base ("não identificado" ficou em branco). É o que entra na
            pesquisa; o texto como está acima e as imagens vão junto. Depois de enviado, o texto não muda mais aqui.
          </p>
          <FormularioDoRegistro v-model="registro" :prefixo="`registro-${saida.id}`" :desligado="!!acao" com-situacao />
          <div class="mt-3 flex flex-wrap gap-2">
            <button type="button" class="botao-primario" :disabled="!!acao || registroComProblema" @click="confirmar">
              {{ acao === 'confirmar' ? 'Enviando…' : 'Confirmar e enviar' }}
            </button>
            <button type="button" class="botao-secundario" :disabled="!!acao" @click="confirmando = false">Cancelar</button>
          </div>
        </div>
      </section>

      <!-- rodapé -->
      <div class="flex flex-wrap items-center justify-between gap-2 border-t border-linha pt-3">
        <p class="text-[0.7rem] text-tinta-apagada">
          Versão {{ saida.versao }} · {{ saida.modelo }} · prompt v{{ saida.versaoPrompt }} · {{ dataHora(saida.criadaEm) }}
          <template v-if="saida.tokensEntrada && !modulos.iaLocal"> · {{ tokens(saida.tokensEntrada) }} + {{ tokens(saida.tokensSaida) }} tokens</template>
          <template v-if="saida.editado"> · editado</template>
        </p>
        <div v-if="!confirmandoGerar">
          <button type="button" class="botao-secundario" :disabled="!!acao || editando" @click="confirmandoGerar = true">Gerar de novo</button>
        </div>
        <div v-else role="alertdialog" aria-label="Confirmar gerar de novo" class="flex flex-wrap items-center gap-2 text-sm">
          <span>Escrever uma versão nova? As análises são aproveitadas; esta versão fica guardada.</span>
          <button type="button" class="botao-primario" :disabled="!!acao" @click="gerarDeNovo">{{ acao === 'gerar' ? 'Pedindo…' : 'Gerar' }}</button>
          <button type="button" class="botao-secundario" @click="confirmandoGerar = false">Cancelar</button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.previa :deep(p) {
  margin: 0.5rem 0;
}
.previa.fixo {
  color: var(--color-tinta-suave);
  background: color-mix(in srgb, var(--color-papel-escuro) 35%, transparent);
}
.previa.fixo :deep(p) {
  margin: 0.15rem 0;
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
.previa :deep(li > p) {
  margin: 0.25rem 0;
}
.previa :deep([data-imagem]) {
  border-left: 3px solid var(--color-linha);
  padding-left: 0.5rem;
  color: var(--color-tinta-suave);
}
.previa:not(.editando) :deep([data-fontes]) {
  cursor: pointer;
  border-radius: 2px;
}
.previa:not(.editando) :deep([data-fontes]:hover) {
  background: color-mix(in srgb, var(--color-papel-escuro) 60%, transparent);
}
.previa:not(.editando):not(.sem-fontes) :deep([data-fontes='']) {
  background: color-mix(in srgb, var(--color-carimbo) 10%, transparent);
  box-shadow: inset 2px 0 0 var(--color-carimbo);
}
</style>
