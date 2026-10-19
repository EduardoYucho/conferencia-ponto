<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { acessosApi } from '@/api/acessosApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { mensagemDe } from '@/utils/erros'

/**
 * Perfis de acesso: cada perfil é um grupo de permissões (por tela e ação), e cada pessoa tem um ou mais perfis
 * (em Usuários → Acesso). Mudar um perfil vale na hora para todos que o têm. O Administrador tem tudo e não muda;
 * os perfis do sistema podem ser editados, mas não excluídos. Toda mudança fica no histórico.
 */
const auth = useAuthStore()
const ponto = usePontoStore()

const grupos = ref([])
const perfis = ref([])
const historico = ref([])
const carregando = ref(false)
const erro = ref(null)
const aviso = ref('')
let timerAviso = null
let timerConfirmacao = null
onBeforeUnmount(() => {
  clearTimeout(timerAviso)
  clearTimeout(timerConfirmacao)
})

async function carregar() {
  carregando.value = true
  erro.value = null
  try {
    const [g, p, h] = await Promise.all([acessosApi.permissoes(), acessosApi.perfis(), acessosApi.historico(null, 30)])
    grupos.value = g
    perfis.value = p
    historico.value = h
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)
watch(() => ponto.ultimaAlteracaoUsuarios, () => carregar())

const catalogo = computed(() => grupos.value.flatMap((g) => g.permissoes))
const nomeDe = (codigo) => catalogo.value.find((p) => p.codigo === codigo)?.nome ?? codigo

function mostrar(texto) {
  aviso.value = texto
  clearTimeout(timerAviso)
  timerAviso = setTimeout(() => (aviso.value = ''), 4000)
}

// ------------------------------------------------------------------ edição
/** O perfil em edição: { id (null = novo), nome, descricao, permissoes: Set }. */
const editando = ref(null)
const erroEdicao = ref('')
const salvando = ref(false)

function novo() {
  erroEdicao.value = ''
  editando.value = { id: null, nome: '', descricao: '', permissoes: new Set() }
}

function editar(p) {
  erroEdicao.value = ''
  editando.value = { id: p.id, nome: p.nome, descricao: p.descricao ?? '', permissoes: new Set(p.permissoes) }
}

/** Marcar uma permissão marca o requisito dela; desmarcar tira junto as que dependiam dela. */
function alternar(codigo) {
  const marcadas = new Set(editando.value.permissoes)
  if (marcadas.has(codigo)) {
    const tirar = [codigo]
    while (tirar.length) {
      const c = tirar.pop()
      marcadas.delete(c)
      catalogo.value.filter((p) => p.requer === c && marcadas.has(p.codigo)).forEach((p) => tirar.push(p.codigo))
    }
  } else {
    let c = codigo
    while (c) {
      marcadas.add(c)
      c = catalogo.value.find((p) => p.codigo === c)?.requer
    }
  }
  editando.value.permissoes = marcadas
}

async function salvar() {
  const e = editando.value
  erroEdicao.value = ''
  if (e.nome.trim().length < 2) {
    erroEdicao.value = 'Dê um nome ao perfil (pelo menos 2 letras).'
    return
  }
  salvando.value = true
  try {
    const corpo = { nome: e.nome.trim(), descricao: e.descricao.trim() || null, permissoes: [...e.permissoes] }
    if (e.id) await acessosApi.alterarPerfil(e.id, corpo)
    else await acessosApi.criarPerfil(corpo)
    mostrar(e.id ? 'Perfil salvo. Vale na hora para quem tem este perfil.' : 'Perfil criado. Dê o perfil às pessoas em Usuários → Acesso.')
    editando.value = null
    // quem salvou pode ter mudado o próprio acesso
    auth.atualizarUsuario().catch(() => {})
    await carregar()
  } catch (ex) {
    erroEdicao.value = mensagemDe(ex)
  } finally {
    salvando.value = false
  }
}

// ----------------------------------------------------------------- exclusão
const confirmandoExclusao = ref(null)
const falhaNoPerfil = ref(null)

async function excluir(p) {
  falhaNoPerfil.value = null
  if (confirmandoExclusao.value !== p.id) {
    confirmandoExclusao.value = p.id
    clearTimeout(timerConfirmacao)
    timerConfirmacao = setTimeout(() => (confirmandoExclusao.value = null), 5000)
    return
  }
  clearTimeout(timerConfirmacao)
  confirmandoExclusao.value = null
  try {
    await acessosApi.excluirPerfil(p.id)
    mostrar(`Perfil ${p.nome} excluído.`)
    auth.atualizarUsuario().catch(() => {})
    await carregar()
  } catch (e) {
    falhaNoPerfil.value = { id: p.id, mensagem: mensagemDe(e) }
  }
}

/** O que o perfil libera, por grupo (só os grupos com alguma). */
function resumo(p) {
  return grupos.value
    .map((g) => ({ nome: g.nome, itens: g.permissoes.filter((x) => p.todas || p.permissoes.includes(x.codigo)).map((x) => x.nome) }))
    .filter((g) => g.itens.length)
}

function quando(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  return `${d.toLocaleDateString('pt-BR')} ${d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}`
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-4 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div>
        <p class="rotulo">Administração<template v-if="perfis.length"> · {{ perfis.length }} perfil(is)</template></p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Perfis de acesso</h1>
      </div>
      <div class="flex flex-wrap gap-2">
        <RouterLink :to="{ name: 'usuarios' }" class="botao-secundario">Usuários</RouterLink>
        <button type="button" class="botao-primario" @click="novo">Novo perfil</button>
      </div>
    </header>

    <p class="mt-4 max-w-3xl text-sm text-tinta-suave">
      Cada perfil é um grupo de permissões. Cada pessoa tem <b class="text-tinta">um ou mais perfis</b> e pode a soma do que
      eles liberam; em <RouterLink :to="{ name: 'usuarios' }" class="font-semibold text-tinta underline underline-offset-4">Usuários → Acesso</RouterLink>
      dá para liberar ou retirar uma permissão só de uma pessoa. Mudar um perfil vale na hora para todos que o têm.
    </p>

    <!-- Novo perfil -->
    <form v-if="editando && !editando.id" class="cartao mt-6 px-5 py-5" novalidate @submit.prevent="salvar">
      <h2 class="font-sans text-lg font-bold">Novo perfil</h2>
      <div class="mt-3 grid gap-4 sm:grid-cols-2">
        <label class="flex flex-col">
          <span class="rotulo">Nome</span>
          <input v-model="editando.nome" class="campo mt-1.5 font-sans" placeholder="Atendente" maxlength="60" />
        </label>
        <label class="flex flex-col">
          <span class="rotulo">Descrição (opcional)</span>
          <input v-model="editando.descricao" class="campo mt-1.5 font-sans text-sm" placeholder="Para que serve este perfil" maxlength="200" />
        </label>
      </div>
      <div v-for="g in grupos" :key="g.nome" class="mt-4">
        <p class="text-xs font-bold tracking-wide text-tinta-suave uppercase">{{ g.nome }}</p>
        <div class="mt-1.5 grid gap-2 sm:grid-cols-2">
          <label
            v-for="p in g.permissoes"
            :key="p.codigo"
            class="flex cursor-pointer flex-col rounded-[3px] border px-3 py-2 text-sm transition"
            :class="editando.permissoes.has(p.codigo) ? 'border-tinta bg-papel-escuro/60' : 'border-linha hover:border-tinta'"
          >
            <span class="flex items-center gap-2 font-semibold">
              <input type="checkbox" class="accent-tinta" :checked="editando.permissoes.has(p.codigo)" @change="alternar(p.codigo)" />
              {{ p.nome }}
            </span>
            <span v-if="p.descricao" class="mt-0.5 text-xs text-tinta-suave">{{ p.descricao }}</span>
            <span v-if="p.requer" class="mt-0.5 text-xs text-tinta-apagada">Vem junto: {{ nomeDe(p.requer) }}</span>
          </label>
        </div>
      </div>
      <p v-if="erroEdicao" role="alert" class="mt-4 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erroEdicao }}</p>
      <div class="mt-4 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" class="botao-secundario" :disabled="salvando" @click="editando = null">Cancelar</button>
        <button type="submit" class="botao-primario" :disabled="salvando">{{ salvando ? 'Criando…' : 'Criar perfil' }}</button>
      </div>
    </form>

    <div class="mt-6">
      <EstadoDaTela :carregando="carregando" :erro="erro" :vazio="!perfis.length" vazio-texto="Nenhum perfil." :manter="perfis.length > 0" @tentar="carregar">
        <div class="grid gap-4 lg:grid-cols-2">
          <section v-for="p in perfis" :key="p.id" class="cartao px-5 py-4" :aria-label="`Perfil ${p.nome}`">
            <!-- Edição -->
            <form v-if="editando?.id === p.id" novalidate @submit.prevent="salvar">
              <div class="grid gap-3">
                <label class="flex flex-col">
                  <span class="rotulo">Nome</span>
                  <input v-model="editando.nome" class="campo mt-1 py-1.5! font-sans" maxlength="60" />
                </label>
                <label class="flex flex-col">
                  <span class="rotulo">Descrição (opcional)</span>
                  <input v-model="editando.descricao" class="campo mt-1 py-1.5! font-sans text-sm" maxlength="200" />
                </label>
              </div>
              <div v-for="g in grupos" :key="g.nome" class="mt-3">
                <p class="text-xs font-bold tracking-wide text-tinta-suave uppercase">{{ g.nome }}</p>
                <ul class="mt-1 space-y-1">
                  <li v-for="x in g.permissoes" :key="x.codigo">
                    <label class="flex cursor-pointer items-start gap-2 text-sm">
                      <input type="checkbox" class="mt-1 accent-tinta" :checked="editando.permissoes.has(x.codigo)" @change="alternar(x.codigo)" />
                      <span>
                        <span class="font-semibold">{{ x.nome }}</span>
                        <span v-if="x.descricao" class="block text-xs text-tinta-suave">{{ x.descricao }}</span>
                      </span>
                    </label>
                  </li>
                </ul>
              </div>
              <p v-if="p.pessoas.length" class="mt-3 text-xs text-tinta-suave">Vale na hora para {{ p.pessoas.length }} pessoa(s): {{ p.pessoas.map((x) => x.nome).join(', ') }}.</p>
              <p v-if="erroEdicao" role="alert" class="mt-3 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erroEdicao }}</p>
              <div class="mt-3 flex justify-end gap-2">
                <button type="button" class="botao-secundario py-1.5! text-xs" :disabled="salvando" @click="editando = null">Cancelar</button>
                <button type="submit" class="botao-primario py-1.5! text-xs" :disabled="salvando">{{ salvando ? 'Salvando…' : 'Salvar' }}</button>
              </div>
            </form>

            <!-- Leitura -->
            <template v-else>
              <div class="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <h2 class="font-sans text-lg font-bold">{{ p.nome }}</h2>
                  <p class="mt-0.5 flex flex-wrap gap-1">
                    <span v-if="p.todas" class="rounded-[2px] bg-tinta px-1.5 text-[0.68rem] font-bold text-cartao uppercase">tudo liberado</span>
                    <span v-if="p.doSistema" class="rounded-[2px] bg-tinta/10 px-1.5 text-[0.68rem] font-bold uppercase">do sistema</span>
                  </p>
                </div>
                <div class="flex gap-1">
                  <button v-if="!p.todas" type="button" class="rounded-[3px] px-2 py-1 text-xs font-semibold text-tinta-suave hover:bg-papel-escuro hover:text-tinta" @click="editar(p)">Editar</button>
                  <button
                    v-if="!p.doSistema"
                    type="button"
                    class="rounded-[3px] px-2 py-1 text-xs font-semibold transition"
                    :class="confirmandoExclusao === p.id ? 'bg-carimbo text-cartao' : 'text-tinta-suave hover:bg-papel-escuro hover:text-carimbo'"
                    @click="excluir(p)"
                  >{{ confirmandoExclusao === p.id ? (p.pessoas.length ? `Excluir? Sai de ${p.pessoas.length} pessoa(s)` : 'Excluir?') : 'Excluir' }}</button>
                </div>
              </div>
              <p v-if="p.descricao" class="mt-2 text-sm text-tinta-suave">{{ p.descricao }}</p>
              <p v-if="falhaNoPerfil?.id === p.id" role="alert" class="mt-2 text-sm text-carimbo">{{ falhaNoPerfil.mensagem }}</p>

              <dl class="mt-3 space-y-1.5 text-sm">
                <div v-for="g in resumo(p)" :key="g.nome">
                  <dt class="text-xs font-bold tracking-wide text-tinta-suave uppercase">{{ g.nome }}</dt>
                  <dd>{{ g.itens.join(' · ') }}</dd>
                </div>
                <p v-if="!resumo(p).length" class="text-xs text-tinta-apagada">Ainda não libera nada.</p>
              </dl>
              <p v-if="p.todas" class="mt-2 text-xs text-tinta-apagada">Inclui o que vier nas próximas versões. Para dar menos, crie outro perfil.</p>

              <p class="mt-3 border-t border-dashed border-linha pt-2 text-xs text-tinta-suave">
                <template v-if="p.pessoas.length">
                  {{ p.pessoas.length }} pessoa(s):
                  <span v-for="(x, i) in p.pessoas" :key="x.id" :class="x.ativo ? 'text-tinta' : 'line-through opacity-60'">{{ x.nome }}{{ i < p.pessoas.length - 1 ? ', ' : '' }}</span>
                </template>
                <template v-else>Ninguém tem este perfil ainda.</template>
              </p>
            </template>
          </section>
        </div>
      </EstadoDaTela>
    </div>

    <section v-if="historico.length" class="cartao mt-8 px-5 py-4" aria-label="Histórico de acessos">
      <h2 class="font-sans text-lg font-bold">Histórico</h2>
      <p class="text-xs text-tinta-suave">As últimas mudanças nos perfis e no acesso das pessoas.</p>
      <ul class="mt-3 divide-y divide-linha/70 text-sm">
        <li v-for="(m, i) in historico" :key="i" class="py-1.5">
          <span class="carimbo text-xs text-tinta-suave">{{ quando(m.quando) }} · {{ m.quem }}</span>
          <span class="block">{{ m.descricao }}</span>
        </li>
      </ul>
    </section>

    <Transition
      enter-active-class="transition duration-200"
      enter-from-class="translate-y-3 opacity-0"
      leave-active-class="transition duration-150"
      leave-to-class="opacity-0"
    >
      <p v-if="aviso" role="status" class="fixed inset-x-4 bottom-6 z-50 mx-auto max-w-md rounded-[3px] bg-tinta px-4 py-3 text-center text-sm text-cartao shadow-lg">{{ aviso }}</p>
    </Transition>
  </div>
</template>
