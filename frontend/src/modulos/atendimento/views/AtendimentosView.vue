<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { useAuthStore } from '@/stores/auth'
import { atendimentoApi } from '@/modulos/atendimento/api'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { dataHora, periodo, SITUACOES, validadeDosLinks } from '@/modulos/atendimento/formato'

/**
 * Início do gerador: os atendimentos da pessoa (cada um vê só os próprios), o botão para criar um novo a partir
 * do PDF do Digisac e o atalho para a chave do Gemini.
 */
const auth = useAuthStore()
const modulos = useAcessoModulosStore()
const router = useRouter()

const linhas = ref([])
const carregando = ref(false)
const erro = ref(null)
const carregou = ref(false)

async function carregar() {
  if (!modulos.gerador) return
  carregando.value = true
  erro.value = null
  try {
    linhas.value = await atendimentoApi.atendimentos()
    carregou.value = true
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)
watch(() => modulos.gerador, (liberado) => liberado && !carregou.value && carregar())

function abrir(linha) {
  router.push({ name: 'atendimento', params: { id: linha.id } })
}

function situacao(linha) {
  return SITUACOES[linha.situacao] ?? SITUACOES.novo
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-3 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div>
        <p class="rotulo">Gerador de textos de atendimento</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Atendimentos</h1>
      </div>
      <div v-if="modulos.gerador" class="flex flex-wrap gap-2">
        <RouterLink :to="{ name: 'chave-gemini' }" class="botao-secundario">Minha chave do Gemini</RouterLink>
        <RouterLink :to="{ name: 'atendimento-novo' }" class="botao-primario">Novo atendimento</RouterLink>
      </div>
    </header>

    <div class="mt-6">
      <EstadoDaTela :carregando="!modulos.carregado" :erro="modulos.erro" carregando-texto="Conferindo o seu acesso…" @tentar="modulos.carregar()">
        <section v-if="!modulos.gerador" class="cartao px-5 py-4">
          <p class="font-semibold">O gerador de atendimentos não está liberado para você.</p>
          <p class="mt-1 text-sm text-tinta-suave">
            {{ auth.ehAdmin ? 'Libere em Acessos (inclusive para você mesmo).' : 'Peça ao administrador para liberar.' }}
          </p>
          <RouterLink v-if="auth.ehAdmin" :to="{ name: 'acessos-modulos' }" class="botao-secundario mt-3 inline-block">Abrir Acessos</RouterLink>
        </section>

        <EstadoDaTela
          v-else
          :carregando="carregando"
          :erro="erro"
          :vazio="carregou && !linhas.length"
          :manter="linhas.length > 0"
          carregando-texto="Carregando os seus atendimentos…"
          @tentar="carregar"
        >
          <template #vazio>
            Nenhum atendimento ainda. Use <b class="text-tinta">Novo atendimento</b> e envie o PDF de uma conversa do Digisac.
          </template>

          <!-- computador: tabela -->
          <section class="cartao hidden overflow-x-auto md:block" aria-label="Meus atendimentos">
            <table class="w-full text-sm">
              <thead>
                <tr class="border-b-2 border-tinta text-left">
                  <th class="rotulo px-4 py-2.5">Chamado</th>
                  <th class="rotulo py-2.5">Contato</th>
                  <th class="rotulo py-2.5">Período</th>
                  <th class="rotulo px-2 py-2.5 text-right">Mensagens</th>
                  <th class="rotulo px-2 py-2.5 text-right">Anexos</th>
                  <th class="rotulo px-2 py-2.5">Situação</th>
                  <th class="rotulo py-2.5 pr-4">Criado em</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="l in linhas"
                  :key="l.id"
                  class="cursor-pointer border-b border-linha/70 hover:bg-papel-escuro/40"
                  @click="abrir(l)"
                >
                  <td class="px-4 py-2.5">
                    <RouterLink :to="{ name: 'atendimento', params: { id: l.id } }" class="carimbo font-semibold underline-offset-4 hover:underline" @click.stop>
                      {{ l.chamado }}
                    </RouterLink>
                  </td>
                  <td class="max-w-[16rem] truncate py-2.5" :title="l.contato">{{ l.contato ?? '—' }}</td>
                  <td class="py-2.5 whitespace-nowrap">{{ periodo(l.inicio, l.fim) }}</td>
                  <td class="carimbo px-2 py-2.5 text-right">{{ l.mensagens }}</td>
                  <td class="px-2 py-2.5 text-right">
                    <span class="carimbo">{{ l.anexos }}</span>
                    <span v-if="l.anexos && l.linksVencidos" class="ml-1 text-xs text-carimbo" :title="validadeDosLinks(l.linksValidosAte, true)">vencidos</span>
                  </td>
                  <td class="px-2 py-2.5">
                    <span class="rounded-[2px] border px-1.5 py-0.5 text-xs font-bold tracking-wide uppercase" :class="situacao(l).classe">{{ situacao(l).rotulo }}</span>
                  </td>
                  <td class="py-2.5 pr-4 text-xs whitespace-nowrap text-tinta-suave">{{ dataHora(l.criadoEm) }}</td>
                </tr>
              </tbody>
            </table>
          </section>

          <!-- celular: cartões -->
          <ul class="grid gap-3 md:hidden" aria-label="Meus atendimentos">
            <li v-for="l in linhas" :key="l.id">
              <RouterLink :to="{ name: 'atendimento', params: { id: l.id } }" class="cartao block px-4 py-3">
                <div class="flex items-start justify-between gap-3">
                  <span class="min-w-0">
                    <span class="block truncate font-semibold">{{ l.contato ?? '—' }}</span>
                    <span class="carimbo text-xs text-tinta-suave">{{ l.chamado }}</span>
                  </span>
                  <span class="shrink-0 rounded-[2px] border px-1.5 py-0.5 text-xs font-bold tracking-wide uppercase" :class="situacao(l).classe">{{ situacao(l).rotulo }}</span>
                </div>
                <p class="mt-2 text-sm">{{ periodo(l.inicio, l.fim) }}</p>
                <p class="mt-0.5 text-xs text-tinta-suave">
                  {{ l.mensagens }} mensagens · {{ l.anexos }} anexos<span v-if="l.anexos && l.linksVencidos" class="text-carimbo"> (links vencidos)</span>
                </p>
              </RouterLink>
            </li>
          </ul>
        </EstadoDaTela>
      </EstadoDaTela>
    </div>
  </div>
</template>
