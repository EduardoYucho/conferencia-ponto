<script setup>
import { computed, onMounted, ref } from 'vue'
import { acessosApi } from '@/api/acessosApi'
import { useAuthStore } from '@/stores/auth'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { efetivas } from '@/utils/permissoes'
import { mensagemDe } from '@/utils/erros'

/**
 * O acesso de uma pessoa (tela Usuários): os perfis dela e os ajustes só dela, permissão por permissão ("pelo
 * perfil", "liberar" ou "retirar"). O resultado aparece na hora, antes de salvar; quem garante é o servidor, que
 * também não deixa ninguém tirar de si mesmo "Perfis e acessos" nem o sistema ficar sem quem cuide dos acessos.
 */
const props = defineProps({
  /** { id, nome, login, ativo } */
  usuario: { type: Object, required: true },
  /** Os grupos de permissões: [{ nome, permissoes: [{ codigo, nome, descricao, requer }] }]. */
  grupos: { type: Array, required: true },
  /** Os perfis que existem: [{ id, nome, descricao, todas, permissoes }]. */
  perfis: { type: Array, required: true },
})
const emit = defineEmits(['salvo', 'cancelar'])

const auth = useAuthStore()

const carregando = ref(false)
const erroCarga = ref(null)
const erro = ref('')
const salvando = ref(false)
/** Os ids dos perfis marcados. */
const escolhidos = ref(new Set())
/** código → true (liberada) ou false (retirada); sem a chave = pelo perfil. */
const ajustes = ref({})
const historico = ref([])

async function carregar() {
  carregando.value = true
  erroCarga.value = null
  try {
    const [acesso, mudancas] = await Promise.all([
      acessosApi.acessoDe(props.usuario.id),
      acessosApi.historico(props.usuario.id, 8).catch(() => []),
    ])
    escolhidos.value = new Set(acesso.perfis)
    ajustes.value = Object.fromEntries(acesso.ajustes.map((a) => [a.permissao, a.liberada]))
    historico.value = mudancas
  } catch (e) {
    erroCarga.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)

const catalogo = computed(() => props.grupos.flatMap((g) => g.permissoes))
const nomeDe = (codigo) => catalogo.value.find((p) => p.codigo === codigo)?.nome ?? codigo
const perfisEscolhidos = computed(() => props.perfis.filter((p) => escolhidos.value.has(p.id)))
const valem = computed(() => efetivas(catalogo.value, perfisEscolhidos.value, ajustes.value))

function alternarPerfil(id) {
  const novos = new Set(escolhidos.value)
  if (novos.has(id)) novos.delete(id)
  else novos.add(id)
  escolhidos.value = novos
}

function modo(codigo) {
  return codigo in ajustes.value ? (ajustes.value[codigo] ? 'liberar' : 'retirar') : 'perfil'
}
function definirModo(codigo, novo) {
  const proximos = { ...ajustes.value }
  if (novo === 'perfil') delete proximos[codigo]
  else proximos[codigo] = novo === 'liberar'
  ajustes.value = proximos
}

/** De onde vem (ou por que não vale) a permissão, com o que está na tela agora. */
function origem(p) {
  const dePerfis = perfisEscolhidos.value.filter((perfil) => perfil.todas || perfil.permissoes.includes(p.codigo)).map((perfil) => perfil.nome)
  const ajuste = ajustes.value[p.codigo]
  let texto
  if (ajuste === false) texto = 'Retirada só desta pessoa' + (dePerfis.length ? ` (o perfil ${dePerfis.join(', ')} daria)` : '')
  else if (ajuste === true) texto = 'Liberada só para esta pessoa'
  else if (dePerfis.length) texto = `Pelo perfil ${dePerfis.join(', ')}`
  else texto = 'Nenhum perfil dá'
  const somada = ajuste === true || (ajuste !== false && dePerfis.length > 0)
  if (somada && !valem.value.has(p.codigo) && p.requer) texto += ` · não vale sem "${nomeDe(p.requer)}"`
  return texto
}

const MODOS = [
  { valor: 'perfil', rotulo: 'Pelo perfil' },
  { valor: 'liberar', rotulo: 'Liberar' },
  { valor: 'retirar', rotulo: 'Retirar' },
]

const ehVoce = computed(() => props.usuario.id === auth.usuario?.id)
const quantas = computed(() => valem.value.size)

async function salvar() {
  erro.value = ''
  salvando.value = true
  try {
    const lista = Object.entries(ajustes.value).map(([permissao, liberada]) => ({ permissao, liberada }))
    const salvo = await acessosApi.definirAcesso(props.usuario.id, [...escolhidos.value], lista)
    if (ehVoce.value) auth.atualizarUsuario().catch(() => {})
    emit('salvo', salvo)
  } catch (e) {
    erro.value = mensagemDe(e)
  } finally {
    salvando.value = false
  }
}

function quando(iso) {
  const d = new Date(iso)
  return `${d.toLocaleDateString('pt-BR')} ${d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}`
}
</script>

<template>
  <section class="rounded-[3px] border border-tinta/25 bg-cartao px-4 py-4" :aria-label="`Acesso de ${usuario.nome}`">
    <EstadoDaTela :carregando="carregando" :erro="erroCarga" carregando-texto="Carregando o acesso…" @tentar="carregar">
      <div class="flex flex-wrap items-baseline justify-between gap-2">
        <h3 class="font-sans text-base font-bold">Acesso de {{ usuario.nome }}<span v-if="ehVoce" class="font-normal text-tinta-suave"> (você)</span></h3>
        <p class="text-xs text-tinta-suave">{{ quantas }} permissão(ões) valendo</p>
      </div>

      <fieldset class="mt-3">
        <legend class="rotulo">Perfis (a pessoa pode a soma do que eles liberam)</legend>
        <div class="mt-1.5 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          <label
            v-for="p in perfis"
            :key="p.id"
            class="flex cursor-pointer flex-col rounded-[3px] border px-3 py-2 text-sm transition"
            :class="escolhidos.has(p.id) ? 'border-tinta bg-papel-escuro/60' : 'border-linha hover:border-tinta'"
          >
            <span class="flex items-center gap-2 font-semibold">
              <input type="checkbox" class="accent-tinta" :checked="escolhidos.has(p.id)" @change="alternarPerfil(p.id)" />
              {{ p.nome }}
              <span v-if="p.todas" class="rounded-[2px] bg-tinta/10 px-1.5 text-[0.68rem] font-bold uppercase">tudo</span>
            </span>
            <span v-if="p.descricao" class="mt-0.5 text-xs text-tinta-suave">{{ p.descricao }}</span>
          </label>
        </div>
        <p v-if="!escolhidos.size" class="mt-1.5 text-xs text-tinta-suave">Sem perfil, a pessoa só tem o que for liberado nos ajustes abaixo.</p>
      </fieldset>

      <div class="mt-4">
        <p class="rotulo">Ajustes só desta pessoa</p>
        <p class="mt-0.5 text-xs text-tinta-suave">"Pelo perfil" segue os perfis marcados. "Liberar" dá a mais; "Retirar" tira mesmo que um perfil dê.</p>
        <div v-for="g in grupos" :key="g.nome" class="mt-3">
          <p class="text-xs font-bold tracking-wide text-tinta-suave uppercase">{{ g.nome }}</p>
          <ul class="mt-1 divide-y divide-linha/70 border-y border-linha/70">
            <li v-for="p in g.permissoes" :key="p.codigo" class="grid gap-2 py-2 sm:grid-cols-[1fr_auto] sm:items-center">
              <div class="min-w-0">
                <p class="flex flex-wrap items-center gap-2 text-sm font-semibold">
                  <span
                    class="inline-flex size-4 shrink-0 items-center justify-center rounded-full text-[0.65rem] font-bold"
                    :class="valem.has(p.codigo) ? 'bg-credito text-cartao' : 'border border-tinta-apagada text-tinta-apagada'"
                    :aria-label="valem.has(p.codigo) ? 'pode' : 'não pode'"
                  >{{ valem.has(p.codigo) ? '✓' : '' }}</span>
                  {{ p.nome }}
                </p>
                <p class="ml-6 text-xs text-tinta-suave">{{ origem(p) }}</p>
              </div>
              <div class="ml-6 flex gap-1 sm:ml-0" role="radiogroup" :aria-label="`Ajuste de ${p.nome}`">
                <label
                  v-for="m in MODOS"
                  :key="m.valor"
                  class="cursor-pointer rounded-[3px] border px-2 py-1 text-xs transition"
                  :class="modo(p.codigo) === m.valor
                    ? (m.valor === 'retirar' ? 'border-carimbo bg-carimbo/10 font-semibold text-carimbo'
                      : m.valor === 'liberar' ? 'border-credito bg-credito/10 font-semibold text-credito'
                        : 'border-tinta bg-papel-escuro/60 font-semibold')
                    : 'border-linha text-tinta-suave hover:border-tinta'"
                >
                  <input type="radio" class="sr-only" :name="`ajuste-${usuario.id}-${p.codigo}`" :value="m.valor"
                         :checked="modo(p.codigo) === m.valor" @change="definirModo(p.codigo, m.valor)" />
                  {{ m.rotulo }}
                </label>
              </div>
            </li>
          </ul>
        </div>
      </div>

      <p v-if="erro" role="alert" class="mt-3 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erro }}</p>
      <div class="mt-4 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" class="botao-secundario" :disabled="salvando" @click="emit('cancelar')">Cancelar</button>
        <button type="button" class="botao-primario" :disabled="salvando" @click="salvar">{{ salvando ? 'Salvando…' : 'Salvar o acesso' }}</button>
      </div>

      <details v-if="historico.length" class="mt-4 text-sm">
        <summary class="cursor-pointer text-xs font-semibold text-tinta-suave">Últimas mudanças no acesso desta pessoa</summary>
        <ul class="mt-2 space-y-1">
          <li v-for="(m, i) in historico" :key="i" class="text-xs">
            <span class="carimbo text-tinta-suave">{{ quando(m.quando) }} · {{ m.quem }}</span> — {{ m.descricao }}
          </li>
        </ul>
      </details>
    </EstadoDaTela>
  </section>
</template>
