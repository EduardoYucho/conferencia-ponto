<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { conhecimentoApi } from '@/modulos/conhecimento/api'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { useAuthStore } from '@/stores/auth'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { dataBR, dataISO } from '@/utils/tempo'
import { mensagemDe } from '@/utils/erros'
import { tamanho } from '@/modulos/atendimento/formato'

/**
 * Quem usa o gerador de atendimentos e a base de conhecimento (administrador). Vale pessoa por pessoa e não
 * depende do perfil do ponto. Cada caixa salva na hora; se o servidor recusar, a caixa volta ao que era e o
 * motivo aparece na linha da pessoa.
 */
const auth = useAuthStore()
const modulos = useAcessoModulosStore()

const PERFIS = { ROLE_ADMIN: 'Administrador', ROLE_USER: 'Usuário', ROLE_VIEWER: 'Coordenação' }

/** { usuarioId, nome, login, perfis, gerador, pesquisar, curar, ultima: { por, em } | null } */
const linhas = ref([])
const carregando = ref(false)
const erro = ref(null)
/** usuarioId -> true enquanto a mudança daquela pessoa está sendo gravada */
const salvando = ref({})
/** { usuarioId, mensagem }: a última mudança recusada, mostrada na linha da pessoa */
const falha = ref(null)
const aviso = ref('')
let timerAviso = null
onBeforeUnmount(() => clearTimeout(timerAviso))

async function carregar() {
  carregando.value = true
  erro.value = null
  try {
    const [doGerador, daBase] = await Promise.all([atendimentoApi.acessos(), conhecimentoApi.acessos()])
    const basePorUsuario = new Map(daBase.map((b) => [b.usuarioId, b]))
    linhas.value = doGerador.map((g) => {
      const b = basePorUsuario.get(g.usuarioId) ?? {}
      return {
        usuarioId: g.usuarioId,
        nome: g.nome,
        login: g.login,
        perfis: g.perfis ?? [],
        gerador: g.gerador,
        pesquisar: !!b.pesquisar,
        curar: !!b.curar,
        ultima: maisRecente(g, b),
      }
    })
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)

/** O espaço em disco dos atendimentos vem à parte: se falhar, a tabela de acessos continua de pé. */
const espaco = ref(null)
const erroDoEspaco = ref('')
async function carregarEspaco() {
  erroDoEspaco.value = ''
  try {
    espaco.value = await atendimentoApi.espaco()
  } catch (e) {
    espaco.value = null
    erroDoEspaco.value = mensagemDe(e)
  }
}
onMounted(carregarEspaco)

function maisRecente(...mudancas) {
  const comData = mudancas.filter((m) => m?.atualizadoEm).sort((a, b) => (a.atualizadoEm < b.atualizadoEm ? 1 : -1))
  return comData.length ? { por: comData[0].concedidoPor, em: comData[0].atualizadoEm } : null
}

async function mudarGerador(linha, valor) {
  const antes = linha.gerador
  linha.gerador = valor
  await salvar(linha, async () => {
    const r = await atendimentoApi.definirAcesso(linha.usuarioId, valor)
    linha.gerador = r.gerador
    linha.ultima = { por: r.concedidoPor, em: r.atualizadoEm }
  }, () => (linha.gerador = antes))
}

/**
 * Curar inclui pesquisar: marcar "curar" marca "pesquisar"; desmarcar "pesquisar" desmarca "curar". Por isso
 * importa qual das duas caixas a pessoa mexeu.
 */
async function mudarBase(linha, caixa, marcada) {
  const antes = { pesquisar: linha.pesquisar, curar: linha.curar }
  if (caixa === 'curar') {
    linha.curar = marcada
    if (marcada) linha.pesquisar = true
  } else {
    linha.pesquisar = marcada
    if (!marcada) linha.curar = false
  }
  await salvar(linha, async () => {
    const r = await conhecimentoApi.definirAcesso(linha.usuarioId, linha.pesquisar, linha.curar)
    linha.pesquisar = r.pesquisar
    linha.curar = r.curar
    linha.ultima = { por: r.concedidoPor, em: r.atualizadoEm }
  }, () => Object.assign(linha, antes))
}

async function salvar(linha, acao, desfazer) {
  falha.value = null
  salvando.value = { ...salvando.value, [linha.usuarioId]: true }
  try {
    await acao()
    mostrar(`Acesso de ${linha.nome} atualizado.`)
    // o próprio administrador: o menu passa a mostrar (ou esconder) os módulos na hora
    if (linha.usuarioId === auth.usuario?.id) await modulos.carregar()
  } catch (e) {
    desfazer()
    falha.value = { usuarioId: linha.usuarioId, mensagem: mensagemDe(e) }
  } finally {
    const resto = { ...salvando.value }
    delete resto[linha.usuarioId]
    salvando.value = resto
  }
}

function mostrar(texto) {
  aviso.value = texto
  clearTimeout(timerAviso)
  timerAviso = setTimeout(() => (aviso.value = ''), 4000)
}

const perfil = (linha) => linha.perfis.map((p) => PERFIS[p] ?? p).join(', ') || '—'
const ultima = (linha) =>
  linha.ultima ? `${dataBR(dataISO(new Date(linha.ultima.em)))} · ${linha.ultima.por ?? '—'}` : 'nunca liberado'
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <p class="rotulo">Administração · atendimentos e base de conhecimento</p>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Acessos</h1>
    </header>

    <p class="mt-4 max-w-3xl text-sm text-tinta-suave">
      Quem usa o <b class="text-tinta">gerador de atendimentos</b> e a <b class="text-tinta">base de conhecimento</b>.
      Vale pessoa por pessoa e não depende do perfil do ponto: a coordenação também pode ser liberada, e você
      também precisa se liberar para usar. <b class="text-tinta">Curar</b> é editar qualquer registro da base e mudar
      a situação no desenvolvimento (inclui pesquisar).
    </p>

    <section class="mt-4 max-w-3xl text-sm" aria-label="Espaço em disco dos atendimentos">
      <p v-if="espaco" class="text-tinta-suave">
        Arquivos dos atendimentos:
        <b class="text-tinta">{{ espaco.ocupado >= 0 ? tamanho(espaco.ocupado) : 'não deu para somar' }}</b> ocupados<template v-if="espaco.livre >= 0">
          · <b class="text-tinta">{{ tamanho(espaco.livre) }}</b> livres no disco</template>.
      </p>
      <p v-if="espaco?.pausados" role="alert" class="mt-1 font-semibold text-carimbo">
        {{ espaco.pausados }} atendimento(s) pausado(s) por falta de espaço. Libere espaço no disco; cada pessoa retoma o seu
        no próprio atendimento.
      </p>
      <p v-else-if="erroDoEspaco" class="text-carimbo">
        Não deu para ver o espaço em disco: {{ erroDoEspaco }}
        <button type="button" class="ml-1 font-semibold underline underline-offset-4" @click="carregarEspaco">Tentar de novo</button>
      </p>
    </section>

    <p v-if="aviso" role="status" class="mt-3 text-sm font-semibold text-credito">{{ aviso }}</p>

    <div class="mt-4">
      <EstadoDaTela
        :carregando="carregando"
        :erro="erro"
        :vazio="!linhas.length"
        vazio-texto="Nenhum usuário ativo."
        :manter="linhas.length > 0"
        @tentar="carregar"
      >
        <section class="cartao overflow-x-auto" aria-label="Acessos por pessoa">
          <table class="w-full min-w-[46rem] text-sm">
            <thead>
              <tr class="border-b-2 border-tinta text-left">
                <th class="rotulo px-4 py-2.5">Pessoa</th>
                <th class="rotulo py-2.5">Perfil no ponto</th>
                <th class="rotulo px-2 py-2.5 text-center">Gerador</th>
                <th class="rotulo px-2 py-2.5 text-center">Base: pesquisar</th>
                <th class="rotulo px-2 py-2.5 text-center">Base: curar</th>
                <th class="rotulo py-2.5 pr-4">Última mudança</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="l in linhas" :key="l.usuarioId">
                <tr class="border-b border-linha/70">
                  <td class="px-4 py-2.5">
                    <span class="block font-semibold">
                      {{ l.nome }}<span v-if="l.usuarioId === auth.usuario?.id" class="ml-1 text-xs font-normal text-tinta-suave">(você)</span>
                    </span>
                    <span class="carimbo text-xs text-tinta-suave">{{ l.login }}</span>
                  </td>
                  <td class="py-2.5">{{ perfil(l) }}</td>
                  <td class="px-2 py-2.5 text-center">
                    <input
                      type="checkbox"
                      class="size-4 accent-tinta"
                      :checked="l.gerador"
                      :disabled="!!salvando[l.usuarioId]"
                      :aria-label="`Gerador de atendimentos para ${l.nome}`"
                      @change="mudarGerador(l, $event.target.checked)"
                    />
                  </td>
                  <td class="px-2 py-2.5 text-center">
                    <input
                      type="checkbox"
                      class="size-4 accent-tinta"
                      :checked="l.pesquisar"
                      :disabled="!!salvando[l.usuarioId]"
                      :aria-label="`Pesquisar na base para ${l.nome}`"
                      @change="mudarBase(l, 'pesquisar', $event.target.checked)"
                    />
                  </td>
                  <td class="px-2 py-2.5 text-center">
                    <input
                      type="checkbox"
                      class="size-4 accent-tinta"
                      :checked="l.curar"
                      :disabled="!!salvando[l.usuarioId]"
                      :aria-label="`Curar a base para ${l.nome}`"
                      @change="mudarBase(l, 'curar', $event.target.checked)"
                    />
                  </td>
                  <td class="py-2.5 pr-4 text-xs text-tinta-suave">{{ salvando[l.usuarioId] ? 'salvando…' : ultima(l) }}</td>
                </tr>
                <tr v-if="falha?.usuarioId === l.usuarioId">
                  <td colspan="6" class="px-4 pb-2.5 text-sm text-carimbo" role="alert">{{ falha.mensagem }}</td>
                </tr>
              </template>
            </tbody>
          </table>
        </section>
      </EstadoDaTela>
    </div>
  </div>
</template>
