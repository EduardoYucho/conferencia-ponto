<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { mensagemDe } from '@/utils/erros'
import { dataBR, diaMes } from '@/utils/horas'
import { diaSemanaCurto } from '@/utils/tempo'
import Icone from '@/components/Icone.vue'

/**
 * "Marcar folga, férias ou feriado": marca um dia (ou vários) em que a pessoa não trabalha — folga, férias,
 * atestado, licença, outra justificativa ou feriado. O dia marcado não conta como falta. Se o dia já tem uma
 * marcação, a janela mostra qual é e deixa removê-la (com um passo de confirmação).
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** yyyy-MM-dd */
  data: { type: String, default: null },
  /**
   * Marcação que o dia já tem, se houver: { tipo: 'feriado', rotulo, feriado: { data, descricao, abrangencia } }
   * ou { tipo: 'ausencia', rotulo, descricao, ausencia: { id, dataInicio, dataFim } }.
   */
  marcador: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue', 'salvo', 'compensar'])

const store = usePontoStore()
const auth = useAuthStore()

/** `feito`: como o aviso de resultado começa ("Folga marcada em 25/09..."); `feitos`: quando são vários dias. */
const TODOS_TIPOS = [
  { valor: 'FOLGA', rotulo: 'Folga', feito: 'Folga marcada' },
  { valor: 'FERIAS', rotulo: 'Férias', feito: 'Férias marcadas' },
  { valor: 'ATESTADO', rotulo: 'Atestado', feito: 'Atestado marcado' },
  { valor: 'LICENCA', rotulo: 'Licença', feito: 'Licença marcada' },
  { valor: 'ABONO', rotulo: 'Outra justificativa', feito: 'Dia justificado', feitos: 'Dias justificados' },
  { valor: 'FERIADO', rotulo: 'Feriado', feito: 'Feriado marcado' },
]
/** Feriado vale para todas as pessoas: só o administrador marca e remove (o servidor confere). */
const TIPOS = computed(() => TODOS_TIPOS.filter((t) => t.valor !== 'FERIADO' || auth.ehAdmin))
const ABRANGENCIAS = [
  { valor: 'MUNICIPAL', rotulo: 'Da cidade (municipal)' },
  { valor: 'ESTADUAL', rotulo: 'Do estado (estadual)' },
  { valor: 'NACIONAL', rotulo: 'Do país (nacional)' },
  { valor: 'EMPRESA', rotulo: 'Só da empresa (ponto facultativo)' },
]
const EXEMPLO = {
  FERIADO: 'Ex.: Corpus Christi',
  FOLGA: 'Ex.: folga de aniversário',
  FERIAS: 'Ex.: férias de 2026',
  ATESTADO: 'Se quiser, escreva uma observação',
  LICENCA: 'Se quiser, escreva uma observação',
  ABONO: 'Ex.: doação de sangue, declaração de comparecimento',
}

const tipo = ref('FOLGA')
const dataInicio = ref('')
const dataFim = ref('')
const descricao = ref('')
const abrangencia = ref('MUNICIPAL')
const tentou = ref(false)
const erroServidor = ref('')
/** Passo de confirmação antes de remover a marcação que o dia já tem. */
const confirmandoRemocao = ref(false)
const botaoFechar = ref(null)
const botaoRemover = ref(null)
const botaoConfirmar = ref(null)
let quemAbriu = null

watch(
  () => props.modelValue,
  async (aberto) => {
    if (!aberto) {
      quemAbriu?.focus?.()
      quemAbriu = null
      return
    }
    quemAbriu = document.activeElement
    tipo.value = 'FOLGA'
    dataInicio.value = props.data ?? store.hoje
    dataFim.value = props.data ?? store.hoje
    descricao.value = ''
    abrangencia.value = 'MUNICIPAL'
    tentou.value = false
    erroServidor.value = ''
    confirmandoRemocao.value = false
    await nextTick()
    if (props.marcador) (botaoRemover.value ?? botaoFechar.value)?.focus()
    else document.getElementById(`dia-especial-tipo-${tipo.value}`)?.focus()
  },
  { immediate: true },
)

function fechar() {
  if (!store.salvando) emit('update:modelValue', false)
}
function aoTeclar(evento) {
  if (evento.key !== 'Escape' || !props.modelValue) return
  // no passo de confirmação, Esc volta um passo (não fecha a janela inteira)
  if (confirmandoRemocao.value) cancelarRemocao()
  else fechar()
}
window.addEventListener('keydown', aoTeclar)
onBeforeUnmount(() => window.removeEventListener('keydown', aoTeclar))

// ------------------------------------------------------------------ nova marcação
const ehFeriado = computed(() => tipo.value === 'FERIADO')
const descricaoObrigatoria = computed(() => tipo.value === 'FERIADO' || tipo.value === 'ABONO')
const rotuloDescricao = computed(() => (ehFeriado.value
  ? 'Nome do feriado'
  : tipo.value === 'ABONO' ? 'Qual é a justificativa?' : 'Observação (opcional)'))

function aoMudarInicio() {
  if (!dataFim.value || dataFim.value < dataInicio.value) dataFim.value = dataInicio.value
}

/** Ajuda de digitação: o que falta preencher (quem aceita a marcação é o servidor). */
const erro = computed(() => {
  if (!dataInicio.value) return 'Informe a data.'
  if (!ehFeriado.value && dataFim.value && dataFim.value < dataInicio.value) return 'O último dia precisa ser igual ou depois do primeiro.'
  if (descricaoObrigatoria.value && !descricao.value.trim()) {
    return ehFeriado.value ? 'Escreva o nome do feriado.' : 'Escreva a justificativa.'
  }
  return null
})

/** Quantos dias do calendário a marcação cobre (para o texto do botão). */
const dias = computed(() => {
  if (ehFeriado.value || !dataInicio.value || !dataFim.value || dataFim.value < dataInicio.value) return 1
  return Math.round((new Date(dataFim.value) - new Date(dataInicio.value)) / 86_400_000) + 1
})

/** "25/09" no ano corrente; em outro ano, a data inteira. */
const dataNoAviso = (iso) => (iso?.slice(0, 4) === store.hoje.slice(0, 4) ? diaMes(iso) : dataBR(iso))
const rotuloDoDia = (iso) => `${diaSemanaCurto(iso)}, ${diaMes(iso)}`

async function enviar() {
  tentou.value = true
  erroServidor.value = ''
  if (erro.value) return
  try {
    await store.marcarDias({
      tipo: tipo.value,
      dataInicio: dataInicio.value,
      dataFim: ehFeriado.value ? dataInicio.value : dataFim.value,
      descricao: descricao.value.trim(),
      abrangencia: ehFeriado.value ? abrangencia.value : undefined,
    })
  } catch (e) {
    erroServidor.value = mensagemDe(e)
    return
  }
  // marcou: a janela fecha antes de avisar a tela (nada depois daqui pode parecer falha da gravação)
  emit('update:modelValue', false)
  const frase = TODOS_TIPOS.find((t) => t.valor === tipo.value)
  emit('salvo', dias.value === 1
    ? `${frase.feito} em ${dataNoAviso(dataInicio.value)}: o dia não conta como falta.`
    : `${frase.feitos ?? frase.feito} de ${dataNoAviso(dataInicio.value)} a ${dataNoAviso(dataFim.value)}: os ${dias.value} dias não contam como falta.`)
}

/** "Folga para usar horas do banco": em vez de marcar o dia, abre a janela de usar horas do banco. */
function compensar() {
  emit('compensar', dataInicio.value || props.data)
  emit('update:modelValue', false)
}

// ------------------------------------------------------------------ marcação que o dia já tem
const marcadoFeriado = computed(() => props.marcador?.tipo === 'feriado')
/** Nome da marcação em minúsculas, para as frases: "folga", "férias", "feriado". */
const nomeMarcacao = computed(() => (marcadoFeriado.value ? 'feriado' : (props.marcador?.rotulo ?? 'marcação').toLowerCase()))
const feminina = computed(() => /^(folga|licença|férias|marcação)/.test(nomeMarcacao.value))
const plural = computed(() => /^férias/.test(nomeMarcacao.value))
const artigo = computed(() => (feminina.value ? 'a' : 'o') + (plural.value ? 's' : ''))
/** Só o administrador remove feriado (o servidor confere). */
const podeRemover = computed(() => !!props.marcador && (!marcadoFeriado.value || auth.ehAdmin))
const variosDias = computed(() => {
  const a = props.marcador?.ausencia
  return !!a && a.dataInicio !== a.dataFim
})
/** "25/09/2026" ou "21/09/2026 a 25/09/2026". */
const periodoAtual = computed(() => {
  if (marcadoFeriado.value) return dataBR(props.marcador.feriado?.data ?? props.data)
  const a = props.marcador?.ausencia
  if (!a) return props.data ? dataBR(props.data) : ''
  return variosDias.value ? `${dataBR(a.dataInicio)} a ${dataBR(a.dataFim)}` : dataBR(a.dataInicio)
})
/** O nome do feriado ("Corpus Christi") ou a observação da folga, quando há. */
const nomeProprio = computed(() => {
  const m = props.marcador
  if (!m) return ''
  const texto = marcadoFeriado.value ? (m.feriado?.descricao ?? m.rotulo) : m.descricao
  return texto && texto.toLowerCase() !== 'feriado' ? texto : ''
})
const abrangenciaAtual = computed(() => {
  const feriado = props.marcador?.feriado
  if (!feriado) return null
  return feriado.abrangenciaRotulo ?? ABRANGENCIAS.find((a) => a.valor === feriado.abrangencia)?.rotulo ?? null
})

async function pedirRemocao() {
  erroServidor.value = ''
  confirmandoRemocao.value = true
  await nextTick()
  botaoConfirmar.value?.focus()
}
async function cancelarRemocao() {
  confirmandoRemocao.value = false
  await nextTick()
  botaoRemover.value?.focus()
}

async function remover() {
  erroServidor.value = ''
  // a marcação some da tela assim que os dados recarregam: o texto do aviso é montado antes
  const marcador = props.marcador
  const removido = `removid${feminina.value ? 'a' : 'o'}${plural.value ? 's' : ''}`
  const nome = nomeMarcacao.value.charAt(0).toUpperCase() + nomeMarcacao.value.slice(1)
  const texto = marcadoFeriado.value
    ? `Feriado de ${dataNoAviso(marcador.feriado?.data ?? props.data)} removido: o dia voltou a contar normalmente.`
    : variosDias.value
      ? `${nome} de ${dataNoAviso(marcador.ausencia.dataInicio)} a ${dataNoAviso(marcador.ausencia.dataFim)} ${removido}: os dias voltaram a contar normalmente.`
      : `${nome} de ${dataNoAviso(marcador.ausencia?.dataInicio ?? props.data)} ${removido}: o dia voltou a contar normalmente.`
  try {
    await store.removerMarcacao(marcador)
  } catch (e) {
    erroServidor.value = mensagemDe(e)
    return
  }
  emit('update:modelValue', false)
  emit('salvo', texto)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="janela-fundo" @mousedown.self="fechar">
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="titulo-dia-especial"
        aria-describedby="explicacao-dia-especial"
        class="janela"
        novalidate
        @submit.prevent="marcador ? undefined : enviar()"
      >
        <header class="flex items-start justify-between gap-4 border-b border-borda px-5 pt-5 pb-4">
          <div>
            <h2 id="titulo-dia-especial" class="text-xl font-extrabold tracking-tight">
              <template v-if="!marcador">Marcar folga, férias ou feriado</template>
              <template v-else-if="confirmandoRemocao">Remover {{ artigo }} {{ nomeMarcacao }}?</template>
              <template v-else>Este dia tem {{ marcadoFeriado ? 'um feriado' : 'uma marcação' }}</template>
            </h2>
            <p id="explicacao-dia-especial" class="mt-1 text-[0.95rem] text-texto-3">
              <template v-if="!marcador">O dia marcado não conta como falta. Se você trabalhar nele, as horas contam a favor.</template>
              <template v-else>
                <b v-if="data" class="text-texto-2">{{ rotuloDoDia(data) }}.</b>
                {{ confirmandoRemocao ? 'Confira o que vai acontecer antes de remover.' : 'Dia marcado não conta como falta.' }}
              </template>
            </p>
          </div>
          <button
            type="button"
            class="-mt-1 -mr-2 grid size-11 shrink-0 place-items-center rounded-xl text-texto-3 hover:bg-neutro hover:text-texto"
            aria-label="Fechar"
            @click="fechar"
          >
            <Icone nome="fechar" />
          </button>
        </header>

        <!-- O dia já tem marcação: ver e, se puder, remover -->
        <template v-if="marcador">
          <div class="flex flex-col gap-4 px-5 py-5">
            <div class="rounded-xl bg-superficie-2 px-4 py-3">
              <p class="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                <span class="selo" :class="marcadoFeriado ? 'selo-neutro' : 'selo-info'">{{ marcadoFeriado ? 'Feriado' : marcador.rotulo }}</span>
                <span v-if="nomeProprio" class="text-base font-bold">{{ nomeProprio }}</span>
              </p>
              <dl class="mt-2 flex flex-col gap-1 text-[0.95rem]">
                <div class="flex flex-wrap gap-x-2">
                  <dt class="text-texto-3">{{ variosDias ? 'Período:' : 'Dia:' }}</dt>
                  <dd class="font-semibold">{{ periodoAtual }}</dd>
                </div>
                <div v-if="abrangenciaAtual" class="flex flex-wrap gap-x-2">
                  <dt class="text-texto-3">Tipo de feriado:</dt>
                  <dd class="font-semibold">{{ abrangenciaAtual }}</dd>
                </div>
              </dl>
            </div>

            <!-- Passo de confirmação -->
            <div v-if="confirmandoRemocao" class="aviso-atencao flex items-start gap-2.5" role="alert">
              <Icone nome="alerta" class="mt-0.5" />
              <p class="text-[0.95rem]">
                <template v-if="marcadoFeriado">
                  O feriado deixa de valer <b>para todas as pessoas</b>. O dia volta a ser um dia comum: quem não trabalhou
                  nele fica com o dia para corrigir.
                </template>
                <template v-else-if="variosDias">
                  A marcação inteira, de <b>{{ periodoAtual }}</b>, é removida. Os dias voltam a contar normalmente: os
                  dias de trabalho sem batida ficam para corrigir.
                </template>
                <template v-else>
                  O dia <b>{{ periodoAtual }}</b> volta a contar normalmente: se for dia de trabalho e não tiver batida,
                  fica para corrigir.
                </template>
              </p>
            </div>
            <p v-else-if="!podeRemover" class="text-[0.95rem] text-texto-2">
              Feriado vale para todas as pessoas: só o administrador pode remover.
            </p>
            <p v-else class="text-[0.95rem] text-texto-2">
              Para trocar por outra marcação, remova esta primeiro<template v-if="variosDias"> (o período inteiro é removido)</template>.
            </p>

            <p v-if="erroServidor" class="aviso-erro" role="alert">{{ erroServidor }}</p>
          </div>

          <footer class="sticky bottom-0 flex flex-wrap justify-end gap-2 border-t border-borda bg-superficie px-5 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
            <template v-if="confirmandoRemocao">
              <button type="button" class="botao-secundario min-h-11" :disabled="store.salvando" @click="cancelarRemocao">Cancelar</button>
              <button ref="botaoConfirmar" type="button" class="botao-perigo min-h-11 grow sm:grow-0" :disabled="store.salvando" @click="remover">
                <Icone nome="lixeira" tamanho="18" /> {{ store.salvando ? 'Removendo…' : `Sim, remover ${artigo} ${nomeMarcacao}` }}
              </button>
            </template>
            <template v-else>
              <button ref="botaoFechar" type="button" class="botao-secundario min-h-11" @click="fechar">Fechar</button>
              <button v-if="podeRemover" ref="botaoRemover" type="button" class="botao-secundario min-h-11 grow text-negativo! sm:grow-0" @click="pedirRemocao">
                <Icone nome="lixeira" tamanho="18" /> Remover {{ artigo }} {{ nomeMarcacao }}
              </button>
            </template>
          </footer>
        </template>

        <!-- Nova marcação -->
        <template v-else>
          <div class="flex flex-col gap-5 px-5 py-5">
            <fieldset>
              <legend class="rotulo">O que foi?</legend>
              <div class="mt-1.5 grid grid-cols-2 gap-2 sm:grid-cols-3">
                <label
                  v-for="t in TIPOS"
                  :key="t.valor"
                  class="flex min-h-11 cursor-pointer items-center justify-center rounded-xl border px-3 py-2 text-center text-[0.95rem] transition-colors has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-primaria"
                  :class="tipo === t.valor ? 'border-inverso bg-inverso font-bold text-sobre-inverso' : 'border-borda-forte bg-superficie font-semibold text-texto hover:bg-neutro'"
                >
                  <input :id="`dia-especial-tipo-${t.valor}`" v-model="tipo" type="radio" name="dia-especial-tipo" :value="t.valor" class="sr-only" />
                  {{ t.rotulo }}
                </label>
              </div>
              <p v-if="ehFeriado" class="mt-1.5 text-sm text-texto-3">O feriado vale para todas as pessoas.</p>
            </fieldset>

            <div class="grid gap-x-4 gap-y-5 sm:grid-cols-2">
              <div>
                <label for="dia-especial-inicio" class="rotulo">{{ ehFeriado ? 'Dia do feriado' : 'Primeiro dia' }}</label>
                <input id="dia-especial-inicio" v-model="dataInicio" type="date" class="campo mt-1.5" @change="aoMudarInicio" />
                <p v-if="dataInicio" class="mt-1.5 text-sm text-texto-3">{{ rotuloDoDia(dataInicio) }}</p>
              </div>
              <div v-if="!ehFeriado">
                <label for="dia-especial-fim" class="rotulo">Último dia</label>
                <input id="dia-especial-fim" v-model="dataFim" type="date" :min="dataInicio || undefined" class="campo mt-1.5" />
                <p v-if="dataFim" class="mt-1.5 text-sm text-texto-3">
                  {{ rotuloDoDia(dataFim) }}<template v-if="dias > 1"> · {{ dias }} dias</template><template v-else> · um dia só</template>
                </p>
              </div>
              <div v-else>
                <label for="dia-especial-abrangencia" class="rotulo">Tipo de feriado</label>
                <select id="dia-especial-abrangencia" v-model="abrangencia" class="campo mt-1.5">
                  <option v-for="a in ABRANGENCIAS" :key="a.valor" :value="a.valor">{{ a.rotulo }}</option>
                </select>
              </div>
            </div>

            <div>
              <label for="dia-especial-descricao" class="rotulo">{{ rotuloDescricao }}</label>
              <input
                id="dia-especial-descricao"
                v-model="descricao"
                type="text"
                :maxlength="ehFeriado ? 120 : 200"
                class="campo mt-1.5"
                :placeholder="EXEMPLO[tipo]"
                :aria-invalid="tentou && descricaoObrigatoria && !descricao.trim()"
              />
            </div>

            <p v-if="tentou && erro" class="aviso-erro" role="alert">{{ erro }}</p>
            <p v-if="erroServidor" class="aviso-erro" role="alert">{{ erroServidor }}</p>

            <div v-if="!ehFeriado" class="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 rounded-xl bg-superficie-2 px-4 py-3">
              <p class="min-w-48 flex-1 text-[0.95rem] text-texto-2">
                É uma folga para <b>usar horas do banco</b>? Então não marque o dia: lance as horas usadas.
              </p>
              <button type="button" class="botao-linha" @click="compensar"><Icone nome="subtrair" tamanho="18" /> Usar horas do banco</button>
            </div>
          </div>

          <footer class="sticky bottom-0 flex flex-wrap justify-end gap-2 border-t border-borda bg-superficie px-5 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
            <button type="button" class="botao-secundario min-h-11" :disabled="store.salvando" @click="fechar">Cancelar</button>
            <button type="submit" class="botao-primario min-h-11 grow sm:grow-0" :disabled="store.salvando || (tentou && !!erro)">
              {{ store.salvando ? 'Marcando…' : dias > 1 ? `Marcar ${dias} dias` : 'Marcar o dia' }}
            </button>
          </footer>
        </template>
      </form>
    </div>
  </Teleport>
</template>
