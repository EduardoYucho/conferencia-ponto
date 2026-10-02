<script setup>
import { computed, onMounted, ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import EditorHorario from '@/components/EditorHorario.vue'
import EditorPasta from '@/components/EditorPasta.vue'
import EditorPlanilha from '@/components/EditorPlanilha.vue'
import EnvioComprovantes from '@/components/EnvioComprovantes.vue'
import { dataBR, dataISO } from '@/utils/tempo'

/**
 * Minha conta: senha, pasta dos comprovantes, horário de trabalho e planilha no Google. O administrador
 * consultando outra pessoa ("Dados de") vê e altera a pasta, o horário e a planilha dela por aqui; a senha é
 * sempre a do próprio usuário.
 */
const auth = useAuthStore()
const ponto = usePontoStore()

const pessoa = computed(() => auth.pessoaEmTela)
/** Pasta e horário: da pessoa em tela, se ela tem dados de ponto. */
const temPonto = computed(() => auth.ehTitular || !auth.vendoOsProprios)
const outraPessoa = computed(() => !auth.vendoOsProprios)
/** O próprio titular, ou o administrador alterando outra pessoa. */
const podeEditar = computed(() => (auth.vendoOsProprios ? auth.ehTitular : auth.ehAdmin))

const vigencias = ref([])
const pasta = ref(null)
const monitor = ref(null)
const recentes = ref([])
const carregando = ref(false)
const erro = ref('')
const aviso = ref('')
const hoje = computed(() => ponto.hoje ?? dataISO())

async function carregar() {
  if (!temPonto.value) return
  carregando.value = true
  erro.value = ''
  try {
    const [h, imp] = await Promise.all([pontoApi.horarios(), pontoApi.importacoes(8)])
    vigencias.value = h
    const { recentes: lista, ...estado } = imp
    monitor.value = estado
    recentes.value = lista
    if (!outraPessoa.value) {
      pasta.value = auth.usuario?.pastaComprovantes ?? null
    } else if (auth.ehAdmin) {
      const lista = await pontoApi.usuarios()
      pasta.value = lista.find((u) => u.login === pessoa.value?.login)?.pastaComprovantes ?? null
    } else {
      pasta.value = estado.situacao === 'SEM_PASTA' ? null : estado.diretorio
    }
  } catch (e) {
    erro.value = e.message
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)

// ------------------------------------------------------------------ senha
const senha = ref({ atual: '', nova: '', confirmacao: '' })
const erroSenha = ref('')
const salvandoSenha = ref(false)

async function trocarSenha() {
  const s = senha.value
  erroSenha.value = !s.atual ? 'Informe a senha atual.'
    : s.nova.length < 8 ? 'A nova senha precisa ter pelo menos 8 caracteres.'
      : s.nova !== s.confirmacao ? 'A confirmação não confere com a nova senha.' : ''
  if (erroSenha.value) return
  salvandoSenha.value = true
  try {
    await auth.alterarSenha(s.atual, s.nova)
    senha.value = { atual: '', nova: '', confirmacao: '' }
    mostrar('Senha alterada.')
  } catch (e) {
    erroSenha.value = e.message
  } finally {
    salvandoSenha.value = false
  }
}

// ---------------------------------------------------------- pasta e horário
async function aoSalvarPasta(r) {
  pasta.value = r.pasta
  if (!outraPessoa.value) auth.atualizarUsuario().catch(() => {})
  setTimeout(carregar, 1500) // o monitor leva um instante para conectar à pasta nova
}

function aoSalvarHorario(r) {
  vigencias.value = r.vigencias
  mostrar(r.diasRecalculados
    ? `Horário salvo · ${r.diasRecalculados} dia(s) já registrado(s) recalculado(s).`
    : 'Horário salvo.')
  ponto.recarregarConfiguracao()
}

function aoEnviar({ total, importados }) {
  mostrar(`${importados} de ${total} comprovante(s) importado(s).`)
  carregar()
}

let timer = null
function mostrar(texto) {
  aviso.value = texto
  clearTimeout(timer)
  timer = setTimeout(() => (aviso.value = ''), 5000)
}

const ROTULO_STATUS = { IMPORTADO: 'importado', DUPLICADO: 'já existia', REJEITADO: 'recusado', INVALIDO: 'inválido' }
const quando = (iso) => {
  const d = new Date(iso)
  return `${dataBR(dataISO(d))} ${d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}`
}
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <p class="rotulo">{{ outraPessoa ? 'Administração' : auth.rotuloPerfil }}</p>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">
        {{ outraPessoa ? `Conta de ${pessoa?.nome}` : 'Minha conta' }}
      </h1>
      <p v-if="!outraPessoa" class="mt-2 text-sm text-tinta-suave">
        {{ auth.usuario?.nome }} · login <b class="carimbo">{{ auth.usuario?.login }}</b>
      </p>
    </header>

    <p v-if="erro" role="alert" class="mt-6 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erro }}</p>

    <!-- Horário -->
    <section v-if="temPonto" class="cartao mt-6 px-5 py-5" aria-labelledby="titulo-horario">
      <h2 id="titulo-horario" class="font-sans text-lg font-bold">Horário de trabalho</h2>
      <p class="mt-1 mb-4 text-sm text-tinta-suave">
        A base do cálculo: o que é previsto em cada dia e onde vale a tolerância. Dia sem expediente
        (como sábado e domingo) conta todo o tempo trabalhado como crédito.
      </p>
      <p v-if="carregando && !vigencias.length" class="text-sm text-tinta-suave">Carregando…</p>
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
    <section v-if="temPonto" class="cartao mt-6 px-5 py-5" aria-labelledby="titulo-pasta">
      <h2 id="titulo-pasta" class="font-sans text-lg font-bold">Comprovantes de ponto</h2>
      <p class="mt-1 mb-4 text-sm text-tinta-suave">
        Os PDFs dos comprovantes viram batidas automaticamente. Escolha a pasta onde eles chegam
        {{ outraPessoa ? '' : '(a mesma onde o navegador salva os downloads, por exemplo)' }} e/ou envie-os pela tela.
      </p>
      <EditorPasta
        :pasta="pasta"
        :monitor="monitor"
        :pode-editar="podeEditar"
        :usuario-id="outraPessoa ? pessoa?.id : null"
        @salvo="aoSalvarPasta"
      />
      <div v-if="!outraPessoa && auth.ehTitular" class="mt-6">
        <h3 class="rotulo mb-2">Enviar pela tela</h3>
        <EnvioComprovantes compacto @enviados="aoEnviar" />
      </div>
      <div v-if="recentes.length" class="mt-6">
        <h3 class="rotulo mb-2">Últimos comprovantes processados</h3>
        <ul class="divide-y divide-linha/70 text-sm">
          <li v-for="(c, i) in recentes" :key="i" class="flex flex-wrap items-center gap-x-3 gap-y-0.5 py-1.5">
            <span class="carimbo w-32 text-xs text-tinta-suave">{{ quando(c.processadoEm) }}</span>
            <span class="w-20 text-xs font-semibold" :class="c.status === 'IMPORTADO' ? 'text-credito' : c.status === 'DUPLICADO' ? 'text-tinta-suave' : 'text-carimbo'">
              {{ ROTULO_STATUS[c.status] ?? c.status }}
            </span>
            <span class="min-w-0 flex-1 truncate" :title="c.mensagem">{{ c.nomeArquivo }}</span>
          </li>
        </ul>
      </div>
    </section>

    <!-- Planilha no Google -->
    <section v-if="temPonto" id="planilha" class="cartao mt-6 scroll-mt-6 px-5 py-5" aria-labelledby="titulo-planilha">
      <h2 id="titulo-planilha" class="font-sans text-lg font-bold">Planilha no Google</h2>
      <p class="mt-1 mb-4 text-sm text-tinta-suave">
        A conferência {{ outraPessoa ? 'desta pessoa' : 'do seu ponto' }} numa planilha do Google Sheets, mês a mês,
        com o total de horas e o banco de horas, sempre atualizada. Serve para compartilhar com quem confere.
      </p>
      <EditorPlanilha :pode-editar="podeEditar" :usuario="outraPessoa ? pessoa?.login : null" />
    </section>

    <!-- Senha -->
    <section v-if="!outraPessoa" class="cartao mt-6 px-5 py-5" aria-labelledby="titulo-senha">
      <h2 id="titulo-senha" class="font-sans text-lg font-bold">Senha</h2>
      <form class="mt-4 grid gap-4 sm:grid-cols-3" novalidate @submit.prevent="trocarSenha">
        <label class="block">
          <span class="rotulo">Senha atual</span>
          <input v-model="senha.atual" type="password" class="campo mt-1.5" autocomplete="current-password" />
        </label>
        <label class="block">
          <span class="rotulo">Nova senha</span>
          <input v-model="senha.nova" type="password" class="campo mt-1.5" autocomplete="new-password" />
        </label>
        <label class="block">
          <span class="rotulo">Repita a nova senha</span>
          <input v-model="senha.confirmacao" type="password" class="campo mt-1.5" autocomplete="new-password" />
        </label>
        <p v-if="erroSenha" role="alert" class="text-sm text-carimbo sm:col-span-3">{{ erroSenha }}</p>
        <div class="sm:col-span-3 sm:text-right">
          <button type="submit" class="botao-primario" :disabled="salvandoSenha">{{ salvandoSenha ? 'Salvando…' : 'Alterar senha' }}</button>
        </div>
      </form>
    </section>

    <Transition
      enter-active-class="transition duration-200"
      enter-from-class="translate-y-3 opacity-0"
      leave-active-class="transition duration-150"
      leave-to-class="opacity-0"
    >
      <p
        v-if="aviso"
        role="status"
        class="fixed inset-x-4 bottom-6 z-50 mx-auto max-w-md rounded-[3px] bg-tinta px-4 py-3 text-center text-sm text-cartao shadow-lg"
      >{{ aviso }}</p>
    </Transition>
  </div>
</template>
