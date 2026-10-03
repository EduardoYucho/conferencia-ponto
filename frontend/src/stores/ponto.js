import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { conectarEventos } from '@/api/eventos'
import { useAuthStore } from '@/stores/auth'
import { useNotificacoesStore } from '@/stores/notificacoes'
import { dataISO, deISO } from '@/utils/tempo'
import { relatar } from '@/utils/erros'

const SIGLAS = ['DOM', 'SEG', 'TER', 'QUA', 'QUI', 'SEX', 'SAB']

/**
 * O que é comum a todas as telas da pessoa em exibição: o relógio e o horário de trabalho vindos do servidor,
 * o banco de horas, as gravações (bater ponto, ajustar, lançar, marcar) e a conexão em tempo real.
 *
 * Cada tela busca o próprio conteúdo já pronto na API (Início: /inicio; Meu ponto: /ponto ...). Quando algo
 * muda — uma gravação daqui ou um evento do servidor — `mudouEm` é atualizado e a tela em exibição se
 * recarrega. Nenhuma conta de jornada é feita aqui: saldos, situações e ações vêm decididos do servidor.
 */
export const usePontoStore = defineStore('ponto', () => {
  // ---------------------------------------------------------------- estado
  /** Relógio do servidor e horário da pessoa: { hoje, agora, horario, semana, cargaDiaInteiroSegundos, limites... } */
  const configuracao = ref(null)
  /** Saldos do mês corrente e o ciclo aberto do banco de horas: { ciclo, meses, ... } */
  const saldos = ref(null)
  /** Uma gravação está em andamento (os botões de salvar esperam). */
  const salvando = ref(false)
  /** Os dados de ponto da pessoa em tela mudaram (gravação ou evento): as telas recarregam. */
  const mudouEm = ref(0)
  /** O que mudou por último: { tipo: 'jornada'|'calendario'|'banco'|'ciclo'|'conciliacao', data? } */
  const ultimaMudanca = ref(null)
  /** Diferenças com o RH esperando decisão (o número ao lado de "Conferir com o RH" no menu). */
  const pendentesRh = ref(null)
  /** Dias que alguma tela já carregou: data → { previstoSegundos, periodos } (as janelas consultam). */
  const diasConhecidos = reactive({})

  /** 'desconectado' | 'conectando' | 'conectado' | 'reconectando' */
  const tempoReal = ref('desconectado')
  /** { habilitado, ativo, situacao, diretorio, mensagem } — estado do monitor de PDFs no servidor */
  const monitoramento = ref(null)
  /** Último evento recebido pelo tempo real (a tela decide se mostra aviso/realce). */
  const ultimoEvento = ref(null)
  /** Último aviso de mudança na conciliação com o RH (a tela de conciliação recarrega). */
  const ultimaConciliacao = ref(null)
  /** Cadastro de usuários mudou (a tela de usuários recarrega). */
  const ultimaAlteracaoUsuarios = ref(null)
  /** Planilha do Google da pessoa em tela: { situacao, emailServico, url, titulo, sincronizadaEm, erro }. */
  const planilha = ref(null)
  const erroPlanilha = ref(null)
  /**
   * Uma alteração foi salva, mas algo da tela não conseguiu se atualizar em seguida. Um aviso global oferece
   * "Atualizar agora" — o que NÃO pode acontecer é a gravação parecer ter falhado e a pessoa repetir.
   */
  const desatualizado = ref(false)
  /** Alguém bateu o ponto hoje (ou o dia de hoje mudou para alguém): o painel da equipe consulta de novo. */
  const presencaMudouEm = ref(null)
  /** Momento da última reconexão do tempo real (as telas recarregam o que podem ter perdido). */
  const reconectouEm = ref(null)

  let encerrarEventos = null
  let timerMudanca = null
  let timerDia = null
  let diaDoNavegador = dataISO()
  let jaConectou = false
  let pessoa = 0 // muda a cada troca de pessoa: respostas da anterior são descartadas
  let aoPerderSessao = () => {}

  // -------------------------------------------------------------- consultas
  const hoje = computed(() => configuracao.value?.hoje ?? dataISO())
  const horario = computed(() => configuracao.value?.horario ?? null)
  /** "Um dia inteiro" no horário da pessoa (o mais comum entre os dias com expediente), calculado no servidor. */
  const cargaDiaInteiro = computed(() => configuracao.value?.cargaDiaInteiroSegundos ?? null)
  const limites = computed(() => configuracao.value?.limites ?? null)
  /** Ciclo aberto do banco de horas: o saldo "de verdade", que o RH zera a cada fechamento. */
  const ciclo = computed(() => saldos.value?.ciclo ?? null)

  /** Expediente do dia da semana no horário vigente (do servidor): { periodos, cargaSegundos } ou undefined. */
  const expedienteDaSemana = (data) => configuracao.value?.semana?.[SIGLAS[deISO(data).getDay()]]

  /** O horário da pessoa prevê trabalho no dia. */
  function temExpediente(data) {
    if (!data) return false
    const conhecido = diasConhecidos[data]
    return conhecido ? conhecido.previstoSegundos > 0 || conhecido.periodos.length > 0 : !!expedienteDaSemana(data)
  }

  /** Períodos do horário no dia: [{ entrada, saida }]. */
  function periodosDoDia(data) {
    if (!data) return []
    const conhecido = diasConhecidos[data]
    if (conhecido?.periodos.length) return conhecido.periodos
    return expedienteDaSemana(data)?.periodos ?? []
  }

  /** Carga prevista do dia pelo horário (0 = sem expediente). */
  function cargaDoDia(data) {
    if (!data) return 0
    const conhecido = diasConhecidos[data]
    if (conhecido) return conhecido.previstoSegundos
    return expedienteDaSemana(data)?.cargaSegundos ?? 0
  }

  /** As telas registram os dias que receberam da API (para as janelas de ajuste e lançamento). */
  function registrarDias(dias) {
    for (const d of dias ?? []) {
      diasConhecidos[d.data] = { previstoSegundos: d.previstoSegundos ?? 0, periodos: d.registro?.grade ?? [] }
    }
  }

  // -------------------------------------------------------------- carga
  async function fetchConfiguracao() {
    const pedido = pessoa
    const dados = await pontoApi.configuracao()
    if (pedido === pessoa) configuracao.value = dados
    return dados
  }

  async function recarregarConfiguracao() {
    try {
      await fetchConfiguracao()
    } catch {
      // o relógio e o horário anteriores continuam valendo; a próxima tela tenta de novo
    }
  }

  /**
   * Saldos e ciclo do banco de horas (o que as janelas de lançar e fechar o banco consultam).
   * @param {{ silencioso?: boolean }} [opcoes] silencioso: uma falha não vira aviso de "tela desatualizada"
   * @returns {Promise<boolean>} estão em dia
   */
  async function atualizarSaldos({ silencioso = false } = {}) {
    const pedido = pessoa
    try {
      const dados = await pontoApi.saldos()
      if (pedido === pessoa) saldos.value = dados
      return true
    } catch {
      if (!silencioso) desatualizado.value = true // o aviso global oferece "Atualizar agora"
      return false
    }
  }

  /** Quantas diferenças com o RH esperam decisão (o número no menu). */
  async function carregarPendentesRh() {
    const pedido = pessoa
    try {
      const resumo = await pontoApi.resumoConciliacao()
      if (pedido === pessoa) pendentesRh.value = resumo?.pendentes ?? 0
    } catch {
      // o menu fica sem o número; a tela "Conferir com o RH" mostra o erro, se houver
    }
  }

  /** O que é comum a todas as telas, ao entrar e ao trocar a pessoa em tela. */
  function carregarBase() {
    return Promise.all([recarregarConfiguracao(), atualizarSaldos({ silencioso: true }), carregarPendentesRh()])
  }

  /** Avisa as telas de que os dados da pessoa mudaram (elas recarregam o que mostram). */
  function sinalizarMudanca(mudanca = null) {
    if (mudanca) ultimaMudanca.value = { ...mudanca, em: Date.now() }
    mudouEm.value = Date.now()
  }

  /** Vários eventos seguidos (uma pasta de PDFs sendo lida) viram um aviso só. */
  function agendarMudanca(mudanca) {
    if (mudanca) ultimaMudanca.value = { ...mudanca, em: Date.now() }
    clearTimeout(timerMudanca)
    timerMudanca = setTimeout(() => {
      mudouEm.value = Date.now()
      atualizarSaldos({ silencioso: true })
      carregarPendentesRh()
    }, 400)
  }

  /** "Atualizar agora" do aviso global: relê o que é comum e manda as telas recarregarem. */
  async function atualizarTudo() {
    await recarregarConfiguracao()
    const ok = await atualizarSaldos()
    if (ok) desatualizado.value = false // só some o aviso quando a atualização deu certo
    carregarPendentesRh()
    sinalizarMudanca()
    return ok
  }

  // -------------------------------------------------------------- gravações
  /**
   * Faz a gravação e, se deu certo, avisa as telas. O que vem depois (reler saldos) nunca transforma uma
   * gravação bem-sucedida em erro: no máximo liga o aviso de "tela desatualizada".
   */
  async function gravar(chamada, mudanca) {
    salvando.value = true
    let resultado
    try {
      resultado = await chamada()
    } finally {
      salvando.value = false
    }
    sinalizarMudanca(typeof mudanca === 'function' ? mudanca(resultado) : mudanca)
    atualizarSaldos()
    return resultado
  }

  /**
   * Insere a próxima batida do dia. Sem parâmetros, o servidor usa o relógio dele.
   * @param {{ data?: string, horario?: string }} [batida]
   */
  const postBatida = (batida = {}) =>
    gravar(() => pontoApi.registrarBatida(batida), (r) => ({ tipo: 'jornada', data: r?.data }))

  /**
   * Lançamento de horas em dia sem expediente ou feriado (conta inteiro a favor).
   * @param {{ data: string, intervalos: { entrada: string, saida: string }[] }} lancamento
   */
  const postRegistroManual = (lancamento) =>
    gravar(() => pontoApi.lancarManual(lancamento), (r) => ({ tipo: 'jornada', data: r?.data }))

  /** Ajuste das batidas de um dia (falha no relógio, batida esquecida). */
  const ajustarBatidas = ({ data, horarios, justificativa }) =>
    gravar(() => pontoApi.ajustarBatidas(data, { horarios, justificativa }), { tipo: 'jornada', data })

  /**
   * Débito (abater) ou crédito avulso no banco de horas.
   * @param {{ data: string, duracao: string, sentido: 'DEBITO'|'CREDITO', descricao: string }} lancamento
   */
  const lancarNoBanco = (lancamento) =>
    gravar(() => pontoApi.lancarNoBanco(lancamento), { tipo: 'banco', data: lancamento.data })

  const excluirLancamentoBanco = (lancamento) =>
    gravar(() => pontoApi.excluirLancamentoBanco(lancamento.id), { tipo: 'banco', data: lancamento.data })

  /**
   * Marca dias sem jornada: feriado (um dia) ou férias/folga/atestado/licença/abono (período).
   * @param {{ tipo: 'FERIADO'|'FERIAS'|'FOLGA'|'ATESTADO'|'LICENCA'|'ABONO', dataInicio: string, dataFim?: string,
   *           descricao?: string, abrangencia?: string }} marcacao
   */
  const marcarDias = ({ tipo, dataInicio, dataFim, descricao, abrangencia }) =>
    gravar(() => (tipo === 'FERIADO'
      ? pontoApi.cadastrarFeriado({ data: dataInicio, descricao, abrangencia })
      : pontoApi.cadastrarAusencia({ dataInicio, dataFim: dataFim || dataInicio, tipo, descricao: descricao || null })),
    { tipo: 'calendario', data: dataInicio })

  /** Remove a marcação de um dia (o feriado, ou o período de ausência inteiro). */
  const removerMarcacao = (marcador) =>
    gravar(() => (marcador.tipo === 'feriado'
      ? pontoApi.excluirFeriado(marcador.feriado.data)
      : pontoApi.excluirAusencia(marcador.ausencia.id)), { tipo: 'calendario' })

  const excluirRegistro = (data) => gravar(() => pontoApi.excluir(data), { tipo: 'jornada', data })

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
        // voltou depois de uma queda: o que mudou nesse meio-tempo não chegou por evento
        if (jaConectou) {
          reconectouEm.value = Date.now()
          recarregarConfiguracao()
          agendarMudanca()
        }
        jaConectou = true
      },
      onMonitor: (estado) => daTela(estado) && (monitoramento.value = estado), // pasta dos PDFs caiu/voltou
      onJornadaAtualizada: (evento) => {
        if (!daTela(evento)) return
        try {
          ultimoEvento.value = { tipo: 'jornada-atualizada', ...evento, recebidoEm: Date.now() }
          agendarMudanca({ tipo: 'jornada', data: evento.data })
        } catch (e) {
          relatar(e, 'tempo real')
        }
      },
      onComprovanteNaoImportado: (comprovante) => {
        if (daTela(comprovante)) {
          ultimoEvento.value = { tipo: 'comprovante-nao-importado', ...comprovante, recebidoEm: Date.now() }
        }
      },
      onCiclo: (payload) => {
        // banco de horas fechado/corrigido: saldos novos e avisos de prazo do ciclo antigo arquivados
        if (daTela(payload)) agendarMudanca({ tipo: 'ciclo' })
        if (meu(payload)) useNotificacoesStore().carregar().catch(() => {})
      },
      onNotificacao: (payload) => meu(payload) && useNotificacoesStore().receber(payload),
      onConciliacao: (payload) => {
        if (!daTela(payload)) return
        ultimaConciliacao.value = { ...payload, recebidoEm: Date.now() }
        agendarMudanca({ tipo: 'conciliacao' })
      },
      // feriado, ausência, horário ou lançamento no banco feito em outra aba (ou pela conciliação)
      onCalendario: (payload) => {
        if (!daTela(payload)) return
        if (payload.usuarioId) recarregarConfiguracao() // pode ter sido o horário
        agendarMudanca({ tipo: 'calendario' })
      },
      onBanco: (payload) => daTela(payload) && agendarMudanca({ tipo: 'banco', data: payload.data }),
      onUsuarios: ({ alterado }) => {
        auth.carregarTitulares().catch(() => {})
        if (alterado === auth.usuario?.id) auth.atualizarUsuario().catch(() => {})
        if (alterado === auth.idEmTela) carregarMonitor()
        ultimaAlteracaoUsuarios.value = Date.now()
      },
      onPlanilha: (estado) => daTela(estado) && (planilha.value = estado), // gravada no Google (ou falhou)
      onPresenca: () => (presencaMudouEm.value = Date.now()),
      // sessão vencida (ou senha provisória): o mesmo caminho de uma requisição recusada
      onNaoAutorizado: (status, codigo) => aoPerderSessao(status, codigo),
    })
    // virou o dia com a aba aberta: o "hoje" (e o que dá para bater) vem do servidor de novo
    clearInterval(timerDia)
    timerDia = setInterval(() => {
      if (dataISO() === diaDoNavegador) return
      diaDoNavegador = dataISO()
      recarregarConfiguracao().then(() => agendarMudanca())
    }, 30_000)
  }

  /** Registrado no main.js: o tempo real descobriu que a sessão acabou. */
  function definirAoPerderSessao(acao) {
    aoPerderSessao = acao
  }

  /** Situação da planilha do Google da pessoa em tela (depois, o tempo real mantém em dia). */
  async function carregarPlanilha() {
    try {
      planilha.value = await pontoApi.planilha()
      erroPlanilha.value = null
    } catch (e) {
      erroPlanilha.value = e
    }
    return planilha.value
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

  function zerarDadosDaPessoa() {
    pessoa++
    configuracao.value = null
    saldos.value = null
    pendentesRh.value = null
    monitoramento.value = null
    planilha.value = null
    erroPlanilha.value = null
    desatualizado.value = false
    ultimoEvento.value = null
    ultimaConciliacao.value = null
    ultimaMudanca.value = null
    for (const data of Object.keys(diasConhecidos)) delete diasConhecidos[data]
  }

  /**
   * Trocou a pessoa em tela (admin/coordenação): zera os dados da anterior. As telas são remontadas e
   * carregam os dados da nova pessoa.
   */
  function trocarPessoa() {
    zerarDadosDaPessoa()
    carregarMonitor()
    carregarBase()
  }

  function desconectarTempoReal() {
    encerrarEventos?.()
    encerrarEventos = null
    jaConectou = false
    clearTimeout(timerMudanca)
    clearInterval(timerDia)
  }

  /** Limpa o estado ao sair (outro usuário pode entrar na mesma aba). */
  function limpar() {
    desconectarTempoReal()
    zerarDadosDaPessoa()
    reconectouEm.value = null
    mudouEm.value = 0
  }

  return {
    // estado
    configuracao, saldos, salvando, mudouEm, ultimaMudanca, pendentesRh, tempoReal, monitoramento, ultimoEvento,
    ultimaConciliacao, ultimaAlteracaoUsuarios, planilha, erroPlanilha, desatualizado, reconectouEm, presencaMudouEm,
    // consultas
    hoje, horario, cargaDiaInteiro, limites, ciclo, temExpediente, periodosDoDia, cargaDoDia,
    // carga
    fetchConfiguracao, recarregarConfiguracao, atualizarSaldos, atualizarTudo, sinalizarMudanca, registrarDias,
    carregarBase, carregarPendentesRh,
    carregarMonitor, carregarPlanilha, trocarPessoa,
    // gravações
    postBatida, postRegistroManual, ajustarBatidas, excluirRegistro, lancarNoBanco, excluirLancamentoBanco,
    marcarDias, removerMarcacao,
    // compatibilidade com telas que pediam "recarregar o mês": hoje basta avisar que mudou
    recarregarMes: async () => sinalizarMudanca(),
    // tempo real
    conectarTempoReal, desconectarTempoReal, definirAoPerderSessao, limpar,
  }
})
