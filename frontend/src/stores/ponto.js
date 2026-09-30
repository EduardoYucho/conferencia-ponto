import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { conectarEventos } from '@/api/eventos'
import { useAuthStore } from '@/stores/auth'
import { useNotificacoesStore } from '@/stores/notificacoes'
import { dataISO, deISO, JORNADA_BASE_PADRAO } from '@/utils/tempo'

const proximoDia = (iso) => {
  const d = deISO(iso)
  d.setDate(d.getDate() + 1)
  return dataISO(d)
}

/**
 * Estado central do ponto: configuração da jornada, dias do mês em exibição,
 * saldos consolidados (mensal e ciclo do banco de horas) e a conexão em tempo real (SSE)
 * que aplica na tela as batidas importadas dos PDFs. Durações em SEGUNDOS, como no RH.
 */
export const usePontoStore = defineStore('ponto', () => {
  // ---------------------------------------------------------------- state
  const configuracao = ref(null)
  const ano = ref(null)
  const mes = ref(null)
  const dias = ref([])
  /** Férias/atestados/folgas e feriados que tocam o mês (rotulam os dias sem registro). */
  const ausenciasMes = ref([])
  const feriadosMes = ref([])
  const resumo = ref(null)
  const saldos = ref(null)
  const dataSelecionada = ref(null)

  const carregando = ref(false)
  const salvando = ref(false)
  const erro = ref(null)

  /** 'desconectado' | 'conectando' | 'conectado' | 'reconectando' */
  const tempoReal = ref('desconectado')
  /** { habilitado, ativo, situacao, diretorio, mensagem } — estado do monitor de PDFs no servidor */
  const monitoramento = ref(null)
  /** Último evento recebido pelo SSE (a view decide se mostra aviso/realce). */
  const ultimoEvento = ref(null)
  /** Último aviso de mudança na conciliação com o RH (a tela de conciliação recarrega). */
  const ultimaConciliacao = ref(null)
  let encerrarEventos = null
  let timerSaldos = null

  // -------------------------------------------------------------- getters
  const hoje = computed(() => configuracao.value?.hoje ?? dataISO())
  const jornadaBaseSegundos = computed(() => configuracao.value?.jornadaBaseSegundos ?? JORNADA_BASE_PADRAO)

  const diasPorData = computed(() => Object.fromEntries(dias.value.map((d) => [d.data, d])))
  const registroHoje = computed(() => diasPorData.value[hoje.value] ?? null)
  const diaSelecionado = computed(() => diasPorData.value[dataSelecionada.value] ?? null)

  const saldoMensal = computed(() => resumo.value?.saldoMensalSegundos ?? 0)
  const saldoAnualAcumulado = computed(() => resumo.value?.saldoAnualAcumuladoSegundos ?? 0)
  const serieAnual = computed(() => saldos.value?.meses ?? [])
  /** Ciclo aberto do banco de horas: o saldo "de verdade", que o RH zera a cada fechamento. */
  const ciclo = computed(() => saldos.value?.ciclo ?? null)

  /** data -> { tipo: 'ausencia' | 'feriado', rotulo } para dias sem registro. */
  const marcadoresDoMes = computed(() => {
    const mapa = {}
    for (const f of feriadosMes.value) mapa[f.data] = { tipo: 'feriado', rotulo: f.descricao || 'Feriado' }
    for (const a of ausenciasMes.value) {
      for (let d = a.dataInicio; d <= a.dataFim; d = proximoDia(d)) {
        if (!mapa[d]) mapa[d] = { tipo: 'ausencia', rotulo: a.tipoRotulo, descricao: a.descricao }
      }
    }
    return mapa
  })

  const ehMesAtual = computed(() => {
    const [a, m] = hoje.value.split('-').map(Number)
    return ano.value === a && mes.value === m
  })

  // -------------------------------------------------------------- actions
  async function executar(acao, { indicador = carregando } = {}) {
    indicador.value = true
    erro.value = null
    try {
      return await acao()
    } catch (e) {
      erro.value = e
      throw e
    } finally {
      indicador.value = false
    }
  }

  async function fetchConfiguracao() {
    configuracao.value = await pontoApi.configuracao()
    return configuracao.value
  }

  /** Carrega dias + resumo do mês e a série anual de saldos. */
  async function fetchMes(anoAlvo, mesAlvo) {
    return executar(async () => {
      const [dadosMes, dadosSaldos] = await Promise.all([
        pontoApi.mes(anoAlvo, mesAlvo),
        pontoApi.saldos(anoAlvo, mesAlvo),
      ])
      ano.value = dadosMes.ano
      mes.value = dadosMes.mes
      dias.value = dadosMes.dias
      ausenciasMes.value = dadosMes.ausencias ?? []
      feriadosMes.value = dadosMes.feriados ?? []
      resumo.value = dadosMes.resumo
      saldos.value = dadosSaldos

      if (!diasPorData.value[dataSelecionada.value]) {
        dataSelecionada.value = diasPorData.value[hoje.value]
          ? hoje.value
          : (dadosMes.dias.at(-1)?.data ?? null)
      }
      return dadosMes
    })
  }

  /** Mês corrente segundo o relógio do servidor. */
  async function fetchMesAtual() {
    if (!configuracao.value) {
      await executar(fetchConfiguracao)
    }
    const [a, m] = hoje.value.split('-').map(Number)
    return fetchMes(a, m)
  }

  async function navegarMes(deslocamento) {
    const alvo = new Date(ano.value, mes.value - 1 + deslocamento, 1)
    dataSelecionada.value = null
    return fetchMes(alvo.getFullYear(), alvo.getMonth() + 1)
  }

  /** Recarrega o mês da data afetada e a seleciona. */
  async function recarregarPara(data) {
    const [a, m] = data.split('-').map(Number)
    dataSelecionada.value = data
    await fetchMes(a, m)
  }

  /**
   * Insere a próxima batida do dia. Sem parâmetros, o servidor usa o relógio dele.
   * @param {{ data?: string, horario?: string }} [batida]
   */
  async function postBatida(batida = {}) {
    const registro = await executar(() => pontoApi.registrarBatida(batida), { indicador: salvando })
    await recarregarPara(registro.data)
    return registro
  }

  /**
   * Lançamento manual de fim de semana/feriado (100% crédito).
   * @param {{ data: string, intervalos: { entrada: string, saida: string }[] }} lancamento
   */
  async function postRegistroManual(lancamento) {
    const registro = await executar(() => pontoApi.lancarManual(lancamento), { indicador: salvando })
    await recarregarPara(registro.data)
    return registro
  }

  /** Ajuste manual das batidas de um dia (falha no relógio corrigida pelo RH). */
  async function ajustarBatidas({ data, horarios, justificativa }) {
    const registro = await executar(() => pontoApi.ajustarBatidas(data, { horarios, justificativa }),
      { indicador: salvando })
    await recarregarPara(registro.data)
    return registro
  }

  async function excluirRegistro(data) {
    await executar(() => pontoApi.excluir(data), { indicador: salvando })
    dataSelecionada.value = null
    await fetchMes(ano.value, mes.value)
  }

  function selecionarDia(data) {
    dataSelecionada.value = data
  }

  function limparErro() {
    erro.value = null
  }

  // ------------------------------------------------------- tempo real (SSE)
  /** Abre a assinatura do stream /api/v1/eventos (idempotente). */
  function conectarTempoReal() {
    if (encerrarEventos) return
    encerrarEventos = conectarEventos({
      onStatus: (status) => (tempoReal.value = status),
      onConectado: (estado) => (monitoramento.value = estado),
      onMonitor: (estado) => (monitoramento.value = estado), // pasta dos PDFs caiu/voltou
      onJornadaAtualizada: aplicarAtualizacao,
      onComprovanteNaoImportado: (comprovante) => {
        ultimoEvento.value = { tipo: 'comprovante-nao-importado', ...comprovante, recebidoEm: Date.now() }
      },
      onCiclo: () => {
        // banco de horas fechado/corrigido: saldos novos e avisos de prazo do ciclo antigo arquivados
        agendarSaldos()
        useNotificacoesStore().carregar().catch(() => {})
      },
      onNotificacao: (payload) => useNotificacoesStore().receber(payload),
      onConciliacao: (payload) => {
        ultimaConciliacao.value = { ...payload, recebidoEm: Date.now() }
        agendarSaldos()
      },
      onNaoAutorizado: () => useAuthStore().logout(),
    })
  }

  function desconectarTempoReal() {
    encerrarEventos?.()
    encerrarEventos = null
    clearTimeout(timerSaldos)
  }

  /** Limpa o estado ao sair (outro usuário pode entrar na mesma aba). */
  function limpar() {
    desconectarTempoReal()
    configuracao.value = null
    ano.value = null
    mes.value = null
    dias.value = []
    ausenciasMes.value = []
    feriadosMes.value = []
    resumo.value = null
    saldos.value = null
    dataSelecionada.value = null
    monitoramento.value = null
    ultimoEvento.value = null
    ultimaConciliacao.value = null
    erro.value = null
  }

  /**
   * Push do servidor: substitui (ou remove) o dia afetado direto no estado, sem
   * recarregar o mês, e em seguida atualiza só os saldos consolidados.
   */
  async function aplicarAtualizacao(evento) {
    const { data, registro, origem } = evento
    const [a, m] = data.split('-').map(Number)

    if (a === ano.value && m === mes.value) {
      const outros = dias.value.filter((d) => d.data !== data)
      dias.value = registro ? [...outros, registro].sort((x, y) => x.data.localeCompare(y.data)) : outros

      if (registro && origem === 'COMPROVANTE_PDF') {
        dataSelecionada.value = data // leva a linha do tempo para o dia importado
      } else if (!diasPorData.value[dataSelecionada.value]) {
        dataSelecionada.value = diasPorData.value[hoje.value] ? hoje.value : (dias.value.at(-1)?.data ?? null)
      }
    }

    ultimoEvento.value = { tipo: 'jornada-atualizada', ...evento, recebidoEm: Date.now() }
    agendarSaldos()
  }

  /**
   * Os saldos são recalculados uma vez só depois de uma rajada de eventos
   * (ex.: primeira leitura de uma pasta com centenas de PDFs).
   */
  function agendarSaldos() {
    clearTimeout(timerSaldos)
    timerSaldos = setTimeout(atualizarSaldos, 400)
  }

  async function atualizarSaldos() {
    if (!ano.value) return
    try {
      const dadosSaldos = await pontoApi.saldos(ano.value, mes.value)
      saldos.value = dadosSaldos
      resumo.value = dadosSaldos.meses[mes.value - 1]
    } catch {
      // silencioso: o próximo evento ou recarga corrige os totais
    }
  }

  return {
    // state
    configuracao, ano, mes, dias, ausenciasMes, feriadosMes, resumo, saldos, dataSelecionada, carregando, salvando,
    erro, tempoReal, monitoramento, ultimoEvento, ultimaConciliacao,
    // getters
    hoje, jornadaBaseSegundos, diasPorData, registroHoje, diaSelecionado,
    saldoMensal, saldoAnualAcumulado, serieAnual, ciclo, marcadoresDoMes, ehMesAtual,
    // actions
    fetchConfiguracao, fetchMes, fetchMesAtual, navegarMes, postBatida, postRegistroManual,
    ajustarBatidas, excluirRegistro, selecionarDia, limparErro,
    conectarTempoReal, desconectarTempoReal, aplicarAtualizacao, atualizarSaldos, limpar,
  }
})
