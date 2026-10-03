<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import EditorPasta from '@/components/EditorPasta.vue'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'
import IntegracaoGoogle from '@/components/IntegracaoGoogle.vue'
import { avisar } from '@/utils/avisar'
import { mensagemDe } from '@/utils/erros'
import { dataBR } from '@/utils/horas'
import { dataISO } from '@/utils/tempo'

/**
 * Usuários (administrador). Cada pessoa tem o próprio acesso, horário, banco de horas e pasta de comprovantes.
 * O acesso é criado com uma senha provisória, que a pessoa troca na primeira vez que entra. Usuários não são
 * excluídos (o ponto já registrado fica): são desativados, e podem ser reativados.
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const router = useRouter()
const route = useRoute()

const PERFIS = [
  { valor: 'ROLE_USER', rotulo: 'Usuário', descricao: 'Registra e confere o próprio ponto.' },
  { valor: 'ROLE_ADMIN', rotulo: 'Administrador', descricao: 'Além do próprio ponto, cadastra usuários e feriados e consulta o ponto de todos.' },
  { valor: 'ROLE_VIEWER', rotulo: 'Coordenação (só consulta)', descricao: 'Só consulta o ponto de todos. Não registra ponto.' },
]
const rotuloPerfil = (valor) => PERFIS.find((p) => p.valor === valor)?.rotulo ?? valor
const primeiroNome = (nome) => (nome ?? '').trim().split(/\s+/)[0]
const souEu = (u) => u.id === auth.usuario?.id

const usuarios = ref([])
const carregando = ref(false)
/** Falha ao carregar a lista (com "Tentar de novo"). As falhas das ações aparecem junto de cada ação. */
const erroLista = ref(null)
const erroNovo = ref('')
const erroEdicao = ref('')
let timerPasta = null
onBeforeUnmount(() => clearTimeout(timerPasta))

/** No início de cada ação: o erro de uma ação anterior não fica na tela depois de outra tentativa. */
function limparErrosDeAcao() {
  erroNovo.value = ''
  erroEdicao.value = ''
}

async function carregar() {
  carregando.value = true
  erroLista.value = null
  try {
    usuarios.value = await pontoApi.usuarios()
  } catch (e) {
    erroLista.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(async () => {
  await carregar()
  // veio por um link para uma seção (ex.: #google): a lista que carregou acima empurrou a seção para baixo
  if (route.hash) {
    await nextTick()
    document.querySelector(route.hash)?.scrollIntoView({ block: 'start' })
  }
})
watch(() => ponto.ultimaAlteracaoUsuarios, () => carregar())

const ativos = computed(() => usuarios.value.filter((u) => u.ativo).length)

// ------------------------------------------------------------- novo usuário
const novoAberto = ref(false)
const novo = ref(null)
const tentouNovo = ref(false)
const salvandoNovo = ref(false)
const campoNome = ref(null)
/** Dados de acesso para repassar à pessoa (mostrados uma vez, depois de criar ou de redefinir a senha). */
const credenciais = ref(null)

/** Senha provisória sugerida (a pessoa troca na primeira vez que entra). */
function senhaAleatoria() {
  const letras = 'abcdefghjkmnpqrstuvwxyz23456789'
  const bytes = crypto.getRandomValues(new Uint8Array(10))
  return Array.from(bytes, (b) => letras[b % letras.length]).join('')
}

async function abrirNovo() {
  limparErrosDeAcao()
  novo.value = { nome: '', login: '', perfil: 'ROLE_USER', senhaProvisoria: senhaAleatoria(), pastaComprovantes: '' }
  tentouNovo.value = false
  novoAberto.value = true
  await nextTick()
  campoNome.value?.focus()
  campoNome.value?.closest('form')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

/** Sugere o login a partir do primeiro nome ("Maria Souza" -> "maria"). */
function sugerirLogin() {
  if (novo.value.login) return
  const primeiro = novo.value.nome.trim().split(/\s+/)[0] ?? ''
  novo.value.login = primeiro.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9._-]/g, '')
}

/** Ajuda de digitação: quem decide se o cadastro é aceito é o servidor. */
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
  limparErrosDeAcao()
  if (Object.values(errosNovo.value).some(Boolean)) return
  salvandoNovo.value = true
  try {
    const n = novo.value
    const enviado = {
      nome: n.nome.trim(),
      login: n.login.trim().toLowerCase(),
      perfil: n.perfil,
      senhaProvisoria: n.senhaProvisoria,
      pastaComprovantes: n.perfil === 'ROLE_VIEWER' ? null : (n.pastaComprovantes.trim() || null),
    }
    let criado
    try {
      criado = await pontoApi.criarUsuario(enviado)
    } catch (e) {
      erroNovo.value = mensagemDe(e)
      return
    }
    // criou: o que vem depois (recarregar a lista) não pode parecer falha da criação
    credenciais.value = { nome: criado?.nome ?? enviado.nome, login: criado?.login ?? enviado.login, senha: n.senhaProvisoria, novo: true }
    novoAberto.value = false
    await carregar()
    auth.carregarTitulares().catch(() => {})
  } finally {
    salvandoNovo.value = false
  }
}

const naoCopiou = ref(false)
const botaoCopiar = ref(null)
// a janela com os dados de acesso abre com o foco no botão de copiar
watch(credenciais, async (c) => {
  if (!c) return
  await nextTick()
  botaoCopiar.value?.focus()
})
async function copiarCredenciais() {
  const c = credenciais.value
  const texto = `Conferência de Ponto\nEndereço: ${window.location.origin}\nLogin: ${c.login}\nSenha provisória: ${c.senha}\n(você vai criar sua senha no primeiro acesso)`
  naoCopiou.value = false
  try {
    await navigator.clipboard.writeText(texto)
    avisar('Dados de acesso copiados. Agora é só colar na mensagem para a pessoa.')
  } catch {
    naoCopiou.value = true
  }
}
function fecharCredenciais() {
  credenciais.value = null
  naoCopiou.value = false
}

// ------------------------------------------------------------------ edição (nome e perfil)
const editando = ref(null)
const salvandoEdicao = ref(false)

function editar(u) {
  limparErrosDeAcao()
  editando.value = { id: u.id, nome: u.nome, perfil: u.perfil, ativo: u.ativo, login: u.login }
}

async function salvarEdicao() {
  const e = editando.value
  limparErrosDeAcao()
  if (!e.nome.trim()) {
    erroEdicao.value = 'Informe o nome.'
    return
  }
  salvandoEdicao.value = true
  try {
    try {
      await pontoApi.atualizarUsuario(e.id, { nome: e.nome.trim(), perfil: e.perfil, ativo: e.ativo })
    } catch (ex) {
      erroEdicao.value = mensagemDe(ex)
      return
    }
    // salvou: o que vem depois (recarregar a lista) não pode parecer falha da gravação
    editando.value = null
    avisar('Usuário atualizado.')
    await carregar()
    auth.carregarTitulares().catch(() => {})
  } finally {
    salvandoEdicao.value = false
  }
}

// ------------------------------------------------------------------ mais opções de cada pessoa
const maisAberto = ref(null) // id da pessoa com "Mais opções" aberto
const pastaAberta = ref(null) // id da pessoa com o editor da pasta aberto

function alternarMais(u) {
  maisAberto.value = maisAberto.value === u.id ? null : u.id
  pastaAberta.value = null
}

async function aoSalvarPasta(u) {
  if (souEu(u)) auth.atualizarUsuario().catch(() => {})
  await carregar()
  clearTimeout(timerPasta)
  timerPasta = setTimeout(carregar, 1500) // o sistema leva um instante para abrir a pasta nova
}

const reativando = ref(null)
async function reativar(u) {
  if (reativando.value) return
  reativando.value = u.id
  try {
    await pontoApi.atualizarUsuario(u.id, { nome: u.nome, perfil: u.perfil, ativo: true })
  } catch (e) {
    avisar(`Não foi possível reativar ${u.nome}. ${mensagemDe(e)}`, 'erro')
    return
  } finally {
    reativando.value = null
  }
  avisar(`${u.nome} pode entrar no sistema de novo.`)
  await carregar()
  auth.carregarTitulares().catch(() => {})
}

// ------------------------------------------------------------------ confirmações (desativar, redefinir senha)
/** { tipo: 'desativar' | 'senha', usuario, erro, ocupado } */
const confirmacao = ref(null)
const botaoCancelar = ref(null)

async function pedirConfirmacao(tipo, u) {
  limparErrosDeAcao()
  confirmacao.value = { tipo, usuario: u, erro: '', ocupado: false }
  await nextTick()
  botaoCancelar.value?.focus()
}
function cancelarConfirmacao() {
  if (!confirmacao.value?.ocupado) confirmacao.value = null
}

async function confirmar() {
  const c = confirmacao.value
  if (!c || c.ocupado) return
  const u = c.usuario
  c.ocupado = true
  c.erro = ''
  try {
    if (c.tipo === 'desativar') {
      await pontoApi.atualizarUsuario(u.id, { nome: u.nome, perfil: u.perfil, ativo: false })
    } else {
      const senha = senhaAleatoria()
      await pontoApi.redefinirSenha(u.id, senha)
      credenciais.value = { nome: u.nome, login: u.login, senha, novo: false }
    }
  } catch (e) {
    c.erro = mensagemDe(e)
    c.ocupado = false
    return
  }
  // deu certo: o que vem depois (recarregar a lista) não pode parecer falha da ação
  confirmacao.value = null
  if (c.tipo === 'desativar') {
    avisar(`Acesso de ${u.nome} desativado: essa pessoa não entra mais no sistema.`, 'info')
    if (editando.value?.id === u.id) editando.value = null
  }
  await carregar()
  auth.carregarTitulares().catch(() => {})
}

function aoTeclar(evento) {
  if (evento.key === 'Escape') cancelarConfirmacao()
}
onMounted(() => window.addEventListener('keydown', aoTeclar))
onBeforeUnmount(() => window.removeEventListener('keydown', aoTeclar))

// --------------------------------------------------------------- navegação
function verDados(u, rota = 'painel') {
  auth.verComo(u.login)
  ponto.trocarPessoa()
  router.push({ name: rota })
}

// ------------------------------------------------------------------- apoio
/** O servidor diz como está a leitura da pasta de cada pessoa; aqui só se escolhe a frase e a cor. */
const PASTA = {
  ATIVO: { ponto: 'bg-positivo-solido', texto: 'chegando da pasta' },
  INDISPONIVEL: { ponto: 'bg-atencao-solido', texto: 'o sistema não consegue abrir a pasta' },
  INICIANDO: { ponto: 'bg-atencao-solido', texto: 'abrindo a pasta…' },
  SEM_PASTA: { ponto: 'bg-texto-4', texto: 'sem pasta (envia pela tela)' },
  DESABILITADO: { ponto: 'bg-texto-4', texto: 'leitura de pastas desligada no servidor' },
}
const ultimoAcesso = (iso) => (iso ? `Último acesso em ${dataBR(dataISO(new Date(iso)))}` : 'Nunca entrou')
</script>

<template>
  <main class="pagina">
    <header class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="titulo-pagina">Usuários</h1>
        <p class="subtitulo-pagina">Quem entra no sistema e o que cada pessoa pode fazer.</p>
      </div>
      <button type="button" class="botao-primario min-h-11" :disabled="novoAberto" @click="abrirNovo">
        <Icone nome="usuario" tamanho="18" /> Novo usuário
      </button>
    </header>

    <!-- Novo usuário -->
    <form v-if="novoAberto && novo" class="cartao grid gap-4 p-5 sm:grid-cols-2 sm:p-6" novalidate aria-labelledby="titulo-novo" @submit.prevent="criar">
      <div class="sm:col-span-2">
        <h2 id="titulo-novo" class="titulo-secao">Novo usuário</h2>
        <p class="mt-1 text-[0.95rem] text-texto-3">Você cria o acesso com uma senha provisória e passa os dados para a pessoa.</p>
      </div>
      <div>
        <label class="rotulo" for="novo-nome">Nome</label>
        <input id="novo-nome" ref="campoNome" v-model="novo.nome" class="campo mt-1.5" placeholder="Maria Souza" autocomplete="off" @blur="sugerirLogin" />
        <p v-if="tentouNovo && errosNovo.nome" class="mt-1 text-sm text-negativo" role="alert">{{ errosNovo.nome }}</p>
      </div>
      <div>
        <label class="rotulo" for="novo-login">Login (o que a pessoa digita para entrar)</label>
        <input id="novo-login" v-model="novo.login" class="campo mt-1.5" placeholder="maria" autocapitalize="off" autocomplete="off" spellcheck="false" />
        <p v-if="tentouNovo && errosNovo.login" class="mt-1 text-sm text-negativo" role="alert">{{ errosNovo.login }}</p>
      </div>
      <fieldset class="sm:col-span-2">
        <legend class="rotulo">Perfil (o que a pessoa pode fazer)</legend>
        <div class="mt-1.5 grid gap-2.5 sm:grid-cols-3">
          <label
            v-for="p in PERFIS"
            :key="p.valor"
            class="flex cursor-pointer flex-col gap-0.5 rounded-xl border-2 px-3.5 py-3 transition-colors"
            :class="novo.perfil === p.valor ? 'border-primaria bg-primaria-suave' : 'border-borda hover:bg-neutro'"
          >
            <span class="flex items-center gap-2 font-bold">
              <input v-model="novo.perfil" type="radio" name="novo-perfil" :value="p.valor" class="size-4 accent-botao" /> {{ p.rotulo }}
            </span>
            <span class="text-sm text-texto-2">{{ p.descricao }}</span>
          </label>
        </div>
      </fieldset>
      <div>
        <label class="rotulo" for="novo-senha">Senha provisória</label>
        <div class="mt-1.5 flex gap-2">
          <input id="novo-senha" v-model="novo.senhaProvisoria" class="campo tracking-wide" spellcheck="false" autocomplete="off" autocapitalize="off" aria-describedby="ajuda-novo-senha" />
          <button type="button" class="botao-secundario shrink-0" @click="novo.senhaProvisoria = senhaAleatoria()">Gerar outra</button>
        </div>
        <p v-if="tentouNovo && errosNovo.senha" class="mt-1 text-sm text-negativo" role="alert">{{ errosNovo.senha }}</p>
        <p id="ajuda-novo-senha" class="mt-1 text-sm text-texto-3">A pessoa escolhe a própria senha na primeira vez que entrar.</p>
      </div>
      <div v-if="novo.perfil !== 'ROLE_VIEWER'">
        <label class="rotulo" for="novo-pasta">Pasta dos comprovantes (se quiser)</label>
        <input id="novo-pasta" v-model="novo.pastaComprovantes" class="campo mt-1.5 font-mono text-[0.95rem]!" placeholder="\\NOME-DO-PC\Ponto" spellcheck="false" autocomplete="off" aria-describedby="ajuda-novo-pasta" />
        <p id="ajuda-novo-pasta" class="mt-1 text-sm text-texto-3">Pode ficar em branco: a pessoa escolhe depois, em “Minha conta”.</p>
      </div>
      <p v-if="novo.perfil !== 'ROLE_VIEWER'" class="text-[0.95rem] text-texto-2 sm:col-span-2">
        A pessoa começa com o horário de trabalho padrão do sistema. Ele pode ser alterado depois, por ela ou por você.
      </p>
      <p v-if="erroNovo" role="alert" class="aviso-erro sm:col-span-2">{{ erroNovo }}</p>
      <div class="flex flex-col-reverse gap-2 sm:col-span-2 sm:flex-row sm:justify-end">
        <button type="button" class="botao-secundario min-h-11" :disabled="salvandoNovo" @click="novoAberto = false">Cancelar</button>
        <button type="submit" class="botao-primario min-h-11" :disabled="salvandoNovo">{{ salvandoNovo ? 'Criando…' : 'Criar usuário' }}</button>
      </div>
    </form>

    <!-- Pessoas -->
    <section class="cartao px-5 pt-5 pb-2 sm:px-6" aria-labelledby="titulo-pessoas">
      <h2 id="titulo-pessoas" class="titulo-secao">
        Pessoas cadastradas
        <span v-if="usuarios.length" class="text-[0.95rem] font-semibold text-texto-3">({{ ativos === usuarios.length ? `${usuarios.length}` : `${ativos} ativas de ${usuarios.length}` }})</span>
      </h2>
      <div class="mt-3">
        <EstadoDaTela
          :carregando="carregando"
          :erro="erroLista"
          :manter="usuarios.length > 0"
          :vazio="!usuarios.length"
          vazio-texto="Nenhum usuário cadastrado."
          carregando-texto="Carregando as pessoas…"
          @tentar="carregar"
        />
      </div>
      <ul v-if="usuarios.length" class="divide-y divide-borda">
        <li v-for="u in usuarios" :key="u.id" class="py-4">
          <div class="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
            <!-- quem é -->
            <div class="min-w-0 flex-1 basis-72">
              <p class="flex flex-wrap items-center gap-x-2.5 gap-y-1.5">
                <b class="text-base" :class="u.ativo ? '' : 'text-texto-3'">{{ u.nome }}</b>
                <span v-if="souEu(u)" class="text-[0.95rem] text-texto-3">(você)</span>
                <span class="selo" :class="u.perfil === 'ROLE_ADMIN' ? 'selo-info' : 'selo-neutro'">{{ rotuloPerfil(u.perfil) }}</span>
                <span class="selo" :class="u.ativo ? 'selo-positivo' : 'selo-neutro'">{{ u.ativo ? 'Ativo' : 'Desativado' }}</span>
                <span v-if="u.ativo && u.trocarSenha" class="selo selo-atencao">Ainda com a senha provisória</span>
              </p>
              <p class="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-0.5 text-[0.95rem] text-texto-2">
                <span>Login <b class="text-texto">{{ u.login }}</b></span>
                <span>{{ ultimoAcesso(u.ultimoLoginEm) }}</span>
              </p>
              <p class="mt-0.5 flex flex-wrap items-center gap-x-1.5 text-[0.95rem] text-texto-2">
                <template v-if="u.titular">
                  Comprovantes:
                  <span class="size-2 shrink-0 rounded-full" :class="PASTA[u.situacaoMonitor]?.ponto ?? 'bg-texto-4'" aria-hidden="true" />
                  {{ PASTA[u.situacaoMonitor]?.texto ?? u.situacaoMonitor ?? 'sem informação' }}
                </template>
                <template v-else>Não registra ponto.</template>
              </p>
              <p v-if="u.mensagemMonitor" class="mt-0.5 text-sm text-negativo">{{ u.mensagemMonitor }}</p>
            </div>

            <!-- o que dá para fazer -->
            <div class="flex flex-wrap items-center gap-2">
              <button v-if="u.titular && u.ativo" type="button" class="botao-linha" @click="verDados(u)">
                <Icone nome="calendario" tamanho="18" /> Ver o ponto
              </button>
              <button type="button" class="botao-linha" :aria-expanded="editando?.id === u.id" @click="editando?.id === u.id ? (editando = null) : editar(u)">
                <Icone nome="lapis" tamanho="18" /> Editar
              </button>
              <button
                v-if="!u.ativo && !souEu(u)"
                type="button"
                class="botao-linha"
                :disabled="reativando === u.id"
                @click="reativar(u)"
              >{{ reativando === u.id ? 'Reativando…' : 'Reativar' }}</button>
              <button
                type="button"
                class="botao-linha gap-1!"
                :aria-expanded="maisAberto === u.id"
                :aria-controls="`mais-${u.id}`"
                :aria-label="`Mais opções para ${u.nome}`"
                @click="alternarMais(u)"
              >
                Mais opções
                <Icone nome="abaixo" tamanho="16" class="transition-transform" :class="{ 'rotate-180': maisAberto === u.id }" />
              </button>
            </div>
          </div>

          <!-- Editar nome e perfil -->
          <form v-if="editando?.id === u.id" class="mt-3 grid gap-3 rounded-xl bg-superficie-2 p-4 sm:grid-cols-[minmax(0,1fr)_16rem_auto] sm:items-end" novalidate @submit.prevent="salvarEdicao">
            <div>
              <label class="rotulo" :for="`nome-${u.id}`">Nome</label>
              <input :id="`nome-${u.id}`" v-model="editando.nome" class="campo mt-1.5" autocomplete="off" />
            </div>
            <div>
              <label class="rotulo" :for="`perfil-${u.id}`">Perfil</label>
              <select :id="`perfil-${u.id}`" v-model="editando.perfil" class="campo mt-1.5" :disabled="souEu(u)" :aria-describedby="souEu(u) ? `perfil-ajuda-${u.id}` : undefined">
                <option v-for="p in PERFIS" :key="p.valor" :value="p.valor">{{ p.rotulo }}</option>
              </select>
            </div>
            <div class="flex gap-2">
              <button type="submit" class="botao-primario min-h-11" :disabled="salvandoEdicao">{{ salvandoEdicao ? 'Salvando…' : 'Salvar' }}</button>
              <button type="button" class="botao-secundario min-h-11" :disabled="salvandoEdicao" @click="editando = null">Cancelar</button>
            </div>
            <p v-if="souEu(u)" :id="`perfil-ajuda-${u.id}`" class="text-sm text-texto-3 sm:col-span-3">Você não pode mudar o seu próprio perfil.</p>
            <p v-if="erroEdicao" role="alert" class="aviso-erro sm:col-span-3">{{ erroEdicao }}</p>
          </form>

          <!-- Mais opções -->
          <div v-if="maisAberto === u.id" :id="`mais-${u.id}`" class="mt-3 flex flex-col gap-3 rounded-xl bg-superficie-2 p-4">
            <div class="flex flex-wrap gap-2">
              <button v-if="u.titular && u.ativo" type="button" class="botao-linha" @click="verDados(u, 'conta')">
                <Icone nome="relogio" tamanho="18" /> Horário e planilha
              </button>
              <button v-if="u.titular && u.ativo" type="button" class="botao-linha" :aria-expanded="pastaAberta === u.id" @click="pastaAberta = pastaAberta === u.id ? null : u.id">
                <Icone nome="pasta" tamanho="18" /> Pasta dos comprovantes
              </button>
              <button v-if="!souEu(u)" type="button" class="botao-linha" @click="pedirConfirmacao('senha', u)">
                <Icone nome="cadeado" tamanho="18" /> Redefinir a senha
              </button>
              <button v-if="!souEu(u) && u.ativo" type="button" class="botao-linha text-negativo!" @click="pedirConfirmacao('desativar', u)">
                Desativar
              </button>
            </div>
            <p v-if="u.titular && u.ativo" class="text-sm text-texto-3">
              “Horário e planilha” abre a conta {{ souEu(u) ? 'que é sua' : `de ${primeiroNome(u.nome)}` }}, onde ficam o horário de trabalho e a planilha no Google.
            </p>
            <div v-if="pastaAberta === u.id && u.titular && u.ativo" class="rounded-xl border border-borda bg-superficie p-4">
              <h3 class="mb-3 font-bold">Pasta dos comprovantes de {{ u.nome }}</h3>
              <EditorPasta
                :pasta="u.pastaComprovantes"
                :monitor="{ situacao: u.situacaoMonitor, mensagem: u.mensagemMonitor }"
                pode-editar
                :usuario-id="souEu(u) ? null : u.id"
                :de-outra-pessoa="!souEu(u)"
                @salvo="aoSalvarPasta(u)"
              />
            </div>
          </div>
        </li>
      </ul>
    </section>

    <IntegracaoGoogle />

    <Teleport to="body">
      <!-- Confirmação antes de desativar ou de redefinir a senha -->
      <div v-if="confirmacao" class="janela-fundo" @mousedown.self="cancelarConfirmacao">
        <section role="alertdialog" aria-modal="true" aria-labelledby="titulo-confirmacao" aria-describedby="texto-confirmacao" class="janela max-w-md px-5 py-5">
          <template v-if="confirmacao.tipo === 'desativar'">
            <h2 id="titulo-confirmacao" class="text-xl font-extrabold tracking-tight">Desativar {{ confirmacao.usuario.nome }}?</h2>
            <p id="texto-confirmacao" class="mt-2 text-[0.95rem] text-texto-2">
              Esta pessoa não entra mais no sistema<template v-if="confirmacao.usuario.titular"> e a pasta de comprovantes dela deixa de ser lida</template>.
              O ponto já registrado fica guardado, e você pode reativar quando quiser.
            </p>
          </template>
          <template v-else>
            <h2 id="titulo-confirmacao" class="text-xl font-extrabold tracking-tight">Redefinir a senha de {{ confirmacao.usuario.nome }}?</h2>
            <p id="texto-confirmacao" class="mt-2 text-[0.95rem] text-texto-2">
              A senha atual deixa de valer agora. O sistema cria uma senha provisória para você passar à pessoa; na
              próxima vez que entrar, ela escolhe uma senha nova.
            </p>
          </template>
          <p v-if="confirmacao.erro" role="alert" class="aviso-erro mt-3">{{ confirmacao.erro }}</p>
          <div class="mt-5 flex flex-wrap justify-end gap-2">
            <button ref="botaoCancelar" type="button" class="botao-secundario min-h-11" :disabled="confirmacao.ocupado" @click="cancelarConfirmacao">Cancelar</button>
            <button type="button" class="botao-perigo min-h-11" :disabled="confirmacao.ocupado" @click="confirmar">
              {{ confirmacao.ocupado ? 'Aguarde…' : confirmacao.tipo === 'desativar' ? 'Sim, desativar' : 'Sim, redefinir a senha' }}
            </button>
          </div>
        </section>
      </div>

      <!-- Dados de acesso para repassar (só fecha no botão: a senha não aparece de novo) -->
      <div v-else-if="credenciais" class="janela-fundo">
        <section role="dialog" aria-modal="true" aria-labelledby="titulo-credenciais" class="janela max-w-md px-5 py-5">
          <h2 id="titulo-credenciais" class="text-xl font-extrabold tracking-tight">
            {{ credenciais.novo ? 'Usuário criado' : 'Senha redefinida' }}
          </h2>
          <p class="mt-2 text-[0.95rem] text-texto-2">Passe estes dados para <b class="text-texto">{{ credenciais.nome }}</b>:</p>
          <dl class="mt-3 grid grid-cols-[auto_minmax(0,1fr)] items-baseline gap-x-4 gap-y-2 rounded-xl bg-superficie-2 px-4 py-3">
            <dt class="text-sm text-texto-3">Login</dt>
            <dd class="text-lg font-bold break-all select-all">{{ credenciais.login }}</dd>
            <dt class="text-sm text-texto-3">Senha provisória</dt>
            <dd class="text-lg font-bold tracking-wide break-all select-all">{{ credenciais.senha }}</dd>
          </dl>
          <p class="aviso-atencao mt-3">
            Esta senha não aparece de novo. Na primeira vez que entrar, a pessoa escolhe a própria senha.
          </p>
          <p v-if="naoCopiou" role="alert" class="aviso-erro mt-3">Não foi possível copiar: selecione o login e a senha acima e copie.</p>
          <div class="mt-5 flex flex-wrap justify-end gap-2">
            <button type="button" class="botao-secundario min-h-11" @click="fecharCredenciais">Já passei os dados, fechar</button>
            <button ref="botaoCopiar" type="button" class="botao-primario min-h-11" @click="copiarCredenciais">Copiar os dados</button>
          </div>
        </section>
      </div>
    </Teleport>
  </main>
</template>
