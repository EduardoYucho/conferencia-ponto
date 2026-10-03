<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import EditorHorario from '@/components/EditorHorario.vue'
import EditorPasta from '@/components/EditorPasta.vue'
import EditorPlanilha from '@/components/EditorPlanilha.vue'
import EnvioComprovantes from '@/components/EnvioComprovantes.vue'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'
import { avisar } from '@/utils/avisar'
import { mensagemDe } from '@/utils/erros'
import { momento } from '@/utils/horas'
import { TEMAS, definirTema, escuro, tema } from '@/utils/tema'
import { dataISO } from '@/utils/tempo'

/**
 * Minha conta: a aparência da tela e a senha de quem está usando; e, da pessoa em tela, o horário de trabalho,
 * a pasta dos comprovantes e a planilha no Google. O administrador consultando outra pessoa ("Dados de") vê e
 * altera o horário, a pasta e a planilha dela por aqui; a coordenação só consulta.
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const route = useRoute()

const pessoa = computed(() => auth.pessoaEmTela)
/** Horário, pasta e planilha: da pessoa em tela, se ela tem dados de ponto. */
const temPonto = computed(() => auth.ehTitular || !auth.vendoOsProprios)
const outraPessoa = computed(() => !auth.vendoOsProprios)
/** O próprio titular, ou o administrador alterando outra pessoa. */
const podeEditar = computed(() => (auth.vendoOsProprios ? auth.ehTitular : auth.ehAdmin))
/**
 * A página é a conta de quem está usando (com aparência e senha). Só deixa de ser quando o administrador abre a
 * conta de outra pessoa: aí a página inteira é dela.
 */
const minhaPagina = computed(() => auth.vendoOsProprios || !auth.ehAdmin)
const perfil = computed(() => (auth.ehAdmin ? 'Administrador' : auth.ehTitular ? 'Usuário' : 'Coordenação (só consulta)'))
const primeiroNome = computed(() => (pessoa.value?.nome ?? '').trim().split(/\s+/)[0])

const vigencias = ref([])
const pasta = ref(null)
const monitor = ref(null)
const recentes = ref([])
const carregando = ref(false)
/** O horário e a pasta já chegaram (antes disso não dá para dizer "nenhuma pasta": é desconhecido). */
const carregado = ref(false)
const erro = ref(null)
const recentesAbertos = ref(false)
const hoje = computed(() => ponto.hoje ?? dataISO())
let timerRecarga = null
onBeforeUnmount(() => clearTimeout(timerRecarga))

async function carregar() {
  if (!temPonto.value) return
  carregando.value = true
  erro.value = null
  try {
    const [h, imp] = await Promise.all([pontoApi.horarios(), pontoApi.importacoes(8)])
    vigencias.value = h
    const { recentes: lista, ...estado } = imp
    monitor.value = estado
    recentes.value = lista
    if (!outraPessoa.value) {
      pasta.value = auth.usuario?.pastaComprovantes ?? null
    } else if (auth.ehAdmin) {
      const pessoas = await pontoApi.usuarios()
      pasta.value = pessoas.find((u) => u.login === pessoa.value?.login)?.pastaComprovantes ?? null
    } else {
      pasta.value = estado.situacao === 'SEM_PASTA' ? null : estado.diretorio
    }
    carregado.value = true
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(async () => {
  await carregar()
  // veio por um link para uma seção (ex.: #planilha): o que carregou acima empurrou a seção para baixo
  if (route.hash) {
    await nextTick()
    document.querySelector(route.hash)?.scrollIntoView({ block: 'start' })
  }
})

// ------------------------------------------------------------------ aparência
const ICONES_TEMA = { claro: 'sol', escuro: 'lua', auto: 'monitor' }
const descricaoTema = computed(() => ({
  claro: 'Fundo claro.',
  escuro: 'Fundo escuro, descansa a vista.',
  auto: `Acompanha o tema do seu computador (agora: ${escuro.value ? 'escuro' : 'claro'}).`,
}))

// ------------------------------------------------------------------ senha
const senhaAberta = ref(false)
const senha = ref({ atual: '', nova: '', confirmacao: '' })
const erroSenha = ref('')
const salvandoSenha = ref(false)

function fecharSenha() {
  senhaAberta.value = false
  senha.value = { atual: '', nova: '', confirmacao: '' }
  erroSenha.value = ''
}

async function trocarSenha() {
  const s = senha.value
  // ajuda de digitação; quem decide se a senha serve é o servidor
  erroSenha.value = !s.atual ? 'Informe a senha atual.'
    : s.nova.length < 8 ? 'A nova senha precisa ter pelo menos 8 caracteres.'
      : s.nova !== s.confirmacao ? 'As duas digitações da nova senha estão diferentes.' : ''
  if (erroSenha.value) return
  salvandoSenha.value = true
  try {
    await auth.alterarSenha(s.atual, s.nova)
    fecharSenha()
    avisar('Senha alterada.')
  } catch (e) {
    erroSenha.value = mensagemDe(e)
  } finally {
    salvandoSenha.value = false
  }
}

// ---------------------------------------------------------- pasta e horário
async function aoSalvarPasta(r) {
  pasta.value = r.pasta
  if (!outraPessoa.value) auth.atualizarUsuario().catch(() => {})
  clearTimeout(timerRecarga)
  timerRecarga = setTimeout(carregar, 1500) // o sistema leva um instante para abrir a pasta nova
}

function aoSalvarHorario(r) {
  vigencias.value = r.vigencias
  // o horário mudou (e dias podem ter sido recalculados): o que é comum às telas é lido de novo
  ponto.recarregarConfiguracao()
  ponto.atualizarSaldos({ silencioso: true })
  ponto.sinalizarMudanca({ tipo: 'jornada' })
}

function aoEnviar({ total, importados }) {
  avisar(total === 1
    ? (importados ? 'O comprovante virou batida.' : 'O comprovante não virou batida: veja o motivo na lista.')
    : `${importados} de ${total} comprovantes viraram batida.`, importados ? 'ok' : 'info')
  carregar()
}

/** O servidor diz o que aconteceu com cada comprovante; aqui só se escolhe a etiqueta. */
const SITUACAO_COMPROVANTE = {
  IMPORTADO: { texto: 'Virou batida', selo: 'selo-positivo' },
  DUPLICADO: { texto: 'Já existia', selo: 'selo-neutro' },
  REJEITADO: { texto: 'Recusado', selo: 'selo-negativo' },
  INVALIDO: { texto: 'Arquivo inválido', selo: 'selo-negativo' },
}
</script>

<template>
  <main class="pagina">
    <header>
      <h1 class="titulo-pagina">{{ minhaPagina ? 'Minha conta' : `Conta de ${pessoa?.nome ?? '…'}` }}</h1>
      <p v-if="!minhaPagina" class="subtitulo-pagina">
        O horário de trabalho, a pasta dos comprovantes e a planilha de {{ primeiroNome }}. O ponto desta pessoa é só para
        consulta, mas estes três itens você, como administrador, pode alterar aqui.
      </p>
      <p v-else-if="temPonto && !outraPessoa" class="subtitulo-pagina">
        A aparência da tela, a sua senha, o seu horário de trabalho e de onde chegam os seus comprovantes.
      </p>
      <p v-else class="subtitulo-pagina">A aparência da tela e a sua senha.</p>
      <p v-if="minhaPagina" class="mt-3 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[0.95rem] text-texto-2">
        <b class="text-texto">{{ auth.usuario?.nome }}</b>
        <span>login <b class="text-texto">{{ auth.usuario?.login }}</b></span>
        <span class="selo" :class="auth.ehAdmin ? 'selo-info' : 'selo-neutro'">{{ perfil }}</span>
      </p>
    </header>

    <!-- Aparência -->
    <section v-if="minhaPagina" class="cartao p-5 sm:p-6" aria-labelledby="titulo-aparencia">
      <h2 id="titulo-aparencia" class="titulo-secao">Aparência</h2>
      <p class="mt-1 text-[0.95rem] text-texto-3">Escolha como a tela fica melhor para você. A escolha vale para este navegador.</p>
      <div class="mt-4 grid gap-3 sm:grid-cols-3" role="group" aria-labelledby="titulo-aparencia">
        <button
          v-for="t in TEMAS"
          :key="t.valor"
          type="button"
          class="flex min-h-[4.75rem] items-center gap-3 rounded-xl border-2 px-4 py-3 text-left transition-colors"
          :class="tema === t.valor ? 'border-primaria bg-primaria-suave' : 'border-borda bg-superficie hover:bg-neutro'"
          :aria-pressed="tema === t.valor"
          @click="definirTema(t.valor)"
        >
          <span
            class="grid size-11 shrink-0 place-items-center rounded-full"
            :class="tema === t.valor ? 'bg-botao text-sobre-botao' : 'bg-neutro text-texto-2'"
          ><Icone :nome="ICONES_TEMA[t.valor]" tamanho="22" /></span>
          <span class="min-w-0 flex-1">
            <span class="flex flex-wrap items-center gap-x-2 text-base font-bold">
              {{ t.rotulo }}
              <span v-if="tema === t.valor" class="flex items-center gap-1 text-sm text-primaria"><Icone nome="certo" tamanho="16" /> em uso</span>
            </span>
            <span class="block text-sm text-texto-2">{{ descricaoTema[t.valor] }}</span>
          </span>
        </button>
      </div>
    </section>

    <!-- Senha -->
    <section v-if="minhaPagina" class="cartao p-5 sm:p-6" aria-labelledby="titulo-senha">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 id="titulo-senha" class="titulo-secao">Senha</h2>
          <p class="mt-1 text-[0.95rem] text-texto-3">A senha que você usa para entrar no sistema.</p>
        </div>
        <button v-if="!senhaAberta" type="button" class="botao-secundario min-h-11" @click="senhaAberta = true">
          <Icone nome="cadeado" tamanho="18" /> Trocar a senha
        </button>
      </div>
      <form v-if="senhaAberta" class="mt-4 grid gap-4 sm:grid-cols-3" novalidate @submit.prevent="trocarSenha">
        <div>
          <label class="rotulo" for="senha-atual">Senha atual</label>
          <input id="senha-atual" v-model="senha.atual" type="password" class="campo mt-1.5" autocomplete="current-password" />
        </div>
        <div>
          <label class="rotulo" for="senha-nova">Nova senha</label>
          <input id="senha-nova" v-model="senha.nova" type="password" class="campo mt-1.5" autocomplete="new-password" aria-describedby="ajuda-senha" />
          <p id="ajuda-senha" class="mt-1 text-sm text-texto-3">Pelo menos 8 caracteres.</p>
        </div>
        <div>
          <label class="rotulo" for="senha-confirmacao">Repita a nova senha</label>
          <input id="senha-confirmacao" v-model="senha.confirmacao" type="password" class="campo mt-1.5" autocomplete="new-password" />
        </div>
        <p v-if="erroSenha" role="alert" class="aviso-erro sm:col-span-3">{{ erroSenha }}</p>
        <div class="flex flex-col-reverse gap-2 sm:col-span-3 sm:flex-row sm:justify-end">
          <button type="button" class="botao-secundario min-h-11" :disabled="salvandoSenha" @click="fecharSenha">Cancelar</button>
          <button type="submit" class="botao-primario min-h-11" :disabled="salvandoSenha">{{ salvandoSenha ? 'Salvando…' : 'Salvar a nova senha' }}</button>
        </div>
      </form>
    </section>

    <!-- A coordenação não tem ponto próprio: daqui para baixo é a pessoa escolhida em "Dados de" -->
    <div v-if="minhaPagina && outraPessoa && temPonto" class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1">
      <h2 class="text-xl font-extrabold tracking-tight">Dados de {{ pessoa?.nome ?? '…' }}</h2>
      <span class="selo selo-atencao">Somente consulta</span>
      <p class="w-full text-[0.95rem] text-texto-3">O horário, a pasta dos comprovantes e a planilha da pessoa escolhida em “Dados de”, lá em cima.</p>
    </div>

    <!-- Falha ao carregar o horário e a pasta (numa recarga, o que já estava na tela continua) -->
    <EstadoDaTela v-if="temPonto && erro" :erro="erro" :carregando="carregando" manter @tentar="carregar" />

    <!-- Horário -->
    <section v-if="temPonto" class="cartao p-5 sm:p-6" aria-labelledby="titulo-horario">
      <h2 id="titulo-horario" class="titulo-secao">Horário de trabalho</h2>
      <p class="mt-1 mb-4 text-[0.95rem] text-texto-3">
        O previsto para cada dia: é com ele que o sistema calcula o que fica a favor ou devendo. Em dia sem
        expediente, todo o tempo trabalhado fica a favor.
      </p>
      <EstadoDaTela v-if="!carregado" :carregando="carregando" vazio vazio-texto="O horário não foi carregado." carregando-texto="Carregando o horário…" />
      <EditorHorario
        v-else
        :vigencias="vigencias"
        :hoje="hoje"
        :pode-editar="podeEditar"
        :usuario="outraPessoa ? pessoa?.login : null"
        @salvo="aoSalvarHorario"
      />
    </section>

    <!-- Comprovantes -->
    <section v-if="temPonto" class="cartao p-5 sm:p-6" aria-labelledby="titulo-pasta">
      <h2 id="titulo-pasta" class="titulo-secao">Comprovantes de ponto</h2>
      <p class="mt-1 mb-4 text-[0.95rem] text-texto-3">
        Cada comprovante em PDF vira uma batida. {{ podeEditar
          ? (outraPessoa ? 'Escolha a pasta onde os PDFs desta pessoa chegam.' : 'Escolha a pasta onde os PDFs chegam (a de downloads do navegador, por exemplo) ou envie-os pela tela.')
          : 'Aqui aparece de onde eles chegam.' }}
      </p>
      <EstadoDaTela v-if="!carregado" :carregando="carregando" vazio vazio-texto="A pasta não foi carregada." carregando-texto="Carregando a pasta…" />
      <EditorPasta
        v-else
        :pasta="pasta"
        :monitor="monitor"
        :pode-editar="podeEditar"
        :usuario-id="outraPessoa ? pessoa?.id : null"
        :de-outra-pessoa="outraPessoa"
        @salvo="aoSalvarPasta"
      />

      <div v-if="!outraPessoa && auth.ehTitular" class="mt-5 border-t border-borda pt-4">
        <h3 class="font-bold">Enviar pela tela</h3>
        <p class="mt-0.5 mb-3 text-[0.95rem] text-texto-3">Para mandar um comprovante agora, sem depender da pasta.</p>
        <EnvioComprovantes compacto @enviados="aoEnviar" />
      </div>

      <div v-if="recentes.length" class="mt-5 border-t border-borda">
        <button
          type="button"
          class="flex min-h-12 w-full items-center justify-between gap-3 text-left"
          :aria-expanded="recentesAbertos"
          aria-controls="ultimos-comprovantes"
          @click="recentesAbertos = !recentesAbertos"
        >
          <span class="font-bold">Últimos comprovantes recebidos <span class="font-semibold text-texto-3">({{ recentes.length }})</span></span>
          <span class="flex items-center gap-1 text-[0.95rem] font-semibold text-primaria">
            {{ recentesAbertos ? 'Esconder' : 'Mostrar' }}
            <Icone nome="abaixo" tamanho="18" class="transition-transform" :class="{ 'rotate-180': recentesAbertos }" />
          </span>
        </button>
        <ul v-if="recentesAbertos" id="ultimos-comprovantes" class="divide-y divide-borda border-t border-borda">
          <li v-for="(c, i) in recentes" :key="i" class="flex flex-wrap items-center gap-x-3 gap-y-1 py-2.5">
            <span class="selo" :class="SITUACAO_COMPROVANTE[c.status]?.selo ?? 'selo-neutro'">{{ SITUACAO_COMPROVANTE[c.status]?.texto ?? c.status }}</span>
            <span class="min-w-0 flex-1 basis-56 font-mono text-sm break-all">{{ c.nomeArquivo }}</span>
            <span class="text-sm text-texto-3">{{ momento(c.processadoEm) }}</span>
            <span v-if="c.mensagem && c.status !== 'IMPORTADO'" class="w-full text-sm text-texto-2">{{ c.mensagem }}</span>
          </li>
        </ul>
      </div>
    </section>

    <!-- Planilha no Google (outras telas mandam para cá com #planilha) -->
    <section v-if="temPonto" id="planilha" class="cartao scroll-mt-20 p-5 sm:p-6" aria-labelledby="titulo-planilha">
      <h2 id="titulo-planilha" class="titulo-secao">Planilha no Google</h2>
      <p class="mt-1 mb-4 text-[0.95rem] text-texto-3">
        O ponto {{ outraPessoa ? 'desta pessoa' : 'que você vê aqui' }}, mês a mês, numa planilha do Google que o sistema mantém
        atualizada. Serve para mostrar a quem confere.
      </p>
      <EditorPlanilha :pode-editar="podeEditar" :usuario="outraPessoa ? pessoa?.login : null" />
    </section>

    <!-- O administrador na conta de outra pessoa: a aparência e a senha ficam na conta dele -->
    <p v-if="!minhaPagina" class="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-borda bg-superficie-2 px-5 py-4 text-[0.95rem] text-texto-2">
      <span>A aparência da tela e a sua senha ficam na sua própria conta.</span>
      <button type="button" class="botao-secundario min-h-11" @click="auth.verComo(null)">Voltar para a minha conta</button>
    </p>
  </main>
</template>
