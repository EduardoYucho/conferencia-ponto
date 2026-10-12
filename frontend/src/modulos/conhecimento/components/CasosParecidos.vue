<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { conhecimentoApi } from '@/modulos/conhecimento/api'
import { porcentagem, situacaoDoRegistro } from '@/modulos/conhecimento/formato'
import { mensagemDe } from '@/utils/erros'

/**
 * O aviso de caso parecido, nos chamados para o desenvolvimento: até 5 registros da base parecidos pelo significado
 * ou da mesma tela, com a situação e o número do chamado, para não abrir um chamado repetido. Abre cada um numa aba
 * nova (a tela do texto fica como está).
 */
const props = defineProps({
  titulo: { type: String, default: '' },
  texto: { type: String, default: '' },
  telaCodigo: { type: String, default: '' },
  atendimentoId: { type: String, default: null },
})

const router = useRouter()
const endereco = (id) => router.resolve({ name: 'registro-da-base', params: { id } }).href
const resultado = ref(null)
const carregando = ref(false)
const erro = ref('')

async function carregar() {
  if (!props.titulo?.trim() && !props.texto?.trim() && !props.telaCodigo?.trim()) {
    resultado.value = { registros: [], porSignificado: false, aviso: null }
    return
  }
  carregando.value = true
  erro.value = ''
  try {
    resultado.value = await conhecimentoApi.parecidos({
      titulo: props.titulo || null,
      texto: props.texto || null,
      telaCodigo: props.telaCodigo || null,
      atendimentoId: props.atendimentoId,
    })
  } catch (e) {
    erro.value = mensagemDe(e)
  } finally {
    carregando.value = false
  }
}

onMounted(carregar)
watch(() => [props.titulo, props.texto, props.telaCodigo], carregar)

const classe = (codigo) => situacaoDoRegistro(codigo)?.classe ?? 'border-linha'
</script>

<template>
  <section class="rounded-[3px] border border-linha px-3 py-3 text-sm" aria-label="Casos parecidos na base">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <p class="font-semibold">
        Casos parecidos na base<template v-if="resultado?.registros?.length"> ({{ resultado.registros.length }})</template>
      </p>
      <span v-if="carregando" role="status" class="text-xs text-tinta-suave">Procurando…</span>
    </div>
    <p v-if="erro" role="alert" class="mt-1 text-xs text-carimbo">
      {{ erro }} <button type="button" class="font-semibold underline underline-offset-4" @click="carregar">Tentar de novo</button>
    </p>
    <template v-else-if="resultado">
      <p v-if="!resultado.registros.length" class="mt-0.5 text-xs text-tinta-suave">
        Nenhum caso parecido na base<template v-if="resultado.porSignificado"> (pelo significado e pela tela)</template><template v-else-if="telaCodigo"> da mesma tela</template>.
      </p>
      <p v-else class="mt-0.5 text-xs text-tinta-suave">Confira antes de abrir o chamado: pode ser o mesmo caso.</p>
      <ul v-if="resultado.registros.length" class="mt-2 grid gap-1.5">
        <li v-for="r in resultado.registros" :key="r.id" class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 border-t border-linha/60 pt-1.5">
          <a :href="endereco(r.id)" target="_blank" rel="noopener" class="min-w-0 font-semibold break-words underline-offset-4 hover:underline">{{ r.titulo }}</a>
          <span class="flex flex-wrap items-center gap-1.5 text-xs">
            <span class="rounded-[2px] border px-1.5 py-0.5 font-bold tracking-wide uppercase" :class="classe(r.situacao)">{{ r.situacaoRotulo }}</span>
            <span v-if="r.chamadoDev" class="text-tinta-suave">{{ r.chamadoDev }}<template v-if="r.versaoCorrecao"> · versão {{ r.versaoCorrecao }}</template></span>
            <span v-if="r.mesmaTela" class="carimbo text-tinta-suave">mesma tela {{ r.telaCodigo }}</span>
            <span v-if="r.similaridade != null" class="text-tinta-suave">parecido {{ porcentagem(r.similaridade) }}</span>
          </span>
        </li>
      </ul>
      <p v-if="resultado.aviso" class="mt-1 text-xs text-tinta-suave">{{ resultado.aviso }}</p>
    </template>
  </section>
</template>
