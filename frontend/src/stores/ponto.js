import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { conectarEventos } from '@/api/eventos'
import { useAuthStore } from '@/stores/auth'
import { useNotificacoesStore } from '@/stores/notificacoes'
import { cargaTipica, dataISO, deISO, ehFimDeSemana, JORNADA_BASE_PADRAO } from '@/utils/tempo'

const proximoDia = (iso) => {
  const d = deISO(iso)
  d.setDate(d.getDate() + 1)
  return dataISO(d)
}

/**
 * Estado central do ponto da pessoa em tela: horário de trabalho, dias do mês em exibição,
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
  /** Débitos/créditos avulsos no banco de horas com data no mês (segundos negativos = abatidos). */
  const lancamentosMes = ref([])
  /** Expediente previsto de cada dia do mês pelo horário da pessoa: [{ data, previstoSegundos, periodos }]. */
  const expedientesMes = ref(null)
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
  /** Cadastro de usuários mudou (a tela de usuários recarrega). */
  const ultimaAlteracaoUsuarios = ref(null)
  let encerrarEventos = null
  let timerSaldos = null
  let timerMes = null

  // -------------------------------------------------------------- getters
  const hoje = computed(() => configuracao.value?.hoje ?? dataISO())
  const jornadaBaseSegundos = computed(() => configuracao.value?.jornadaBaseSegundos ?? JORNADA_BASE_PADRAO)
  /** Horário semanal vigente: { vigenteDesde, toleranciaMinutos, dias: { SEG: '08:00-12:00 13:00-17:48', ... } }. */
  const horario = computed(() => configuracao.value?.horario ?? null)
  /** Carga do dia mais comum no horário (ex.: "folga compensada" = um dia inteiro). */
  const cargaDiaInteiro = computed(() => cargaTipica(horario.value?.dias))
  const expedientePorData = computed(() =>
    Object.fromEntries((expedientesMes.value ?? []).map((e) => [e.data, e])))

  const diasPorData = computed(() => Object.fromEntries(dias.value.map((d) => [d.data, d])))
  const registroHoje = computed(() => diasPorData.value[hoje.value] ?? null)
  const diaSelecionado = computed(() => diasPorData.value[dataSelecionada.value] ?? null)

  const saldoMensal = computed(() => resumo.value?.saldoMensalSegundos ?? 0)
  const saldoAnualAcumulado = computed(() => resumo.value?.saldoAnualAcumuladoSegundos ?? 0)
  const serieAnual = computed(() => saldos.value?.meses ?? [])
  /** Ciclo aberto do banco de horas: o saldo "de verdade", que o RH zera a cada fechamento. */
  const ciclo = computed(() => saldos.value?.ciclo ?? null)

  /**
   * data -> marcador do dia: { tipo: 'feriado', rotulo, feriado } ou { tipo: 'ausencia', rotulo, descricao, ausencia }.
   * Rotula os dias sem registro e permite remover a marcação pelo próprio dia.
   */
  const marcadoresDoMes = computed(() => {
    const mapa = {}
    for (const f of feriadosMes.value) mapa[f.data] = { tipo: 'feriado', rotulo: f.descricao || 'Feriado', feriado: f }
    for (const a of ausenciasMes.value) {
      for (let d = a.dataInicio; d <= a.dataFim; d = proximoDia(d)) {
        if (!mapa[d]) mapa[d] = { tipo: 'ausencia', rotulo: a.tipoRotulo, descricao: a.descricao, ausencia: a }
      }
    }
    return mapa
  })

  /** data -> lançamentos no banco daquele dia. */
  const lancamentosPorData = computed(() => {
    const mapa = {}
    for (const l of lancamentosMes.value) (mapa[l.data] ??= []).push(l)
    return mapa
  })

  /** O horário da pessoa prevê trabalho no dia (fora do mês carregado: segunda a sexta). */
  function temExpediente(data) {
    if (expedientesMes.value && data?.slice(0, 7) === mesCarregado()) return !!expedientePorData.value[data]
    return !ehFimDeSemana(data)
  }

  /** Períodos da grade do dia ([{ entrada, saida }]): do registro, do expediente do mês ou do horário atual. */
  function periodosDoDia(data) {
    const registro = diasPorData.value[data]
    if (registro?.grade?.length) return registro.grade
    const expediente = expedientePorData.value[data]
    if (expediente) return expediente.periodos
    return data === hoje.value ? (configuracao.value?.periodos ?? []) : []
  }

  /** Carga prevista do dia pelo horário (0 = sem expediente). */
  function cargaDoDia(data) {
    const expediente = expedientePorData.value[data]
    if (expediente) return expediente.previstoSegundos
    return temExpediente(data) ? cargaDiaInteiro.value : 0
  }

  function mesCarregado() {
    return ano.value ? `${ano.value}-${String(mes.value).padStart(2, '0')}` : null
  }

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
      lancamentosMes.value = dadosMes.lancamentos ?? []
      expedientesMes.value = dadosMes.expedientes ?? null
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

  /** Recarrega o mês em exibição (dias, marcações, lançamentos) e os saldos. */
  async function recarregarMes() {
    if (ano.value) await fetchMes(ano.value, mes.value)
  }

  /**
   * Débito (abater) ou crédito avulso no banco de horas.
   * @param {{ data: string, duracao: string, sentido: 'DEBITO'|'CREDITO', descricao: string }} lancamento
   */
  async function lancarNoBanco(lancamento) {
    const salvo = await executar(() => pontoApi.lancarNoBanco(lancamento), { indicador: salvando })
    await recarregarMes()
    return salvo
  }

  async function excluirLancamentoBanco(lancamento) {
    await executar(() => pontoApi.excluirLancamentoBanco(lancamento.id), { indicador: salvando })
    await recarregarMes()
  }

  /**
   * Marca dias sem jornada: feriado (um dia) ou férias/folga/atestado/licença/abono (período).
   * @param {{ tipo: 'FERIADO'|'FERIAS'|'FOLGA'|'ATESTADO'|'LICENCA'|'ABONO', dataInicio: string, dataFim?: string,
   *           descricao?: string, abrangencia?: string }} marcacao
   */
  async function marcarDias({ tipo, dataInicio, dataFim, descricao, abrangencia }) {
    const salvo = await executar(() => (tipo === 'FERIADO'
      ? pontoApi.cadastrarFeriado({ data: dataInicio, descricao, abrangencia })
      : pontoApi.cadastrarAusencia({ dataInicio, dataFim: dataFim || dataInicio, tipo, descricao: descricao || null })),
    { indicador: salvando })
    await recarregarMes()
    return salvo
  }

  /** Remove a marcação de um dia (o feriado, ou o período de ausência inteiro). */
  async function removerMarcacao(marcador) {
    await executar(() => (marcador.tipo === 'feriado'
      ? pontoApi.excluirFeriado(marcador.feriado.data)
      : pontoApi.excluirAusencia(marcador.ausencia.id)), { indicador: salvando })
    await recarregarMes()
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
  /**
   * Abre a assinatura do stream /api/v1/eventos (idempotente). Admin e coordenação recebem os eventos de
   * todas as pessoas: só o que é da pessoa em tela (ou de todos, como um feriado) muda a tela.
   */
  function conectarTempoReal() {
    if (encerrarEventos) return
    const auth = useAuthStore()
    /** O evento é da pessoa em tela (usuarioId nulo = vale para todos). */
    const daTela = (payload) => !payload?.usuarioId || payload.usuarioId === auth.idEmTela
    const meu = (payload) => !payload?.usuarioId || payload.usuarioId === auth.usuario?.id
    encerrarEventos = conectarEventos({
      onStatus: (status) => (tempoReal.value = status),
      onConectado: (estado) => {
        if (auth.vendoOsProprios && auth.ehTitular) monitoramento.value = estado
        else carregarMonitor()
      },
      onMonitor: (estado) => daTela(estado) && (monitoramento.value = estado), // pasta dos PDFs caiu/voltou
      onJornadaAtualizada: (evento) => daTela(evento) && aplicarAtualizacao(evento),
      onComprovanteNaoImportado: (comprovante) => {
        if (daTela(comprovante)) {
          ultimoEvento.value = { tipo: 'comprovante-nao-importado', ...comprovante, recebidoEm: Date.now() }
        }
      },
      onCiclo: (payload) => {
        // banco de horas fechado/corrigido: saldos novos e avisos de prazo do ciclo antigo arquivados
        if (daTela(payload)) agendarSaldos()
        if (meu(payload)) useNotificacoesStore().carregar().catch(() => {})
      },
      onNotificacao: (payload) => meu(payload) && useNotificacoesStore().receber(payload),
      onConciliacao: (payload) => {
        if (!daTela(payload)) return
        ultimaConciliacao.value = { ...payload, recebidoEm: Date.now() }
        agendarSaldos()
      },
      // feriado/ausência/horário ou lançamento no banco feito em outra aba (ou pela conciliação): recarrega o mês
      onCalendario: (payload) => {
        if (!daTela(payload) || !afetaMesExibido(payload.inicio, payload.fim)) return
        if (payload.usuarioId) recarregarConfiguracao() // pode ter sido o horário
        agendarRecargaMes()
      },
      onBanco: (payload) => {
        if (!daTela(payload)) return
        if (afetaMesExibido(payload.data, payload.data)) agendarRecargaMes()
        else agendarSaldos()
      },
      onUsuarios: ({ alterado }) => {
        auth.carregarTitulares().catch(() => {})
        if (alterado === auth.usuario?.id) auth.atualizarUsuario().catch(() => {})
        if (alterado === auth.idEmTela) carregarMonitor()
        ultimaAlteracaoUsuarios.value = Date.now()
      },
      onNaoAutorizado: () => useAuthStore().logout(),
    })
  }

  /** Situação da pasta de comprovantes da pessoa em tela (o evento "conectado" traz só a do usuário logado). */
  async function carregarMonitor() {
    try {
      const { recentes, ...estado } = await pontoApi.importacoes(1)
      monitoramento.value = estado
    } catch {
      // a próxima conexão ou evento corrige
    }
  }

  async function recarregarConfiguracao() {
    try {
      await fetchConfiguracao()
    } catch {
      // silencioso
    }
  }

  /**
   * Trocou a pessoa em tela (admin/coordenação): zera os dados da anterior. As telas são remontadas e
   * carregam os dados da nova pessoa.
   */
  function trocarPessoa() {
    configuracao.value = null
    ano.value = null
    mes.value = null
    dias.value = []
    ausenciasMes.value = []
    feriadosMes.value = []
    lancamentosMes.value = []
    expedientesMes.value = null
    resumo.value = null
    saldos.value = null
    dataSelecionada.value = null
    monitoramento.value = null
    ultimoEvento.value = null
    ultimaConciliacao.value = null
    erro.value = null
    carregarMonitor()
  }

  function desconectarTempoReal() {
    encerrarEventos?.()
    encerrarEventos = null
    clearTimeout(timerSaldos)
    clearTimeout(timerMes)
  }

  function afetaMesExibido(inicio, fim) {
    if (!ano.value) return false
    const primeiro = `${ano.value}-${String(mes.value).padStart(2, '0')}-01`
    const ultimo = `${ano.value}-${String(mes.value).padStart(2, '0')}-31`
    return inicio <= ultimo && fim >= primeiro
  }

  function agendarRecargaMes() {
    clearTimeout(timerMes)
    timerMes = setTimeout(() => recarregarMes().catch(() => {}), 500)
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
    lancamentosMes.value = []
    expedientesMes.value = null
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
    configuracao, ano, mes, dias, ausenciasMes, feriadosMes, lancamentosMes, expedientesMes, resumo, saldos,
    dataSelecionada, carregando, salvando,
    erro, tempoReal, monitoramento, ultimoEvento, ultimaConciliacao, ultimaAlteracaoUsuarios,
    // getters
    hoje, jornadaBaseSegundos, horario, cargaDiaInteiro, expedientePorData, diasPorData, registroHoje, diaSelecionado,
    saldoMensal, saldoAnualAcumulado, serieAnual, ciclo, marcadoresDoMes, lancamentosPorData, ehMesAtual,
    temExpediente, periodosDoDia, cargaDoDia,
    // actions
    carregarMonitor, recarregarConfiguracao, trocarPessoa,
    fetchConfiguracao, fetchMes, fetchMesAtual, navegarMes, postBatida, postRegistroManual,
    ajustarBatidas, excluirRegistro, selecionarDia, limparErro, recarregarMes, lancarNoBanco, excluirLancamentoBanco,
    marcarDias, removerMarcacao,
    conectarTempoReal, desconectarTempoReal, aplicarAtualizacao, atualizarSaldos, limpar,
  }
})
