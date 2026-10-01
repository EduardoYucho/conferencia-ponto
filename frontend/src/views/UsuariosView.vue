<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { dataBR, dataISO } from '@/utils/tempo'

/**
 * Cadastro de usuários (administrador). Cada pessoa tem o próprio acesso, horário, banco de horas e pasta de
 * comprovantes. O acesso é criado com uma senha provisória, que a pessoa troca no primeiro login. Usuários
 * não são excluídos (o histórico de ponto fica): são desativados.
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const router = useRouter()

const PERFIS = [
  { valor: 'ROLE_USER', rotulo: 'Usuário', descricao: 'registra e confere o próprio ponto' },
  { valor: 'ROLE_ADMIN', rotulo: 'Administrador', descricao: 'também cadastra usuários e feriados e consulta todos' },
  { valor: 'ROLE_VIEWER', rotulo: 'Coordenação', descricao: 'só consulta o ponto de todos' },
]
const rotuloPerfil = (valor) => PERFIS.find((p) => p.valor === valor)?.rotulo ?? valor

const usuarios = ref([])
const carregando = ref(false)
const erro = ref('')
const aviso = ref('')

async function carregar() {
  carregando.value = true
  erro.value = ''
  try {
    usuarios.value = await pontoApi.usuarios()
  } catch (e) {
    erro.value = e.message
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)
watch(() => ponto.ultimaAlteracaoUsuarios, () => carregar())

const ativos = computed(() => usuarios.value.filter((u) => u.ativo).length)

// ------------------------------------------------------------- novo usuário
const novoAberto = ref(false)
const novo = ref(null)
const tentouNovo = ref(false)
const salvandoNovo = ref(false)
/** Credenciais para repassar à pessoa (mostradas uma vez, depois de criar ou redefinir). */
const credenciais = ref(null)

function senhaAleatoria() {
  const letras = 'abcdefghjkmnpqrstuvwxyz23456789'
  const bytes = crypto.getRandomValues(new Uint8Array(10))
  return Array.from(bytes, (b) => letras[b % letras.length]).join('')
}

function abrirNovo() {
  novo.value = { nome: '', login: '', perfil: 'ROLE_USER', senhaProvisoria: senhaAleatoria(), pastaComprovantes: '' }
  tentouNovo.value = false
  credenciais.value = null
  novoAberto.value = true
}

/** Sugere o login a partir do primeiro nome ("Maria Souza" -> "maria"). */
function sugerirLogin() {
  if (novo.value.login) return
  const primeiro = novo.value.nome.trim().split(/\s+/)[0] ?? ''
  novo.value.login = primeiro.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9._-]/g, '')
}

const errosNovo = computed(() => {
  const n = novo.value
  if (!n) return {}
  return {
    nome: !n.nome.trim() ? 'Informe o nome.' : null,
    login: !/^[a-z0-9][a-z0-9._-]{2,59}$/.test(n.login.trim().toLowerCase())
      ? 'De 3 a 60 caracteres: letras minúsculas, números, ponto, hífen ou sublinhado.' : null,
    senha: n.senhaProvisoria.length < 8 ? 'A senha provisória precisa ter pelo menos 8 caracteres.' : null,
  }
})

async function criar() {
  tentouNovo.value = true
  if (Object.values(errosNovo.value).some(Boolean)) return
  salvandoNovo.value = true
  erro.value = ''
  try {
    const n = novo.value
    const criado = await pontoApi.criarUsuario({
      nome: n.nome.trim(),
      login: n.login.trim().toLowerCase(),
      perfil: n.perfil,
      senhaProvisoria: n.senhaProvisoria,
      pastaComprovantes: n.perfil === 'ROLE_VIEWER' ? null : (n.pastaComprovantes.trim() || null),
    })
    credenciais.value = { nome: criado.nome, login: criado.login, senha: n.senhaProvisoria }
    novoAberto.value = false
    await carregar()
    auth.carregarTitulares().catch(() => {})
  } catch (e) {
    erro.value = e.message
  } finally {
    salvandoNovo.value = false
  }
}

async function copiarCredenciais() {
  const c = credenciais.value
  const texto = `Conferência de Ponto\nEndereço: ${window.location.origin}\nLogin: ${c.login}\nSenha provisória: ${c.senha}\n(você vai criar sua senha no primeiro acesso)`
  try {
    await navigator.clipboard.writeText(texto)
    mostrar('Dados de acesso copiados.')
  } catch {
    mostrar('Não foi possível copiar: selecione e copie o texto.')
  }
}

// ------------------------------------------------------------------ edição
const editando = ref(null)
const salvandoEdicao = ref(false)

function editar(u) {
  editando.value = { id: u.id, nome: u.nome, perfil: u.perfil, ativo: u.ativo, login: u.login }
}

async function salvarEdicao() {
  const e = editando.value
  if (!e.nome.trim()) {
    erro.value = 'Informe o nome.'
    return
  }
  salvandoEdicao.value = true
  erro.value = ''
  try {
    await pontoApi.atualizarUsuario(e.id, { nome: e.nome.trim(), perfil: e.perfil, ativo: e.ativo })
    editando.value = null
    mostrar('Usuário atualizado.')
    await carregar()
    auth.carregarTitulares().catch(() => {})
  } catch (ex) {
    erro.value = ex.message
  } finally {
    salvandoEdicao.value = false
  }
}

const confirmandoSenha = ref(null)
async function redefinirSenha(u) {
  if (confirmandoSenha.value !== u.id) {
    confirmandoSenha.value = u.id
    setTimeout(() => (confirmandoSenha.value = null), 4000)
    return
  }
  confirmandoSenha.value = null
  const senha = senhaAleatoria()
  try {
    await pontoApi.redefinirSenha(u.id, senha)
    credenciais.value = { nome: u.nome, login: u.login, senha }
    await carregar()
  } catch (e) {
    erro.value = e.message
  }
}

// --------------------------------------------------------------- navegação
function verDados(u, rota = 'painel') {
  auth.verComo(u.login)
  ponto.trocarPessoa()
  router.push({ name: rota })
}

// ------------------------------------------------------------------- apoio
let timer = null
function mostrar(texto) {
  aviso.value = texto
  clearTimeout(timer)
  timer = setTimeout(() => (aviso.value = ''), 4000)
}

const MONITOR = {
  ATIVO: { cor: 'bg-credito', texto: 'monitorando' },
  INDISPONIVEL: { cor: 'bg-amber-500', texto: 'pasta inacessível' },
  INICIANDO: { cor: 'bg-amber-500', texto: 'conectando' },
  SEM_PASTA: { cor: 'bg-tinta-apagada', texto: 'sem pasta (envio pela tela)' },
  DESABILITADO: { cor: 'bg-tinta-apagada', texto: 'monitoramento desligado' },
}
const ultimoAcesso = (iso) => (iso ? dataBR(dataISO(new Date(iso))) : 'nunca entrou')
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-4 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div>
        <p class="rotulo">Administração · {{ ativos }} usuário(s) ativo(s)</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Usuários</h1>
      </div>
      <button type="button" class="botao-primario" @click="abrirNovo">Novo usuário</button>
    </header>

    <p v-if="erro" role="alert" class="mt-6 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erro }}</p>

    <!-- Dados para repassar -->
    <section v-if="credenciais" class="cartao mt-6 border-credito/50 px-5 py-4" aria-live="polite">
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p class="font-semibold">Passe estes dados para {{ credenciais.nome }}:</p>
          <p class="mt-1 text-sm">
            Login <b class="carimbo">{{ credenciais.login }}</b> · senha provisória <b class="carimbo">{{ credenciais.senha }}</b>
          </p>
          <p class="mt-1 text-xs text-tinta-suave">No primeiro acesso a pessoa cria a própria senha. Esta senha não aparece de novo.</p>
        </div>
        <div class="flex gap-2">
          <button type="button" class="botao-secundario py-1.5! text-xs" @click="copiarCredenciais">Copiar</button>
          <button type="button" class="botao-secundario py-1.5! text-xs" @click="credenciais = null">Fechar</button>
        </div>
      </div>
    </section>

    <!-- Novo usuário -->
    <form v-if="novoAberto && novo" class="cartao mt-6 grid gap-4 px-5 py-5 sm:grid-cols-2" novalidate @submit.prevent="criar">
      <h2 class="font-sans text-lg font-bold sm:col-span-2">Novo usuário</h2>
      <label class="flex flex-col">
        <span class="rotulo">Nome</span>
        <input v-model="novo.nome" class="campo mt-1.5 font-sans" placeholder="Maria Souza" @blur="sugerirLogin" />
        <span v-if="tentouNovo && errosNovo.nome" class="mt-1 text-xs text-carimbo">{{ errosNovo.nome }}</span>
      </label>
      <label class="flex flex-col">
        <span class="rotulo">Login</span>
        <input v-model="novo.login" class="campo mt-1.5 font-sans" placeholder="maria" autocapitalize="off" spellcheck="false" />
        <span v-if="tentouNovo && errosNovo.login" class="mt-1 text-xs text-carimbo">{{ errosNovo.login }}</span>
      </label>
      <fieldset class="sm:col-span-2">
        <legend class="rotulo">Perfil</legend>
        <div class="mt-1.5 grid gap-2 sm:grid-cols-3">
          <label
            v-for="p in PERFIS"
            :key="p.valor"
            class="flex cursor-pointer flex-col rounded-[3px] border px-3 py-2 text-sm transition"
            :class="novo.perfil === p.valor ? 'border-tinta bg-papel-escuro/60' : 'border-linha hover:border-tinta'"
          >
            <span class="flex items-center gap-2 font-semibold">
              <input v-model="novo.perfil" type="radio" :value="p.valor" class="accent-tinta" /> {{ p.rotulo }}
            </span>
            <span class="mt-0.5 text-xs text-tinta-suave">{{ p.descricao }}</span>
          </label>
        </div>
      </fieldset>
      <label class="flex flex-col">
        <span class="rotulo">Senha provisória</span>
        <span class="mt-1.5 flex gap-2">
          <input v-model="novo.senhaProvisoria" class="campo" spellcheck="false" autocomplete="off" />
          <button type="button" class="botao-secundario px-3! text-xs" title="Gerar outra" @click="novo.senhaProvisoria = senhaAleatoria()">gerar</button>
        </span>
        <span v-if="tentouNovo && errosNovo.senha" class="mt-1 text-xs text-carimbo">{{ errosNovo.senha }}</span>
        <span v-else class="mt-1 text-xs text-tinta-apagada">A pessoa troca no primeiro acesso.</span>
      </label>
      <label v-if="novo.perfil !== 'ROLE_VIEWER'" class="flex flex-col">
        <span class="rotulo">Pasta dos comprovantes (opcional)</span>
        <input v-model="novo.pastaComprovantes" class="campo mt-1.5 text-sm" placeholder="\\NOME-DO-PC\Ponto" spellcheck="false" />
        <span class="mt-1 text-xs text-tinta-apagada">A pessoa também pode escolher depois, em "Minha conta".</span>
      </label>
      <p class="text-sm text-tinta-suave sm:col-span-2">
        O usuário começa com o horário padrão (segunda a sexta, 08:00–12:00 e 13:00–17:48), que pode ser alterado
        por ele ou por você.
      </p>
      <div class="flex flex-col-reverse gap-2 sm:col-span-2 sm:flex-row sm:justify-end">
        <button type="button" class="botao-secundario" :disabled="salvandoNovo" @click="novoAberto = false">Cancelar</button>
        <button type="submit" class="botao-primario" :disabled="salvandoNovo">{{ salvandoNovo ? 'Criando…' : 'Criar usuário' }}</button>
      </div>
    </form>

    <!-- Lista -->
    <section class="cartao mt-6 overflow-x-auto" aria-label="Usuários cadastrados">
      <table class="w-full min-w-[52rem] text-sm">
        <thead>
          <tr class="border-b-2 border-tinta text-left">
            <th class="rotulo px-4 py-2.5">Pessoa</th>
            <th class="rotulo py-2.5">Perfil</th>
            <th class="rotulo py-2.5">Comprovantes</th>
            <th class="rotulo py-2.5">Último acesso</th>
            <th class="py-2.5"><span class="sr-only">Ações</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="carregando && !usuarios.length"><td colspan="5" class="px-4 py-6 text-center text-tinta-suave">Carregando…</td></tr>
          <template v-for="u in usuarios" :key="u.id">
            <tr class="border-b border-linha/70 align-top" :class="u.ativo ? '' : 'opacity-55'">
              <td class="px-4 py-2.5">
                <span class="block font-semibold">{{ u.nome }}</span>
                <span class="carimbo text-xs text-tinta-suave">{{ u.login }}</span>
                <span v-if="!u.ativo" class="ml-2 rounded-[2px] bg-tinta/10 px-1.5 text-[0.68rem] font-bold uppercase">inativo</span>
                <span v-else-if="u.trocarSenha" class="ml-2 rounded-[2px] bg-amber-500/15 px-1.5 text-[0.68rem] font-bold text-amber-700 uppercase">senha provisória</span>
              </td>
              <td class="py-2.5">{{ rotuloPerfil(u.perfil) }}</td>
              <td class="max-w-[16rem] py-2.5">
                <template v-if="u.titular">
                  <span class="flex items-center gap-1.5 text-xs">
                    <span class="size-2 rounded-full" :class="MONITOR[u.situacaoMonitor]?.cor ?? 'bg-tinta-apagada'" />
                    {{ MONITOR[u.situacaoMonitor]?.texto ?? u.situacaoMonitor }}
                  </span>
                  <span v-if="u.pastaComprovantes" class="carimbo block truncate text-xs text-tinta-suave" :title="u.pastaComprovantes">{{ u.pastaComprovantes }}</span>
                  <span v-if="u.mensagemMonitor" class="block text-xs text-carimbo">{{ u.mensagemMonitor }}</span>
                </template>
                <span v-else class="text-xs text-tinta-apagada">não registra ponto</span>
              </td>
              <td class="py-2.5 text-xs text-tinta-suave">{{ ultimoAcesso(u.ultimoLoginEm) }}</td>
              <td class="py-2 pr-3 text-right whitespace-nowrap">
                <template v-if="u.titular && u.ativo">
                  <button type="button" class="rounded-[3px] px-2 py-1 text-xs font-semibold text-tinta-suave hover:bg-papel-escuro hover:text-tinta" @click="verDados(u)">Ver ponto</button>
                  <button type="button" class="rounded-[3px] px-2 py-1 text-xs font-semibold text-tinta-suave hover:bg-papel-escuro hover:text-tinta" @click="verDados(u, 'conta')">Horário e pasta</button>
                </template>
                <button type="button" class="rounded-[3px] px-2 py-1 text-xs font-semibold text-tinta-suave hover:bg-papel-escuro hover:text-tinta" @click="editar(u)">Editar</button>
                <button
                  v-if="u.id !== auth.usuario?.id"
                  type="button"
                  class="rounded-[3px] px-2 py-1 text-xs font-semibold transition"
                  :class="confirmandoSenha === u.id ? 'bg-carimbo text-cartao' : 'text-tinta-suave hover:bg-papel-escuro hover:text-carimbo'"
                  @click="redefinirSenha(u)"
                >{{ confirmandoSenha === u.id ? 'Gerar senha provisória?' : 'Redefinir senha' }}</button>
              </td>
            </tr>
            <tr v-if="editando?.id === u.id" class="border-b border-linha/70 bg-papel/50">
              <td colspan="5" class="px-4 py-3">
                <form class="flex flex-wrap items-end gap-3" novalidate @submit.prevent="salvarEdicao">
                  <label class="flex min-w-[14rem] flex-1 flex-col">
                    <span class="rotulo">Nome</span>
                    <input v-model="editando.nome" class="campo mt-1 py-1.5! font-sans text-sm" />
                  </label>
                  <label class="flex flex-col">
                    <span class="rotulo">Perfil</span>
                    <select v-model="editando.perfil" class="campo mt-1 py-1.5! font-sans text-sm" :disabled="u.id === auth.usuario?.id">
                      <option v-for="p in PERFIS" :key="p.valor" :value="p.valor">{{ p.rotulo }}</option>
                    </select>
                  </label>
                  <label class="flex items-center gap-2 pb-2 text-sm" :class="u.id === auth.usuario?.id ? 'opacity-50' : ''">
                    <input v-model="editando.ativo" type="checkbox" class="accent-tinta" :disabled="u.id === auth.usuario?.id" /> Ativo
                  </label>
                  <span class="flex gap-2">
                    <button type="button" class="botao-secundario py-1.5! text-xs" @click="editando = null">Cancelar</button>
                    <button type="submit" class="botao-primario py-1.5! text-xs" :disabled="salvandoEdicao">Salvar</button>
                  </span>
                  <p v-if="!editando.ativo && u.ativo" class="w-full text-xs text-tinta-suave">
                    Desativado, o usuário não entra mais e a pasta dele deixa de ser monitorada. O histórico de ponto fica guardado.
                  </p>
                </form>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
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
