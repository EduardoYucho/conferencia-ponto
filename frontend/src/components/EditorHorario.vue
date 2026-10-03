<script setup>
import { computed, ref, useId, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { usePontoStore } from '@/stores/ponto'
import { avisar } from '@/utils/avisar'
import { mensagemDe } from '@/utils/erros'
import { dataBR, duracao } from '@/utils/horas'
import { DIAS_HORARIO, cargaDosPeriodos, periodosDoTexto, textoDosPeriodos } from '@/utils/tempo'
import Icone from '@/components/Icone.vue'

/**
 * Horário de trabalho: os períodos de cada dia da semana (dia sem período = sem expediente) e a tolerância em
 * cada batida. Mudar o horário vale "a partir de" uma data: os dias anteriores continuam calculados com o
 * horário que valia para eles. Os limites (quantos períodos por dia, tolerância máxima) vêm do servidor; a
 * conferência feita aqui é só ajuda de digitação — quem decide se o horário é aceito é o servidor.
 */
const props = defineProps({
  /** Horários cadastrados (GET /horarios), do mais antigo para o mais recente. */
  vigencias: { type: Array, default: () => [] },
  hoje: { type: String, required: true },
  podeEditar: { type: Boolean, default: false },
  /** Login de outra pessoa (o administrador alterando o horário dela); null = o próprio. */
  usuario: { type: String, default: null },
})
const emit = defineEmits(['salvo'])
const id = useId()

const ponto = usePontoStore()
/** Limites do servidor (com os valores de sempre como reserva, enquanto a configuração não chega). */
const maxPeriodos = computed(() => ponto.limites?.periodosPorDia ?? 3)
const toleranciaMaxima = computed(() => ponto.limites?.toleranciaMaximaMinutos ?? 60)
/** O que entra num dia quando a pessoa clica em "Trabalhar neste dia" (só um ponto de partida para digitar). */
const SUGESTAO = [{ entrada: '08:00', saida: '12:00' }, { entrada: '13:00', saida: '17:48' }]

const vigente = computed(() => {
  let atual = props.vigencias[0] ?? null
  for (const v of props.vigencias) if (v.vigenteDesde <= props.hoje) atual = v
  return atual
})
const futuras = computed(() => props.vigencias.filter((v) => v.vigenteDesde > props.hoje))
/** O horário mais antigo vale "desde o início" (não tem uma data de começo para mostrar). */
const desdeOInicio = (v) => v.vigenteDesde <= '2000-01-01'

const editando = ref(false)
const salvando = ref(false)
const erro = ref('')
const tentou = ref(false)
const form = ref(null)
const historicoAberto = ref(false)
/** Horário que a pessoa pediu para excluir e ainda não confirmou. */
const paraExcluir = ref(null)
const excluindo = ref(false)

function periodosDe(vigencia, sigla) {
  try {
    return periodosDoTexto(vigencia?.dias?.[sigla])
  } catch {
    return []
  }
}

function abrir() {
  const base = vigente.value
  form.value = {
    vigenteDesde: props.hoje,
    toleranciaMinutos: base?.toleranciaMinutos ?? 5,
    dias: Object.fromEntries(DIAS_HORARIO.map((d) => [d.sigla, periodosDe(base, d.sigla).map((p) => ({ ...p }))])),
  }
  erro.value = ''
  tentou.value = false
  paraExcluir.value = null
  editando.value = true
}

watch(() => props.usuario, () => {
  editando.value = false
  paraExcluir.value = null
})

function adicionarPeriodo(sigla) {
  const lista = form.value.dias[sigla]
  if (lista.length >= maxPeriodos.value) return
  if (!lista.length) {
    lista.push(...SUGESTAO.map((p) => ({ ...p })))
    return
  }
  lista.push({ entrada: lista.at(-1).saida, saida: '' })
}

function removerPeriodo(sigla, indice) {
  form.value.dias[sigla].splice(indice, 1)
}

function semExpediente(sigla) {
  form.value.dias[sigla] = []
}

/** Repete os períodos de segunda em terça a sexta. */
function copiarSegunda() {
  const segunda = form.value.dias.SEG
  for (const sigla of ['TER', 'QUA', 'QUI', 'SEX']) form.value.dias[sigla] = segunda.map((p) => ({ ...p }))
}

/** Conferência de cada dia enquanto se digita (mensagem ou null) e o total de horas do dia. */
const situacaoDias = computed(() => {
  if (!form.value) return {}
  return Object.fromEntries(DIAS_HORARIO.map(({ sigla }) => {
    const periodos = form.value.dias[sigla]
    if (periodos.some((p) => !p.entrada || !p.saida)) return [sigla, { erro: 'Preencha a hora de começo e a de fim.', carga: 0 }]
    try {
      const lidos = periodosDoTexto(textoDosPeriodos(periodos))
      return [sigla, { erro: null, carga: cargaDosPeriodos(lidos) }]
    } catch (e) {
      // periodosDoTexto explica o problema do horário numa frase própria (Error simples); qualquer outra falha
      // é defeito da tela e não aparece como veio
      return [sigla, { erro: e?.name === 'Error' ? e.message : mensagemDe(e), carga: 0 }]
    }
  }))
})

const cargaSemanal = computed(() => Object.values(situacaoDias.value).reduce((s, d) => s + d.carga, 0))
const diasComExpediente = computed(() => (form.value ? DIAS_HORARIO.filter((d) => form.value.dias[d.sigla].length).length : 0))

const erroForm = computed(() => {
  const f = form.value
  if (!f) return null
  if (!f.vigenteDesde) return 'Informe a partir de quando o horário vale.'
  const t = Number(f.toleranciaMinutos)
  if (f.toleranciaMinutos === '' || !Number.isInteger(t) || t < 0 || t > toleranciaMaxima.value) {
    return `A tolerância deve ficar entre 0 e ${toleranciaMaxima.value} minutos.`
  }
  if (!diasComExpediente.value) return 'Informe o horário de pelo menos um dia da semana.'
  const comErro = DIAS_HORARIO.find((d) => situacaoDias.value[d.sigla].erro)
  return comErro ? `${comErro.nome}: ${situacaoDias.value[comErro.sigla].erro}` : null
})

const avisoPassado = computed(() => form.value?.vigenteDesde && form.value.vigenteDesde < props.hoje)

/** "3 dias já registrados foram recalculados." (o número vem do servidor) */
function fraseRecalculo(resultado) {
  const n = resultado?.diasRecalculados ?? 0
  if (!n) return ''
  return n === 1 ? ' 1 dia já registrado foi recalculado.' : ` ${n} dias já registrados foram recalculados.`
}

async function salvar() {
  tentou.value = true
  erro.value = ''
  if (erroForm.value) return
  salvando.value = true
  let resultado
  try {
    const f = form.value
    resultado = await pontoApi.salvarHorario({
      vigenteDesde: f.vigenteDesde,
      toleranciaMinutos: Number(f.toleranciaMinutos),
      dias: Object.fromEntries(DIAS_HORARIO.map((d) => [d.sigla, textoDosPeriodos(f.dias[d.sigla])])),
    }, props.usuario)
  } catch (e) {
    erro.value = mensagemDe(e)
    return
  } finally {
    salvando.value = false
  }
  // salvou: nada depois daqui pode parecer falha da gravação
  editando.value = false
  avisar(`Horário salvo.${fraseRecalculo(resultado)}`)
  emit('salvo', resultado)
}

function pedirExclusao(vigencia) {
  erro.value = ''
  paraExcluir.value = vigencia
}

async function excluir() {
  const vigencia = paraExcluir.value
  if (!vigencia || excluindo.value) return
  excluindo.value = true
  erro.value = ''
  let resultado
  try {
    resultado = await pontoApi.excluirHorario(vigencia.id, props.usuario)
  } catch (e) {
    erro.value = mensagemDe(e)
    return
  } finally {
    excluindo.value = false
  }
  paraExcluir.value = null
  avisar(`Horário excluído.${fraseRecalculo(resultado)}`)
  emit('salvo', resultado)
}

const cargaDoDia = (vigencia, sigla) => cargaDosPeriodos(periodosDe(vigencia, sigla))
const textoDia = (vigencia, sigla) => periodosDe(vigencia, sigla).map((p) => `${p.entrada}–${p.saida}`).join(' e ')
const ordinal = (i) => `${i + 1}º`
</script>

<template>
  <div>
    <!-- O horário que vale hoje -->
    <div v-if="!editando">
      <template v-if="vigente">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <p class="text-[0.95rem] text-texto-2">
            Vale desde <b class="text-texto">{{ desdeOInicio(vigente) ? 'o início' : dataBR(vigente.vigenteDesde) }}</b>
            · <b class="text-texto">{{ duracao(vigente.cargaSemanalSegundos) }}</b> por semana
            · tolerância de <b class="text-texto">{{ vigente.toleranciaMinutos }} min</b> em cada batida
          </p>
          <button v-if="podeEditar" type="button" class="botao-secundario min-h-11" @click="abrir">
            <Icone nome="lapis" tamanho="18" /> Alterar horário
          </button>
        </div>

        <ul class="mt-3 divide-y divide-borda border-t border-borda">
          <li v-for="d in DIAS_HORARIO" :key="d.sigla" class="flex flex-wrap items-baseline gap-x-4 gap-y-0.5 py-2.5">
            <span class="w-24 font-bold" :class="vigente.dias[d.sigla] ? '' : 'text-texto-3'">{{ d.nome }}</span>
            <span class="min-w-0 flex-1" :class="vigente.dias[d.sigla] ? '' : 'text-texto-3'">
              {{ vigente.dias[d.sigla] ? textoDia(vigente, d.sigla) : 'Sem expediente' }}
            </span>
            <span v-if="vigente.dias[d.sigla]" class="text-[0.95rem] text-texto-3">{{ duracao(cargaDoDia(vigente, d.sigla)) }}</span>
          </li>
        </ul>

        <p v-for="f in futuras" :key="f.id" class="mt-3 flex items-start gap-2 rounded-xl border border-primaria-borda bg-primaria-suave px-3.5 py-2.5 text-[0.95rem] text-texto">
          <Icone nome="calendario" class="mt-0.5 text-primaria" />
          <span>A partir de <b>{{ dataBR(f.vigenteDesde) }}</b> passa a valer outro horário ({{ duracao(f.cargaSemanalSegundos) }} por semana).</span>
        </p>
      </template>
      <div v-else class="flex flex-wrap items-center justify-between gap-3">
        <p class="text-[0.95rem] text-texto-3">Nenhum horário de trabalho cadastrado.</p>
        <button v-if="podeEditar" type="button" class="botao-primario min-h-11" @click="abrir">Informar o horário</button>
      </div>

      <!-- Horários anteriores e futuros -->
      <div v-if="vigencias.length > 1" class="mt-4 border-t border-borda">
        <button
          type="button"
          class="flex min-h-12 w-full items-center justify-between gap-3 text-left"
          :aria-expanded="historicoAberto"
          :aria-controls="`${id}-historico`"
          @click="historicoAberto = !historicoAberto"
        >
          <span class="font-bold">Todos os horários <span class="font-semibold text-texto-3">({{ vigencias.length }})</span></span>
          <span class="flex items-center gap-1 text-[0.95rem] font-semibold text-primaria">
            {{ historicoAberto ? 'Esconder' : 'Mostrar' }}
            <Icone nome="abaixo" tamanho="18" class="transition-transform" :class="{ 'rotate-180': historicoAberto }" />
          </span>
        </button>
        <ul v-if="historicoAberto" :id="`${id}-historico`" class="divide-y divide-borda border-t border-borda">
          <li v-for="(v, i) in [...vigencias].reverse()" :key="v.id ?? v.vigenteDesde" class="py-3">
            <div class="flex flex-wrap items-center gap-x-4 gap-y-1.5">
              <span class="font-bold">{{ desdeOInicio(v) ? 'Desde o início' : `Desde ${dataBR(v.vigenteDesde)}` }}</span>
              <span v-if="v === vigente" class="selo selo-positivo">vale hoje</span>
              <span v-else-if="v.vigenteDesde > hoje" class="selo selo-info">ainda vai começar</span>
              <span class="min-w-0 flex-1 text-[0.95rem] text-texto-2">
                {{ duracao(v.cargaSemanalSegundos) }} por semana · tolerância de {{ v.toleranciaMinutos }} min<template v-if="v.criadoPor"> · informado por {{ v.criadoPor }}</template>
              </span>
              <button
                v-if="podeEditar && i < vigencias.length - 1 && paraExcluir?.id !== v.id"
                type="button"
                class="botao-linha"
                @click="pedirExclusao(v)"
              ><Icone nome="lixeira" tamanho="18" /> Excluir</button>
            </div>
            <!-- confirmação antes de excluir -->
            <div v-if="paraExcluir?.id === v.id" class="mt-3 rounded-xl border border-negativo-borda bg-negativo-suave p-4" role="group" :aria-label="`Confirmar a exclusão do horário que vale desde ${dataBR(v.vigenteDesde)}`">
              <p class="font-bold text-negativo">Excluir o horário que vale desde {{ dataBR(v.vigenteDesde) }}?</p>
              <p class="mt-1 text-[0.95rem] text-texto-2">
                Os dias a partir dessa data voltam a ser calculados com o horário anterior a ele.
              </p>
              <div class="mt-3 flex flex-wrap gap-2">
                <button type="button" class="botao-perigo min-h-11" :disabled="excluindo" @click="excluir">
                  {{ excluindo ? 'Excluindo…' : 'Sim, excluir este horário' }}
                </button>
                <button type="button" class="botao-secundario min-h-11" :disabled="excluindo" @click="paraExcluir = null">Cancelar</button>
              </div>
            </div>
          </li>
        </ul>
      </div>
      <p v-if="erro" role="alert" class="aviso-erro mt-3">{{ erro }}</p>
    </div>

    <!-- Alterar o horário -->
    <form v-else-if="form" class="flex flex-col gap-5" novalidate @submit.prevent="salvar">
      <div class="grid gap-4 sm:grid-cols-2">
        <div>
          <label class="rotulo" :for="`${id}-desde`">Vale a partir de</label>
          <input :id="`${id}-desde`" v-model="form.vigenteDesde" type="date" class="campo mt-1.5" />
        </div>
        <div>
          <label class="rotulo" :for="`${id}-tolerancia`">Tolerância em cada batida (minutos)</label>
          <input :id="`${id}-tolerancia`" v-model.number="form.toleranciaMinutos" type="number" min="0" :max="toleranciaMaxima" inputmode="numeric" class="campo mt-1.5" :aria-describedby="`${id}-ajuda`" />
          <p :id="`${id}-ajuda`" class="mt-1 text-sm text-texto-3">Diferenças de até esse tanto em cada batida não contam no saldo do dia.</p>
        </div>
      </div>
      <p v-if="avisoPassado" class="aviso-atencao" role="status">
        Os dias já registrados a partir de <b>{{ dataBR(form.vigenteDesde) }}</b> serão recalculados com este horário.
      </p>

      <fieldset>
        <legend class="rotulo">Horário de cada dia</legend>
        <ul class="mt-1.5 divide-y divide-borda rounded-xl border border-borda">
          <li v-for="d in DIAS_HORARIO" :key="d.sigla" class="flex flex-col gap-2.5 px-3.5 py-3">
            <div class="flex flex-wrap items-baseline justify-between gap-x-3">
              <span class="font-bold">{{ d.nome }}</span>
              <span v-if="tentou && situacaoDias[d.sigla]?.erro" class="text-sm font-semibold text-negativo" role="alert">{{ situacaoDias[d.sigla].erro }}</span>
              <span v-else-if="form.dias[d.sigla].length" class="text-[0.95rem] text-texto-3">{{ duracao(situacaoDias[d.sigla]?.carga ?? 0) }}</span>
              <span v-else class="text-[0.95rem] text-texto-3">Sem expediente</span>
            </div>
            <div v-if="form.dias[d.sigla].length" class="flex flex-wrap gap-2">
              <div v-for="(p, i) in form.dias[d.sigla]" :key="i" class="flex flex-wrap items-center gap-x-2 gap-y-1 rounded-xl bg-superficie-2 px-2.5 py-2">
                <input v-model="p.entrada" type="time" class="campo w-[8.75rem] px-2.5! py-2!" :aria-label="`${d.nome}, começo do ${ordinal(i)} período`" />
                <span class="text-texto-3">às</span>
                <input v-model="p.saida" type="time" class="campo w-[8.75rem] px-2.5! py-2!" :aria-label="`${d.nome}, fim do ${ordinal(i)} período`" />
                <button type="button" class="link min-h-11 px-1.5 text-sm" :aria-label="`Tirar o ${ordinal(i)} período de ${d.nome}`" @click="removerPeriodo(d.sigla, i)">Tirar</button>
              </div>
            </div>
            <div class="flex flex-wrap gap-2">
              <button v-if="form.dias[d.sigla].length < maxPeriodos" type="button" class="botao-linha" @click="adicionarPeriodo(d.sigla)">
                <Icone nome="somar" tamanho="18" /> {{ form.dias[d.sigla].length ? 'Mais um período' : 'Trabalhar neste dia' }}
              </button>
              <button v-if="form.dias[d.sigla].length" type="button" class="botao-linha" @click="semExpediente(d.sigla)">Deixar sem expediente</button>
            </div>
          </li>
        </ul>
      </fieldset>

      <div class="flex flex-wrap items-center justify-between gap-3">
        <button type="button" class="botao-linha" @click="copiarSegunda">Repetir o horário de segunda de terça a sexta</button>
        <p class="text-[0.95rem] text-texto-2" role="status">
          Total: <b class="text-texto">{{ duracao(cargaSemanal) }}</b> por semana, em {{ diasComExpediente }} dia{{ diasComExpediente === 1 ? '' : 's' }}
        </p>
      </div>

      <p v-if="(tentou && erroForm) || erro" role="alert" class="aviso-erro">{{ erro || erroForm }}</p>
      <div class="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" class="botao-secundario min-h-11" :disabled="salvando" @click="editando = false">Cancelar</button>
        <button type="submit" class="botao-primario min-h-11" :disabled="salvando">{{ salvando ? 'Salvando…' : 'Salvar horário' }}</button>
      </div>
    </form>
  </div>
</template>
