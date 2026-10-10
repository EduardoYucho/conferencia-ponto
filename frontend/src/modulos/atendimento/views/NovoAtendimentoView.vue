<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { mensagemDe } from '@/utils/erros'
import { ApiError } from '@/api/http'
import { anexosPorCategoria, contagem, dataHora, periodo, textoDosOmitidos, validadeDosLinks } from '@/modulos/atendimento/formato'

/**
 * Novo atendimento: a pessoa solta o PDF da conversa exportada do Digisac e vê, na hora, o que o servidor leu
 * (mensagens, eventos, anexos, validade dos links e o que foi omitido). Toda regra é do servidor: a tela só
 * confere que é um arquivo .pdf, para não mandar outro tipo de arquivo à toa.
 */
const modulos = useAcessoModulosStore()
const router = useRouter()
const baixando = ref(false)

const campo = ref(null)
const arrastando = ref(false)
const enviando = ref(false)
const progresso = ref(null)
const nomeDoArquivo = ref('')
/** { criado, atendimento, leitura } */
const resultado = ref(null)
/** { mensagem, protocolo } */
const falha = ref(null)
const aviso = ref('')

const leitura = computed(() => resultado.value?.leitura)
const percentual = computed(() => (progresso.value == null ? null : Math.round(progresso.value * 100)))

function escolher() {
  campo.value?.click()
}

async function enviar(lista) {
  const arquivos = [...(lista ?? [])]
  if (!arquivos.length || enviando.value) return
  aviso.value = arquivos.length > 1 ? `Um PDF por vez: foi enviado só "${arquivos[0].name}".` : ''
  const arquivo = arquivos[0]
  resultado.value = null
  falha.value = null
  nomeDoArquivo.value = arquivo.name
  if (!arquivo.name.toLowerCase().endsWith('.pdf')) {
    falha.value = { mensagem: 'Envie o PDF da conversa exportada do Digisac (um arquivo .pdf).', protocolo: null }
    limparCampo()
    return
  }
  enviando.value = true
  progresso.value = 0
  try {
    resultado.value = await atendimentoApi.enviarPdf(arquivo, (p) => (progresso.value = p))
  } catch (e) {
    falha.value = { mensagem: mensagemDe(e), protocolo: e instanceof ApiError ? e.protocolo : null }
  } finally {
    enviando.value = false
    progresso.value = null
    limparCampo()
  }
}

function limparCampo() {
  if (campo.value) campo.value.value = ''
}

function aoSoltar(evento) {
  arrastando.value = false
  enviar(evento.dataTransfer?.files)
}

/** Os links valem 24 h: o mais comum é já pôr os anexos para baixar e acompanhar no atendimento. */
async function baixarEAbrir() {
  baixando.value = true
  falha.value = null
  const id = resultado.value.atendimento.id
  try {
    await atendimentoApi.processar(id)
    router.push({ name: 'atendimento', params: { id } })
  } catch (e) {
    falha.value = { mensagem: mensagemDe(e), protocolo: e instanceof ApiError ? e.protocolo : null }
  } finally {
    baixando.value = false
  }
}

function recomecar() {
  resultado.value = null
  falha.value = null
  aviso.value = ''
  nomeDoArquivo.value = ''
}
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <RouterLink :to="{ name: 'atendimentos' }" class="rotulo hover:text-tinta">← Atendimentos</RouterLink>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">
        Novo atendimento
      </h1>
    </header>

    <div class="mt-6">
      <EstadoDaTela :carregando="!modulos.carregado" :erro="modulos.erro" carregando-texto="Conferindo o seu acesso…" @tentar="modulos.carregar()">
        <section v-if="!modulos.gerador" class="cartao px-5 py-4">
          <p class="font-semibold">O gerador de atendimentos não está liberado para você.</p>
          <p class="mt-1 text-sm text-tinta-suave">Peça ao administrador para liberar.</p>
        </section>

        <div v-else class="grid grid-cols-[minmax(0,1fr)] gap-5">
          <!-- envio -->
          <section v-if="!resultado" class="cartao px-5 py-5" aria-label="Enviar o PDF do Digisac">
            <p class="text-sm text-tinta-suave">
              No Digisac, abra o chamado e exporte a conversa em PDF. Os links dos anexos valem 24 horas a partir da
              exportação: para os anexos serem baixados, envie o PDF no mesmo dia.
            </p>
            <div
              class="mt-4 grid place-items-center rounded-[3px] border-2 border-dashed px-6 py-8 text-center transition"
              :class="arrastando ? 'border-tinta bg-papel-escuro/60' : 'border-linha'"
              @dragenter.prevent="arrastando = true"
              @dragover.prevent="arrastando = true"
              @dragleave.prevent="arrastando = false"
              @drop.prevent="aoSoltar"
            >
              <svg viewBox="0 0 24 24" class="size-7 text-tinta-suave" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
                <path d="M12 16V4m0 0l-4 4m4-4l4 4M4 16v3a1 1 0 001 1h14a1 1 0 001-1v-3" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <p v-if="!enviando" class="mt-2 text-sm">
                Arraste o PDF da conversa para cá ou
                <button type="button" class="font-semibold text-tinta underline underline-offset-4" @click="escolher">escolha o arquivo</button>.
              </p>
              <div v-else class="mt-2 w-full max-w-sm" role="status">
                <p class="truncate text-sm font-semibold" :title="nomeDoArquivo">{{ nomeDoArquivo }}</p>
                <p class="mt-1 text-xs text-tinta-suave">
                  {{ percentual != null && percentual < 100 ? `Enviando… ${percentual}%` : 'Lendo a conversa…' }}
                </p>
                <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-linha">
                  <div class="h-full bg-tinta transition-[width]" :style="{ width: `${percentual ?? 100}%` }" />
                </div>
              </div>
              <input ref="campo" type="file" accept="application/pdf,.pdf" class="sr-only" :disabled="enviando" @change="enviar($event.target.files)" />
            </div>
            <p v-if="aviso" role="status" class="mt-3 text-sm text-tinta-suave">{{ aviso }}</p>
            <div v-if="falha" role="alert" class="mt-3 rounded-[3px] border border-carimbo/40 bg-carimbo/5 px-3 py-2 text-sm text-carimbo">
              <p class="break-words"><span v-if="nomeDoArquivo" class="font-semibold">{{ nomeDoArquivo }}: </span>{{ falha.mensagem }}</p>
              <p v-if="falha.protocolo" class="mt-1 text-xs">Protocolo: <span class="carimbo font-bold">{{ falha.protocolo }}</span></p>
            </div>
          </section>

          <!-- o que foi lido -->
          <section v-else class="cartao px-5 py-5" aria-label="Resumo da leitura">
            <p v-if="aviso" role="status" class="mb-3 text-sm text-tinta-suave">{{ aviso }}</p>
            <div
              role="status"
              class="rounded-[3px] border px-3 py-2 text-sm"
              :class="resultado.criado ? 'border-credito/40 bg-credito/5 text-credito' : 'border-tinta/30 bg-papel-escuro/40 text-tinta'"
            >
              <p v-if="resultado.criado" class="font-semibold">Atendimento criado a partir de {{ leitura.arquivo ?? 'PDF enviado' }}.</p>
              <template v-else>
                <p class="font-semibold">Você já tem um atendimento deste chamado (criado em {{ dataHora(resultado.atendimento.criadoEm) }}).</p>
                <p class="mt-0.5">
                  Nada foi criado agora.
                  <template v-if="resultado.linksRenovados">
                    Os links de {{ resultado.linksRenovados }} anexo(s) que faltavam foram renovados com este PDF.
                  </template>
                  Abra o existente para continuar.
                </p>
              </template>
            </div>

            <dl class="mt-4 grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
              <div>
                <dt class="rotulo">Chamado</dt>
                <dd class="carimbo mt-0.5 text-base">{{ leitura.chamado }}</dd>
              </div>
              <div>
                <dt class="rotulo">Contato</dt>
                <dd class="mt-0.5 font-semibold">{{ leitura.contato ?? '—' }}</dd>
              </div>
              <div>
                <dt class="rotulo">Período</dt>
                <dd class="mt-0.5">{{ periodo(leitura.inicio, leitura.fim) }}</dd>
              </div>
              <div>
                <dt class="rotulo">Conversa</dt>
                <dd class="mt-0.5">{{ contagem(leitura.mensagens, 'mensagem', 'mensagens') }} · {{ contagem(leitura.eventos, 'evento', 'eventos') }}</dd>
              </div>
              <div>
                <dt class="rotulo">Anexos</dt>
                <dd class="mt-0.5">{{ leitura.anexos }} ({{ anexosPorCategoria(leitura.anexosPorCategoria) }})</dd>
              </div>
              <div v-if="leitura.anexos">
                <dt class="rotulo">Links dos anexos</dt>
                <dd class="mt-0.5" :class="leitura.linksVencidos ? 'font-semibold text-carimbo' : ''">
                  {{ validadeDosLinks(leitura.linksValidosAte, leitura.linksVencidos) }}
                </dd>
              </div>
              <div v-if="leitura.assunto" class="sm:col-span-2">
                <dt class="rotulo">Assunto (no Digisac)</dt>
                <dd class="mt-0.5 whitespace-pre-line">{{ leitura.assunto }}</dd>
              </div>
            </dl>

            <p class="mt-4 text-sm text-tinta-suave">{{ textoDosOmitidos(leitura.omitidos) }}</p>
            <p v-if="leitura.anexos && leitura.linksVencidos" class="mt-2 text-sm text-carimbo">
              Os anexos não poderão ser baixados por estes links. Para baixá-los, exporte o PDF de novo no Digisac.
            </p>
            <p v-if="leitura.linhasSemCabecalho" role="alert" class="mt-2 text-sm text-carimbo">
              {{ leitura.linhasSemCabecalho }} linha(s) de mensagem vieram sem remetente e horário. Confira a conversa no
              atendimento; se estiver errada, avise o administrador (o formato do PDF pode ter mudado).
            </p>

            <div v-if="falha" role="alert" class="mt-4 rounded-[3px] border border-carimbo/40 bg-carimbo/5 px-3 py-2 text-sm text-carimbo">
              <p>{{ falha.mensagem }}</p>
              <p v-if="falha.protocolo" class="mt-1 text-xs">Protocolo: <span class="carimbo font-bold">{{ falha.protocolo }}</span></p>
            </div>
            <div class="mt-5 flex flex-wrap gap-3">
              <button
                v-if="leitura.anexos && !leitura.linksVencidos && (resultado.criado || resultado.linksRenovados)"
                type="button"
                class="botao-primario"
                :disabled="baixando"
                @click="baixarEAbrir"
              >{{ baixando ? 'Pondo na fila…' : 'Baixar os anexos e abrir' }}</button>
              <RouterLink
                :to="{ name: 'atendimento', params: { id: resultado.atendimento.id } }"
                :class="leitura.anexos && !leitura.linksVencidos && (resultado.criado || resultado.linksRenovados) ? 'botao-secundario' : 'botao-primario'"
              >
                {{ resultado.criado ? 'Abrir o atendimento' : 'Abrir o existente' }}
              </RouterLink>
              <button type="button" class="botao-secundario" @click="recomecar">Enviar outro PDF</button>
            </div>
          </section>
        </div>
      </EstadoDaTela>
    </div>
  </div>
</template>
