<script setup>
import { computed, ref, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import {
  DIAS_HORARIO, cargaDosPeriodos, dataBR, formatarDuracao, periodosDoTexto, textoDosPeriodos,
} from '@/utils/tempo'

/**
 * Horário de trabalho com vigência: cada dia da semana com até 3 períodos (dia sem período = sem expediente)
 * e a tolerância por marcação. Mudar o horário vale "a partir de" uma data: os dias anteriores continuam
 * calculados com o horário que valia para eles.
 */
const props = defineProps({
  /** Vigências (GET /horarios), da mais antiga para a mais recente. */
  vigencias: { type: Array, default: () => [] },
  hoje: { type: String, required: true },
  podeEditar: { type: Boolean, default: false },
  /** Login de outra pessoa (o administrador alterando o horário dela); null = o próprio. */
  usuario: { type: String, default: null },
})
const emit = defineEmits(['salvo'])

const MAX_PERIODOS = 3
const PADRAO = [{ entrada: '08:00', saida: '12:00' }, { entrada: '13:00', saida: '17:48' }]

const vigente = computed(() => {
  let atual = props.vigencias[0] ?? null
  for (const v of props.vigencias) if (v.vigenteDesde <= props.hoje) atual = v
  return atual
})
const futuras = computed(() => props.vigencias.filter((v) => v.vigenteDesde > props.hoje))

const editando = ref(false)
const salvando = ref(false)
const erro = ref('')
const tentou = ref(false)
const confirmandoRemocao = ref(null)
const form = ref(null)

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
  editando.value = true
}

watch(() => props.usuario, () => (editando.value = false))

function adicionarPeriodo(sigla) {
  const lista = form.value.dias[sigla]
  if (lista.length >= MAX_PERIODOS) return
  if (!lista.length) {
    lista.push(...PADRAO.map((p) => ({ ...p })))
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

/** Validação de cada dia (mensagem ou null) e a carga calculada. */
const situacaoDias = computed(() => {
  if (!form.value) return {}
  return Object.fromEntries(DIAS_HORARIO.map(({ sigla }) => {
    const periodos = form.value.dias[sigla]
    if (periodos.some((p) => !p.entrada || !p.saida)) return [sigla, { erro: 'Preencha entrada e saída.', carga: 0 }]
    try {
      const lidos = periodosDoTexto(textoDosPeriodos(periodos))
      return [sigla, { erro: null, carga: cargaDosPeriodos(lidos) }]
    } catch (e) {
      return [sigla, { erro: e.message, carga: 0 }]
    }
  }))
})

const cargaSemanal = computed(() => Object.values(situacaoDias.value).reduce((s, d) => s + d.carga, 0))
const diasComExpediente = computed(() => form.value ? DIAS_HORARIO.filter((d) => form.value.dias[d.sigla].length).length : 0)

const erroForm = computed(() => {
  const f = form.value
  if (!f) return null
  if (!f.vigenteDesde) return 'Informe a partir de quando o horário vale.'
  const t = Number(f.toleranciaMinutos)
  if (!Number.isInteger(t) || t < 0 || t > 60) return 'A tolerância deve ficar entre 0 e 60 minutos.'
  if (!diasComExpediente.value) return 'Informe o horário de pelo menos um dia da semana.'
  const comErro = DIAS_HORARIO.find((d) => situacaoDias.value[d.sigla].erro)
  return comErro ? `${comErro.nome}: ${situacaoDias.value[comErro.sigla].erro}` : null
})

const avisoPassado = computed(() => form.value?.vigenteDesde && form.value.vigenteDesde < props.hoje)

async function salvar() {
  tentou.value = true
  erro.value = ''
  if (erroForm.value) return
  salvando.value = true
  try {
    const f = form.value
    const resultado = await pontoApi.salvarHorario({
      vigenteDesde: f.vigenteDesde,
      toleranciaMinutos: Number(f.toleranciaMinutos),
      dias: Object.fromEntries(DIAS_HORARIO.map((d) => [d.sigla, textoDosPeriodos(f.dias[d.sigla])])),
    }, props.usuario)
    editando.value = false
    emit('salvo', resultado)
  } catch (e) {
    erro.value = e.message
  } finally {
    salvando.value = false
  }
}

async function remover(vigencia) {
  if (confirmandoRemocao.value !== vigencia.id) {
    confirmandoRemocao.value = vigencia.id
    setTimeout(() => (confirmandoRemocao.value = null), 4000)
    return
  }
  confirmandoRemocao.value = null
  erro.value = ''
  try {
    emit('salvo', await pontoApi.excluirHorario(vigencia.id, props.usuario))
  } catch (e) {
    erro.value = e.message
  }
}

const cargaDoDia = (vigencia, sigla) => cargaDosPeriodos(periodosDe(vigencia, sigla))
const textoDia = (vigencia, sigla) => periodosDe(vigencia, sigla).map((p) => `${p.entrada}–${p.saida}`).join(' · ')
</script>

<template>
  <div>
    <!-- Horário em vigor -->
    <div v-if="vigente && !editando">
      <div class="flex flex-wrap items-baseline justify-between gap-2">
        <p class="text-sm text-tinta-suave">
          Em vigor desde <b class="text-tinta">{{ vigente.vigenteDesde <= '2000-01-01' ? 'sempre' : dataBR(vigente.vigenteDesde) }}</b>
          · tolerância de <b class="carimbo text-tinta">{{ vigente.toleranciaMinutos }} min</b> por marcação
          · <b class="carimbo text-tinta">{{ formatarDuracao(vigente.cargaSemanalSegundos, { curto: true }) }}</b> por semana
        </p>
        <button v-if="podeEditar" type="button" class="botao-secundario py-1.5! text-xs" @click="abrir">Alterar horário</button>
      </div>
      <table class="mt-3 w-full text-sm">
        <tbody>
          <tr v-for="d in DIAS_HORARIO" :key="d.sigla" class="border-b border-linha/60 last:border-0">
            <th scope="row" class="w-24 py-1.5 text-left font-semibold">{{ d.nome }}</th>
            <td class="carimbo py-1.5" :class="vigente.dias[d.sigla] ? '' : 'text-tinta-apagada'">
              {{ vigente.dias[d.sigla] ? textoDia(vigente, d.sigla) : 'sem expediente' }}
            </td>
            <td class="carimbo w-16 py-1.5 text-right text-tinta-suave">
              {{ vigente.dias[d.sigla] ? formatarDuracao(cargaDoDia(vigente, d.sigla), { curto: true }) : '' }}
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="futuras.length" class="mt-3 rounded-[3px] border border-dashed border-tinta/40 px-3 py-2 text-sm">
        <p v-for="f in futuras" :key="f.id">
          A partir de <b>{{ dataBR(f.vigenteDesde) }}</b>: novo horário
          ({{ formatarDuracao(f.cargaSemanalSegundos, { curto: true }) }} por semana).
        </p>
      </div>

      <details v-if="vigencias.length > 1" class="mt-4">
        <summary class="cursor-pointer text-sm font-semibold text-tinta-suave hover:text-tinta">
          Histórico de horários ({{ vigencias.length }})
        </summary>
        <ul class="mt-2 divide-y divide-linha/70 text-sm">
          <li v-for="(v, i) in [...vigencias].reverse()" :key="v.id ?? v.vigenteDesde" class="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
            <span class="carimbo w-28 font-semibold">{{ v.vigenteDesde <= '2000-01-01' ? 'início' : dataBR(v.vigenteDesde) }}</span>
            <span class="carimbo text-tinta-suave">{{ formatarDuracao(v.cargaSemanalSegundos, { curto: true }) }}/semana · ±{{ v.toleranciaMinutos }} min</span>
            <span v-if="v.criadoPor" class="text-xs text-tinta-apagada">por {{ v.criadoPor }}</span>
            <button
              v-if="podeEditar && i < vigencias.length - 1"
              type="button"
              class="ml-auto rounded-[3px] px-2 py-1 text-xs font-semibold transition"
              :class="confirmandoRemocao === v.id ? 'bg-carimbo text-cartao' : 'text-tinta-suave hover:bg-papel-escuro hover:text-carimbo'"
              @click="remover(v)"
            >{{ confirmandoRemocao === v.id ? 'Confirmar remoção?' : 'Remover' }}</button>
          </li>
        </ul>
      </details>
    </div>

    <!-- Edição -->
    <form v-if="editando && form" class="space-y-5" novalidate @submit.prevent="salvar">
      <div class="grid gap-4 sm:grid-cols-[1fr_1fr_1.4fr]">
        <label class="flex flex-col">
          <span class="rotulo">Vale a partir de</span>
          <input v-model="form.vigenteDesde" type="date" class="campo mt-1.5" />
        </label>
        <label class="flex flex-col">
          <span class="rotulo">Tolerância (min)</span>
          <input v-model.number="form.toleranciaMinutos" type="number" min="0" max="60" class="campo mt-1.5" />
        </label>
        <p class="self-end text-sm text-tinta-suave">
          <b class="carimbo text-tinta">{{ formatarDuracao(cargaSemanal, { curto: true }) }}</b> por semana
          em {{ diasComExpediente }} dia(s)
        </p>
      </div>
      <p v-if="avisoPassado" class="-mt-2 rounded-[3px] border border-carimbo/30 bg-carimbo/5 px-3 py-2 text-sm">
        Os dias já registrados a partir de <b>{{ dataBR(form.vigenteDesde) }}</b> serão recalculados com este horário.
      </p>

      <div class="divide-y divide-linha/70 rounded-[3px] border border-linha">
        <div v-for="d in DIAS_HORARIO" :key="d.sigla" class="grid gap-2 px-3 py-2.5 sm:grid-cols-[6rem_1fr_auto] sm:items-center">
          <span class="font-semibold">{{ d.nome }}</span>
          <div class="flex flex-wrap items-center gap-2">
            <template v-if="form.dias[d.sigla].length">
              <span v-for="(p, i) in form.dias[d.sigla]" :key="i" class="flex items-center gap-1 rounded-[3px] border border-linha bg-papel/60 px-1.5 py-1">
                <input v-model="p.entrada" type="time" class="carimbo w-[5.5rem] bg-transparent text-sm outline-none" :aria-label="`${d.nome}, entrada ${i + 1}`" />
                <span class="text-tinta-apagada">–</span>
                <input v-model="p.saida" type="time" class="carimbo w-[5.5rem] bg-transparent text-sm outline-none" :aria-label="`${d.nome}, saída ${i + 1}`" />
                <button type="button" class="px-1 text-tinta-apagada hover:text-carimbo" :aria-label="`Remover período ${i + 1} de ${d.nome}`" @click="removerPeriodo(d.sigla, i)">×</button>
              </span>
            </template>
            <span v-else class="text-sm text-tinta-apagada">sem expediente</span>
            <button
              v-if="form.dias[d.sigla].length < MAX_PERIODOS"
              type="button"
              class="text-xs font-semibold text-tinta-suave underline underline-offset-4 hover:text-tinta"
              @click="adicionarPeriodo(d.sigla)"
            >{{ form.dias[d.sigla].length ? '+ período' : '+ trabalhar neste dia' }}</button>
            <button
              v-if="form.dias[d.sigla].length"
              type="button"
              class="text-xs text-tinta-apagada underline underline-offset-4 hover:text-carimbo"
              @click="semExpediente(d.sigla)"
            >folga</button>
          </div>
          <span class="carimbo text-right text-sm" :class="tentou && situacaoDias[d.sigla]?.erro ? 'text-carimbo' : 'text-tinta-suave'">
            {{ tentou && situacaoDias[d.sigla]?.erro ? situacaoDias[d.sigla].erro
              : form.dias[d.sigla].length ? formatarDuracao(situacaoDias[d.sigla]?.carga ?? 0, { curto: true }) : '' }}
          </span>
        </div>
      </div>
      <button type="button" class="text-sm text-tinta-suave underline underline-offset-4 hover:text-tinta" @click="copiarSegunda">
        Copiar o horário de segunda para terça a sexta
      </button>

      <p v-if="(tentou && erroForm) || erro" role="alert" class="rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">
        {{ erro || erroForm }}
      </p>
      <div class="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" class="botao-secundario" :disabled="salvando" @click="editando = false">Cancelar</button>
        <button type="submit" class="botao-primario" :disabled="salvando">{{ salvando ? 'Salvando…' : 'Salvar horário' }}</button>
      </div>
    </form>
    <p v-if="erro && !editando" role="alert" class="mt-3 text-sm text-carimbo">{{ erro }}</p>
  </div>
</template>
