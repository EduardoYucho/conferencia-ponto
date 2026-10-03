<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { avisar, fecharAviso } from '@/utils/avisar'
import { mensagemDe } from '@/utils/erros'
import { dataBR, diaMes, saldo, saldoComSentido } from '@/utils/horas'
import EnvioComprovantes from '@/components/EnvioComprovantes.vue'
import Icone from '@/components/Icone.vue'
import ModalAjusteBatidas from '@/components/ModalAjusteBatidas.vue'
import ModalCicloBanco from '@/components/ModalCicloBanco.vue'
import ModalDiaEspecial from '@/components/ModalDiaEspecial.vue'
import ModalLancamentoBanco from '@/components/ModalLancamentoBanco.vue'
import ModalLancamentoManual from '@/components/ModalLancamentoManual.vue'

/**
 * Tudo o que a pessoa pode FAZER com o ponto, num lugar só: bater, corrigir os horários de um dia, lançar horas,
 * usar ou somar horas do banco, marcar folga/férias/feriado, enviar comprovantes e fechar o banco. As telas
 * (Início, Meu ponto, Banco de horas) só chamam `acoes.value.xxx(...)`; as janelas e as mensagens de resultado
 * ficam aqui, iguais em todas elas. Quais ações cada dia aceita vem decidido do servidor (`dia.acoes`).
 */
defineProps({
  /** Aceita PDFs arrastados para qualquer lugar da página. */
  arrastar: { type: Boolean, default: true },
})

const store = usePontoStore()
const auth = useAuthStore()
const router = useRouter()

const ajuste = reactive({ aberto: false, data: null, registro: null })
const manual = reactive({ aberto: false, data: null })
const banco = reactive({ aberto: false, data: null, duracao: '', descricao: '' })
const marcacao = reactive({ aberto: false, data: null, marcador: null })
const ciclo = reactive({ aberto: false, modo: 'fechar' })
const envio = reactive({ aberto: false, arrastando: false })
/** Pergunta antes de apagar: { titulo, texto, botao, responder } */
const confirmacao = ref(null)
const janelaEnvio = ref(null)
const botaoFecharEnvio = ref(null)

// ------------------------------------------------------------------ bater o ponto
/** @returns {Promise<boolean>} a batida foi registrada */
async function bater() {
  fecharAviso() // o erro da tentativa anterior sai antes da nova
  let registro
  try {
    registro = await store.postBatida()
  } catch (e) {
    avisar(mensagemDe(e), 'erro')
    return false
  }
  const batida = [...(registro?.batidas ?? [])].reverse().find((b) => b.real)
  avisar(batida ? `Ponto batido às ${batida.real.slice(0, 5)}.` : 'Ponto batido.')
  return true
}

// ------------------------------------------------------------------ janelas
/** Incluir, corrigir ou remover as batidas de um dia. @param dia dia vindo da API (com `data` e `registro`) */
function ajustar(dia) {
  Object.assign(ajuste, { aberto: true, data: dia.data, registro: dia.registro ?? null })
}

/** Horas trabalhadas num dia sem expediente ou feriado (contam inteiras a favor). */
function lancarHoras(data = null) {
  Object.assign(manual, { aberto: true, data })
}

/** Usar (abater) ou somar horas no banco. */
function usarBanco(data = null, { duracao: tempo = '', descricao = '' } = {}) {
  Object.assign(banco, { aberto: true, data, duracao: tempo, descricao })
}

/** Marcar folga, férias, feriado ou outra justificativa — ou ver/remover a marcação que o dia já tem. */
function marcar(dia = null) {
  const m = dia?.marcador
  Object.assign(marcacao, {
    aberto: true,
    data: dia?.data ?? null,
    marcador: !m ? null : m.feriado
      ? { tipo: 'feriado', rotulo: m.descricao || m.rotulo, descricao: null, feriado: { data: dia.data, descricao: m.descricao, abrangencia: m.abrangencia } }
      : { tipo: 'ausencia', rotulo: m.rotulo, descricao: m.descricao, ausencia: { id: m.ausenciaId, dataInicio: m.inicio, dataFim: m.fim } },
  })
}

async function enviarComprovantes() {
  envio.aberto = true
  await nextTick()
  botaoFecharEnvio.value?.focus() // o foco entra na janela (Esc e Tab passam a valer nela)
}

function fecharBanco() {
  Object.assign(ciclo, { aberto: true, modo: 'fechar' })
}

function corrigirPeriodo() {
  Object.assign(ciclo, { aberto: true, modo: 'periodo' })
}

/** @returns {Promise<boolean>} */
function confirmar(titulo, texto, botao) {
  return new Promise((resolve) => {
    confirmacao.value = { titulo, texto, botao, responder: resolve }
  })
}
function responder(valor) {
  confirmacao.value?.responder(valor)
  confirmacao.value = null
}

async function excluirDia(dia) {
  const sim = await confirmar(`Apagar o registro de ${dia.rotulo}?`,
    'As batidas deste dia são apagadas e o dia volta a ficar sem registro. Não dá para desfazer.', 'Apagar o registro')
  if (!sim) return false
  try {
    await store.excluirRegistro(dia.data)
  } catch (e) {
    avisar(mensagemDe(e), 'erro')
    return false
  }
  avisar(`Registro de ${diaMes(dia.data)} apagado.`)
  return true
}

/** @param lancamento { id, data, segundos, descricao } */
async function removerLancamento(lancamento) {
  const sim = await confirmar('Remover este lançamento do banco?',
    `O lançamento de ${diaMes(lancamento.data)} (${saldo(lancamento.segundos)} · ${lancamento.descricao}) deixa de contar no banco de horas.`,
    'Remover')
  if (!sim) return false
  try {
    await store.excluirLancamentoBanco(lancamento)
  } catch (e) {
    avisar(mensagemDe(e), 'erro')
    return false
  }
  avisar(`Lançamento de ${diaMes(lancamento.data)} removido do banco.`)
  return true
}

/** Fechou o banco por engano: o período anterior volta a ser o aberto. */
async function desfazerFechamento() {
  const sim = await confirmar('Desfazer o último fechamento?',
    'O período anterior volta a ficar aberto, com tudo o que tinha, e o período atual deixa de existir. Os dias e as batidas não mudam.',
    'Desfazer o fechamento')
  if (!sim) return false
  try {
    await pontoApi.desfazerFechamento()
  } catch (e) {
    avisar(mensagemDe(e), 'erro')
    return false
  }
  store.sinalizarMudanca({ tipo: 'ciclo' })
  store.atualizarSaldos()
  avisar('Fechamento desfeito: o período anterior voltou a ficar aberto.')
  return true
}

/** Uma ação de `dia.acoes` (o servidor diz quais o dia aceita). */
function executar(acao, dia) {
  if (acao === 'corrigir' || acao === 'ajustar' || acao === 'informarBatidas') return ajustar(dia)
  if (acao === 'lancarHoras' || acao === 'editarLancamento') return lancarHoras(dia.data)
  if (acao === 'marcar' || acao === 'removerMarcacao') return marcar(dia)
  if (acao === 'lancarNoBanco') return usarBanco(dia.data)
  if (acao === 'excluir') return excluirDia(dia)
  if (acao === 'conferirRh') return router.push({ name: 'conciliacao' })
  return undefined
}

// ------------------------------------------------------------------ resultados (o texto é igual em todas as telas)
function aoSalvarAjuste(registro) {
  avisar(`Horários de ${diaMes(registro.data)} salvos. ${registro.status === 'FECHADA'
    ? `O dia ficou ${saldoComSentido(registro.saldoDiarioSegundos)}.`
    : 'Ainda falta uma batida neste dia.'}`)
}
function aoSalvarManual(registro) {
  avisar(`Horas de ${diaMes(registro.data)} lançadas: ${saldoComSentido(registro.saldoDiarioSegundos)}.`)
}
function aoLancarNoBanco(lancamento) {
  avisar(`Lançado no banco em ${diaMes(lancamento.data)}: ${saldo(lancamento.segundos)}.`)
}
function aoConcluirCiclo(resultado) {
  store.sinalizarMudanca({ tipo: 'ciclo' })
  if (resultado) {
    avisar(`Banco de horas fechado: ${saldoComSentido(resultado.fechado.saldoSegundos)}. A contagem recomeçou em ${dataBR(resultado.novo.dataInicio)}.`)
  }
}
function aoEnviarComprovantes({ total, importados }) {
  const enviados = total === 1 ? 'no comprovante enviado' : `nos ${total} comprovantes enviados`
  avisar(importados
    ? `${importados} de ${total} ${total === 1 ? 'comprovante importado' : 'comprovantes importados'}.`
    : `Nenhuma batida nova ${enviados}.`, importados ? 'ok' : 'info')
}
/** "Folga compensando o banco": lança um dia inteiro do horário da pessoa como horas usadas. */
function compensarDia(data) {
  const carga = store.cargaDoDia(data) || store.cargaDiaInteiro
  const h = Math.floor(carga / 3600)
  const m = Math.floor((carga % 3600) / 60)
  usarBanco(data, { duracao: `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`, descricao: 'Folga compensada' })
}

// ------------------------------------------------------------------ arrastar PDFs para a página
const temArquivos = (evento) => auth.podeEscrever && [...(evento.dataTransfer?.types ?? [])].includes('Files')
function aoArrastarSobre(evento) {
  if (!temArquivos(evento)) return
  evento.preventDefault()
  envio.arrastando = true
}
function aoSairArrastando(evento) {
  if (!evento.relatedTarget) envio.arrastando = false
}
async function aoSoltar(evento) {
  envio.arrastando = false
  if (!temArquivos(evento) || envio.aberto) return // com a janela aberta, quem recebe é a área de envio dela
  evento.preventDefault()
  const arquivos = [...evento.dataTransfer.files]
  envio.aberto = true
  await nextTick() // a janela de envio precisa existir para receber os arquivos
  if (janelaEnvio.value) janelaEnvio.value.enviar(arquivos)
  else avisar('Os arquivos não foram enviados. Solte-os de novo na janela de envio.', 'erro')
}
function aoTeclar(evento) {
  if (evento.key !== 'Escape') return
  if (confirmacao.value) responder(false)
  else if (envio.aberto) envio.aberto = false
}

onMounted(() => {
  window.addEventListener('keydown', aoTeclar)
  window.addEventListener('dragover', aoArrastarSobre)
  window.addEventListener('dragleave', aoSairArrastando)
  window.addEventListener('drop', aoSoltar)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', aoTeclar)
  window.removeEventListener('dragover', aoArrastarSobre)
  window.removeEventListener('dragleave', aoSairArrastando)
  window.removeEventListener('drop', aoSoltar)
  confirmacao.value?.responder(false)
})

defineExpose({
  bater, ajustar, lancarHoras, usarBanco, marcar, enviarComprovantes, fecharBanco, corrigirPeriodo, excluirDia,
  removerLancamento, desfazerFechamento, executar,
})
</script>

<template>
  <template v-if="auth.podeEscrever">
    <ModalAjusteBatidas v-model="ajuste.aberto" :data="ajuste.data" :registro="ajuste.registro" @salvo="aoSalvarAjuste" />
    <ModalLancamentoManual v-model="manual.aberto" :data-inicial="manual.data" @salvo="aoSalvarManual" />
    <ModalLancamentoBanco
      v-model="banco.aberto"
      :data-inicial="banco.data"
      :duracao-inicial="banco.duracao"
      :descricao-inicial="banco.descricao"
      @salvo="aoLancarNoBanco"
    />
    <ModalDiaEspecial
      v-model="marcacao.aberto"
      :data="marcacao.data"
      :marcador="marcacao.marcador"
      @salvo="(texto) => avisar(texto)"
      @compensar="compensarDia"
    />
    <ModalCicloBanco v-model="ciclo.aberto" :modo="ciclo.modo" :ciclo="store.ciclo" @concluido="aoConcluirCiclo" />

    <Teleport to="body">
      <!-- Arrastando PDFs sobre a página -->
      <div
        v-if="arrastar && envio.arrastando && !envio.aberto"
        class="pointer-events-none fixed inset-3 z-50 grid place-items-center rounded-2xl border-4 border-dashed border-primaria bg-fundo/90"
      >
        <p class="flex items-center gap-3 text-2xl font-extrabold text-primaria"><Icone nome="enviar" tamanho="28" /> Solte os comprovantes em PDF</p>
      </div>

      <!-- Envio dos comprovantes -->
      <div v-if="envio.aberto" class="janela-fundo" @mousedown.self="envio.aberto = false">
        <section role="dialog" aria-modal="true" aria-labelledby="titulo-envio" class="janela">
          <header class="flex items-start justify-between gap-4 border-b border-borda px-5 pt-5 pb-4">
            <div>
              <h2 id="titulo-envio" class="text-xl font-extrabold tracking-tight">Enviar comprovantes (PDF)</h2>
              <p class="mt-1 text-[0.95rem] text-texto-3">
                Cada comprovante vira a batida do dia. Para o sistema importar sozinho, escolha a pasta em
                <RouterLink :to="{ name: 'conta' }" class="link">Minha conta</RouterLink>.
              </p>
            </div>
            <button ref="botaoFecharEnvio" type="button" class="-mt-1 -mr-2 grid size-11 shrink-0 place-items-center rounded-xl text-texto-3 hover:bg-neutro hover:text-texto" aria-label="Fechar" @click="envio.aberto = false">
              <Icone nome="fechar" />
            </button>
          </header>
          <div class="px-5 py-5">
            <EnvioComprovantes ref="janelaEnvio" @enviados="aoEnviarComprovantes" />
          </div>
        </section>
      </div>

      <!-- Confirmação antes de apagar -->
      <div v-if="confirmacao" class="janela-fundo z-[55]" @mousedown.self="responder(false)">
        <section role="alertdialog" aria-modal="true" aria-labelledby="titulo-confirmacao" aria-describedby="texto-confirmacao" class="janela max-w-md px-5 py-5">
          <h2 id="titulo-confirmacao" class="text-xl font-extrabold tracking-tight">{{ confirmacao.titulo }}</h2>
          <p id="texto-confirmacao" class="mt-2 text-[0.95rem] text-texto-2">{{ confirmacao.texto }}</p>
          <div class="mt-5 flex flex-wrap justify-end gap-2">
            <button type="button" class="botao-secundario min-h-11" @click="responder(false)">Cancelar</button>
            <button type="button" class="botao-perigo min-h-11" :disabled="store.salvando" @click="responder(true)">{{ confirmacao.botao }}</button>
          </div>
        </section>
      </div>
    </Teleport>
  </template>
</template>
